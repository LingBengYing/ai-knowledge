package com.evidence.rag.model.domain;

import java.math.BigInteger;

public record VideoAvEpoch(
    long sourceFirstPts,
    long sourceTimeBaseNumerator,
    long sourceTimeBaseDenominator,
    long ticksPerSecond) {
  public VideoAvEpoch {
    if (sourceTimeBaseNumerator < 1
        || sourceTimeBaseDenominator < 1
        || sourceTimeBaseNumerator > 10000000000L
        || sourceTimeBaseDenominator > 10000000000L) {
      throw ModelValues.invalid();
    }
    var n = BigInteger.valueOf(sourceTimeBaseNumerator);
    var d = BigInteger.valueOf(sourceTimeBaseDenominator);
    if (!n.gcd(d).equals(BigInteger.ONE)
        || ticksPerSecond
            != d.divide(d.gcd(BigInteger.valueOf(16000)))
                .multiply(BigInteger.valueOf(16000))
                .longValueExact()) {
      throw ModelValues.invalid();
    }
    origin(sourceFirstPts, sourceTimeBaseNumerator, sourceTimeBaseDenominator);
  }

  private static long origin(long pts, long n, long d) {
    var parts =
        BigInteger.valueOf(pts)
            .multiply(BigInteger.valueOf(n))
            .multiply(BigInteger.valueOf(1000000))
            .divideAndRemainder(BigInteger.valueOf(d));
    return (parts[1].signum() < 0 ? parts[0].subtract(BigInteger.ONE) : parts[0]).longValueExact();
  }

  public long timelineOriginUs() {
    return origin(sourceFirstPts, sourceTimeBaseNumerator, sourceTimeBaseDenominator);
  }

  public long sampleAt(long tick) {
    return scaled(tick, 16000, false);
  }

  public long startMs(long tick) {
    return scaled(tick, 1000, false);
  }

  public long endMs(long tick) {
    return scaled(tick, 1000, true);
  }

  public long durationLimit(int seconds) {
    return Math.multiplyExact(ticksPerSecond, seconds);
  }

  private long scaled(long tick, long scale, boolean ceil) {
    if (tick < 0) {
      throw ModelValues.invalid();
    }
    var p =
        BigInteger.valueOf(tick)
            .multiply(BigInteger.valueOf(scale))
            .divideAndRemainder(BigInteger.valueOf(ticksPerSecond));
    return (ceil && p[1].signum() != 0 ? p[0].add(BigInteger.ONE) : p[0]).longValueExact();
  }
}
