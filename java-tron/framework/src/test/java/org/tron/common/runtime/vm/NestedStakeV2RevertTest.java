package org.tron.common.runtime.vm;

import static org.tron.core.config.Parameter.ChainConstant.TRX_PRECISION;
import static org.tron.protos.Protocol.Transaction.Result.contractResult.REVERT;
import static org.tron.protos.Protocol.Transaction.Result.contractResult.SUCCESS;
import static org.tron.protos.contract.Common.ResourceCode.BANDWIDTH;
import static org.tron.protos.contract.Common.ResourceCode.ENERGY;

import com.google.common.primitives.Bytes;
import com.google.protobuf.ByteString;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bouncycastle.util.encoders.Hex;
import org.junit.Assert;
import org.junit.Test;
import org.tron.common.BaseMethodTest;
import org.tron.common.runtime.RuntimeImpl;
import org.tron.common.runtime.TVMTestResult;
import org.tron.common.runtime.TvmTestUtils;
import org.tron.common.utils.Commons;
import org.tron.common.utils.WalletUtil;
import org.tron.core.Wallet;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.DelegatedResourceAccountIndexCapsule;
import org.tron.core.capsule.DelegatedResourceCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.db.TransactionTrace;
import org.tron.core.store.DelegatedResourceAccountIndexStore;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.core.store.StoreFactory;
import org.tron.core.vm.Op;
import org.tron.core.vm.config.ConfigLoader;
import org.tron.core.vm.config.VMConfig;
import org.tron.core.vm.repository.Repository;
import org.tron.core.vm.repository.RepositoryImpl;
import org.tron.protos.Protocol;
import org.tron.protos.Protocol.Transaction.Result.contractResult;

/**
 * Checks ONE property across nested executions: the state changes made by a valid
 * DELEGATERESOURCE or UNDELEGATERESOURCE (Stake 2.0), executed by a CALLED contract, are discarded
 * when the frame that contains the operation reverts, in either of two shapes:
 *
 * <ul>
 *   <li><b>Outer reverts</b>: the callee runs the operation and returns normally (its frame is
 *       merged into the caller's state), then the caller reverts. The whole transaction reverts.
 *   <li><b>Inner reverts</b>: the callee runs the operation and then reverts; the caller sees the
 *       call fail, continues and returns normally. The transaction SUCCEEDS and its state is
 *       committed, so anything that leaked out of the inner frame would be persisted.
 * </ul>
 *
 * <p>The same-frame case is covered by {@link DelegateResourceRevertTest} and
 * {@link UnDelegateResourceRevertTest}, which this class deliberately does not modify.
 *
 * <p>Two hand-assembled contracts.
 * <pre>
 * STAKE (the callee, owner of the stake), calldata = five 32-byte words:
 *   w0 op   0 freeze, 1 delegate, 2 un-delegate
 *   w1..w3  op 0: amount, resource, -      ops 1, 2: receiver, amount, resource
 *   w4 mode 0 = RETURN the 5 result words, otherwise REVERT with the same 5 words
 *   result words: 0 = op result; then, read back inside the same execution through the Stake 2.0
 *   query precompiles (they read the in-flight state): 1 = 0x01000010(receiver, this, resource)
 *   delegated to receiver, 2 = 0x01000014(this, resource) total delegated, 3 =
 *   0x01000015(receiver, resource) total acquired by receiver, 4 = 0x01000010(this, this,
 *   resource) own frozenV2 balance (ops 1 and 2 only)
 *
 * CALLER, calldata = target, outerMode, then the five STAKE words:
 *   CALL(target, the five words); result words = the callee's 5 words (RETURN or REVERT data,
 *   the VM copies both) + the CALL success flag; then RETURN, or REVERT if outerMode != 0
 * </pre>
 */
public class NestedStakeV2RevertTest extends BaseMethodTest {

  private static final int DELEGATE = 1;
  private static final int UNDELEGATE = 2;

  private static final long HEAD_TIMESTAMP = 1_700_000_000_000L;
  private static final long BALANCE = 100_000_000_000_000_000L;
  private static final long FEE_LIMIT = 1_000_000_000L;
  private static final long FROZEN = 10 * TRX_PRECISION;
  private static final long DELEGATED = 4 * TRX_PRECISION;
  private static final long PARTIAL = 1 * TRX_PRECISION;
  private static final long USAGE = 1_000_000L;
  private static final long STALE_SLOTS = 1_000L;

