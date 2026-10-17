package com.evidence.rag.worker.indexing;

import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.model.domain.EmbeddingThroughput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * One token bucket for embedding requests, shared by every indexing child process of this user and
 * embedding endpoint. Each indexing attempt runs in its own JVM, so the bucket lives in a small
 * owner-only state file next to the index leases and is read and refilled under an exclusive file
 * lock; in-JVM callers serialize first, because two channels of one process must never hold or
 * close a lock on the same file concurrently.
 *
 * <p>Two balances refill continuously: requests at {@code requestsPerMinute/60000} per ms and
 * estimated input tokens at {@code tokensPerMinute/60000} per ms, each capped at two seconds' worth
 * (at least one request). A request takes 1 request and its estimated tokens once both balances
 * allow it; a request larger than the token capacity waits for a full bucket and then borrows, so
 * the long-run rate still holds. A limit of 0 disables that balance.
 */
final class EmbeddingRateLimiter {
  private static final Object JVM_LOCK = new Object();
  private static final int STATE_BYTES = 24;
  private static final double BURST_MS = 2000;
  private final Path file;
  private final double requestRate;
  private final double tokenRate;
  private final double requestCapacity;
  private final double tokenCapacity;
  private final LongSupplier clock;

  private EmbeddingRateLimiter(Path file, EmbeddingThroughput limits, LongSupplier clock) {
    this.file = file;
    this.requestRate = limits.requestsPerMinute() / 60000.0;
    this.tokenRate = limits.tokensPerMinute() / 60000.0;
    this.requestCapacity = Math.max(1, requestRate * BURST_MS);
    this.tokenCapacity = Math.max(1, tokenRate * BURST_MS);
    this.clock = clock;
  }

  /** Null when no limit is configured. */
  static EmbeddingRateLimiter open(
      OpenAiCompatibleModels.Endpoint endpoint, EmbeddingThroughput limits) {
    if (!limits.limited()) {
      return null;
    }
    try {
      String key =
          endpoint.baseUrl()
              + "\n"
              + endpoint.model()
              + "\n"
              + limits.requestsPerMinute()
              + "\n"
              + limits.tokensPerMinute();
      Path file =
          IndexWorkerLifetime.privateRoot()
              .resolve(IndexWorkerLifetime.privateName(key) + ".embedding-bucket");
      return new EmbeddingRateLimiter(file, limits, System::currentTimeMillis);
    } catch (IOException | RuntimeException unavailable) {
      throw new ProcessTextIndexer.Failure("indexing_lifetime_failed");
    }
  }

  /** Test seam: an explicit state file and clock. */
  static EmbeddingRateLimiter at(Path file, EmbeddingThroughput limits, LongSupplier clock) {
    return new EmbeddingRateLimiter(file, limits, clock);
  }

  /** Waits (within the attempt's lifetime) until one request of {@code tokens} may start. */
  void acquire(long tokens, IndexWorkerLifetime lifetime) {
    while (true) {
      lifetime.check();
      long wait = tryTake(tokens);
      if (wait == 0) {
        return;
      }
      lifetime.pause(Duration.ofMillis(Math.min(wait, 250)));
    }
  }

  /** Takes one request now and returns 0, or returns the milliseconds until it could be taken. */
  long tryTake(long tokens) {
    synchronized (JVM_LOCK) {
      try (var channel =
          FileChannel.open(
              file,
              Set.of(
                  StandardOpenOption.CREATE,
                  StandardOpenOption.READ,
                  StandardOpenOption.WRITE,
                  LinkOption.NOFOLLOW_LINKS),
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
        validate();
        try (var lock = channel.lock()) {
          var state = ByteBuffer.allocate(STATE_BYTES);
          long size = channel.size();
          long now = clock.getAsLong();
          long last;
          double requests;
          double estimated;
          if (size == 0) {
            last = now;
            requests = requestCapacity;
            estimated = tokenCapacity;
          } else if (size == STATE_BYTES) {
            while (state.hasRemaining() && channel.read(state, state.position()) > 0) {
              // Read the whole fixed-size state.
            }
            if (state.hasRemaining()) throw new IOException();
            state.flip();
            last = state.getLong();
            requests = state.getDouble();
            estimated = state.getDouble();
            if (!Double.isFinite(requests) || !Double.isFinite(estimated)) throw new IOException();
          } else {
            throw new IOException();
          }
          // A clock that went backwards refills nothing; a long gap refills to capacity.
          long elapsed = Math.max(0, now - last);
          requests = Math.min(requestCapacity, requests + elapsed * requestRate);
          estimated = Math.min(tokenCapacity, estimated + elapsed * tokenRate);
          double tokenNeed = Math.min(tokens, tokenCapacity);
          long wait = 0;
          if (requestRate > 0 && requests < 1) {
            wait = Math.max(wait, (long) Math.ceil((1 - requests) / requestRate));
          }
          if (tokenRate > 0 && estimated < tokenNeed) {
            wait = Math.max(wait, (long) Math.ceil((tokenNeed - estimated) / tokenRate));
          }
          if (wait == 0) {
            if (requestRate > 0) requests -= 1;
            if (tokenRate > 0) estimated -= tokens;
          }
          state.clear();
          state.putLong(Math.max(now, last)).putDouble(requests).putDouble(estimated).flip();
          while (state.hasRemaining()) {
            channel.write(state, state.position());
          }
          return Math.max(0, wait == 0 ? 0 : Math.max(1, wait));
        }
      } catch (IOException | RuntimeException unavailable) {
        throw new ProcessTextIndexer.Failure("indexing_lifetime_failed");
      }
    }
  }

  private void validate() throws IOException {
    var attributes =
        Files.readAttributes(file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attributes.owner().equals(IndexWorkerLifetime.currentOwner(file))
        || !attributes.permissions().equals(PosixFilePermissions.fromString("rw-------"))
        || !attributes.isRegularFile()
        || attributes.isSymbolicLink()
        || (attributes.size() != 0 && attributes.size() != STATE_BYTES)
        || ((Number) Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue()
            != 1) {
      throw new IOException();
    }
  }
}
