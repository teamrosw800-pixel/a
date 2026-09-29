package org.tron.common.runtime.vm;

import static org.tron.core.config.Parameter.ChainConstant.TRX_PRECISION;
import static org.tron.protos.Protocol.Transaction.Result.contractResult.REVERT;
import static org.tron.protos.Protocol.Transaction.Result.contractResult.SUCCESS;

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
import org.tron.common.utils.WalletUtil;
import org.tron.core.Wallet;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.db.TransactionTrace;
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
 * Checks ONE property across nested executions for FREEZEBALANCEV2: the state changes made by a
 * valid FREEZEBALANCEV2 (Stake 2.0), executed by a CALLED contract, are discarded when the frame
 * that contains the operation reverts, in two shapes:
 *
 * <ul>
 *   <li><b>Outer reverts</b>: the callee freezes and returns normally, then the caller reverts.
 *       This shape is also covered by {@link FreezeV2RevertTest}; it is repeated here in the same
 *       harness as the other operations of the family.
 *   <li><b>Inner reverts</b>: the callee freezes and then reverts; the caller sees the call fail,
 *       continues and returns normally. The transaction SUCCEEDS and commits, so anything that
 *       leaked out of the inner frame would be persisted. <b>This shape was not covered for
 *       FREEZEBALANCEV2</b> before this class, while the other five operations of the family
 *       already had it.
 * </ul>
 *
 * <p>FREEZEBALANCEV2 changes: the account's available balance, the frozen balance of the resource,
 * the account's old tron power (initialised from 0 to -1 by the first freeze), and the global
 * weight of the resource. Each of the three resources (bandwidth, energy, tron power) is tested.
 *
 * <p>Two hand-assembled contracts.
 * <pre>
 * STAKE   calldata = amount, resource, mode (three 32-byte words, no selector)
 *   word0 = FREEZEBALANCEV2(amount, resource); then, inside the same execution, word1 =
 *   0x0100000d(this, resource) (own frozenV2 balance) and word2 = BALANCE(this); then RETURN the
 *   three words, or REVERT with them if mode != 0
 * CALLER  calldata = target, outerMode, amount, resource, mode
 *   CALL(target, amount, resource, mode); result words = the callee's 3 words (RETURN or REVERT
 *   data, the VM copies both) + the CALL success flag; then RETURN, or REVERT if outerMode != 0
 * </pre>
 */
public class NestedFreezeV2RevertTest extends BaseMethodTest {

  private static final long T0 = 1_700_000_000_000L;
  private static final long BALANCE = 100_000_000_000_000_000L;
  private static final long FEE_LIMIT = 1_000_000_000L;
  private static final long AMOUNT = 5 * TRX_PRECISION;

  private static final String STAKE_INIT = initCode(buildStake());
  private static final String CALLER_INIT = initCode(buildCaller());

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
    root.commit();

    ConfigLoader.disable = true;
    DynamicPropertiesStore dps = dbManager.getDynamicPropertiesStore();
    dps.saveAllowTvmFreeze(1);
    dps.saveUnfreezeDelayDays(30);
    dps.saveAllowNewResourceModel(1L);
    dps.saveAllowDelegateResource(1);
    dps.saveLatestBlockHeaderTimestamp(T0);
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
  public void bandwidthFreezeOuterRevertDiscardsCalleeChanges() throws Exception {
    run(0, false);
  }

  @Test
  public void bandwidthFreezeInnerRevertDiscardsCalleeChanges() throws Exception {
    run(0, true);
  }

  @Test
  public void energyFreezeOuterRevertDiscardsCalleeChanges() throws Exception {
    run(1, false);
  }

  @Test
  public void energyFreezeInnerRevertDiscardsCalleeChanges() throws Exception {
    run(1, true);
  }

  @Test
  public void tronPowerFreezeOuterRevertDiscardsCalleeChanges() throws Exception {
    run(2, false);
  }

  @Test
  public void tronPowerFreezeInnerRevertDiscardsCalleeChanges() throws Exception {
    run(2, true);
  }

  // ---------------------------------------------------------------- scenario

