package com.evidence.rag.worker.indexing;

import com.evidence.rag.client.model.OpenAiCompatibleModels.Configuration;
import com.evidence.rag.client.vector.MilvusRestProjection.Settings;
import java.time.Duration;
import java.util.List;

/**
 * Explicit test-only proxy bootstrap; the real isolated worker still owns execution and protocol.
 */
public final class LiveIndexWorker {
  private LiveIndexWorker() {}

  public static ProcessTextIndexer create(
      Configuration models, Settings projection, Duration timeout, String host, String port) {
    validateProxy(host, port);
    // ProcessTextIndexer clears the child environment. Only these non-secret arguments cross it.
    return new ProcessTextIndexer(
        models, projection, timeout, LiveIndexWorker.class.getName(), List.of(host, port));
  }

  public static void main(String[] args) {
    if (args.length != 2) {
      throw new IllegalArgumentException("Explicit live-worker loopback proxy is required");
    }
    validateProxy(args[0], args[1]);
    System.setProperty("https.proxyHost", args[0]);
    System.setProperty("https.proxyPort", args[1]);
    // main, not run: retain the production parent watchdog, lifetime and collection lease.
    IndexWorker.main(new String[0]);
  }

  public static void validateProxy(String host, String port) {
    if (!"127.0.0.1".equals(host) || port == null || !port.matches("[1-9][0-9]{3,4}")) {
      throw new IllegalArgumentException("Explicit live-worker loopback proxy is required");
    }
    int number = Integer.parseInt(port);
    if (number < 1024 || number > 65535) {
      throw new IllegalArgumentException("Live-worker proxy port is outside the allowed range");
    }
  }
}