  private static final String STAKE_INIT = initCode(buildStake());
  private static final String CALLER_INIT = initCode(buildCaller());

  private static final byte[] RECEIVER =
      Commons.decode58Check("TWyoFfJBiKGkVQd28HTqxsc8kbMtQUmqgi");

  private static byte[] owner;
  private int deployCount;

  @Override
  protected String[] extraArgs() {
    // same as FreezeV2Test: disables the VM CPU-time limit, which a cold JVM can hit
    return new String[]{"--debug"};
  }

  @Override
  protected void afterInit() {
    deployCount = 0;
    owner = Hex.decode(Wallet.getAddressPreFixString()
        + "abd4b9367799eaa3197fecb144eb71de1e049abc");
    Repository root = RepositoryImpl.createRoot(StoreFactory.getInstance());
    root.createAccount(owner, Protocol.AccountType.Normal);
    root.addBalance(owner, 900_000_000_000_000_000L);
    root.createAccount(RECEIVER, Protocol.AccountType.Normal);
    root.commit();

    ConfigLoader.disable = true;
    DynamicPropertiesStore dps = dbManager.getDynamicPropertiesStore();
    dps.saveAllowTvmFreeze(1);
    dps.saveUnfreezeDelayDays(30);
    dps.saveAllowNewResourceModel(1L);
    dps.saveAllowDelegateResource(1);
    dps.saveLatestBlockHeaderTimestamp(HEAD_TIMESTAMP);
    VMConfig.initVmHardFork(true);
    VMConfig.initAllowTvmTransferTrc10(1);
    VMConfig.initAllowTvmConstantinople(1);
    VMConfig.initAllowTvmSolidity059(1);
    VMConfig.initAllowTvmIstanbul(1);
    VMConfig.initAllowTvmFreezeV2(1);
    VMConfig.initAllowTvmVote(1);
  }

  @Override
  protected void beforeDestroy() {
    ConfigLoader.disable = false;
  }

  // ---- DELEGATERESOURCE, called by another contract

  @Test
  public void delegateBandwidthOuterRevertDiscardsCalleeChanges() throws Exception {
    run(DELEGATE, 0, DELEGATED, false);
  }

  @Test
  public void delegateBandwidthInnerRevertDiscardsCalleeChanges() throws Exception {
    run(DELEGATE, 0, DELEGATED, true);
  }

  @Test
  public void delegateEnergyOuterRevertDiscardsCalleeChanges() throws Exception {
    run(DELEGATE, 1, DELEGATED, false);
  }

  @Test
  public void delegateEnergyInnerRevertDiscardsCalleeChanges() throws Exception {
    run(DELEGATE, 1, DELEGATED, true);
  }

  // ---- UNDELEGATERESOURCE, called by another contract

  @Test
  public void partialBandwidthUnDelegateOuterRevertDiscardsCalleeChanges() throws Exception {
    run(UNDELEGATE, 0, PARTIAL, false);
  }

  @Test
  public void partialBandwidthUnDelegateInnerRevertDiscardsCalleeChanges() throws Exception {
    run(UNDELEGATE, 0, PARTIAL, true);
  }

  @Test
  public void fullBandwidthUnDelegateOuterRevertDiscardsCalleeChanges() throws Exception {
    run(UNDELEGATE, 0, DELEGATED, false);
  }

  @Test
  public void fullBandwidthUnDelegateInnerRevertDiscardsCalleeChanges() throws Exception {
    run(UNDELEGATE, 0, DELEGATED, true);
  }

  @Test
  public void partialEnergyUnDelegateOuterRevertDiscardsCalleeChanges() throws Exception {
    run(UNDELEGATE, 1, PARTIAL, false);
  }

  @Test
  public void partialEnergyUnDelegateInnerRevertDiscardsCalleeChanges() throws Exception {
    run(UNDELEGATE, 1, PARTIAL, true);
  }

  @Test
  public void fullEnergyUnDelegateOuterRevertDiscardsCalleeChanges() throws Exception {
    run(UNDELEGATE, 1, DELEGATED, false);
  }

  @Test
  public void fullEnergyUnDelegateInnerRevertDiscardsCalleeChanges() throws Exception {
    run(UNDELEGATE, 1, DELEGATED, true);
  }

