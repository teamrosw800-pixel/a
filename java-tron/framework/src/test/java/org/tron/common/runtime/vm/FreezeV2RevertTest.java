package org.tron.common.runtime.vm;

import static org.tron.core.config.Parameter.ChainConstant.TRX_PRECISION;
import static org.tron.protos.Protocol.Transaction.Result.contractResult.REVERT;
import static org.tron.protos.Protocol.Transaction.Result.contractResult.SUCCESS;

import java.math.BigInteger;
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
import org.tron.core.vm.config.ConfigLoader;
import org.tron.core.vm.config.VMConfig;
import org.tron.core.vm.repository.Repository;
import org.tron.core.vm.repository.RepositoryImpl;
import org.tron.protos.Protocol;
import org.tron.protos.Protocol.Transaction.Result.contractResult;

/**
 * Checks ONE property: the state changes made by a Stake 2.0 opcode (FREEZEBALANCEV2) are
 * discarded when the execution that contains it reverts.
 *
 * <p>Existing tests (FreezeV2Test) only cover REVERTs caused by the stake opcode itself failing
 * validation. Here the opcode SUCCEEDS and the enclosing execution reverts afterwards.
 *
 * <p>Two hand-assembled contracts are used (no solc needed):
 * <pre>
 * STAKE  calldata = amount | resource | mode (three 32-byte words, no selector)
 *        ok = FREEZEBALANCEV2(amount, resource); mem[0] = ok;
 *        mode == 0 -> RETURN ok      mode != 0 -> REVERT with ok as revert data
 * CALLER calldata = target | amount | resource | mode
 *        ok = target.call(amount | resource | 0)   (STAKE, mode 0: freezes and returns)
 *        mode == 0 -> RETURN ok      mode != 0 -> REVERT with ok as revert data
 * </pre>
 * Revert data equal to 1 proves the stake opcode succeeded before the revert.
 *
 * <p>For each resource the same contract is first run with a revert, then, from the same
 * starting state, without one (control). The control proves the opcode does change the
 * observed state, so an unchanged state after the revert is meaningful.
 */
public class FreezeV2RevertTest extends BaseMethodTest {

  // initcode prefix: PUSH1 len DUP1 PUSH1 0x0b PUSH1 0 CODECOPY PUSH1 0 RETURN (11 bytes)
  private static final String STAKE_RUNTIME = ""
      + "6000" + "35"          // amount = calldataload(0)
      + "6020" + "35"          // resource = calldataload(0x20)
      + "da"                   // FREEZEBALANCEV2 (pops resource, then amount) -> ok
      + "6000" + "52"          // mstore(0, ok)
      + "6040" + "35"          // mode = calldataload(0x40)
      + "6015" + "57"          // jumpi(0x15, mode)
      + "6020" + "6000" + "f3" // return(0, 0x20)
      + "5b"                   // 0x15: jumpdest
      + "6020" + "6000" + "fd"; // revert(0, 0x20)
  private static final String STAKE_CODE = "601b80600b6000396000f3" + STAKE_RUNTIME;

  private static final String CALLER_RUNTIME = ""
      + "6040" + "6020" + "6000" + "37" // calldatacopy(0, 0x20, 0x40): amount, resource
      + "6020"                          // retSize
      + "6060"                          // retOffset
      + "6060"                          // argsSize (amount | resource | 0)
      + "6000"                          // argsOffset
      + "6000"                          // value
      + "6000" + "35"                   // target = calldataload(0)
      + "5a" + "f1" + "50"              // call(gas, target, ...); pop success flag
      + "6060" + "35"                   // mode = calldataload(0x60)
      + "6022" + "57"                   // jumpi(0x22, mode)
      + "6020" + "6060" + "f3"          // return(0x60, 0x20)
      + "5b"                            // 0x22: jumpdest
      + "6020" + "6060" + "fd";         // revert(0x60, 0x20)
  private static final String CALLER_CODE = "602880600b6000396000f3" + CALLER_RUNTIME;

  private static final long BALANCE = 100_000_000_000_000_000L;
  private static final long FEE_LIMIT = 1_000_000_000L;
  private static final long AMOUNT = 5 * TRX_PRECISION;
  private static final long[] RESOURCES = {0, 1, 2}; // bandwidth, energy, tron power

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

