package com.evidence.rag.corpus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Controlled real child for the scheduler cancellation integration tests; no production hook. */
public final class SlowParserFixture {
  private SlowParserFixture() {}

  public static ProcessTextParser parser(Duration deadline, Path pidFile) {
    return new ProcessTextParser(
        deadline, SlowParserFixture.class.getName(), List.of(pidFile.toString()));
  }

  public static void main(String[] args) throws Exception {
    Files.writeString(Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
    System.in.transferTo(java.io.OutputStream.nullOutputStream());
    Thread.sleep(60000);
  }
}
