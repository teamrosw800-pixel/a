package org.tron.common.runtime.vm;

import static org.tron.core.config.Parameter.ChainConstant.FROZEN_PERIOD;
import static org.tron.core.config.Parameter.ChainConstant.TRX_PRECISION;
import static org.tron.protos.Protocol.Transaction.Result.contractResult.REVERT;
import static org.tron.protos.Protocol.Transaction.Result.contractResult.SUCCESS;
import static org.tron.protos.contract.Common.ResourceCode.BANDWIDTH;
import static org.tron.protos.contract.Common.ResourceCode.ENERGY;
import static org.tron.protos.contract.Common.ResourceCode.TRON_POWER;

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
import org.tron.common.utils.WalletUtil;
import org.tron.core.Wallet;
import org.tron.core.actuator.UnfreezeBalanceV2Actuator;
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
import org.tron.protos.contract.Common.ResourceCode;

/**
 * Checks ONE property for the last two operations of the Stake 2.0 pending-unfreeze family:
 * the state changes made by a valid WITHDRAWEXPIREUNFREEZE or CANCELALLUNFREEZEV2 are discarded
 * when the execution that contains the operation reverts afterwards.
 *
 * <ul>
 *   <li>WITHDRAWEXPIREUNFREEZE pays every already-EXPIRED pending unfreeze into the available
 *       balance and keeps the ones not yet expired. It touches only the account (balance and
 *       pending list) and its result is the amount withdrawn.
 *   <li>CANCELALLUNFREEZEV2 pays the expired entries into the balance too, but returns every
 *       still-PENDING entry to the frozen balance of its resource (and raises the global weight
 *       of that resource), then empties the list. Its result is a success flag.
 * </ul>
 * Neither touches votes, rewards or DelegationStore (by reading UnfreezeBalanceV2Processor's
 * neighbours; this test does not check that).
 *
 * <p>Each operation is tested in three shapes, each in a fresh Spring context:
 * <ul>
 *   <li><b>Same frame</b>: the operation runs in the contract that then executes REVERT.
 *   <li><b>Outer reverts</b>: a CALLED contract runs the operation and returns normally, then the
 *       caller reverts. The whole transaction reverts.
 *   <li><b>Inner reverts</b>: the called contract runs the operation and then reverts; the caller
 *       sees the call fail, continues and returns normally. The transaction SUCCEEDS and commits,
 *       so anything that leaked out of the inner frame would be persisted.
 * </ul>
 *
 * <p>The starting state is built through the VM with the clock moving, with three resources at
 * once: the contract freezes 10 TRX of bandwidth, energy and tron power; at T0 it un-freezes
 * A (bandwidth, 3 TRX) and B (energy, 2 TRX); at T0 + 20 days it un-freezes C (bandwidth, 1 TRX) and
 * D (tron power, 4 TRX); the operation then runs at T0 + 35 days, when A and B have expired
 * (T0 + 30 days) and C and D have not (T0 + 50 days).
 *
 * <p>Hand-assembled contracts driven by calldata (five 32-byte words, no selector):
 * <pre>
 * STAKE  w0 op: 0 freeze, 3 unfreeze, 4 withdraw, 5 cancel   w1 amount   w2 resource
 *        w3 time in seconds (read-back only)   w4 mode: 0 RETURN, otherwise REVERT with the words
 *   ops 4 and 5: word0 = the opcode's result; then, inside the same execution and before
 *   RETURN/REVERT, read back through the Stake 2.0 query precompiles and BALANCE (they read the
 *   in-flight state): word1..3 = own frozenV2 of bandwidth, energy, tron power (0x0100000d),
 *   word4 = free pending slots (0x0100000c), word5 = BALANCE(this), word6 = pending unfreezes
 *   already expired at time w3 (0x0100000e)
 * CALLER w0 target, w1 outerMode, then the five STAKE words: CALLs STAKE; its result words are the
 *        callee's 7 words (the VM copies RETURN and REVERT data) + the CALL success flag; then
 *        RETURN, or REVERT if outerMode != 0
 * </pre>
 */
public class WithdrawAndCancelUnfreezeV2RevertTest extends BaseMethodTest {

  private static final int WITHDRAW = 4;
  private static final int CANCEL = 5;

  private static final long T0 = 1_700_000_000_000L;
  private static final long T_SECOND = T0 + 20 * FROZEN_PERIOD;
  private static final long T_TEST = T0 + 35 * FROZEN_PERIOD;
  private static final long DELAY = 30 * FROZEN_PERIOD; // unfreezeDelayDays = 30