  private void run(int op, int res, long amount, boolean innerReverts) throws Exception {
    boolean full = op == UNDELEGATE && amount == DELEGATED;
    String tag = (op == DELEGATE ? "delegate" : full ? "undelegate full" : "undelegate partial")
        + (res == 0 ? " bandwidth" : " energy")
        + (innerReverts ? " / inner reverts" : " / outer reverts");
    byte[] stake = deploy(STAKE_INIT);
    byte[] caller = deploy(CALLER_INIT);
    long priorDelegated = op == UNDELEGATE ? DELEGATED : 0;

    // valid precondition, built through the VM by direct calls to the callee
    for (int r = 0; r < 2; r++) {
      Assert.assertEquals(tag + ": setup freeze must return 1", 1L,
          word(trigger(stake, freezeCalldata(FROZEN, r), SUCCESS), 0));
    }
    long headSlot = chainBaseManager.getHeadSlot();
    if (op == UNDELEGATE) {
      Assert.assertEquals(tag + ": setup delegation must return 1", 1L,
          word(trigger(stake, stakeCalldata(DELEGATE, res, DELEGATED, 0), SUCCESS), 0));
      // give the receiver real, stale usage of the tested resource
      AccountCapsule seeded = dbManager.getAccountStore().get(RECEIVER);
      if (res == 0) {
        seeded.setNetUsage(USAGE);
        seeded.setLatestConsumeTime(headSlot - STALE_SLOTS);
      } else {
        seeded.setEnergyUsage(USAGE);
        seeded.setLatestConsumeTimeForEnergy(headSlot - STALE_SLOTS);
      }
      dbManager.getAccountStore().put(seeded.createDbKey(), seeded);
    }

    State before = capture(stake, caller);
    Assert.assertEquals(tag + ": setup callee delegated", priorDelegated,
        before.stake.delegated[res]);
    Assert.assertEquals(tag + ": setup callee frozenV2", FROZEN - priorDelegated,
        before.stake.frozen[res]);
    Assert.assertEquals(tag + ": setup receiver acquired", priorDelegated,
        before.receiver.acquired[res]);
    if (op == UNDELEGATE) {
      Assert.assertNotNull(tag + ": setup record", before.recordCapsule);
      Assert.assertNotNull(tag + ": setup from-index", before.fromIndex);
      Assert.assertNotNull(tag + ": setup to-index", before.toIndex);
      Assert.assertEquals(tag + ": setup receiver usage", USAGE, before.receiver.usage[res]);
    } else {
      Assert.assertNull(tag + ": setup record", before.recordCapsule);
      Assert.assertNull(tag + ": setup from-index", before.fromIndex);
    }

    long[] expectedWords = expectedWords(op, res, amount, priorDelegated);

    // ---- reverted arm
    TVMTestResult reverted;
    if (innerReverts) {
      // callee reverts after the op; caller continues and the transaction succeeds
      reverted = trigger(caller, callerCalldata(stake, 0,
          stakeCalldata(op, res, amount, 1)), SUCCESS);
      Assert.assertFalse(tag + ": the transaction itself must not be reverted",
          reverted.getRuntime().getResult().isRevert());
      Assert.assertEquals(tag + ": CALL must report failure", 0L, word(reverted, 5));
    } else {
      // callee returns normally, caller reverts afterwards
      reverted = trigger(caller, callerCalldata(stake, 1,
          stakeCalldata(op, res, amount, 0)), REVERT);
      Assert.assertTrue(tag + ": the transaction must be reverted",
          reverted.getRuntime().getResult().isRevert());
      Assert.assertEquals(tag + ": CALL must report success", 1L, word(reverted, 5));
    }
    assertVisibleInsideExecution(tag + " (reverted arm)", reverted, expectedWords);
    State afterRevert = capture(stake, caller);
    assertNothingChanged(tag + " after revert", before, afterRevert);
    assertOnlyEnergyFeeCharged(tag + " after revert", before, afterRevert, reverted);

    // ---- control: same contracts, same arguments, nothing reverts
    TVMTestResult committed = trigger(caller, callerCalldata(stake, 0,
        stakeCalldata(op, res, amount, 0)), SUCCESS);
    State afterCommit = capture(stake, caller);
    Assert.assertEquals(tag + " control: CALL must report success", 1L, word(committed, 5));
    assertVisibleInsideExecution(tag + " (control)", committed, expectedWords);
    assertApplied(tag + " control", before, afterCommit, op, res, amount, full);
    assertAccountEqual(tag + " control / caller contract", before.caller, afterCommit.caller);
    assertOnlyEnergyFeeCharged(tag + " control", afterRevert, afterCommit, committed);

    System.out.println("OBS " + tag + " | reverted-arm words " + words(reverted)
        + " | control words " + words(committed)
        + " | receiver usage " + before.receiver.usage[res] + " -> "
        + afterCommit.receiver.usage[res]
        + " | index entries after control from=" + (afterCommit.fromIndex != null)
        + " to=" + (afterCommit.toIndex != null));
  }

