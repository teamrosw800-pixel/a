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
 * Checks ONE property: the state changes made by a valid UNDELEGATERESOURCE (Stake 2.0) are
 * discarded when the execution that contains it reverts afterwards.
 *
 * <p>Existing coverage (FreezeV2Test#testDelegateResourceOperations) only produces REVERT when the
 * un-delegation itself fails validation, and its receiver never has resource usage, so the usage
 * transfer of UnDelegateResourceProcessor is not exercised. Here the un-delegation SUCCEEDS, the
 * receiver has real usage, and the enclosing execution reverts after it.
 *
 * <p>Four scenarios, each in a fresh Spring context: {bandwidth, energy} x {partial, full}.
 * "Full" un-delegates everything that was delegated, which makes the processor write empty
 * capsules for both index entries (a deletion on commit); a revert must bring them back.
 *
 * <p>One hand-assembled contract, driven by calldata (five 32-byte words, no selector):
 * <pre>
 * w0 op      0 = freeze, 1 = delegate, 2 = un-delegate
 * w1..w3     op 0: amount, resource, -   ops 1, 2: receiver, amount, resource
 * w4 mode    0 = RETURN the 5 result words, otherwise REVERT with the same 5 words
 *
 * op 2: word0 = UNDELEGATERESOURCE(receiver, amount, resource)   (the op under test)
 *       then, inside the same execution and before RETURN/REVERT, read back through the Stake 2.0
 *       query precompiles (they read the in-flight state, not the persisted one):
 *       word1 = 0x01000010(receiver, this, resource)   still delegated to receiver
 *       word2 = 0x01000014(this, resource)             total delegated by this
 *       word3 = 0x01000015(receiver, resource)         total acquired by receiver
 *       word4 = 0x01000010(this, this, resource)       this contract's own frozenV2 balance
 * </pre>
 *
 * <p>For each scenario the reverted run is followed, from the state the revert left, by the same
 * call without a revert (control).
 */
public class UnDelegateResourceRevertTest extends BaseMethodTest {

  private static final long HEAD_TIMESTAMP = 1_700_000_000_000L;
  private static final long BALANCE = 100_000_000_000_000_000L;
  private static final long FEE_LIMIT = 1_000_000_000L;
  private static final long FROZEN = 10 * TRX_PRECISION;
  private static final long DELEGATED = 4 * TRX_PRECISION;
  private static final long PARTIAL = 1 * TRX_PRECISION;
  private static final long FULL = DELEGATED;
  // raw resource usage given to the receiver, and how many slots old its last consume time is
  private static final long USAGE = 1_000_000L;
  private static final long STALE_SLOTS = 1_000L;

  private static final byte[] CODE = buildRuntime();
  private static final String INIT_CODE = initCode(CODE);

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

  @Test
  public void partialBandwidthUnDelegationIsDiscardedWhenExecutionReverts() throws Exception {
    runScenario(0, PARTIAL);
  }

  @Test
  public void fullBandwidthUnDelegationIsDiscardedWhenExecutionReverts() throws Exception {
    runScenario(0, FULL);
  }

  @Test
  public void partialEnergyUnDelegationIsDiscardedWhenExecutionReverts() throws Exception {
    runScenario(1, PARTIAL);
  }

  @Test
  public void fullEnergyUnDelegationIsDiscardedWhenExecutionReverts() throws Exception {
    runScenario(1, FULL);
  }

  private void runScenario(int res, long amount) throws Exception {
    boolean full = amount == DELEGATED;
    String tag = (res == 0 ? "bandwidth" : "energy") + (full ? " full" : " partial");
    byte[] contract = deploy();

    // valid precondition, built through the VM: stake both resources, delegate DELEGATED of the
    // tested one
    for (int r = 0; r < 2; r++) {
      Assert.assertEquals(tag + ": setup freeze must return 1", 1L,
          word(trigger(contract, freezeCalldata(FROZEN, r), SUCCESS), 0));
    }
    Assert.assertEquals(tag + ": setup delegation must return 1", 1L,
        word(trigger(contract, calldata(1, res, DELEGATED, 0), SUCCESS), 0));
    // give the receiver real, slightly stale usage of the tested resource
    long headSlot = chainBaseManager.getHeadSlot();
    AccountCapsule seeded = dbManager.getAccountStore().get(RECEIVER);
    if (res == 0) {
      seeded.setNetUsage(USAGE);
      seeded.setLatestConsumeTime(headSlot - STALE_SLOTS);
    } else {
      seeded.setEnergyUsage(USAGE);
      seeded.setLatestConsumeTimeForEnergy(headSlot - STALE_SLOTS);
    }
    dbManager.getAccountStore().put(seeded.createDbKey(), seeded);

    State before = capture(contract);
    Assert.assertEquals(tag + ": setup owner frozenV2", FROZEN - DELEGATED,
        before.owner.frozen[res]);
    Assert.assertEquals(tag + ": setup owner delegated", DELEGATED, before.owner.delegated[res]);
    Assert.assertEquals(tag + ": setup receiver acquired", DELEGATED,
        before.receiver.acquired[res]);
    Assert.assertEquals(tag + ": setup receiver usage", USAGE, before.receiver.usage[res]);
    Assert.assertEquals(tag + ": setup receiver last consume", headSlot - STALE_SLOTS,
        before.receiver.lastConsume[res]);
    Assert.assertEquals(tag + ": setup owner usage", 0L, before.owner.usage[res]);
    Assert.assertNotNull(tag + ": setup delegation record", before.recordCapsule);
    Assert.assertEquals(tag + ": setup record", DELEGATED, recordBalance(before, res));
    Assert.assertNotNull(tag + ": setup from-index", before.fromIndex);
    Assert.assertNotNull(tag + ": setup to-index", before.toIndex);
    Assert.assertEquals(tag + ": setup delegated-to view", 1, before.ownerTo.size());
    Assert.assertEquals(tag + ": setup delegated-from view", 1, before.receiverFrom.size());

    // ---- reverted run
    TVMTestResult reverted = trigger(contract, calldata(2, res, amount, 1), REVERT);
    State afterRevert = capture(contract);
    Assert.assertTrue(tag + ": runtime must be marked as reverted",
        reverted.getRuntime().getResult().isRevert());
    assertUnDelegationVisibleInsideExecution(tag + " (reverted)", reverted, amount);
    assertNothingChanged(tag + " after revert", before, afterRevert);
    assertOnlyEnergyFeeCharged(tag + " after revert", before, afterRevert, reverted);

    // ---- control: same contract, same arguments, no revert
    TVMTestResult committed = trigger(contract, calldata(2, res, amount, 0), SUCCESS);
    State afterCommit = capture(contract);
    assertUnDelegationVisibleInsideExecution(tag + " (control)", committed, amount);
    assertUnDelegationApplied(tag + " control", before, afterCommit, res, amount, full);
    assertOnlyEnergyFeeCharged(tag + " control", afterRevert, afterCommit, committed);

    System.out.println("OBS " + tag
        + " | in-execution words " + words(reverted)
        + " | receiver usage " + before.receiver.usage[res] + " -> "
        + afterCommit.receiver.usage[res]
        + " | owner usage " + before.owner.usage[res] + " -> " + afterCommit.owner.usage[res]
        + " | receiver lastConsume " + before.receiver.lastConsume[res] + " -> "
        + afterCommit.receiver.lastConsume[res] + " (head slot " + headSlot + ")"
        + " | record after control " + (afterCommit.recordCapsule == null ? "absent"
            : "bw=" + afterCommit.recordCapsule.getFrozenBalanceForBandwidth()
            + " en=" + afterCommit.recordCapsule.getFrozenBalanceForEnergy())
        + " | index entries after control from=" + (afterCommit.fromIndex != null)
        + " to=" + (afterCommit.toIndex != null));
  }

  // ---------------------------------------------------------------- assertions

  private static long recordBalance(State s, int res) {
    return res == 0 ? s.recordCapsule.getFrozenBalanceForBandwidth()
        : s.recordCapsule.getFrozenBalanceForEnergy();
  }

  /** Words: ok, still delegated to receiver, total out, total in, owner's own frozenV2. */
  private void assertUnDelegationVisibleInsideExecution(
      String tag, TVMTestResult result, long amount) {
    byte[] data = result.getRuntime().getResult().getHReturn();
    Assert.assertEquals(tag + ": 5 result words expected", 160, data.length);
    Assert.assertEquals(tag + ": UNDELEGATERESOURCE must have returned 1", 1L, word(result, 0));
    Assert.assertEquals(tag + ": in-execution still delegated to receiver (0x01000010)",
        DELEGATED - amount, word(result, 1));
    Assert.assertEquals(tag + ": in-execution total delegated by owner (0x01000014)",
        DELEGATED - amount, word(result, 2));
    Assert.assertEquals(tag + ": in-execution total acquired by receiver (0x01000015)",
        DELEGATED - amount, word(result, 3));
    Assert.assertEquals(tag + ": in-execution owner's own frozenV2 (0x01000010)",
        FROZEN - DELEGATED + amount, word(result, 4));
  }

  /** Every store and index the operation can touch is identical to the state before. */
  private void assertNothingChanged(String tag, State before, State after) {
    assertAccountEqual(tag + " / owner", before.owner, after.owner);
    assertAccountEqual(tag + " / receiver", before.receiver, after.receiver);
    Assert.assertArrayEquals(tag + ": delegation record (lock=false key)",
        before.record, after.record);
    Assert.assertArrayEquals(tag + ": delegation record (lock=true key)",
        before.lockedRecord, after.lockedRecord);
    Assert.assertArrayEquals(tag + ": from-index entry", before.fromIndex, after.fromIndex);
    Assert.assertArrayEquals(tag + ": to-index entry", before.toIndex, after.toIndex);
    Assert.assertEquals(tag + ": owner's delegated-to list (API view)",
        before.ownerTo, after.ownerTo);
    Assert.assertEquals(tag + ": receiver's delegated-from list (API view)",
        before.receiverFrom, after.receiverFrom);
    Assert.assertEquals(tag + ": owner's delegated-from list (API view)",
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

  /** Control: the expected fields change, by the un-delegated amount, and nothing else does. */
  private void assertUnDelegationApplied(
      String tag, State before, State after, int res, long amount, boolean full) {
    // owner: gets `amount` back as frozen, loses it as delegated; the usage transferred from the
    // receiver lands on the owner; no TRX moves
    Assert.assertEquals(tag + ": owner available balance", before.owner.available,
        after.owner.available);
    Assert.assertEquals(tag + ": owner tron power", before.owner.tronPower, after.owner.tronPower);
    Assert.assertEquals(tag + ": receiver available balance", before.receiver.available,
        after.receiver.available);
    for (int r = 0; r < 2; r++) {
      boolean tested = r == res;
      Assert.assertEquals(tag + ": owner frozenV2, resource " + r,
          before.owner.frozen[r] + (tested ? amount : 0), after.owner.frozen[r]);
      Assert.assertEquals(tag + ": owner delegated, resource " + r,
          before.owner.delegated[r] - (tested ? amount : 0), after.owner.delegated[r]);
      Assert.assertEquals(tag + ": owner acquired, resource " + r,
          before.owner.acquired[r], after.owner.acquired[r]);
      Assert.assertEquals(tag + ": receiver acquired, resource " + r,
          before.receiver.acquired[r] - (tested ? amount : 0), after.receiver.acquired[r]);
      Assert.assertEquals(tag + ": receiver frozenV2, resource " + r,
          before.receiver.frozen[r], after.receiver.frozen[r]);
      Assert.assertEquals(tag + ": receiver delegated, resource " + r,
          before.receiver.delegated[r], after.receiver.delegated[r]);
      if (tested) {
        // the usage-transfer path really ran: usage left the receiver and reached the owner
        Assert.assertTrue(tag + ": receiver usage must drop (before "
                + before.receiver.usage[r] + ", after " + after.receiver.usage[r] + ")",
            after.receiver.usage[r] < before.receiver.usage[r]);
        Assert.assertTrue(tag + ": owner usage must grow (before " + before.owner.usage[r]
                + ", after " + after.owner.usage[r] + ")",
            after.owner.usage[r] > before.owner.usage[r]);
        Assert.assertEquals(tag + ": receiver last consume time moves to the head slot",
            chainBaseManager.getHeadSlot(), after.receiver.lastConsume[r]);
      } else {
        Assert.assertEquals(tag + ": receiver usage, untouched resource " + r,
            before.receiver.usage[r], after.receiver.usage[r]);
        Assert.assertEquals(tag + ": owner usage, untouched resource " + r,
            before.owner.usage[r], after.owner.usage[r]);
        Assert.assertEquals(tag + ": receiver last consume, untouched resource " + r,
            before.receiver.lastConsume[r], after.receiver.lastConsume[r]);
      }
    }

    // delegation record: rewritten with the reduced balance; never deleted, never locked
    Assert.assertNotNull(tag + ": delegation record still exists", after.recordCapsule);
    Assert.assertEquals(tag + ": record bandwidth", res == 0 ? DELEGATED - amount : 0,
        after.recordCapsule.getFrozenBalanceForBandwidth());
    Assert.assertEquals(tag + ": record energy", res == 1 ? DELEGATED - amount : 0,
        after.recordCapsule.getFrozenBalanceForEnergy());
    Assert.assertNull(tag + ": locked record stays absent", after.lockedRecord);

    if (full) {
      // nothing left delegated: both index entries are removed and the API views are empty
      Assert.assertNull(tag + ": from-index entry removed", after.fromIndex);
      Assert.assertNull(tag + ": to-index entry removed", after.toIndex);
      Assert.assertTrue(tag + ": API views empty", after.ownerTo.isEmpty()
          && after.receiverFrom.isEmpty() && after.ownerFrom.isEmpty()
          && after.receiverTo.isEmpty());
    } else {
      // still delegated: the index entries are not rewritten
      Assert.assertArrayEquals(tag + ": from-index entry kept", before.fromIndex,
          after.fromIndex);
      Assert.assertArrayEquals(tag + ": to-index entry kept", before.toIndex, after.toIndex);
      Assert.assertEquals(tag + ": owner's delegated-to list", before.ownerTo, after.ownerTo);
      Assert.assertEquals(tag + ": receiver's delegated-from list", before.receiverFrom,
          after.receiverFrom);
    }
    Assert.assertArrayEquals(tag + ": global weights", before.weights, after.weights);
  }

  /**
   * The protocol keeps the energy of a reverted call: the caller pays it, and that is the only
   * permitted difference in balances. (Bandwidth is charged by the block processor, which this
   * harness does not run, so it is not part of the comparison.)
   */
  private static void assertOnlyEnergyFeeCharged(
      String tag, State before, State after, TVMTestResult result) {
    long energyFee = result.getReceipt().getEnergyFee();
    Assert.assertTrue(tag + ": energy is consumed even though the call reverted",
        result.getReceipt().getEnergyUsageTotal() > 0);
    Assert.assertEquals(tag + ": caller balance decreases by exactly the energy fee",
        before.callerBalance - energyFee, after.callerBalance);
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
    Acct owner;
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
    long callerBalance;
  }

  private State capture(byte[] contract) {
    State s = new State();
    s.owner = new Acct(dbManager.getAccountStore().get(contract));
    s.receiver = new Acct(dbManager.getAccountStore().get(RECEIVER));

    s.recordCapsule = dbManager.getDelegatedResourceStore().get(
        DelegatedResourceCapsule.createDbKeyV2(contract, RECEIVER, false));
    s.record = s.recordCapsule == null ? null : s.recordCapsule.getData();
    DelegatedResourceCapsule locked = dbManager.getDelegatedResourceStore().get(
        DelegatedResourceCapsule.createDbKeyV2(contract, RECEIVER, true));
    s.lockedRecord = locked == null ? null : locked.getData();

    DelegatedResourceAccountIndexStore indexStore =
        dbManager.getDelegatedResourceAccountIndexStore();
    DelegatedResourceAccountIndexCapsule from = indexStore.get(Bytes.concat(
        DelegatedResourceAccountIndexStore.getV2_FROM_PREFIX(), contract, RECEIVER));
    DelegatedResourceAccountIndexCapsule to = indexStore.get(Bytes.concat(
        DelegatedResourceAccountIndexStore.getV2_TO_PREFIX(), RECEIVER, contract));
    s.fromIndex = from == null ? null : from.getData();
    s.toIndex = to == null ? null : to.getData();

    DelegatedResourceAccountIndexCapsule ownerView = indexStore.getV2Index(contract);
    DelegatedResourceAccountIndexCapsule receiverView = indexStore.getV2Index(RECEIVER);
    s.ownerTo = hex(ownerView.getToAccountsList());
    s.ownerFrom = hex(ownerView.getFromAccountsList());
    s.receiverTo = hex(receiverView.getToAccountsList());
    s.receiverFrom = hex(receiverView.getFromAccountsList());

    DynamicPropertiesStore dps = dbManager.getDynamicPropertiesStore();
    s.weights = new long[] {dps.getTotalNetWeight(), dps.getTotalEnergyWeight(),
        dps.getTotalTronPowerWeight()};
    s.callerBalance = dbManager.getAccountStore().get(owner).getBalance();
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

  private byte[] deploy() throws Exception {
    // consumeUserResourcePercent = 100: the caller pays all the energy, not the contract owner.
    // The name is unique per deployment: the address derives from the txid.
    Protocol.Transaction trx = TvmTestUtils.generateDeploySmartContractAndGetTransaction(
        "U" + deployCount++, owner, "[]", INIT_CODE, BALANCE, FEE_LIMIT, 100, null, 100_000);
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

  /** op 1 (delegate) or op 2 (un-delegate) against RECEIVER. */
  private static String calldata(int op, int res, long amount, long mode) {
    return words(op) + "000000000000000000000000" + Hex.toHexString(RECEIVER, 1, 20)
        + words(amount, res, mode);
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

  private static byte[] buildRuntime() {
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
        .pushLabel("TAIL").op(Op.JUMP)
        // op 2: word0 = UNDELEGATERESOURCE(receiver = w1, amount = w2, resource = w3)
        .label("UNDELEG")
        .arg(1).arg(2).arg(3).op(Op.UNDELEGATERESOURCE).store(0x00)
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

  /** Init code: copy the runtime code to memory and return it. */
  private static String initCode(byte[] runtime) {
    // PUSH2 len DUP1 PUSH1 0x0c PUSH1 0 CODECOPY PUSH1 0 RETURN  (12 bytes)
    return String.format("61%04x80600c6000396000f3", runtime.length) + Hex.toHexString(runtime);
  }
}