  private static final long BALANCE = 100_000_000_000_000_000L;
  private static final long FEE_LIMIT = 1_000_000_000L;
  private static final long FROZEN = 10 * TRX_PRECISION;
  private static final long A_BW = 3 * TRX_PRECISION; // expired
  private static final long B_EN = 2 * TRX_PRECISION; // expired
  private static final long C_BW = 1 * TRX_PRECISION; // pending
  private static final long D_TP = 4 * TRX_PRECISION; // pending
  private static final long EXPIRED_TOTAL = A_BW + B_EN;

  private static final String STAKE_INIT = initCode(buildStake());
  private static final String CALLER_INIT = initCode(buildCaller());

  private enum Shape { SAME_FRAME, OUTER_REVERTS, INNER_REVERTS }

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
    dps.saveCurrentCycleNumber(5);
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
  public void withdrawInTheSameFrameIsDiscardedWhenExecutionReverts() throws Exception {
    run(WITHDRAW, Shape.SAME_FRAME);
  }

  @Test
  public void withdrawByCalleeIsDiscardedWhenOuterReverts() throws Exception {
    run(WITHDRAW, Shape.OUTER_REVERTS);
  }

  @Test
  public void withdrawByCalleeIsDiscardedWhenInnerReverts() throws Exception {
    run(WITHDRAW, Shape.INNER_REVERTS);
  }

  @Test
  public void cancelInTheSameFrameIsDiscardedWhenExecutionReverts() throws Exception {
    run(CANCEL, Shape.SAME_FRAME);
  }

  @Test
  public void cancelByCalleeIsDiscardedWhenOuterReverts() throws Exception {
    run(CANCEL, Shape.OUTER_REVERTS);
  }

  @Test
  public void cancelByCalleeIsDiscardedWhenInnerReverts() throws Exception {
    run(CANCEL, Shape.INNER_REVERTS);
  }

  // ---------------------------------------------------------------- scenario