  // ---------------------------------------------------------------- assertions

  /** Expected result words of the callee: op result, then the four in-execution readings. */
  private static long[] expectedWords(int op, int res, long amount, long priorDelegated) {
    long delegatedAfter = op == DELEGATE ? priorDelegated + amount : priorDelegated - amount;
    return new long[] {1, delegatedAfter, delegatedAfter, delegatedAfter,
        FROZEN - delegatedAfter};
  }

  private void assertVisibleInsideExecution(String tag, TVMTestResult result, long[] expected) {
    byte[] data = result.getRuntime().getResult().getHReturn();
    Assert.assertEquals(tag + ": 6 result words expected", 192, data.length);
    String[] names = {"operation result", "delegated to receiver (0x01000010)",
        "total delegated by callee (0x01000014)", "total acquired by receiver (0x01000015)",
        "callee's own frozenV2 (0x01000010)"};
    for (int i = 0; i < 5; i++) {
      Assert.assertEquals(tag + ": in-execution " + names[i], expected[i], word(result, i));
    }
  }

  /** Every store and index the operation can touch is identical to the state before. */
  private void assertNothingChanged(String tag, State before, State after) {
    assertAccountEqual(tag + " / callee (stake owner)", before.stake, after.stake);
    assertAccountEqual(tag + " / caller contract", before.caller, after.caller);
    assertAccountEqual(tag + " / receiver", before.receiver, after.receiver);
    Assert.assertArrayEquals(tag + ": delegation record (lock=false key)",
        before.record, after.record);
    Assert.assertArrayEquals(tag + ": delegation record (lock=true key)",
        before.lockedRecord, after.lockedRecord);
    Assert.assertArrayEquals(tag + ": from-index entry", before.fromIndex, after.fromIndex);
    Assert.assertArrayEquals(tag + ": to-index entry", before.toIndex, after.toIndex);
    Assert.assertEquals(tag + ": callee's delegated-to list (API view)",
        before.ownerTo, after.ownerTo);
    Assert.assertEquals(tag + ": receiver's delegated-from list (API view)",
        before.receiverFrom, after.receiverFrom);
    Assert.assertEquals(tag + ": callee's delegated-from list (API view)",
        before.ownerFrom, after.ownerFrom);
    Assert.assertEquals(tag + ": receiver's delegated-to list (API view)",
        before.receiverTo, after.receiverTo);
    Assert.assertArrayEquals(tag + ": global weights", before.weights, after.weights);
  }

  private static void assertAccountEqual(String tag, Acct before, Acct after) {
    Assert.assertEquals(tag + ": available balance", before.available, after.available);
    Assert.assertEquals(tag + ": tron power frozenV2", before.tronPower, after.tronPower);
    for (int r = 0; r < 2; r++) {
      Assert.assertEquals(tag + ": frozenV2, resource " + r, before.frozen[r], after.frozen[r]);
      Assert.assertEquals(tag + ": delegated, resource " + r,
          before.delegated[r], after.delegated[r]);
      Assert.assertEquals(tag + ": acquired, resource " + r,
          before.acquired[r], after.acquired[r]);
      Assert.assertEquals(tag + ": usage, resource " + r, before.usage[r], after.usage[r]);
      Assert.assertEquals(tag + ": last consume time, resource " + r,
          before.lastConsume[r], after.lastConsume[r]);
    }
    Assert.assertArrayEquals(tag + ": full serialized account", before.bytes, after.bytes);
  }

