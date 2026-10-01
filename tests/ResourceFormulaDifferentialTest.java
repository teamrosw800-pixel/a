package org.tron.core.db;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.tron.common.BaseTest;
import org.tron.common.TestConstants;
import org.tron.core.config.args.Args;
import org.tron.core.store.StoreFactory;
import org.tron.core.vm.config.VMConfig;
import org.tron.core.vm.repository.RepositoryImpl;

/**
 * Differential test: the resource formulas exist twice (vm.repository.RepositoryImpl and
 * db.ResourceProcessor). Each side already has its own parity tests; none compares the two sides
 * with each other. Same inputs, same flag value on both sides, results and exception classes
 * must be identical.
 */
@Slf4j
public class ResourceFormulaDifferentialTest extends BaseTest {

  static {
    Args.setParam(new String[]{"--output-directory", dbPath()}, TestConstants.TEST_CONF);
  }

  private static final int CASES = 20_000;

  private RepositoryImpl repository;
  private EnergyProcessor processor;
  private Method repoIncrease;
  private Method repoGetUsage;
  private Method procGetUsage;

  @Before
  public void setUp() throws Exception {
    repository = RepositoryImpl.createRoot(StoreFactory.getInstance());
    processor = new EnergyProcessor(
        dbManager.getDynamicPropertiesStore(), dbManager.getAccountStore());
    repoIncrease = RepositoryImpl.class.getDeclaredMethod(
        "increase", long.class, long.class, long.class, long.class, long.class);
    repoIncrease.setAccessible(true);
    repoGetUsage = RepositoryImpl.class.getDeclaredMethod("getUsage", long.class, long.class);
    repoGetUsage.setAccessible(true);
    procGetUsage = ResourceProcessor.class.getDeclaredMethod("getUsage", long.class, long.class);
    procGetUsage.setAccessible(true);
  }

  @After
  public void tearDown() {
    VMConfig.initAllowHardenResourceCalculation(0);
    dbManager.getDynamicPropertiesStore().saveAllowHardenResourceCalculation(0);
  }

  private void setFlag(int v) {
    VMConfig.initAllowHardenResourceCalculation(v);
    dbManager.getDynamicPropertiesStore().saveAllowHardenResourceCalculation(v);
  }

  private interface Call {
    long run() throws Exception;
  }

  /** Result as a string: the value, or the exception class (never the message). */
  private static String outcome(Call c) {
    try {
      return Long.toString(c.run());
    } catch (InvocationTargetException e) {
      return "EXC:" + e.getCause().getClass().getSimpleName();
    } catch (Throwable e) {
      return "EXC:" + e.getClass().getSimpleName();
    }
  }

  /** Mix of magnitudes: tiny, realistic, near the 64-bit limits. */
  private static long pick(Random r, long realisticMax) {
    switch (r.nextInt(6)) {
      case 0: return r.nextInt(10);
      case 1: return (long) (r.nextDouble() * realisticMax);
      case 2: return (long) (r.nextDouble() * Long.MAX_VALUE / 1_000_000L);
      case 3: return (long) (r.nextDouble() * Long.MAX_VALUE / 1_000L);
      case 4: return Long.MAX_VALUE - r.nextInt(1000);
      default: return (long) (r.nextDouble() * Long.MAX_VALUE);
    }
  }

  private void compareIncrease(int flag, List<String> mismatches, long[] counters) {
    setFlag(flag);
    Random r = new Random(1234L + flag);
    for (int i = 0; i < CASES; i++) {
      final long lastUsage = pick(r, 100_000_000_000L);
      final long usage = pick(r, 100_000_000_000L);
      final long lastTime = r.nextInt(1_000_000);
      final long now = lastTime + r.nextInt(60_000); // precondition: now >= lastTime
      final long window = 1 + (r.nextInt(3) == 0 ? r.nextInt(100) : 28_800 + r.nextInt(300_000));
      String a = outcome(() -> (long) repoIncrease.invoke(repository,
          lastUsage, usage, lastTime, now, window));
      String b = outcome(() -> processor.increase(lastUsage, usage, lastTime, now, window));
      counters[0]++;
      if (a.startsWith("EXC")) {
        counters[1]++;
      }
      if (!a.equals(b)) {
        counters[2]++;
        if (mismatches.size() < 8) {
          mismatches.add(String.format("flag=%d increase(%d,%d,%d,%d,%d) repo=%s proc=%s",
              flag, lastUsage, usage, lastTime, now, window, a, b));
        }
      }
    }
  }

