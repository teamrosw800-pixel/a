package org.tron.common.runtime.vm;

import static org.tron.core.config.Parameter.ChainConstant.FROZEN_PERIOD;
import static org.tron.core.config.Parameter.ChainConstant.TRX_PRECISION;
import static org.tron.protos.Protocol.Transaction.Result.contractResult.REVERT;
import static org.tron.protos.Protocol.Transaction.Result.contractResult.SUCCESS;
import static org.tron.protos.contract.Common.ResourceCode.BANDWIDTH;
import static org.tron.protos.contract.Common.ResourceCode.ENERGY;
import static org.tron.protos.contract.Common.ResourceCode.TRON_POWER;

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
import org.tron.core.actuator.UnfreezeBalanceV2Actuator;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.capsule.VotesCapsule;
import org.tron.core.db.TransactionTrace;
import org.tron.core.store.DelegationStore;
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
 * Checks ONE property: the state changes made by a valid UNFREEZEBALANCEV2 (Stake 2.0) are
 * discarded when the execution that contains it reverts afterwards.
 *
 * <p>Existing coverage (FreezeV2Test) only produces REVERT when the operation itself fails
 * validation. UnfreezeBalanceV2Processor.execute does much more than lower the frozen balance,
 * and each part is a place where a revert could leave a trace:
 * <ul>
 *   <li>it first pays every already-EXPIRED pending unfreeze into the available balance and drops
 *       it from the list (so a revert must give the entries back and take the TRX back);
 *   <li>it appends a new pending entry and lowers the global weight of the resource;
 *   <li>it settles the voting reward of the account (writes DelegationStore cycles and the
 *       per-cycle vote snapshot, and raises the account's allowance);
 *   <li>for tron power it recomputes or clears the account's votes and rewrites the VotesStore.
 * </ul>
 *
 * <p>Four scenarios, each in a fresh Spring context:
 * <ul>
 *   <li>bandwidth and energy, with a pending history built through the VM: one entry already
 *       expired (paid out by the operation) and one still pending;
 *   <li>tron power with two votes and a pending reward, unfreezing half (votes recomputed) and
 *       all (votes cleared).
 * </ul>
 *
 * <p>One hand-assembled contract, driven by calldata (five 32-byte words, no selector):
 * <pre>
 * w0 op      0 = freeze, 3 = unfreeze
 * w1, w2     amount, resource (0 bandwidth, 1 energy, 2 tron power)
 * w3         a time in seconds, only used by the read-back below
 * w4 mode    0 = RETURN the 6 result words, otherwise REVERT with the same 6 words
 *
 * op 3: word0 = UNFREEZEBALANCEV2(amount, resource)   (the op under test)
 *       then, inside the same execution and before RETURN/REVERT, read back through the Stake 2.0
 *       and vote query precompiles / BALANCE (they read the in-flight state, not the persisted):
 *       word1 = 0x0100000d(this, resource)   own frozenV2 balance
 *       word2 = 0x0100000c(this)             free slots for pending unfreezes
 *       word3 = 0x0100000e(this, w3)         pending unfreezes already expired at time w3
 *       word4 = BALANCE(this)                available balance (a paid-out expired entry lands
 *                                            here)
 *       word5 = 0x01000008(this)             votes in use
 * </pre>
 * The reward settlement has no in-execution witness: the reward query returns pending + paid, so
 * it does not change when the reward is paid. It is evidenced by the control run (the allowance
 * grows by exactly the computed reward) and by the operation having returned 1, since the
 * settlement runs unconditionally at the start of the same execute() call.
 *
 * <p>For each scenario the reverted run is followed, from the state the revert left, by the same
 * call without a revert (control).
 */
public class UnfreezeV2RevertTest extends BaseMethodTest {

  private static final long T0 = 1_700_000_000_000L;
  private static final long T_SECOND = T0 + 20 * FROZEN_PERIOD;
  private static final long T_TEST = T0 + 35 * FROZEN_PERIOD;
  private static final long DELAY = 30 * FROZEN_PERIOD; // unfreezeDelayDays = 30
  private static final long CYCLE = 5;

  private static final long BALANCE = 100_000_000_000_000_000L;
  private static final long FEE_LIMIT = 1_000_000_000L;
  private static final long FROZEN = 10 * TRX_PRECISION;
  private static final long ENTRY_A = 3 * TRX_PRECISION; // becomes expired
  private static final long ENTRY_B = 2 * TRX_PRECISION; // stays pending
  private static final long UNFREEZE = 1 * TRX_PRECISION;
  private static final long TP_FROZEN = 1000 * TRX_PRECISION;
  private static final long VOTES_EACH = 500;
  // reward = (Vi(4) - Vi(2)) * votes / 1e18 per witness: 2e21 * 500 / 1e18 and 1e21 * 500 / 1e18
  private static final BigInteger VI_W1 = new BigInteger("2000000000000000000000");
  private static final BigInteger VI_W2 = new BigInteger("1000000000000000000000");
  private static final long REWARD = 1_500_000L;

  private static final String CODE_INIT = initCode(buildRuntime());

  private static final byte[] WITNESS_1 =
      Commons.decode58Check("TWyoFfJBiKGkVQd28HTqxsc8kbMtQUmqgi");
  private static final byte[] WITNESS_2 =
      Commons.decode58Check("TWtWaUAsJ933xs2n4RkXzaMoKJUrQmctBH");

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
    dps.saveCurrentCycleNumber(CYCLE);
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
  public void bandwidthUnfreezeWithExpiredAndPendingEntriesIsDiscardedWhenExecutionReverts()
      throws Exception {
    runPending(0);
  }

  @Test
  public void energyUnfreezeWithExpiredAndPendingEntriesIsDiscardedWhenExecutionReverts()
      throws Exception {
    runPending(1);
  }

  @Test
  public void partialTronPowerUnfreezeWithVotesAndRewardIsDiscardedWhenExecutionReverts()
      throws Exception {
    runVotes(TP_FROZEN / 2);
  }

  @Test
  public void fullTronPowerUnfreezeWithVotesAndRewardIsDiscardedWhenExecutionReverts()
      throws Exception {
    runVotes(TP_FROZEN);
  }

  @Test
  public void energyUnfreezeWithLegacyTronPowerClearsVotesAndIsDiscardedWhenExecutionReverts()
      throws Exception {
    runLegacyVotes();
  }

  // ---------------------------------------------------------------- scenarios

  /** Bandwidth or energy, with one expired and one pending entry already in the list. */
  private void runPending(int res) throws Exception {
    String tag = res == 0 ? "bandwidth (expired + pending)" : "energy (expired + pending)";
    ResourceCode type = res == 0 ? BANDWIDTH : ENERGY;
    byte[] stake = deploy();

    // valid precondition, built through the VM with the clock moving: freeze, unfreeze A at T0
    // (expires T0 + 30d), unfreeze B at T0 + 20d (expires T0 + 50d), then the test runs at
    // T0 + 35d, when A has expired and B has not
    for (int r = 0; r < 2; r++) {
      Assert.assertEquals(tag + ": setup freeze", 1L,
          word(trigger(stake, calldata(0, FROZEN, r, 0, 0), SUCCESS), 0));
    }
    Assert.assertEquals(tag + ": setup unfreeze A", 1L,
        word(trigger(stake, calldata(3, ENTRY_A, res, 0, 0), SUCCESS), 0));
    setHead(T_SECOND);
    Assert.assertEquals(tag + ": setup unfreeze B", 1L,
        word(trigger(stake, calldata(3, ENTRY_B, res, 0, 0), SUCCESS), 0));
    setHead(T_TEST);

    State before = capture(stake);
    int w = res; // index of the resource in the global weights: 0 net, 1 energy
    Assert.assertEquals(tag + ": setup frozenV2", FROZEN - ENTRY_A - ENTRY_B,
        before.stake.frozen[res]);
    Assert.assertEquals(tag + ": setup pending list",
        Arrays.asList(entry(type, ENTRY_A, T0 + DELAY), entry(type, ENTRY_B, T_SECOND + DELAY)),
        before.stake.unfrozen);
    Assert.assertEquals(tag + ": setup: A expired, B not", 1,
        before.stake.unfreezingCountAtTest);
    Assert.assertEquals(tag + ": setup available balance", BALANCE, before.stake.available);
    Assert.assertEquals(tag + ": setup global weight", FROZEN - ENTRY_A - ENTRY_B,
        before.weights[w] * TRX_PRECISION);

    long[] expected = {1, FROZEN - ENTRY_A - ENTRY_B - UNFREEZE,
        UnfreezeBalanceV2Actuator.getUNFREEZE_MAX_TIMES() - 2, 0, BALANCE + ENTRY_A, 0};
    String callData = calldata(3, UNFREEZE, res, T_TEST / 1000, 1);

    // ---- reverted run
    TVMTestResult reverted = trigger(stake, callData, REVERT);
    State afterRevert = capture(stake);
    Assert.assertTrue(tag + ": runtime must be marked as reverted",
        reverted.getRuntime().getResult().isRevert());
    assertVisibleInsideExecution(tag + " (reverted)", reverted, expected);
    assertNothingChanged(tag + " after revert", before, afterRevert);
    assertOnlyEnergyFeeCharged(tag + " after revert", before, afterRevert, reverted);

    // ---- control
    TVMTestResult committed = trigger(stake, calldata(3, UNFREEZE, res, T_TEST / 1000, 0),
        SUCCESS);
    State afterCommit = capture(stake);
    assertVisibleInsideExecution(tag + " (control)", committed, expected);
    String c = tag + " control";
    Assert.assertEquals(c + ": frozenV2", FROZEN - ENTRY_A - ENTRY_B - UNFREEZE,
        afterCommit.stake.frozen[res]);
    Assert.assertEquals(c + ": other resource untouched", before.stake.frozen[1 - res],
        afterCommit.stake.frozen[1 - res]);
    Assert.assertEquals(c + ": pending list: expired A paid out and dropped, B kept, new entry",
        Arrays.asList(entry(type, ENTRY_B, T_SECOND + DELAY),
            entry(type, UNFREEZE, T_TEST + DELAY)),
        afterCommit.stake.unfrozen);
    Assert.assertEquals(c + ": the expired entry is paid into the available balance",
        BALANCE + ENTRY_A, afterCommit.stake.available);
    Assert.assertEquals(c + ": global weight of the resource drops by the unfrozen TRX",
        before.weights[w] - UNFREEZE / TRX_PRECISION, afterCommit.weights[w]);
    Assert.assertEquals(c + ": allowance (no votes, no reward)", before.stake.allowance,
        afterCommit.stake.allowance);
    Assert.assertTrue(c + ": no votes", afterCommit.stake.votes.isEmpty());
    Assert.assertNull(c + ": no votes record", afterCommit.votesRecord);
    Assert.assertEquals(c + ": reward settlement is a no-op here (begin cycle already ahead)",
        before.beginCycle, afterCommit.beginCycle);
    assertOnlyEnergyFeeCharged(c, afterRevert, afterCommit, committed);
    print(tag, reverted, before, afterCommit);
  }

  /** Tron power with two votes and a pending reward; unfreezes {@code amount}. */
  private void runVotes(long amount) throws Exception {
    boolean full = amount == TP_FROZEN;
    String tag = full ? "tron power full (votes + reward)" : "tron power half (votes + reward)";
    byte[] stake = deploy();

    Assert.assertEquals(tag + ": setup freeze", 1L,
        word(trigger(stake, calldata(0, TP_FROZEN, 2, 0, 0), SUCCESS), 0));
    // votes for two witnesses, written the way FreezeV2Test#testUnfreezeVotes does it
    AccountCapsule account = dbManager.getAccountStore().get(stake);
    VotesCapsule votes = new VotesCapsule(ByteString.copyFrom(stake), account.getVotesList());
    account.addVotes(ByteString.copyFrom(WITNESS_1), VOTES_EACH);
    votes.addNewVotes(ByteString.copyFrom(WITNESS_1), VOTES_EACH);
    account.addVotes(ByteString.copyFrom(WITNESS_2), VOTES_EACH);
    votes.addNewVotes(ByteString.copyFrom(WITNESS_2), VOTES_EACH);
    dbManager.getAccountStore().put(account.createDbKey(), account);
    dbManager.getVotesStore().put(votes.createDbKey(), votes);
    // a reward waiting to be settled: cycles 2..3 recorded for the account, current cycle 5,
    // and reward indexes for the witnesses at cycle 4
    DelegationStore ds = dbManager.getDelegationStore();
    ds.setBeginCycle(stake, 2);
    ds.setEndCycle(stake, 3);
    ds.setWitnessVi(4, WITNESS_1, VI_W1);
    ds.setWitnessVi(4, WITNESS_2, VI_W2);

    State before = capture(stake);
    Assert.assertEquals(tag + ": setup tron power frozen", TP_FROZEN, before.stake.frozen[2]);
    Assert.assertEquals(tag + ": setup votes", Arrays.asList(vote(WITNESS_1, VOTES_EACH),
        vote(WITNESS_2, VOTES_EACH)), before.stake.votes);
    Assert.assertNotNull(tag + ": setup votes record", before.votesRecord);
    // the freeze itself already initialised it (FreezeBalanceV2Processor), as invalid
    Assert.assertEquals(tag + ": setup old tron power (initialised as invalid)", -1L,
        before.stake.oldTronPower);
    Assert.assertEquals(tag + ": setup begin cycle", 2L, before.beginCycle);
    Assert.assertEquals(tag + ": setup end cycle", 3L, before.endCycle);
    Assert.assertNull(tag + ": setup no vote snapshot yet", before.accountVoteSnapshot);
    Assert.assertEquals(tag + ": setup allowance", 0L, before.stake.allowance);

    long usedVotesAfter = full ? 0 : 2 * (VOTES_EACH / 2);
    long[] expected = {1, TP_FROZEN - amount,
        UnfreezeBalanceV2Actuator.getUNFREEZE_MAX_TIMES() - 1, 0, BALANCE, usedVotesAfter};
    String callData = calldata(3, amount, 2, T0 / 1000, 1);

    // ---- reverted run
    TVMTestResult reverted = trigger(stake, callData, REVERT);
    State afterRevert = capture(stake);
    Assert.assertTrue(tag + ": runtime must be marked as reverted",
        reverted.getRuntime().getResult().isRevert());
    assertVisibleInsideExecution(tag + " (reverted)", reverted, expected);
    assertNothingChanged(tag + " after revert", before, afterRevert);
    assertOnlyEnergyFeeCharged(tag + " after revert", before, afterRevert, reverted);

    // ---- control
    TVMTestResult committed = trigger(stake, calldata(3, amount, 2, T0 / 1000, 0), SUCCESS);
    State afterCommit = capture(stake);
    assertVisibleInsideExecution(tag + " (control)", committed, expected);
    String c = tag + " control";
    Assert.assertEquals(c + ": tron power frozen", TP_FROZEN - amount, afterCommit.stake.frozen[2]);
    Assert.assertEquals(c + ": pending list", Arrays.asList(entry(TRON_POWER, amount, T0 + DELAY)),
        afterCommit.stake.unfrozen);
    Assert.assertEquals(c + ": available balance unchanged (nothing expired)", BALANCE,
        afterCommit.stake.available);
    Assert.assertEquals(c + ": global tron power weight drops by the unfrozen TRX",
        before.weights[2] - amount / TRX_PRECISION, afterCommit.weights[2]);
    Assert.assertEquals(c + ": reward paid into the allowance", REWARD,
        afterCommit.stake.allowance);
    Assert.assertEquals(c + ": old tron power stays invalid", -1L, afterCommit.stake.oldTronPower);
    List<String> votesAfter = full ? Collections.<String>emptyList()
        : Arrays.asList(vote(WITNESS_1, VOTES_EACH / 2), vote(WITNESS_2, VOTES_EACH / 2));
    Assert.assertEquals(c + ": account votes", votesAfter, afterCommit.stake.votes);
    Assert.assertNotNull(c + ": votes record", afterCommit.votesCapsule);
    Assert.assertEquals(c + ": votes record, new votes", votesAfter,
        voteStrings(afterCommit.votesCapsule.getNewVotes()));
    Assert.assertEquals(c + ": votes record, old votes untouched",
        voteStrings(before.votesCapsule.getOldVotes()),
        voteStrings(afterCommit.votesCapsule.getOldVotes()));
    Assert.assertEquals(c + ": begin cycle", CYCLE, afterCommit.beginCycle);
    Assert.assertEquals(c + ": end cycle", CYCLE + 1, afterCommit.endCycle);
    Assert.assertNotNull(c + ": vote snapshot of the current cycle",
        afterCommit.accountVoteSnapshot);
    assertOnlyEnergyFeeCharged(c, afterRevert, afterCommit, committed);
    print(tag, reverted, before, afterCommit);
  }

  /**
   * An account that predates the new resource model: it froze energy (stake 2.0), its old tron
   * power is a positive number and it has a vote. Un-freezing clears ALL its votes at once,
   * creates the VotesStore record, and invalidates the old tron power (migration path).
   */
  private void runLegacyVotes() throws Exception {
    String tag = "energy (legacy old tron power + vote)";
    long amount = 400 * TRX_PRECISION;
    byte[] stake = deploy();

    Assert.assertEquals(tag + ": setup freeze", 1L,
        word(trigger(stake, calldata(0, TP_FROZEN, 1, 0, 0), SUCCESS), 0));
    // written the way FreezeV2Test#testUnfreezeWithOldTronPower does it
    AccountCapsule account = dbManager.getAccountStore().get(stake);
    account.setOldTronPower(TP_FROZEN);
    account.addVotes(ByteString.copyFrom(WITNESS_1), 100L);
    dbManager.getAccountStore().put(account.createDbKey(), account);

    State before = capture(stake);
    Assert.assertEquals(tag + ": setup energy frozen", TP_FROZEN, before.stake.frozen[1]);
    Assert.assertEquals(tag + ": setup old tron power", TP_FROZEN, before.stake.oldTronPower);
    Assert.assertEquals(tag + ": setup votes", Arrays.asList(vote(WITNESS_1, 100)),
        before.stake.votes);
    Assert.assertNull(tag + ": setup no votes record", before.votesRecord);
    Assert.assertEquals(tag + ": setup begin cycle (never settled)", 0L, before.beginCycle);
    Assert.assertNull(tag + ": setup no vote snapshot", before.accountVoteSnapshot);

    long[] expected = {1, TP_FROZEN - amount,
        UnfreezeBalanceV2Actuator.getUNFREEZE_MAX_TIMES() - 1, 0, BALANCE, 0};

    // ---- reverted run
    TVMTestResult reverted = trigger(stake, calldata(3, amount, 1, T0 / 1000, 1), REVERT);
    State afterRevert = capture(stake);
    Assert.assertTrue(tag + ": runtime must be marked as reverted",
        reverted.getRuntime().getResult().isRevert());
    assertVisibleInsideExecution(tag + " (reverted)", reverted, expected);
    assertNothingChanged(tag + " after revert", before, afterRevert);
    assertOnlyEnergyFeeCharged(tag + " after revert", before, afterRevert, reverted);

    // ---- control
    TVMTestResult committed = trigger(stake, calldata(3, amount, 1, T0 / 1000, 0), SUCCESS);
    State afterCommit = capture(stake);
    assertVisibleInsideExecution(tag + " (control)", committed, expected);
    String c = tag + " control";
    Assert.assertEquals(c + ": energy frozen", TP_FROZEN - amount, afterCommit.stake.frozen[1]);
    Assert.assertEquals(c + ": pending list", Arrays.asList(entry(ENERGY, amount, T0 + DELAY)),
        afterCommit.stake.unfrozen);
    Assert.assertEquals(c + ": global energy weight drops by the unfrozen TRX",
        before.weights[1] - amount / TRX_PRECISION, afterCommit.weights[1]);
    Assert.assertEquals(c + ": old tron power invalidated", -1L, afterCommit.stake.oldTronPower);
    Assert.assertTrue(c + ": all votes cleared at once", afterCommit.stake.votes.isEmpty());
    Assert.assertNotNull(c + ": votes record created", afterCommit.votesCapsule);
    Assert.assertTrue(c + ": votes record, new votes cleared",
        afterCommit.votesCapsule.getNewVotes().isEmpty());
    Assert.assertEquals(c + ": votes record, old votes = the votes that were cleared",
        Arrays.asList(vote(WITNESS_1, 100)),
        voteStrings(afterCommit.votesCapsule.getOldVotes()));
    Assert.assertEquals(c + ": allowance (no reward accrued)", 0L, afterCommit.stake.allowance);
    Assert.assertEquals(c + ": begin cycle", CYCLE, afterCommit.beginCycle);
    Assert.assertEquals(c + ": end cycle", CYCLE + 1, afterCommit.endCycle);
    Assert.assertNotNull(c + ": vote snapshot of the current cycle",
        afterCommit.accountVoteSnapshot);
    assertOnlyEnergyFeeCharged(c, afterRevert, afterCommit, committed);
    print(tag, reverted, before, afterCommit);
  }

  private void print(String tag, TVMTestResult reverted, State before, State afterCommit) {
    System.out.println("OBS " + tag + " | in-execution words " + words(reverted)
        + " | available " + before.stake.available + " -> " + afterCommit.stake.available
        + " | allowance " + before.stake.allowance + " -> " + afterCommit.stake.allowance
        + " | pending " + before.stake.unfrozen.size() + " -> " + afterCommit.stake.unfrozen.size()
        + " | votes " + before.stake.votes.size() + " -> " + afterCommit.stake.votes.size()
        + " | cycles " + before.beginCycle + "/" + before.endCycle + " -> "
        + afterCommit.beginCycle + "/" + afterCommit.endCycle
        + " | oldTronPower " + before.stake.oldTronPower + " -> "
        + afterCommit.stake.oldTronPower);
  }

  // ---------------------------------------------------------------- assertions

  private void assertVisibleInsideExecution(String tag, TVMTestResult result, long[] expected) {
    byte[] data = result.getRuntime().getResult().getHReturn();
    Assert.assertEquals(tag + ": 6 result words expected", 192, data.length);
    String[] names = {"operation result", "own frozenV2 (0x0100000d)",
        "free pending slots (0x0100000c)", "expired pending at test time (0x0100000e)",
        "available balance (BALANCE)", "votes in use (0x01000008)"};
    for (int i = 0; i < 6; i++) {
      Assert.assertEquals(tag + ": in-execution " + names[i], expected[i], word(result, i));
    }
  }

  /** Every store the operation can touch is identical to the state before. */
  private void assertNothingChanged(String tag, State before, State after) {
    Acct b = before.stake;
    Acct a = after.stake;
    Assert.assertEquals(tag + ": available balance", b.available, a.available);
    Assert.assertEquals(tag + ": allowance", b.allowance, a.allowance);
    Assert.assertEquals(tag + ": old tron power", b.oldTronPower, a.oldTronPower);
    Assert.assertArrayEquals(tag + ": frozenV2 (bandwidth, energy, tron power)",
        b.frozen, a.frozen);
    Assert.assertEquals(tag + ": pending unfreeze list", b.unfrozen, a.unfrozen);
    Assert.assertEquals(tag + ": account votes", b.votes, a.votes);
    Assert.assertArrayEquals(tag + ": full serialized account", b.bytes, a.bytes);
    Assert.assertArrayEquals(tag + ": votes record", before.votesRecord, after.votesRecord);
    Assert.assertEquals(tag + ": reward begin cycle", before.beginCycle, after.beginCycle);
    Assert.assertEquals(tag + ": reward end cycle", before.endCycle, after.endCycle);
    Assert.assertArrayEquals(tag + ": vote snapshot of the current cycle",
        before.accountVoteSnapshot, after.accountVoteSnapshot);
    Assert.assertArrayEquals(tag + ": global weights", before.weights, after.weights);
  }

  /**
   * The protocol keeps the energy of a reverted call: the caller pays it, and that is the only
   * permitted difference in balances. (Bandwidth is charged by the block processor, which this
   * harness does not run.)
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

  private static String vote(byte[] witness, long count) {
    return Hex.toHexString(witness) + ":" + count;
  }

  private static List<String> voteStrings(List<Protocol.Vote> votes) {
    List<String> out = new ArrayList<>();
    for (Protocol.Vote v : votes) {
      out.add(vote(v.getVoteAddress().toByteArray(), v.getVoteCount()));
    }
    return Collections.unmodifiableList(out);
  }

  private static final class Acct {
    final long available;
    final long allowance;
    final long oldTronPower;
    final long[] frozen;   // frozenV2: bandwidth, energy, tron power
    final List<String> unfrozen;
    final List<String> votes;
    final int unfreezingCountAtTest;
    final byte[] bytes;

    Acct(AccountCapsule a, long now) {
      available = a.getBalance();
      allowance = a.getAllowance();
      oldTronPower = a.getInstance().getOldTronPower();
      frozen = new long[] {a.getFrozenV2BalanceForBandwidth(), a.getFrozenV2BalanceForEnergy(),
          a.getTronPowerFrozenV2Balance()};
      List<String> pending = new ArrayList<>();
      for (Protocol.Account.UnFreezeV2 u : a.getUnfrozenV2List()) {
        pending.add(entry(u.getType(), u.getUnfreezeAmount(), u.getUnfreezeExpireTime()));
      }
      unfrozen = Collections.unmodifiableList(pending);
      votes = voteStrings(a.getVotesList());
      unfreezingCountAtTest = a.getUnfreezingV2Count(now);
      bytes = a.getData();
    }
  }

  private static final class State {
    Acct stake;
    VotesCapsule votesCapsule;   // null if absent
    byte[] votesRecord;          // null if absent
    long beginCycle;
    long endCycle;
    byte[] accountVoteSnapshot;  // null if absent
    long[] weights;              // total net, energy, tron power
    long senderBalance;
  }

  private State capture(byte[] stake) {
    State s = new State();
    DynamicPropertiesStore dps = dbManager.getDynamicPropertiesStore();
    s.stake = new Acct(dbManager.getAccountStore().get(stake),
        dps.getLatestBlockHeaderTimestamp());
    s.votesCapsule = dbManager.getVotesStore().get(stake);
    s.votesRecord = s.votesCapsule == null ? null : s.votesCapsule.getData();
    DelegationStore ds = dbManager.getDelegationStore();
    s.beginCycle = ds.getBeginCycle(stake);
    s.endCycle = ds.getEndCycle(stake);
    AccountCapsule snapshot = ds.getAccountVote(CYCLE, stake);
    s.accountVoteSnapshot = snapshot == null ? null : snapshot.getData();
    s.weights = new long[] {dps.getTotalNetWeight(), dps.getTotalEnergyWeight(),
        dps.getTotalTronPowerWeight()};
    s.senderBalance = dbManager.getAccountStore().get(owner).getBalance();
    return s;
  }

  private void setHead(long timestampMs) {
    dbManager.getDynamicPropertiesStore().saveLatestBlockHeaderTimestamp(timestampMs);
  }

  // ---------------------------------------------------------------- running transactions

  private byte[] deploy() throws Exception {
    // consumeUserResourcePercent = 100: the caller pays all the energy, not the contract owner.
    // The name is unique per deployment: the address derives from the txid.
    Protocol.Transaction trx = TvmTestUtils.generateDeploySmartContractAndGetTransaction(
        "F" + deployCount++, owner, "[]", CODE_INIT, BALANCE, FEE_LIMIT, 100, null, 100_000);
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

  /** op, amount, resource, time in seconds, mode. */
  private static String calldata(long op, long amount, long res, long timeSeconds, long mode) {
    StringBuilder sb = new StringBuilder();
    for (long v : new long[] {op, amount, res, timeSeconds, mode}) {
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
        // dispatch on op (w0): 3 -> UNFREEZE, otherwise freeze
        .arg(0).push(3).op(Op.EQ).pushLabel("UNFREEZE").op(Op.JUMPI)
        // op 0: word0 = FREEZEBALANCEV2(amount = w1, resource = w2)   (resource is popped first)
        .arg(1).arg(2).op(Op.FREEZEBALANCEV2).store(0x00)
        .pushLabel("TAIL").op(Op.JUMP)
        // op 3: word0 = UNFREEZEBALANCEV2(amount = w1, resource = w2)
        .label("UNFREEZE")
        .arg(1).arg(2).op(Op.UNFREEZEBALANCEV2).store(0x00)
        // word1 = 0x0100000d(this, resource): own frozenV2 balance
        .op(Op.ADDRESS).store(SCRATCH).arg(2).store(SCRATCH + 0x20)
        .query(0x0d, SCRATCH, 0x40, 0x20)
        // word2 = 0x0100000c(this): free slots for pending unfreezes
        .op(Op.ADDRESS).store(SCRATCH)
        .query(0x0c, SCRATCH, 0x20, 0x40)
        // word3 = 0x0100000e(this, w3): pending unfreezes already expired at time w3 (seconds)
        .op(Op.ADDRESS).store(SCRATCH).arg(3).store(SCRATCH + 0x20)
        .query(0x0e, SCRATCH, 0x40, 0x60)
        // word4 = BALANCE(this)
        .op(Op.ADDRESS, Op.BALANCE).store(0x80)
        // word5 = 0x01000008(this): votes in use
        .op(Op.ADDRESS).store(SCRATCH)
        .query(0x08, SCRATCH, 0x20, 0xa0)
        // tail: mode (w4) != 0 -> REVERT(0, 0xc0), else RETURN(0, 0xc0)
        .label("TAIL")
        .arg(4).pushLabel("REVERT").op(Op.JUMPI)
        .push(0xc0).push(0x00).op(Op.RETURN)
        .label("REVERT")
        .push(0xc0).push(0x00).op(Op.REVERT)
        .build();
  }

  /** Init code: copy the runtime code to memory and return it. */
  private static String initCode(byte[] runtime) {
    // PUSH2 len DUP1 PUSH1 0x0c PUSH1 0 CODECOPY PUSH1 0 RETURN  (12 bytes)
    return String.format("61%04x80600c6000396000f3", runtime.length) + Hex.toHexString(runtime);
  }
}