  /**
   * Control: the nested execution persists the change when nothing reverts, so the comparison
   * after the revert is meaningful. The exact semantics of each operation are covered by the
   * same-frame tests; here the expected fields are checked without repeating every one.
   */
  private void assertApplied(
      String tag, State before, State after, int op, int res, long amount, boolean full) {
    long sign = op == DELEGATE ? 1 : -1; // change of "delegated"; "frozen" moves the other way
    Assert.assertEquals(tag + ": callee available balance", before.stake.available,
        after.stake.available);
    Assert.assertEquals(tag + ": callee delegated", before.stake.delegated[res] + sign * amount,
        after.stake.delegated[res]);
    Assert.assertEquals(tag + ": callee frozenV2", before.stake.frozen[res] - sign * amount,
        after.stake.frozen[res]);
    Assert.assertEquals(tag + ": receiver acquired", before.receiver.acquired[res] + sign * amount,
        after.receiver.acquired[res]);
    int other = 1 - res;
    Assert.assertEquals(tag + ": callee delegated, other resource", before.stake.delegated[other],
        after.stake.delegated[other]);
    Assert.assertEquals(tag + ": receiver acquired, other resource",
        before.receiver.acquired[other], after.receiver.acquired[other]);
    Assert.assertArrayEquals(tag + ": global weights", before.weights, after.weights);
    Assert.assertNotNull(tag + ": delegation record", after.recordCapsule);
    long recordBalance = res == 0 ? after.recordCapsule.getFrozenBalanceForBandwidth()
        : after.recordCapsule.getFrozenBalanceForEnergy();
    Assert.assertEquals(tag + ": delegation record balance",
        (op == DELEGATE ? 0 : DELEGATED) + sign * amount, recordBalance);

    if (op == DELEGATE) {
      Assert.assertNotNull(tag + ": from-index entry", after.fromIndex);
      Assert.assertNotNull(tag + ": to-index entry", after.toIndex);
      Assert.assertEquals(tag + ": callee's delegated-to list",
          Arrays.asList(Hex.toHexString(RECEIVER)), after.ownerTo);
      Assert.assertEquals(tag + ": receiver's delegated-from list",
          Arrays.asList(Hex.toHexString(before.stakeAddress)), after.receiverFrom);
    } else {
      // the usage transferred from the receiver reached the callee; the mark moved to the head
      Assert.assertTrue(tag + ": receiver usage must drop",
          after.receiver.usage[res] < before.receiver.usage[res]);
      Assert.assertTrue(tag + ": callee usage must grow",
          after.stake.usage[res] > before.stake.usage[res]);
      Assert.assertEquals(tag + ": receiver last consume time moves to the head slot",
          chainBaseManager.getHeadSlot(), after.receiver.lastConsume[res]);
      if (full) {
        Assert.assertNull(tag + ": from-index entry removed", after.fromIndex);
        Assert.assertNull(tag + ": to-index entry removed", after.toIndex);
        Assert.assertTrue(tag + ": API views empty", after.ownerTo.isEmpty()
            && after.receiverFrom.isEmpty());
      } else {
        Assert.assertArrayEquals(tag + ": from-index entry kept", before.fromIndex,
            after.fromIndex);
        Assert.assertArrayEquals(tag + ": to-index entry kept", before.toIndex, after.toIndex);
      }
    }
  }

  /**
   * The protocol keeps the energy of the transaction whether or not it reverted: the sender
   * pays it, and that is the only permitted difference in balances. (Bandwidth is charged by
   * the block processor, which this harness does not run.)
   */
  private static void assertOnlyEnergyFeeCharged(
      String tag, State before, State after, TVMTestResult result) {
    long energyFee = result.getReceipt().getEnergyFee();
    Assert.assertTrue(tag + ": energy is consumed", result.getReceipt().getEnergyUsageTotal() > 0);
    Assert.assertEquals(tag + ": sender balance decreases by exactly the energy fee",
        before.senderBalance - energyFee, after.senderBalance);
  }

  // ---------------------------------------------------------------- state capture

  private static final class Acct {
    final long available;
    final long tronPower;
    final long[] frozen;      // frozenV2, [bandwidth, energy]
    final long[] delegated;   // delegatedFrozenV2, [bandwidth, energy]
    final long[] acquired;    // acquiredDelegatedFrozenV2, [bandwidth, energy]
    final long[] usage;       // raw usage, [bandwidth, energy]
    final long[] lastConsume; // last consume slot, [bandwidth, energy]
    final byte[] bytes;

    Acct(AccountCapsule a) {
      available = a.getBalance();
      tronPower = a.getTronPowerFrozenV2Balance();
      frozen = new long[] {a.getFrozenV2BalanceForBandwidth(), a.getFrozenV2BalanceForEnergy()};
      delegated = new long[] {a.getDelegatedFrozenV2BalanceForBandwidth(),
          a.getDelegatedFrozenV2BalanceForEnergy()};
      acquired = new long[] {a.getAcquiredDelegatedFrozenV2BalanceForBandwidth(),
          a.getAcquiredDelegatedFrozenV2BalanceForEnergy()};
      usage = new long[] {a.getNetUsage(), a.getEnergyUsage()};
      lastConsume = new long[] {a.getLastConsumeTime(BANDWIDTH), a.getLastConsumeTime(ENERGY)};
      bytes = a.getData();
    }
  }