  private void run(int op, Shape shape) throws Exception {
    boolean nested = shape != Shape.SAME_FRAME;
    String tag = (op == WITHDRAW ? "withdraw" : "cancel-all") + " / " + shape;
    byte[] stake = deploy(STAKE_INIT);
    byte[] caller = deploy(CALLER_INIT);

    // valid precondition, built through the VM with the clock moving
    for (int r = 0; r < 3; r++) {
      Assert.assertEquals(tag + ": setup freeze", 1L,
          word(trigger(stake, stakeCalldata(0, FROZEN, r, 0, 0), SUCCESS), 0));
    }
    Assert.assertEquals(tag + ": setup unfreeze A", 1L,
        word(trigger(stake, stakeCalldata(3, A_BW, 0, 0, 0), SUCCESS), 0));
    Assert.assertEquals(tag + ": setup unfreeze B", 1L,
        word(trigger(stake, stakeCalldata(3, B_EN, 1, 0, 0), SUCCESS), 0));
    setHead(T_SECOND);
    Assert.assertEquals(tag + ": setup unfreeze C", 1L,
        word(trigger(stake, stakeCalldata(3, C_BW, 0, 0, 0), SUCCESS), 0));
    Assert.assertEquals(tag + ": setup unfreeze D", 1L,
        word(trigger(stake, stakeCalldata(3, D_TP, 2, 0, 0), SUCCESS), 0));
    setHead(T_TEST);

    State before = capture(stake, caller);
    Assert.assertArrayEquals(tag + ": setup frozenV2 (bandwidth, energy, tron power)",
        new long[] {FROZEN - A_BW - C_BW, FROZEN - B_EN, FROZEN - D_TP}, before.stake.frozen);
    Assert.assertEquals(tag + ": setup pending list",
        Arrays.asList(entry(BANDWIDTH, A_BW, T0 + DELAY), entry(ENERGY, B_EN, T0 + DELAY),
            entry(BANDWIDTH, C_BW, T_SECOND + DELAY), entry(TRON_POWER, D_TP, T_SECOND + DELAY)),
        before.stake.unfrozen);
    Assert.assertEquals(tag + ": setup: only C and D still pending", 2,
        before.stake.unfreezingCountAtTest);
    Assert.assertEquals(tag + ": setup available balance (freezing took 30 TRX out)",
        BALANCE - 3 * FROZEN, before.stake.available);
    Assert.assertArrayEquals(tag + ": setup global weights (TRX)",
        new long[] {6, 8, 6}, before.weights);

    long slots = UnfreezeBalanceV2Actuator.getUNFREEZE_MAX_TIMES();
    long[] expected = op == WITHDRAW
        // result = amount withdrawn; frozen untouched; C and D still pending
        ? new long[] {EXPIRED_TOTAL, FROZEN - A_BW - C_BW, FROZEN - B_EN, FROZEN - D_TP,
            slots - 2, before.stake.available + EXPIRED_TOTAL, 0}
        // result = success flag; pending C and D go back to frozen; list empty
        : new long[] {1, FROZEN - A_BW, FROZEN - B_EN, FROZEN, slots,
            before.stake.available + EXPIRED_TOTAL, 0};

    // ---- reverted arm
    TVMTestResult reverted;
    switch (shape) {
      case SAME_FRAME:
        reverted = trigger(stake, stakeCalldata(op, 0, 0, T_TEST / 1000, 1), REVERT);
        Assert.assertTrue(tag + ": runtime must be marked as reverted",
            reverted.getRuntime().getResult().isRevert());
        break;
      case OUTER_REVERTS:
        reverted = trigger(caller, callerCalldata(stake, 1,
            stakeCalldata(op, 0, 0, T_TEST / 1000, 0)), REVERT);
        Assert.assertTrue(tag + ": the transaction must be reverted",
            reverted.getRuntime().getResult().isRevert());
        Assert.assertEquals(tag + ": CALL must report success", 1L, word(reverted, 7));
        break;
      default:
        // callee reverts after the op; caller continues and the transaction succeeds
        reverted = trigger(caller, callerCalldata(stake, 0,
            stakeCalldata(op, 0, 0, T_TEST / 1000, 1)), SUCCESS);
        Assert.assertFalse(tag + ": the transaction itself must not be reverted",
            reverted.getRuntime().getResult().isRevert());
        Assert.assertEquals(tag + ": CALL must report failure", 0L, word(reverted, 7));
        break;
    }
    assertVisibleInsideExecution(tag + " (reverted arm)", reverted, expected, nested);
    State afterRevert = capture(stake, caller);
    assertNothingChanged(tag + " after revert", before, afterRevert);
    assertOnlyEnergyFeeCharged(tag + " after revert", before, afterRevert, reverted);

    // ---- control: same contracts, same arguments, nothing reverts
    TVMTestResult committed = nested
        ? trigger(caller, callerCalldata(stake, 0, stakeCalldata(op, 0, 0, T_TEST / 1000, 0)),
            SUCCESS)
        : trigger(stake, stakeCalldata(op, 0, 0, T_TEST / 1000, 0), SUCCESS);
    if (nested) {
      Assert.assertEquals(tag + " control: CALL must report success", 1L, word(committed, 7));
    }
    State afterCommit = capture(stake, caller);
    assertVisibleInsideExecution(tag + " (control)", committed, expected, nested);
    assertApplied(tag + " control", op, before, afterCommit);
    Assert.assertArrayEquals(tag + " control: caller contract account is untouched",
        before.caller.bytes, afterCommit.caller.bytes);
    assertOnlyEnergyFeeCharged(tag + " control", afterRevert, afterCommit, committed);

    System.out.println("OBS " + tag + " | reverted-arm words " + words(reverted)
        + " | control words " + words(committed)
        + " | available " + before.stake.available + " -> " + afterCommit.stake.available
        + " | frozen " + Arrays.toString(before.stake.frozen) + " -> "
        + Arrays.toString(afterCommit.stake.frozen)
        + " | pending " + before.stake.unfrozen.size() + " -> "
        + afterCommit.stake.unfrozen.size()
        + " | weights " + Arrays.toString(before.weights) + " -> "
        + Arrays.toString(afterCommit.weights));
  }

  // ---------------------------------------------------------------- assertions