  private void run(int res, boolean innerReverts) throws Exception {
    String tag = "freeze " + (res == 0 ? "bandwidth" : res == 1 ? "energy" : "tron power")
        + (innerReverts ? " / inner reverts" : " / outer reverts");
    byte[] stake = deploy(STAKE_INIT);
    byte[] caller = deploy(CALLER_INIT);

    State before = capture(stake, caller);
    Assert.assertEquals(tag + ": setup available balance", BALANCE, before.stake.available);
    Assert.assertArrayEquals(tag + ": setup nothing frozen", new long[] {0, 0, 0},
        before.stake.frozen);
    Assert.assertEquals(tag + ": setup old tron power (not initialised)", 0L,
        before.stake.oldTronPower);
    Assert.assertArrayEquals(tag + ": setup global weights", new long[] {0, 0, 0},
        before.weights);

    long[] expected = {1, AMOUNT, BALANCE - AMOUNT};

    // ---- reverted arm
    TVMTestResult reverted;
    if (innerReverts) {
      // callee reverts after the op; caller continues and the transaction succeeds
      reverted = trigger(caller, callerCalldata(stake, 0, AMOUNT, res, 1), SUCCESS);
      Assert.assertFalse(tag + ": the transaction itself must not be reverted",
          reverted.getRuntime().getResult().isRevert());
      Assert.assertEquals(tag + ": CALL must report failure", 0L, word(reverted, 3));
    } else {
      // callee returns normally, caller reverts afterwards
      reverted = trigger(caller, callerCalldata(stake, 1, AMOUNT, res, 0), REVERT);
      Assert.assertTrue(tag + ": the transaction must be reverted",
          reverted.getRuntime().getResult().isRevert());
      Assert.assertEquals(tag + ": CALL must report success", 1L, word(reverted, 3));
    }
    assertVisibleInsideExecution(tag + " (reverted arm)", reverted, expected);
    State afterRevert = capture(stake, caller);
    assertNothingChanged(tag + " after revert", before, afterRevert);
    assertOnlyEnergyFeeCharged(tag + " after revert", before, afterRevert, reverted);

    // ---- control: same contracts, same arguments, nothing reverts
    TVMTestResult committed = trigger(caller, callerCalldata(stake, 0, AMOUNT, res, 0), SUCCESS);
    Assert.assertEquals(tag + " control: CALL must report success", 1L, word(committed, 3));
    State afterCommit = capture(stake, caller);
    assertVisibleInsideExecution(tag + " (control)", committed, expected);
    String c = tag + " control";
    long[] frozenAfter = new long[3];
    frozenAfter[res] = AMOUNT;
    Assert.assertArrayEquals(c + ": only the frozen balance of the resource grows", frozenAfter,
        afterCommit.stake.frozen);
    Assert.assertEquals(c + ": available balance drops by the frozen amount",
        before.stake.available - AMOUNT, afterCommit.stake.available);
    long[] weightsAfter = new long[3];
    weightsAfter[res] = AMOUNT / TRX_PRECISION;
    Assert.assertArrayEquals(c + ": only the global weight of the resource grows", weightsAfter,
        afterCommit.weights);
    Assert.assertEquals(c + ": old tron power is initialised (invalid) by the first freeze",
        -1L, afterCommit.stake.oldTronPower);
    Assert.assertArrayEquals(c + ": caller contract account is untouched",
        before.caller.bytes, afterCommit.caller.bytes);
    assertOnlyEnergyFeeCharged(c, afterRevert, afterCommit, committed);

    System.out.println("OBS " + tag + " | reverted-arm words " + words(reverted)
        + " | control words " + words(committed)
        + " | available " + before.stake.available + " -> " + afterCommit.stake.available
        + " | frozen " + Arrays.toString(before.stake.frozen) + " -> "
        + Arrays.toString(afterCommit.stake.frozen)
        + " | weights " + Arrays.toString(before.weights) + " -> "
        + Arrays.toString(afterCommit.weights)
        + " | oldTronPower " + before.stake.oldTronPower + " -> "
        + afterCommit.stake.oldTronPower);
  }

  // ---------------------------------------------------------------- assertions

  private void assertVisibleInsideExecution(String tag, TVMTestResult result, long[] expected) {
    byte[] data = result.getRuntime().getResult().getHReturn();
    Assert.assertEquals(tag + ": 4 result words expected", 128, data.length);
    String[] names = {"operation result", "own frozenV2 (0x0100000d)",
        "available balance (BALANCE)"};
    for (int i = 0; i < 3; i++) {
      Assert.assertEquals(tag + ": in-execution " + names[i], expected[i], word(result, i));
    }
  }