  private static final class State {
    byte[] stakeAddress;
    Acct stake;      // the callee: owner of the stake
    Acct caller;     // the calling contract, which holds no stake
    Acct receiver;
    DelegatedResourceCapsule recordCapsule;  // lock=false key, null if absent
    byte[] record;
    byte[] lockedRecord;                      // lock=true key, null if absent
    byte[] fromIndex;                         // null if absent
    byte[] toIndex;                           // null if absent
    List<String> ownerTo;
    List<String> ownerFrom;
    List<String> receiverTo;
    List<String> receiverFrom;
    long[] weights;
    long senderBalance;
  }

  private State capture(byte[] stake, byte[] caller) {
    State s = new State();
    s.stakeAddress = stake;
    s.stake = new Acct(dbManager.getAccountStore().get(stake));
    s.caller = new Acct(dbManager.getAccountStore().get(caller));
    s.receiver = new Acct(dbManager.getAccountStore().get(RECEIVER));

    s.recordCapsule = dbManager.getDelegatedResourceStore().get(
        DelegatedResourceCapsule.createDbKeyV2(stake, RECEIVER, false));
    s.record = s.recordCapsule == null ? null : s.recordCapsule.getData();
    DelegatedResourceCapsule locked = dbManager.getDelegatedResourceStore().get(
        DelegatedResourceCapsule.createDbKeyV2(stake, RECEIVER, true));
    s.lockedRecord = locked == null ? null : locked.getData();

    DelegatedResourceAccountIndexStore indexStore =
        dbManager.getDelegatedResourceAccountIndexStore();
    DelegatedResourceAccountIndexCapsule from = indexStore.get(Bytes.concat(
        DelegatedResourceAccountIndexStore.getV2_FROM_PREFIX(), stake, RECEIVER));
    DelegatedResourceAccountIndexCapsule to = indexStore.get(Bytes.concat(
        DelegatedResourceAccountIndexStore.getV2_TO_PREFIX(), RECEIVER, stake));
    s.fromIndex = from == null ? null : from.getData();
    s.toIndex = to == null ? null : to.getData();

    DelegatedResourceAccountIndexCapsule ownerView = indexStore.getV2Index(stake);
    DelegatedResourceAccountIndexCapsule receiverView = indexStore.getV2Index(RECEIVER);
    s.ownerTo = hex(ownerView.getToAccountsList());
    s.ownerFrom = hex(ownerView.getFromAccountsList());
    s.receiverTo = hex(receiverView.getToAccountsList());
    s.receiverFrom = hex(receiverView.getFromAccountsList());

    DynamicPropertiesStore dps = dbManager.getDynamicPropertiesStore();
    s.weights = new long[] {dps.getTotalNetWeight(), dps.getTotalEnergyWeight(),
        dps.getTotalTronPowerWeight()};
    s.senderBalance = dbManager.getAccountStore().get(owner).getBalance();
    return s;
  }

  private static List<String> hex(List<ByteString> accounts) {
    List<String> out = new ArrayList<>();
    for (ByteString a : accounts) {
      out.add(Hex.toHexString(a.toByteArray()));
    }
    return Collections.unmodifiableList(out);
  }

  // ---------------------------------------------------------------- running transactions

  private byte[] deploy(String initCode) throws Exception {
    // consumeUserResourcePercent = 100: the sender pays all the energy, not the contract owner.
    // The name is unique per deployment: the address derives from the txid.
    Protocol.Transaction trx = TvmTestUtils.generateDeploySmartContractAndGetTransaction(
        "N" + deployCount++, owner, "[]", initCode, BALANCE, FEE_LIMIT, 100, null, 100_000);
    byte[] address = WalletUtil.generateContractAddress(trx);
    TransactionCapsule cap = new TransactionCapsule(trx);
    TransactionTrace trace = new TransactionTrace(cap, StoreFactory.getInstance(),
        new RuntimeImpl());
    cap.setTrxTrace(trace);
    trace.init(null);
    trace.exec();
    trace.finalization();
    Assert.assertEquals(SUCCESS, trace.getRuntime().getResult().getResultCode());
    Assert.assertEquals(BALANCE, dbManager.getAccountStore().get(address).getBalance());
    return address;
  }