  private void assertVisibleInsideExecution(
      String tag, TVMTestResult result, long[] expected, boolean nested) {
    byte[] data = result.getRuntime().getResult().getHReturn();
    Assert.assertEquals(tag + ": result words expected", nested ? 256 : 224, data.length);
    String[] names = {"operation result", "own frozenV2 bandwidth (0x0100000d)",
        "own frozenV2 energy (0x0100000d)", "own frozenV2 tron power (0x0100000d)",
        "free pending slots (0x0100000c)", "available balance (BALANCE)",
        "expired pending at test time (0x0100000e)"};
    for (int i = 0; i < 7; i++) {
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
    Assert.assertEquals(tag + ": pending unfreeze list", b.unfrozen, a.unfrozen);
    Assert.assertArrayEquals(tag + ": full serialized callee account", b.bytes, a.bytes);
    Assert.assertEquals(tag + ": caller contract available balance",
        before.caller.available, after.caller.available);
    Assert.assertArrayEquals(tag + ": full serialized caller contract account",
        before.caller.bytes, after.caller.bytes);
    Assert.assertArrayEquals(tag + ": global weights", before.weights, after.weights);
  }

  /** Control: the operation persists exactly what it is meant to. */
  private void assertApplied(String tag, int op, State before, State after) {
    Acct b = before.stake;
    Acct a = after.stake;
    Assert.assertEquals(tag + ": the expired entries are paid into the available balance",
        b.available + EXPIRED_TOTAL, a.available);
    if (op == WITHDRAW) {
      Assert.assertArrayEquals(tag + ": frozenV2 untouched", b.frozen, a.frozen);
      Assert.assertEquals(tag + ": only the still-pending entries remain",
          Arrays.asList(entry(BANDWIDTH, C_BW, T_SECOND + DELAY),
              entry(TRON_POWER, D_TP, T_SECOND + DELAY)),
          a.unfrozen);
      Assert.assertArrayEquals(tag + ": global weights untouched", before.weights,
          after.weights);
    } else {
      Assert.assertArrayEquals(tag + ": pending C and D go back to frozenV2; B was paid, not "
              + "restored", new long[] {FROZEN - A_BW, FROZEN - B_EN, FROZEN}, a.frozen);
      Assert.assertTrue(tag + ": the pending list is emptied", a.unfrozen.isEmpty());
      Assert.assertArrayEquals(tag + ": global weights follow the restored frozen balance",
          new long[] {before.weights[0] + C_BW / TRX_PRECISION, before.weights[1],
              before.weights[2] + D_TP / TRX_PRECISION}, after.weights);
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

  private static String entry(ResourceCode type, long amount, long expire) {
    return type.name() + ":" + amount + ":" + expire;
  }

  private static final class Acct {
    final long available;
    final long[] frozen;   // frozenV2: bandwidth, energy, tron power
    final List<String> unfrozen;
    final int unfreezingCountAtTest;
    final byte[] bytes;

    Acct(AccountCapsule a, long now) {
      available = a.getBalance();
      frozen = new long[] {a.getFrozenV2BalanceForBandwidth(), a.getFrozenV2BalanceForEnergy(),
          a.getTronPowerFrozenV2Balance()};
      List<String> pending = new ArrayList<>();
      for (Protocol.Account.UnFreezeV2 u : a.getUnfrozenV2List()) {
        pending.add(entry(u.getType(), u.getUnfreezeAmount(), u.getUnfreezeExpireTime()));
      }
      unfrozen = Collections.unmodifiableList(pending);
      unfreezingCountAtTest = a.getUnfreezingV2Count(now);
      bytes = a.getData();
    }
  }

  private static final class State {
    Acct stake;         // the account that owns the stake
    Acct caller;        // the calling contract, which holds no stake
    long[] weights;     // total net, energy, tron power
    long senderBalance;
  }

  private State capture(byte[] stake, byte[] caller) {
    State s = new State();
    DynamicPropertiesStore dps = dbManager.getDynamicPropertiesStore();
    long now = dps.getLatestBlockHeaderTimestamp();
    s.stake = new Acct(dbManager.getAccountStore().get(stake), now);
    s.caller = new Acct(dbManager.getAccountStore().get(caller), now);
    s.weights = new long[] {dps.getTotalNetWeight(), dps.getTotalEnergyWeight(),
        dps.getTotalTronPowerWeight()};
    s.senderBalance = dbManager.getAccountStore().get(owner).getBalance();
    return s;
  }

  private void setHead(long timestampMs) {
    dbManager.getDynamicPropertiesStore().saveLatestBlockHeaderTimestamp(timestampMs);
  }

  // ---------------------------------------------------------------- running transactions

  private byte[] deploy(String initCode) throws Exception {
    // consumeUserResourcePercent = 100: the sender pays all the energy, not the contract owner.
    // The name is unique per deployment: the address derives from the txid.
    Protocol.Transaction trx = TvmTestUtils.generateDeploySmartContractAndGetTransaction(
        "W" + deployCount++, owner, "[]", initCode, BALANCE, FEE_LIMIT, 100, null, 100_000);
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

  /** The five STAKE words: op, amount, resource, time in seconds, mode. */
  private static String stakeCalldata(long op, long amount, long res, long timeSeconds,
                                      long mode) {
    return words(op, amount, res, timeSeconds, mode);
  }

  /** CALLER words: target, outerMode, then the five STAKE words. */
  private static String callerCalldata(byte[] target, long outerMode, String stakeWords) {
    // the target is a 32-byte word: 12 zero bytes + the 20-byte address without the 0x41 prefix
    return "000000000000000000000000" + Hex.toHexString(target, 1, 20) + words(outerMode)
        + stakeWords;
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

    /** word at {@code retOffset} = 0x0100000d(this, type): own frozenV2 balance of the type. */
    Asm frozenOf(int type, int retOffset) {
      return op(Op.ADDRESS).store(SCRATCH).push(type).store(SCRATCH + 0x20)
          .query(0x0d, SCRATCH, 0x40, retOffset);
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
        // dispatch on op (w0): 3 -> UNFREEZE, 4 -> WITHDRAW, 5 -> CANCEL, otherwise freeze
        .arg(0).push(3).op(Op.EQ).pushLabel("UNFREEZE").op(Op.JUMPI)
        .arg(0).push(4).op(Op.EQ).pushLabel("WITHDRAW").op(Op.JUMPI)
        .arg(0).push(5).op(Op.EQ).pushLabel("CANCEL").op(Op.JUMPI)
        // op 0: word0 = FREEZEBALANCEV2(amount = w1, resource = w2)   (resource is popped first)
        .arg(1).arg(2).op(Op.FREEZEBALANCEV2).store(0x00)
        .pushLabel("TAIL").op(Op.JUMP)
        // op 3: word0 = UNFREEZEBALANCEV2(amount = w1, resource = w2)
        .label("UNFREEZE")
        .arg(1).arg(2).op(Op.UNFREEZEBALANCEV2).store(0x00)
        .pushLabel("TAIL").op(Op.JUMP)
        // op 4: word0 = WITHDRAWEXPIREUNFREEZE() = the amount withdrawn
        .label("WITHDRAW")
        .op(Op.WITHDRAWEXPIREUNFREEZE).store(0x00)
        .pushLabel("OBS").op(Op.JUMP)
        // op 5: word0 = CANCELALLUNFREEZEV2() = success flag
        .label("CANCEL")
        .op(Op.CANCELALLUNFREEZEV2).store(0x00)
        // ops 4 and 5: read the in-flight state back
        .label("OBS")
        .frozenOf(0, 0x20)   // word1: own frozenV2 bandwidth
        .frozenOf(1, 0x40)   // word2: own frozenV2 energy
        .frozenOf(2, 0x60)   // word3: own frozenV2 tron power
        // word4 = 0x0100000c(this): free slots for pending unfreezes
        .op(Op.ADDRESS).store(SCRATCH)
        .query(0x0c, SCRATCH, 0x20, 0x80)
        // word5 = BALANCE(this)
        .op(Op.ADDRESS, Op.BALANCE).store(0xa0)
        // word6 = 0x0100000e(this, w3): pending unfreezes already expired at time w3 (seconds)
        .op(Op.ADDRESS).store(SCRATCH).arg(3).store(SCRATCH + 0x20)
        .query(0x0e, SCRATCH, 0x40, 0xc0)
        // tail: mode (w4) != 0 -> REVERT(0, 0xe0), else RETURN(0, 0xe0)
        .label("TAIL")
        .arg(4).pushLabel("REVERT").op(Op.JUMPI)
        .push(0xe0).push(0x00).op(Op.RETURN)
        .label("REVERT")
        .push(0xe0).push(0x00).op(Op.REVERT)
        .build();
  }

  private static byte[] buildCaller() {
    return new Asm()
        // calldatacopy(0, 0x40, 0xa0): the five STAKE words become the call's input
        .push(0xa0).push(0x40).push(0x00).op(Op.CALLDATACOPY)
        // CALL(gas, target = w0, value 0, in = mem[0..0xa0), out = mem[0x100..0x1e0))
        .push(0xe0).pushInt(0x100).push(0xa0).push(0x00).push(0x00).arg(0).op(Op.GAS, Op.CALL)
        // word7 = success flag of the CALL
        .store(0x1e0)
        // outerMode (w1) != 0 -> REVERT(0x100, 0x100), else RETURN(0x100, 0x100)
        .arg(1).pushLabel("REVERT").op(Op.JUMPI)
        .pushInt(0x100).pushInt(0x100).op(Op.RETURN)
        .label("REVERT")
        .pushInt(0x100).pushInt(0x100).op(Op.REVERT)
        .build();
  }

  /** Init code: copy the runtime code to memory and return it. */
  private static String initCode(byte[] runtime) {
    // PUSH2 len DUP1 PUSH1 0x0c PUSH1 0 CODECOPY PUSH1 0 RETURN  (12 bytes)
    return String.format("61%04x80600c6000396000f3", runtime.length) + Hex.toHexString(runtime);
  }
}