    // same configuration as FreezeV2Test
    ConfigLoader.disable = true;
    dbManager.getDynamicPropertiesStore().saveAllowTvmFreeze(1);
    dbManager.getDynamicPropertiesStore().saveUnfreezeDelayDays(30);
    dbManager.getDynamicPropertiesStore().saveAllowNewResourceModel(1L);
    dbManager.getDynamicPropertiesStore().saveAllowDelegateResource(1);
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

  /** The opcode runs in the same frame that later reverts. */
  @Test
  public void revertInSameFrameDiscardsStakeChanges() throws Exception {
    for (long res : RESOURCES) {
      byte[] stake = deploy(STAKE_CODE);
      String tag = "direct res=" + res;

      Snapshot before = snapshot(stake, owner);
      TVMTestResult reverted = trigger(stake, words(AMOUNT, res, 1), REVERT);
      Snapshot afterRevert = snapshot(stake, owner);

      assertStakeOpSucceededBeforeRevert(tag, reverted);
      assertNoStakeChange(tag, before, afterRevert);
      assertOnlyEnergyFeeCharged(tag, before, afterRevert, reverted);

      // control: same contract, same starting state, same arguments, no revert
      TVMTestResult committed = trigger(stake, words(AMOUNT, res, 0), SUCCESS);
      Snapshot afterCommit = snapshot(stake, owner);
      assertReturnedOk(tag + " control", committed);
      assertStakeApplied(tag + " control", before, afterCommit, res);
      assertOnlyEnergyFeeCharged(tag + " control", afterRevert, afterCommit, committed);
    }
  }

  /** The opcode runs in a callee that returns normally; the caller then reverts. */
  @Test
  public void revertOfCallerDiscardsStakeChangesMadeByCallee() throws Exception {
    for (long res : RESOURCES) {
      byte[] stake = deploy(STAKE_CODE);
      byte[] caller = deploy(CALLER_CODE);
      String tag = "nested res=" + res;

      Snapshot stakeBefore = snapshot(stake, owner);
      Snapshot callerBefore = snapshot(caller, owner);
      TVMTestResult reverted = trigger(caller, addrWord(stake) + words(AMOUNT, res, 1), REVERT);
      Snapshot stakeAfter = snapshot(stake, owner);
      Snapshot callerAfter = snapshot(caller, owner);

      assertStakeOpSucceededBeforeRevert(tag, reverted);
      assertNoStakeChange(tag + " callee", stakeBefore, stakeAfter);
      assertNoStakeChange(tag + " caller", callerBefore, callerAfter);
      assertOnlyEnergyFeeCharged(tag, stakeBefore, stakeAfter, reverted);

      TVMTestResult committed = trigger(caller, addrWord(stake) + words(AMOUNT, res, 0), SUCCESS);
      Snapshot stakeCommit = snapshot(stake, owner);
      Snapshot callerCommit = snapshot(caller, owner);
      assertReturnedOk(tag + " control", committed);
      assertStakeApplied(tag + " control callee", stakeBefore, stakeCommit, res);
      // the global counters legitimately move in the control (the callee froze), so compare
      // only the caller's own account here
      assertAccountUnchanged(tag + " control caller", callerBefore, callerCommit);
    }
  }

  // ---------------------------------------------------------------- assertions

  private static void assertStakeOpSucceededBeforeRevert(String tag, TVMTestResult result) {
    Assert.assertTrue(tag + ": runtime should be marked as reverted",
        result.getRuntime().getResult().isRevert());
    Assert.assertEquals(tag + ": FREEZEBALANCEV2 must have returned 1 before the revert"
        + " (otherwise this run does not exercise the property)",
        1L, new BigInteger(1, result.getRuntime().getResult().getHReturn()).longValue());
  }

  private static void assertReturnedOk(String tag, TVMTestResult result) {
    Assert.assertEquals(tag + ": FREEZEBALANCEV2 must have returned 1",
        1L, new BigInteger(1, result.getRuntime().getResult().getHReturn()).longValue());
  }

  /** Everything the stake opcode can touch on the contract or globally is unchanged. */
  private static void assertNoStakeChange(String tag, Snapshot before, Snapshot after) {
    assertAccountUnchanged(tag, before, after);
    for (int i = 0; i < 3; i++) {
      Assert.assertEquals(tag + ": global weight counter, resource " + i,
          before.weight[i], after.weight[i]);
    }
  }