  private TVMTestResult trigger(byte[] contract, String calldataHex, contractResult expected)
      throws Exception {
    TransactionCapsule cap = new TransactionCapsule(
        TvmTestUtils.generateTriggerSmartContractAndGetTransaction(
            owner, contract, Hex.decode(calldataHex), 0, FEE_LIMIT));
    TransactionTrace trace = new TransactionTrace(cap, StoreFactory.getInstance(),
        new RuntimeImpl());
    cap.setTrxTrace(trace);
    trace.init(null);
    trace.exec();
    trace.finalization();
    trace.setResult();
    TVMTestResult result = new TVMTestResult(trace.getRuntime(), trace.getReceipt(), null);
    Assert.assertEquals(expected, result.getReceipt().getResult());
    return result;
  }

  private static long word(TVMTestResult result, int index) {
    byte[] data = result.getRuntime().getResult().getHReturn();
    return new BigInteger(1, Arrays.copyOfRange(data, index * 32, index * 32 + 32)).longValue();
  }

  private static String words(TVMTestResult result) {
    byte[] data = result.getRuntime().getResult().getHReturn();
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < data.length / 32; i++) {
      sb.append(i == 0 ? "" : ", ").append(word(result, i));
    }
    return sb.append("]").toString();
  }

  // ---------------------------------------------------------------- calldata

  private static String freezeCalldata(long amount, int res) {
    return words(0, amount, res, 0, 0);
  }

  /** The five STAKE words for op 1 (delegate) or 2 (un-delegate) against RECEIVER. */
  private static String stakeCalldata(int op, int res, long amount, long mode) {
    return words(op) + addressWord(RECEIVER) + words(amount, res, mode);
  }

  /** CALLER words: target, outerMode, then the five STAKE words. */
  private static String callerCalldata(byte[] target, long outerMode, String stakeWords) {
    return addressWord(target) + words(outerMode) + stakeWords;
  }

  private static String addressWord(byte[] tronAddress) {
    // 32-byte word: 12 zero bytes + the 20-byte address without the 0x41 prefix
    return "000000000000000000000000" + Hex.toHexString(tronAddress, 1, 20);
  }

  private static String words(long... values) {
    StringBuilder sb = new StringBuilder();
    for (long v : values) {
      sb.append(String.format("%064x", v));
    }
    return sb.toString();
  }

  // ---------------------------------------------------------------- bytecode

  /** Minimal assembler: raw opcodes, PUSHn, and two-byte label references. */
  private static final class Asm {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final Map<String, Integer> labels = new HashMap<>();
    private final List<Object[]> fixups = new ArrayList<>();

    Asm op(int... ops) {
      for (int o : ops) {
        out.write(o);
      }
      return this;
    }

    Asm push(int... bytes) {
      out.write(Op.PUSH1 - 1 + bytes.length);
      for (int b : bytes) {
        out.write(b);
      }
      return this;
    }

    /** Smallest PUSH for a value below 65536. */
    Asm pushInt(int v) {
      return v < 0x100 ? push(v) : push(v >> 8, v & 0xff);
    }

    Asm pushLabel(String label) {
      out.write(Op.PUSH1 + 1); // PUSH2
      fixups.add(new Object[] {out.size(), label});
      out.write(0);
      out.write(0);
      return this;
    }

    Asm label(String label) {
      labels.put(label, out.size());
      out.write(Op.JUMPDEST);
      return this;
    }

    /** Pushes calldata word {@code i}. */
    Asm arg(int i) {
      return push(i * 0x20).op(Op.CALLDATALOAD);
    }

    /** mstore(offset, <top of stack>). */
    Asm store(int offset) {
      return pushInt(offset).op(Op.MSTORE);
    }

    /** staticcall(gas, precompile, argsOffset, argsSize, retOffset, 32); pops the success flag. */
    Asm query(int precompile, int argsOffset, int argsSize, int retOffset) {
      return pushInt(0x20).pushInt(retOffset).pushInt(argsSize).pushInt(argsOffset)
          .push(0x01, 0x00, 0x00, precompile).op(Op.GAS, Op.STATICCALL, Op.POP);
    }

    byte[] build() {
      byte[] code = out.toByteArray();
      for (Object[] f : fixups) {
        Integer target = labels.get((String) f[1]);
        Assert.assertNotNull("undefined label " + f[1], target);
        code[(Integer) f[0]] = (byte) (target >> 8);
        code[(Integer) f[0] + 1] = (byte) (target & 0xff);
      }
      Assert.assertTrue("code too long", code.length < 0x10000);
      return code;
    }
  }

  private static final int SCRATCH = 0x100; // argument area for the query precompiles

  private static byte[] buildStake() {
    return new Asm()
        // dispatch on op (w0): 1 -> DELEG, 2 -> UNDELEG, otherwise freeze
        .arg(0).push(1).op(Op.EQ).pushLabel("DELEG").op(Op.JUMPI)
        .arg(0).push(2).op(Op.EQ).pushLabel("UNDELEG").op(Op.JUMPI)
        // op 0: word0 = FREEZEBALANCEV2(amount = w1, resource = w2)   (resource popped first)
        .arg(1).arg(2).op(Op.FREEZEBALANCEV2).store(0x00)
        .pushLabel("TAIL").op(Op.JUMP)
        // op 1: word0 = DELEGATERESOURCE(receiver = w1, amount = w2, resource = w3)
        .label("DELEG")
        .arg(1).arg(2).arg(3).op(Op.DELEGATERESOURCE).store(0x00)
        .pushLabel("OBS").op(Op.JUMP)
        // op 2: word0 = UNDELEGATERESOURCE(receiver = w1, amount = w2, resource = w3)
        .label("UNDELEG")
        .arg(1).arg(2).arg(3).op(Op.UNDELEGATERESOURCE).store(0x00)
        // ops 1 and 2: read the in-flight state back
        .label("OBS")
        // word1 = 0x01000010(target = receiver, from = this, type = resource)
        .arg(1).store(SCRATCH).op(Op.ADDRESS).store(SCRATCH + 0x20).arg(3)
        .store(SCRATCH + 0x40)
        .query(0x10, SCRATCH, 0x60, 0x20)
        // word2 = 0x01000014(this, resource)
        .op(Op.ADDRESS).store(SCRATCH).arg(3).store(SCRATCH + 0x20)
        .query(0x14, SCRATCH, 0x40, 0x40)
        // word3 = 0x01000015(receiver, resource)
        .arg(1).store(SCRATCH).arg(3).store(SCRATCH + 0x20)
        .query(0x15, SCRATCH, 0x40, 0x60)
        // word4 = 0x01000010(target = this, from = this, type = resource): own frozenV2 balance
        .op(Op.ADDRESS).store(SCRATCH).op(Op.ADDRESS).store(SCRATCH + 0x20).arg(3)
        .store(SCRATCH + 0x40)
        .query(0x10, SCRATCH, 0x60, 0x80)
        // tail: mode (w4) != 0 -> REVERT(0, 0xa0), else RETURN(0, 0xa0)
        .label("TAIL")
        .arg(4).pushLabel("REVERT").op(Op.JUMPI)
        .push(0xa0).push(0x00).op(Op.RETURN)
        .label("REVERT")
        .push(0xa0).push(0x00).op(Op.REVERT)
        .build();
  }

  private static byte[] buildCaller() {
    return new Asm()
        // calldatacopy(0, 0x40, 0xa0): the five STAKE words become the call's input
        .push(0xa0).push(0x40).push(0x00).op(Op.CALLDATACOPY)
        // CALL(gas, target = w0, value 0, in = mem[0..0xa0), out = mem[0x100..0x1a0))
        .push(0xa0).pushInt(0x100).push(0xa0).push(0x00).push(0x00).arg(0).op(Op.GAS, Op.CALL)
        // word5 = success flag of the CALL
        .store(0x1a0)
        // outerMode (w1) != 0 -> REVERT(0x100, 0xc0), else RETURN(0x100, 0xc0)
        .arg(1).pushLabel("REVERT").op(Op.JUMPI)
        .push(0xc0).pushInt(0x100).op(Op.RETURN)
        .label("REVERT")
        .push(0xc0).pushInt(0x100).op(Op.REVERT)
        .build();
  }

  /** Init code: copy the runtime code to memory and return it. */
  private static String initCode(byte[] runtime) {
    // PUSH2 len DUP1 PUSH1 0x0c PUSH1 0 CODECOPY PUSH1 0 RETURN  (12 bytes)
    return String.format("61%04x80600c6000396000f3", runtime.length) + Hex.toHexString(runtime);
  }
}
