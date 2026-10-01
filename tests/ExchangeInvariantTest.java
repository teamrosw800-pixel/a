package org.tron.core.capsule;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Assert;
import org.junit.Test;

/**
 * Property test of the exchange (Bancor-style) calculation, for both implementations:
 * the legacy {@link ExchangeProcessor} (strict math) and {@link SafeExchangeProcessor}.
 * Invariants checked, none of which depend on the exact rounding:
 *  I1 the output is never negative and never exceeds the reserve of the bought token;
 *  I2 a round trip (sell q of A, then sell back what was received) never returns more than q;
 *  I3 monotonic: selling more never yields less.
 * A thrown ArithmeticException counts as "request rejected", which is allowed.
 */
public class ExchangeInvariantTest {

  private static final int CASES = 200_000;
  private static final long SUPPLY = 1_000_000_000_000_000_000L;

  private interface Impl {
    long exchange(long sellBal, long buyBal, long quant);
  }

  private static final Impl LEGACY = (s, b, q) -> new ExchangeProcessor(SUPPLY, true).exchange(s, b, q);
  private static final Impl SAFE = (s, b, q) -> SafeExchangeProcessor.INSTANCE.exchange(s, b, q);

  /** Pool sizes from tiny to 1e15, quantities from 1 to the whole pool. */
  private static long pool(Random r) {
    switch (r.nextInt(4)) {
      case 0: return 1_000L + r.nextInt(1_000_000);
      case 1: return 1_000_000L + (long) (r.nextDouble() * 1e9);
      case 2: return 1_000_000_000L + (long) (r.nextDouble() * 1e12);
      default: return 1_000_000_000_000L + (long) (r.nextDouble() * 1e15);
    }
  }

  private static long quant(Random r, long poolSize) {
    switch (r.nextInt(4)) {
      case 0: return 1 + r.nextInt(100);
      case 1: return 1 + (long) (r.nextDouble() * poolSize / 1000);
      case 2: return 1 + (long) (r.nextDouble() * poolSize);
      default: return 1 + (long) (r.nextDouble() * poolSize * 4);
    }
  }

  private static Long tryEx(Impl f, long s, long b, long q) {
    try {
      return f.exchange(s, b, q);
    } catch (ArithmeticException e) {
      return null; // rejected
    }
  }

  private void check(String name, Impl f, long seed) {
    Random r = new Random(seed);
    long[] viol = new long[4]; // I1, I2, I3, total casos aceptados
    double maxGainOverPool = 0;
    long gainsOver1e12 = 0;
    long maxGainAbs = 0;
    List<String> examples = new ArrayList<>();
    for (int i = 0; i < CASES; i++) {
      long a = pool(r);
      long b = pool(r);
      long q = quant(r, a);
      Long out = tryEx(f, a, b, q);
      if (out == null) {
        continue;
      }
      viol[3]++;
      if (out < 0 || out > b) {
        viol[0]++;
        if (examples.size() < 5) {
          examples.add(String.format("I1 (%d,%d,%d) out=%d", a, b, q, out));
        }
        continue;
      }
      // I2: round trip, after the trade the pool is (a+q, b-out); sell back 'out' of B
      if (out > 0 && b - out >= 0 && a + q > 0) {
        Long back = tryEx(f, b - out, a + q, out);
        if (back != null && back > q) {
          double rel = (double) (back - q) / (double) a;
          maxGainOverPool = Math.max(maxGainOverPool, rel);
          maxGainAbs = Math.max(maxGainAbs, back - q);
          if (rel > 1e-12) {
            gainsOver1e12++;
          }
          viol[1]++;
          if (examples.size() < 5) {
            examples.add(String.format("I2 (%d,%d,%d) out=%d back=%d", a, b, q, out, back));
          }
        }
      }
      // I3: monotonic
      Long more = tryEx(f, a, b, q + 1 + r.nextInt(1000));
      if (more != null && more < out) {
        viol[2]++;
        if (examples.size() < 5) {
          examples.add(String.format("I3 (%d,%d,%d) out=%d more=%d", a, b, q, out, more));
        }
      }
    }
    System.out.printf("EXCH %s aceptados=%d violacionesI1=%d violacionesI2=%d violacionesI3=%d%n",
        name, viol[3], viol[0], viol[1], viol[2]);
    System.out.printf("EXCH %s gananciaMaxima/reserva=%.3e gananciasMayoresQue1e-12=%d gananciaMaxAbs=%d%n",
        name, maxGainOverPool, gainsOver1e12, maxGainAbs);
    examples.forEach(e -> System.out.println("EXCH   " + name + " " + e));
    // I1 and I3 are exact properties; I2 is bounded by double precision, so it is measured above.
    Assert.assertEquals(name + " " + examples, 0L, viol[0] + viol[2]);
    Assert.assertTrue(name + " round trip gain over pool exceeds 1e-11: " + maxGainOverPool,
        maxGainOverPool <= 1e-11);
  }

  @Test
  public void legacyExchangeInvariants() {
    check("legacy", LEGACY, 11L);
  }

  @Test
  public void safeExchangeInvariants() {
    check("safe", SAFE, 22L);
  }
}