  /** Only the contract's own account: balance, frozenV2 balances and the serialized account. */
  private static void assertAccountUnchanged(String tag, Snapshot before, Snapshot after) {
    Assert.assertEquals(tag + ": contract available balance", before.available, after.available);
    for (int i = 0; i < 3; i++) {
      Assert.assertEquals(tag + ": frozenV2 balance, resource " + i,
          before.frozen[i], after.frozen[i]);
    }
    Assert.assertArrayEquals(tag + ": full serialized contract account",
        before.accountBytes, after.accountBytes);
  }

  /** Only the tested resource changes, by exactly AMOUNT (and AMOUNT / TRX_PRECISION globally). */
  private static void assertStakeApplied(String tag, Snapshot before, Snapshot after, long res) {
    Assert.assertEquals(tag + ": available balance decreases by the frozen amount",
        before.available - AMOUNT, after.available);
    for (int i = 0; i < 3; i++) {
      boolean tested = i == res;
      Assert.assertEquals(tag + ": frozenV2 balance, resource " + i,
          before.frozen[i] + (tested ? AMOUNT : 0), after.frozen[i]);
      Assert.assertEquals(tag + ": global weight counter, resource " + i,
          before.weight[i] + (tested ? AMOUNT / TRX_PRECISION : 0), after.weight[i]);
    }
  }

  /**
   * The protocol keeps the energy fee of a reverted transaction: the caller pays it, and that is
   * the only permitted difference in balances. Contract-side state must not carry it.
   */
  private static void assertOnlyEnergyFeeCharged(
      String tag, Snapshot before, Snapshot after, TVMTestResult result) {
    long energyFee = result.getReceipt().getEnergyFee();
    Assert.assertTrue(tag + ": energy is consumed (and kept) even though the call reverted",
        result.getReceipt().getEnergyUsageTotal() > 0);
    Assert.assertEquals(tag + ": caller balance decreases by exactly the energy fee",
        before.callerBalance - energyFee, after.callerBalance);
  }

  // ---------------------------------------------------------------- helpers

  private static final class Snapshot {
    final long available;
    final long[] frozen;
    final long[] weight;
    final byte[] accountBytes;
    final long callerBalance;

    Snapshot(long available, long[] frozen, long[] weight, byte[] accountBytes,
             long callerBalance) {
      this.available = available;
      this.frozen = frozen;
      this.weight = weight;
      this.accountBytes = accountBytes;
      this.callerBalance = callerBalance;
    }
  }

  private Snapshot snapshot(byte[] contract, byte[] caller) {
    AccountCapsule account = dbManager.getAccountStore().get(contract);
    DynamicPropertiesStore dps = dbManager.getDynamicPropertiesStore();
    return new Snapshot(
        account.getBalance(),
        new long[] {account.getFrozenV2BalanceForBandwidth(),
            account.getFrozenV2BalanceForEnergy(), account.getTronPowerFrozenV2Balance()},
        new long[] {dps.getTotalNetWeight(), dps.getTotalEnergyWeight(),
            dps.getTotalTronPowerWeight()},
        account.getInstance().toByteArray(),
        dbManager.getAccountStore().get(caller).getBalance());
  }

  private byte[] deploy(String code) throws Exception {
    // consumeUserResourcePercent = 100: the caller pays all the energy, not the contract owner.
    // The name is unique per deployment: the address derives from the txid, so deploying the
    // same code twice with identical fields would collide.
    Protocol.Transaction trx = TvmTestUtils.generateDeploySmartContractAndGetTransaction(
        "T" + deployCount++, owner, "[]", code, BALANCE, FEE_LIMIT, 100, null, 100_000);
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

  private static String words(long... values) {
    StringBuilder sb = new StringBuilder();
    for (long v : values) {
      sb.append(String.format("%064x", v));
    }
    return sb.toString();
  }

  private static String addrWord(byte[] tronAddress) {
    // 32-byte word: 12 zero bytes + the 20-byte address without the 0x41 prefix
    return "000000000000000000000000" + Hex.toHexString(tronAddress, 1, 20);
  }
}
