package org.tron.common.runtime.vm;

import static org.tron.core.config.Parameter.ChainConstant.TRX_PRECISION;
import static org.tron.protos.Protocol.Transaction.Result.contractResult.REVERT;
import static org.tron.protos.Protocol.Transaction.Result.contractResult.SUCCESS;

import com.google.common.primitives.Bytes;
import com.google.protobuf.ByteString;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
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
 * Checks ONE property: the state changes made by a valid DELEGATERESOURCE (Stake 2.0) are
 * discarded when the execution that contains it reverts afterwards.
 *
 * <p>Existing coverage (FreezeV2Test#testDelegateResourceOperations) only produces REVERT when the
 * delegation itself fails validation. Here the delegation SUCCEEDS and the enclosing execution
 * reverts after it.
 *
 * <p>One hand-assembled contract, driven by calldata (five 32-byte words, no selector):
 * <pre>
 * w0 op      0 = freeze, 1 = delegate
 * w1..w3     op 0: amount, resource, -      op 1: receiver, amount, resource
 * w4 mode    0 = RETURN the 4 result words, otherwise REVERT with the same 4 words
 *
 * op 0: word0 = FREEZEBALANCEV2(amount, resource)
 * op 1: word0 = DELEGATERESOURCE(receiver, amount, resource)   (the op under test)
 *       then, inside the same execution and before RETURN/REVERT, read back through the Stake 2.0
 *       query precompiles (they read the in-flight state, not the persisted one):
 *       word1 = 0x01000010(receiver, this, resource)   amount this delegated to receiver
 *       word2 = 0x01000014(this, resource)             total delegated by this
 *       word3 = 0x01000015(receiver, resource)         total acquired by receiver
 * </pre>
 * Words returned in the revert data prove that the delegation existed, in the VM's own view,
 * before the revert, so an unchanged persisted state afterwards is meaningful.
 *
 * <p>For each resource the reverted run is followed, from the state the revert left, by the same
 * call without a revert (control). The control shows which fields really change, and that the
 * delegation can be repeated with identical arguments (nothing consumed by the reverted run).
 */
public class DelegateResourceRevertTest extends BaseMethodTest {

  private static final int BANDWIDTH = 0;
  private static final int ENERGY = 1;

  // Explicit configuration (see afterInit). Latest block time is fixed so the index timestamps
  // are deterministic.
  private static final long HEAD_TIMESTAMP = 1_700_000_000_000L;
  private static final long BALANCE = 100_000_000_000_000_000L;
  private static final long FEE_LIMIT = 1_000_000_000L;
  private static final long FROZEN = 10 * TRX_PRECISION;
  private static final long DELEGATED = 4 * TRX_PRECISION;

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
    // a plain (non-contract) receiver, as DelegateResourceProcessor requires
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
  public void delegatedBandwidthIsDiscardedWhenExecutionReverts() throws Exception {
    runScenario(BANDWIDTH);
  }

  @Test
  public void delegatedEnergyIsDiscardedWhenExecutionReverts() throws Exception {
    runScenario(ENERGY);
  }

  private void runScenario(int res) throws Exception {
    String tag = res == BANDWIDTH ? "bandwidth" : "energy";
    byte[] contract = deploy();

    // valid precondition, built through the VM itself: the contract stakes for both resources
    for (int r : new int[] {BANDWIDTH, ENERGY}) {
      TVMTestResult freeze = trigger(contract, freezeCalldata(FROZEN, r), SUCCESS);
      Assert.assertEquals(tag + ": setup freeze must return 1", 1L, word(freeze, 0));
    }

    State before = capture(contract);
    // the starting state really is "valid, nothing delegated yet"
    Assert.assertEquals(tag + ": setup frozenV2", FROZEN, before.owner.frozen[res]);
    Assert.assertEquals(tag + ": setup delegated", 0L, before.owner.delegated[res]);
    Assert.assertEquals(tag + ": setup acquired", 0L, before.receiver.acquired[res]);
    Assert.assertNull(tag + ": setup delegation record", before.record);
    Assert.assertNull(tag + ": setup from-index", before.fromIndex);
    Assert.assertNull(tag + ": setup to-index", before.toIndex);
    Assert.assertTrue(tag + ": setup index view", before.ownerTo.isEmpty()
        && before.receiverFrom.isEmpty());

    // ---- reverted run
    TVMTestResult reverted = trigger(contract, delegateCalldata(res, 1), REVERT);
    State afterRevert = capture(contract);
    Assert.assertTrue(tag + ": runtime must be marked as reverted",
        reverted.getRuntime().getResult().isRevert());
    assertDelegationVisibleInsideExecution(tag + " (reverted)", reverted);
    assertNothingChanged(tag + " after revert", before, afterRevert);
    assertOnlyEnergyFeeCharged(tag + " after revert", before, afterRevert, reverted);

    // ---- control: same contract, same arguments, no revert
    TVMTestResult committed = trigger(contract, delegateCalldata(res, 0), SUCCESS);
    State afterCommit = capture(contract);
    assertDelegationVisibleInsideExecution(tag + " (control)", committed);
    assertDelegationApplied(tag + " control", before, afterCommit, res, contract);
    assertOnlyEnergyFeeCharged(tag + " control", afterRevert, afterCommit, committed);
  }

  // ---------------------------------------------------------------- assertions

  /** Words returned/reverted by the contract: ok, delegated to receiver, total out, total in. */
  private void assertDelegationVisibleInsideExecution(String tag, TVMTestResult result) {
    byte[] data = result.getRuntime().getResult().getHReturn();
    Assert.assertEquals(tag + ": 4 result words expected", 128, data.length);
    Assert.assertEquals(tag + ": DELEGATERESOURCE must have returned 1", 1L, word(result, 0));
    Assert.assertEquals(tag + ": in-execution delegated amount (0x01000010)",
        DELEGATED, word(result, 1));
    Assert.assertEquals(tag + ": in-execution total delegated by owner (0x01000014)",
        DELEGATED, word(result, 2));
    Assert.assertEquals(tag + ": in-execution total acquired by receiver (0x01000015)",
        DELEGATED, word(result, 3));
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
    }
    Assert.assertArrayEquals(tag + ": full serialized account", before.bytes, after.bytes);
  }

  /** Control: exactly the expected fields change, by exactly the delegated amount. */
  private void assertDelegationApplied(
      String tag, State before, State after, int res, byte[] contract) {
    // owner: moves DELEGATED from "frozen" to "delegated"; no TRX moves
    Assert.assertEquals(tag + ": owner available balance", before.owner.available,
        after.owner.available);
    Assert.assertEquals(tag + ": owner tron power", before.owner.tronPower, after.owner.tronPower);
    for (int r = 0; r < 2; r++) {
      boolean tested = r == res;
      Assert.assertEquals(tag + ": owner frozenV2, resource " + r,
          before.owner.frozen[r] - (tested ? DELEGATED : 0), after.owner.frozen[r]);
      Assert.assertEquals(tag + ": owner delegated, resource " + r,
          before.owner.delegated[r] + (tested ? DELEGATED : 0), after.owner.delegated[r]);
      Assert.assertEquals(tag + ": owner acquired, resource " + r,
          before.owner.acquired[r], after.owner.acquired[r]);
      // receiver: only "acquired" of the tested resource grows
      Assert.assertEquals(tag + ": receiver acquired, resource " + r,
          before.receiver.acquired[r] + (tested ? DELEGATED : 0), after.receiver.acquired[r]);
      Assert.assertEquals(tag + ": receiver frozenV2, resource " + r,
          before.receiver.frozen[r], after.receiver.frozen[r]);
      Assert.assertEquals(tag + ": receiver delegated, resource " + r,
          before.receiver.delegated[r], after.receiver.delegated[r]);
    }
    Assert.assertEquals(tag + ": receiver available balance", before.receiver.available,
        after.receiver.available);

    // delegation record
    Assert.assertNotNull(tag + ": delegation record must exist", after.recordCapsule);
    Assert.assertEquals(tag + ": record bandwidth", res == BANDWIDTH ? DELEGATED : 0,
        after.recordCapsule.getFrozenBalanceForBandwidth());
    Assert.assertEquals(tag + ": record energy", res == ENERGY ? DELEGATED : 0,
        after.recordCapsule.getFrozenBalanceForEnergy());
    Assert.assertEquals(tag + ": record bandwidth expiry", 0L,
        after.recordCapsule.getExpireTimeForBandwidth());
    Assert.assertEquals(tag + ": record energy expiry", 0L,
        after.recordCapsule.getExpireTimeForEnergy());
    Assert.assertNull(tag + ": locked record stays absent (TVM never locks)", after.lockedRecord);

    // the two index entries relating both accounts
    Assert.assertNotNull(tag + ": from-index entry must exist", after.fromIndexCapsule);
    Assert.assertEquals(tag + ": from-index points to receiver",
        Hex.toHexString(RECEIVER), Hex.toHexString(after.fromIndexCapsule.getAccount()
            .toByteArray()));
    Assert.assertEquals(tag + ": from-index timestamp", HEAD_TIMESTAMP,
        after.fromIndexCapsule.getTimestamp());
    Assert.assertNotNull(tag + ": to-index entry must exist", after.toIndexCapsule);
    Assert.assertEquals(tag + ": to-index points to owner",
        Hex.toHexString(contract), Hex.toHexString(after.toIndexCapsule.getAccount()
            .toByteArray()));
    Assert.assertEquals(tag + ": to-index timestamp", HEAD_TIMESTAMP,
        after.toIndexCapsule.getTimestamp());
    Assert.assertEquals(tag + ": owner's delegated-to list (API view)",
        Arrays.asList(Hex.toHexString(RECEIVER)), after.ownerTo);
    Assert.assertEquals(tag + ": receiver's delegated-from list (API view)",
        Arrays.asList(Hex.toHexString(contract)), after.receiverFrom);
    Assert.assertTrue(tag + ": owner has no incoming, receiver no outgoing delegations",
        after.ownerFrom.isEmpty() && after.receiverTo.isEmpty());

    // delegating moves ownership of stake, it does not change how much is staked in total
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
    final long[] frozen;     // frozenV2, [bandwidth, energy]
    final long[] delegated;  // delegatedFrozenV2, [bandwidth, energy]
    final long[] acquired;   // acquiredDelegatedFrozenV2, [bandwidth, energy]
    final byte[] bytes;

    Acct(AccountCapsule a) {
      available = a.getBalance();
      tronPower = a.getTronPowerFrozenV2Balance();
      frozen = new long[] {a.getFrozenV2BalanceForBandwidth(), a.getFrozenV2BalanceForEnergy()};
      delegated = new long[] {a.getDelegatedFrozenV2BalanceForBandwidth(),
          a.getDelegatedFrozenV2BalanceForEnergy()};
      acquired = new long[] {a.getAcquiredDelegatedFrozenV2BalanceForBandwidth(),
          a.getAcquiredDelegatedFrozenV2BalanceForEnergy()};
      bytes = a.getData();
    }
  }

  private static final class State {
    Acct owner;
    Acct receiver;
    DelegatedResourceCapsule recordCapsule;  // lock=false key, null if absent
    byte[] record;
    byte[] lockedRecord;                      // lock=true key, null if absent
    DelegatedResourceAccountIndexCapsule fromIndexCapsule;
    DelegatedResourceAccountIndexCapsule toIndexCapsule;
    byte[] fromIndex;
    byte[] toIndex;
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
    s.fromIndexCapsule = indexStore.get(Bytes.concat(
        DelegatedResourceAccountIndexStore.getV2_FROM_PREFIX(), contract, RECEIVER));
    s.toIndexCapsule = indexStore.get(Bytes.concat(
        DelegatedResourceAccountIndexStore.getV2_TO_PREFIX(), RECEIVER, contract));
    s.fromIndex = s.fromIndexCapsule == null ? null : s.fromIndexCapsule.getData();
    s.toIndex = s.toIndexCapsule == null ? null : s.toIndexCapsule.getData();

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
    return out;
  }

  // ---------------------------------------------------------------- running transactions

  private byte[] deploy() throws Exception {
    // consumeUserResourcePercent = 100: the caller pays all the energy, not the contract owner.
    // The name is unique per deployment: the address derives from the txid.
    Protocol.Transaction trx = TvmTestUtils.generateDeploySmartContractAndGetTransaction(
        "D" + deployCount++, owner, "[]", INIT_CODE, BALANCE, FEE_LIMIT, 100, null, 100_000);
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

  // ---------------------------------------------------------------- calldata

  private static String freezeCalldata(long amount, int res) {
    return words(0, amount, res, 0, 0);
  }

  private static String delegateCalldata(int res, long mode) {
    // op = 1, receiver (address word), amount, resource, mode
    return words(1) + "000000000000000000000000" + Hex.toHexString(RECEIVER, 1, 20)
        + words(DELEGATED, res, mode);
  }

  private static String words(long... values) {
    StringBuilder sb = new StringBuilder();
    for (long v : values) {
      sb.append(String.format("%064x", v));
    }
    return sb.toString();
  }

  // ---------------------------------------------------------------- bytecode

  /** Minimal assembler: raw opcodes, PUSHn, and one-byte label references (code stays < 256). */
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

    Asm pushLabel(String label) {
      out.write(Op.PUSH1);
      fixups.add(new Object[] {out.size(), label});
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
      return push(offset).op(Op.MSTORE);
    }

    /** staticcall(gas, precompile, 0x80, argsSize, retOffset, 32); pops the success flag. */
    Asm query(int precompile, int argsSize, int retOffset) {
      return push(0x20).push(retOffset).push(argsSize).push(0x80)
          .push(0x01, 0x00, 0x00, precompile).op(Op.GAS, Op.STATICCALL, Op.POP);
    }

    byte[] build() {
      byte[] code = out.toByteArray();
      for (Object[] f : fixups) {
        Integer target = labels.get((String) f[1]);
        Assert.assertNotNull("undefined label " + f[1], target);
        code[(Integer) f[0]] = (byte) (int) target;
      }
      Assert.assertTrue("code must fit in one-byte jump targets", code.length < 256);
      return code;
    }
  }

  private static byte[] buildRuntime() {
    return new Asm()
        // dispatch: op != 0 -> DELEG
        .arg(0).pushLabel("DELEG").op(Op.JUMPI)
        // op 0: word0 = FREEZEBALANCEV2(amount = w1, resource = w2)   (resource is popped first)
        .arg(1).arg(2).op(Op.FREEZEBALANCEV2).store(0x00)
        .pushLabel("TAIL").op(Op.JUMP)
        // op 1
        .label("DELEG")
        // word0 = DELEGATERESOURCE(receiver = w1, amount = w2, resource = w3)
        .arg(1).arg(2).arg(3).op(Op.DELEGATERESOURCE).store(0x00)
        // word1 = 0x01000010(target = receiver, from = this, type = resource)
        .arg(1).store(0x80).op(Op.ADDRESS).store(0xa0).arg(3).store(0xc0)
        .query(0x10, 0x60, 0x20)
        // word2 = 0x01000014(this, resource)
        .op(Op.ADDRESS).store(0x80).arg(3).store(0xa0)
        .query(0x14, 0x40, 0x40)
        // word3 = 0x01000015(receiver, resource)
        .arg(1).store(0x80).arg(3).store(0xa0)
        .query(0x15, 0x40, 0x60)
        // tail: mode (w4) != 0 -> REVERT(0, 0x80), else RETURN(0, 0x80)
        .label("TAIL")
        .arg(4).pushLabel("REVERT").op(Op.JUMPI)
        .push(0x80).push(0x00).op(Op.RETURN)
        .label("REVERT")
        .push(0x80).push(0x00).op(Op.REVERT)
        .build();
  }

  /** Init code: copy the runtime code to memory and return it. */
  private static String initCode(byte[] runtime) {
    // PUSH1 len DUP1 PUSH1 0x0b PUSH1 0 CODECOPY PUSH1 0 RETURN  (11 bytes)
    return String.format("60%02x80600b6000396000f3", runtime.length) + Hex.toHexString(runtime);
  }
}
