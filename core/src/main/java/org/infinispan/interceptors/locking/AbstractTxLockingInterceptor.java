package org.infinispan.interceptors.locking;

import static org.infinispan.commons.util.Util.toStr;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

import org.infinispan.atomic.DeltaCompositeKey;
import org.infinispan.commands.read.GetCacheEntryCommand;
import org.infinispan.commands.read.GetAllCommand;
import org.infinispan.commands.read.GetKeyValueCommand;
import org.infinispan.commands.tx.CommitCommand;
import org.infinispan.commands.tx.PrepareCommand;
import org.infinispan.commands.tx.RollbackCommand;
import org.infinispan.commands.write.PutKeyValueCommand;
import org.infinispan.configuration.cache.Configurations;
import org.infinispan.context.Flag;
import org.infinispan.context.InvocationContext;
import org.infinispan.context.impl.TxInvocationContext;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.remoting.rpc.RpcManager;
import org.infinispan.statetransfer.OutdatedTopologyException;
import org.infinispan.transaction.impl.LocalTransaction;
import org.infinispan.transaction.impl.TransactionTable;
import org.infinispan.transaction.xa.CacheTransaction;
import org.infinispan.transaction.xa.GlobalTransaction;
import org.infinispan.util.TimeService;
import org.infinispan.util.concurrent.TimeoutException;
import org.infinispan.util.logging.Log;

/**
 * Base class for transaction based locking interceptors.
 *
 * @author Mircea.Markus@jboss.com
 * @since 5.1
 */
public abstract class AbstractTxLockingInterceptor extends AbstractLockingInterceptor {

   protected TransactionTable txTable;
   protected RpcManager rpcManager;
   private boolean clustered;
   private TimeService timeService;

   @Inject
   @SuppressWarnings("unused")
   public void setDependencies(TransactionTable txTable, RpcManager rpcManager, TimeService timeService) {
      this.txTable = txTable;
      this.rpcManager = rpcManager;
      clustered = rpcManager != null;
      this.timeService = timeService;
   }

   @Override
   public Object visitRollbackCommand(TxInvocationContext ctx, RollbackCommand command) throws Throwable {
      try {
         return invokeNextInterceptor(ctx, command);
      } finally {
         lockManager.unlockAll(ctx);
      }
   }

   @Override
   public Object visitPutKeyValueCommand(InvocationContext ctx, PutKeyValueCommand command) throws Throwable {
      if (command.hasFlag(Flag.PUT_FOR_EXTERNAL_READ)) {
         // Cache.putForExternalRead() is non-transactional
         return visitNonTxDataWriteCommand(ctx, command);
      }
      return visitDataWriteCommand(ctx, command);
   }

   @Override
   public Object visitGetAllCommand(InvocationContext ctx, GetAllCommand command) throws Throwable {
      try {
         return super.visitGetAllCommand(ctx, command);
      } finally {
         //when not invoked in an explicit tx's scope the get is non-transactional(mainly for efficiency).
         //locks need to be released in this situation as they might have been acquired from L1.
         if (!ctx.isInTxScope()) lockManager.unlockAll(ctx);
      }
   }

   @Override
   public Object visitCommitCommand(TxInvocationContext ctx, CommitCommand command) throws Throwable {
      boolean releaseLocks = releaseLockOnTxCompletion(ctx);
      try {
         return super.visitCommitCommand(ctx, command);
      } catch (OutdatedTopologyException e) {
         releaseLocks = false;
         throw e;
      } finally {
         if (releaseLocks) lockManager.unlockAll(ctx);
      }
   }

   protected final Object invokeNextAndCommitIf1Pc(TxInvocationContext ctx, PrepareCommand command) throws Throwable {
      Object result = invokeNextInterceptor(ctx, command);
      if (command.isOnePhaseCommit() && releaseLockOnTxCompletion(ctx)) {
         lockManager.unlockAll(ctx);
      }
      return result;
   }

   /**
    * The backup (non-primary) owners keep a "backup lock" for each key they received in a lock/prepare command.
    * Normally there can be many transactions holding the backup lock at the same time, but when the secondary owner
    * becomes a primary owner a new transaction trying to obtain the "real" lock will have to wait for all backup
    * locks to be released. The backup lock will be released either by a commit/rollback/unlock command or by
    * the originator leaving the cluster (if recovery is disabled).
    */
   protected final void lockAndRegisterBackupLock(TxInvocationContext ctx, Object key, long lockTimeout, boolean skipLocking) throws InterruptedException {
      //with DeltaCompositeKey, the locks should be acquired in the owner of the delta aware key.
      Object keyToCheck = key instanceof DeltaCompositeKey ?
            ((DeltaCompositeKey) key).getDeltaAwareValueKey() :
            key;
      if (cdl.localNodeIsPrimaryOwner(keyToCheck)) {
         lockKeyAndCheckOwnership(ctx, key, lockTimeout, skipLocking);
      } else if (cdl.localNodeIsOwner(keyToCheck)) {
         ctx.getCacheTransaction().addBackupLockForKey(key);
      }
   }