  private void compareGetUsage(int flag, List<String> mismatches, long[] counters) {
    setFlag(flag);
    Random r = new Random(98765L + flag);
    for (int i = 0; i < CASES; i++) {
      final long usage = pick(r, 100_000_000_000L);
      final long window = 1 + (r.nextInt(3) == 0 ? r.nextInt(100) : 28_800 + r.nextInt(300_000));
      String a = outcome(() -> (long) repoGetUsage.invoke(repository, usage, window));
      String b = outcome(() -> (long) procGetUsage.invoke(processor, usage, window));
      counters[0]++;
      if (a.startsWith("EXC")) {
        counters[1]++;
      }
      if (!a.equals(b)) {
        counters[2]++;
        if (mismatches.size() < 8) {
          mismatches.add(String.format("flag=%d getUsage(%d,%d) repo=%s proc=%s",
              flag, usage, window, a, b));
        }
      }
    }
  }

  private void run(String name, boolean increase) {
    for (int flag = 0; flag <= 1; flag++) {
      List<String> mism = new ArrayList<>();
      long[] c = new long[3]; // casos, con excepcion, discrepancias
      if (increase) {
        compareIncrease(flag, mism, c);
      } else {
        compareGetUsage(flag, mism, c);
      }
      System.out.printf("DIFF %s flag=%d casos=%d conExcepcion=%d discrepancias=%d%n",
          name, flag, c[0], c[1], c[2]);
      mism.forEach(m -> System.out.println("DIFF   " + m));
      Assert.assertEquals(name + " flag=" + flag + " " + mism, 0L, c[2]);
    }
  }

  @Test
  public void increaseAgreesBetweenVmSideAndProcessorSide() {
    run("increase", true);
  }

  @Test
  public void getUsageAgreesBetweenVmSideAndProcessorSide() {
    run("getUsage", false);
  }

  /**
   * Independent oracle (BigInteger, no 64-bit anywhere) for the case lastTime == now, where no
   * decay is applied: result = floor((ceil(a*p/w) + ceil(b*p/w)) * w / p). With the hardening flag
   * on, the code must either return exactly this value or throw; returning anything else is a
   * silent wrong result. Inside the realistic range this is asserted; outside it, it is only
   * reported, because real usage values are bounded far below 2^63 by energy and bandwidth limits.
   */
  @Test
  public void hardenedIncreaseMatchesExactOracleOrThrows() throws Exception {
    setFlag(1);
    Field pf = ResourceProcessor.class.getDeclaredField("precision");
    pf.setAccessible(true);
    BigInteger p = BigInteger.valueOf(pf.getLong(processor));
    BigInteger max = BigInteger.valueOf(Long.MAX_VALUE);
    final long realistic = 1_000_000_000_000L; // 1e12: ~10x the network-wide energy limit; with it no
    // 64-bit overflow is possible for any window size >= 1 (1e12 * 1e6 / 1 = 1e18 < 2^63)
    Random r = new Random(777L);
    long[] inRange = new long[2];   // casos, violaciones
    long[] outRange = new long[2];
    List<String> examples = new ArrayList<>();
    for (int i = 0; i < CASES * 2; i++) {
      final long a = pick(r, realistic);
      final long b = pick(r, realistic);
      final long w = 1 + (r.nextInt(3) == 0 ? r.nextInt(100) : 28_800 + r.nextInt(300_000));
      BigInteger bw = BigInteger.valueOf(w);
      BigInteger[] qa = BigInteger.valueOf(a).multiply(p).divideAndRemainder(bw);
      BigInteger[] qb = BigInteger.valueOf(b).multiply(p).divideAndRemainder(bw);
      BigInteger ea = qa[0].add(qa[1].signum() > 0 ? BigInteger.ONE : BigInteger.ZERO);
      BigInteger eb = qb[0].add(qb[1].signum() > 0 ? BigInteger.ONE : BigInteger.ZERO);
      BigInteger sum = ea.add(eb);
      BigInteger exact = sum.multiply(bw).divide(p);
      boolean exactFits = ea.compareTo(max) <= 0 && eb.compareTo(max) <= 0
          && sum.compareTo(max) <= 0 && exact.compareTo(max) <= 0;
      final long fa = a;
      final long fb = b;
      String got = outcome(() -> (long) repoIncrease.invoke(repository, fa, fb, 5L, 5L, w));
      boolean ok = exactFits ? got.equals(exact.toString()) : got.startsWith("EXC");
      boolean inside = a <= realistic && b <= realistic;
      long[] c = inside ? inRange : outRange;
      c[0]++;
      if (!ok) {
        c[1]++;
        if (examples.size() < 10) {
          examples.add(String.format("%s increase(%d,%d,5,5,%d) exacto=%s fitsEn64bits=%b obtenido=%s",
              inside ? "[DENTRO]" : "[fuera]", a, b, w, exact, exactFits, got));
        }
      }
    }
    System.out.printf("ORACLE dentroDeRangoReal casos=%d violaciones=%d | fueraDeRango casos=%d "
        + "violaciones=%d%n", inRange[0], inRange[1], outRange[0], outRange[1]);
    examples.forEach(e -> System.out.println("ORACLE   " + e));
    Assert.assertEquals("violations inside the realistic range", 0L, inRange[1]);
  }
}