  /** Every store the operation can touch is identical to the state before. */
  private void assertNothingChanged(String tag, State before, State after) {
    Acct b = before.stake;
    Acct a = after.stake;
    Assert.assertEquals(tag + ": available balance", b.available, a.available);
    Assert.assertArrayEquals(tag + ": frozenV2 (bandwidth, energy, tron power)",
        b.frozen, a.frozen);
    Assert.assertEquals(tag + ": old tron power", b.oldTronPower, a.oldTronPower);
    Assert.assertArrayEquals(tag + ": full serialized callee account", b.bytes, a.bytes);
    Assert.assertEquals(tag + ": caller contract available balance",
        before.caller.available, after.caller.available);
    Assert.assertArrayEquals(tag + ": full serialized caller contract account",
        before.caller.bytes, after.caller.bytes);
    Assert.assertArrayEquals(tag + ": global weights", before.weights, after.weights);
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
    final long oldTronPower;
    final long[] frozen;   // frozenV2: bandwidth, energy, tron power
    final byte[] bytes;

    Acct(AccountCapsule a) {
      available = a.getBalance();
      oldTronPower = a.getInstance().getOldTronPower();
      frozen = new long[] {a.getFrozenV2BalanceForBandwidth(), a.getFrozenV2BalanceForEnergy(),
          a.getTronPowerFrozenV2Balance()};
      bytes = a.getData();
    }
  }

  private static final class State {
    Acct stake;         // the callee: owner of the stake
    Acct caller;        // the calling contract, which holds no stake
    long[] weights;     // total net, energy, tron power
    long senderBalance;
  }

  private State capture(byte[] stake, byte[] caller) {
    State s = new State();
    DynamicPropertiesStore dps = dbManager.getDynamicPropertiesStore();
    s.stake = new Acct(dbManager.getAccountStore().get(stake));
    s.caller = new Acct(dbManager.getAccountStore().get(caller));
    s.weights = new long[] {dps.getTotalNetWeight(), dps.getTotalEnergyWeight(),
        dps.getTotalTronPowerWeight()};
    s.senderBalance = dbManager.getAccountStore().get(owner).getBalance();
    return s;
  }

  // ---------------------------------------------------------------- running transactions

  private byte[] deploy(String initCode) throws Exception {
    // consumeUserResourcePercent = 100: the sender pays all the energy, not the contract owner.
    // The name is unique per deployment: the address derives from the txid.
    Protocol.Transaction trx = TvmTestUtils.generateDeploySmartContractAndGetTransaction(
        "H" + deployCount++, owner, "[]", initCode, BALANCE, FEE_LIMIT, 100, null, 100_000);
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

  /** CALLER words: target, outerMode, then the three STAKE words (amount, resource, mode). */
  private static String callerCalldata(byte[] target, long outerMode, long amount, long res,
                                       long mode) {
    // the target is a 32-byte word: 12 zero bytes + the 20-byte address without the 0x41 prefix
    return "000000000000000000000000" + Hex.toHexString(target, 1, 20)
        + words(outerMode, amount, res, mode);
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

  private static final int SCRATCH = 0x100; // argument area for the query precompile

  private static byte[] buildStake() {
    return new Asm()
        // word0 = FREEZEBALANCEV2(amount = w0, resource = w1)   (resource is popped first)
        .arg(0).arg(1).op(Op.FREEZEBALANCEV2).store(0x00)
        // word1 = 0x0100000d(this, resource): own frozenV2 balance
        .op(Op.ADDRESS).store(SCRATCH).arg(1).store(SCRATCH + 0x20)
        .query(0x0d, SCRATCH, 0x40, 0x20)
        // word2 = BALANCE(this)
        .op(Op.ADDRESS, Op.BALANCE).store(0x40)
        // mode (w2) != 0 -> REVERT(0, 0x60), else RETURN(0, 0x60)
        .arg(2).pushLabel("REVERT").op(Op.JUMPI)
        .push(0x60).push(0x00).op(Op.RETURN)
        .label("REVERT")
        .push(0x60).push(0x00).op(Op.REVERT)
        .build();
  }

  private static byte[] buildCaller() {
    return new Asm()
        // calldatacopy(0, 0x40, 0x60): (amount, resource, mode) becomes the call's input
        .push(0x60).push(0x40).push(0x00).op(Op.CALLDATACOPY)
        // CALL(gas, target = w0, value 0, in = mem[0..0x60), out = mem[0x100..0x160))
        .push(0x60).pushInt(0x100).push(0x60).push(0x00).push(0x00).arg(0).op(Op.GAS, Op.CALL)
        // word3 = success flag of the CALL
        .store(0x160)
        // outerMode (w1) != 0 -> REVERT(0x100, 0x80), else RETURN(0x100, 0x80)
        .arg(1).pushLabel("REVERT").op(Op.JUMPI)
        .push(0x80).pushInt(0x100).op(Op.RETURN)
        .label("REVERT")
        .push(0x80).pushInt(0x100).op(Op.REVERT)
        .build();
  }

  /** Init code: copy the runtime code to memory and return it. */
  private static String initCode(byte[] runtime) {
    // PUSH2 len DUP1 PUSH1 0x0c PUSH1 0 CODECOPY PUSH1 0 RETURN  (12 bytes)
    return String.format("61%04x80600c6000396000f3", runtime.length) + Hex.toHexString(runtime);
  }
}