   /**
    * Besides acquiring a lock, this method also handles the following situation:
    * 1. consistentHash("k") == {A, B}, tx1 prepared on A and B. Then node A crashed (A  == single lock owner)
    * 2. at this point tx2 which also writes "k" tries to prepare on B.
    * 3. tx2 has to determine that "k" is already locked by another tx (i.e. tx1) and it has to wait for that tx to finish before acquiring the lock.
    *
    * The algorithm used at step 3 is:
    * - the transaction table(TT) associates the current topology id with every remote and local transaction it creates
    * - TT also keeps track of the minimal value of all the topology ids of all the transactions still present in the cache (minTopologyId)
    * - when a tx wants to acquire lock "k":
    *    - if tx.topologyId > TT.minTopologyId then "k" might be a key whose owner crashed. If so:
    *       - obtain the list LT of transactions that started in a previous topology (txTable.getTransactionsPreparedBefore)
    *       - for each t in LT:
    *          - if t wants to write "k" then block until t finishes (CacheTransaction.waitForTransactionsToFinishIfItWritesToKey)
    *       - only then try to acquire lock on "k"
    *    - if tx.topologyId == TT.minTopologyId try to acquire lock straight away.
    *
    * Note: The algorithm described below only when nodes leave the cluster, so it doesn't add a performance burden
    * when the cluster is stable.
    */
   protected final void lockKeyAndCheckOwnership(InvocationContext ctx, Object key, long lockTimeout, boolean skipLocking) throws InterruptedException {
      TxInvocationContext txContext = (TxInvocationContext) ctx;
      int transactionTopologyId = -1;
      boolean checkForPendingLocks = false;
      if (clustered) {
         CacheTransaction tx = txContext.getCacheTransaction();
         boolean isFromStateTransfer = txContext.isOriginLocal() && ((LocalTransaction)tx).isFromStateTransfer();
         // if the transaction is from state transfer it should not wait for the backup locks of other transactions
         if (!isFromStateTransfer) {
            transactionTopologyId = tx.getTopologyId();
            if (transactionTopologyId != TransactionTable.CACHE_STOPPED_TOPOLOGY_ID) {
               checkForPendingLocks = txTable.getMinTopologyId() < transactionTopologyId;
            }
         }
      }

      Log log = getLog();
      boolean trace = log.isTraceEnabled();
      if (checkForPendingLocks) {
         if (trace)
            log.tracef("Checking for pending locks and then locking key %s", toStr(key));

         final long expectedEndTime = timeService.expectedEndTime(cacheConfiguration.locking().lockAcquisitionTimeout(),
                                                                  TimeUnit.MILLISECONDS);

         // Check local transactions first
         waitForTransactionsToComplete(txContext, txTable.getLocalTransactions(), key, transactionTopologyId, expectedEndTime);

         // ... then remote ones
         waitForTransactionsToComplete(txContext, txTable.getRemoteTransactions(), key, transactionTopologyId, expectedEndTime);

         // Then try to acquire a lock
         if (trace)
            log.tracef("Finished waiting for other potential lockers, trying to acquire the lock on %s", toStr(key));

         final long remaining = timeService.remainingTime(expectedEndTime, TimeUnit.MILLISECONDS);
         lockManager.acquireLock(ctx, key, remaining, skipLocking);
      } else {
         if (trace)
            log.tracef("Locking key %s, no need to check for pending locks.", toStr(key));

         lockManager.acquireLock(ctx, key, lockTimeout, skipLocking);
      }
   }

   private void waitForTransactionsToComplete(TxInvocationContext txContext, Collection<? extends CacheTransaction> transactions,
                                              Object key, int transactionTopologyId, long expectedEndTime) throws InterruptedException {
      GlobalTransaction thisTransaction = txContext.getGlobalTransaction();
      for (CacheTransaction tx : transactions) {
         if (tx.getTopologyId() < transactionTopologyId) {
            // don't wait for the current transaction
            if (tx.getGlobalTransaction().equals(thisTransaction))
               continue;

            boolean txCompleted = false;

            long remaining;
            while ((remaining = timeService.remainingTime(expectedEndTime, TimeUnit.MILLISECONDS)) > 0) {
               if (tx.waitForLockRelease(key, remaining)) {
                  txCompleted = true;
                  break;
               }
            }

            if (!txCompleted) {
               throw newTimeoutException(key, tx, txContext);
            }
         }
      }
   }

   private TimeoutException newTimeoutException(Object key, TxInvocationContext txContext) {
      return new TimeoutException("Could not acquire lock on " + key + " on behalf of transaction " +
                                       txContext.getGlobalTransaction() + "." + "Lock is being held by " + lockManager.getOwner(key));
   }
   
   private TimeoutException newTimeoutException(Object key, CacheTransaction tx, TxInvocationContext txContext) {                  
      return new TimeoutException("Could not acquire lock on " + key + " on behalf of transaction " +
                                       txContext.getGlobalTransaction() + ". Waiting to complete tx: " + tx + ".");
   }

   private boolean releaseLockOnTxCompletion(TxInvocationContext ctx) {
      return ctx.isOriginLocal() || Configurations.isSecondPhaseAsync(cacheConfiguration);
   }
}
