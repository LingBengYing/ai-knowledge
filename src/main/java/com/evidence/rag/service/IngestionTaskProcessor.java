package com.evidence.rag.service;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.IngestionClaim;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.parser.ProcessTextParser;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/** One parsing use case; authority transactions finish before the isolated worker is entered. */
public final class IngestionTaskProcessor {
  private final IngestionService authority;
  private final String workspace;
  private final Duration deadline;
  private final Function<Duration, ProcessTextParser> parsers;

  public IngestionTaskProcessor(IngestionService authority, String workspace, Duration deadline) {
    this(authority, workspace, deadline, ProcessTextParser::new);
  }

  // Production and controlled real child processes are the two Adapters at this internal Seam.
  IngestionTaskProcessor(
      IngestionService authority,
      String workspace,
      Duration deadline,
      Function<Duration, ProcessTextParser> parsers) {
    this.authority = authority;
    this.workspace = workspace;
    this.deadline = deadline;
    this.parsers = parsers;
  }

  public Optional<IngestionClaim> claim() {
    return authority.claimIngestion(workspace);
  }

  public boolean isCurrent(IngestionClaim claim) {
    return authority.isIngestionClaimCurrent(claim);
  }

  public void process(IngestionClaim claim) {
    try {
      if (!authority.isIngestionClaimCurrent(claim)) {
        return;
      }
      try (var parser = parsers.apply(deadline)) {
        var parsed = parser.parse(claim.filename(), claim.mimeType(), claim.content());
        authority.completeIngestion(claim, parsed);
      }
    } catch (TextParser.Failure failure) {
      fail(
          claim,
          switch (failure.code()) {
            case "unsupported_document" -> "unsupported_document";
            case "parser_timeout" -> "parser_timeout";
            case "parser_interrupted", "parser_cancelled", "parser_closed" -> "worker_interrupted";
            case "parser_invalid_output" -> "parser_output_invalid";
            default -> "parser_failed";
          });
    } catch (RuntimeException failure) {
      failUnexpected(claim);
    }
  }

  public void failUnexpected(IngestionClaim claim) {
    fail(claim, "parser_failed");
  }

  private void fail(IngestionClaim claim, String code) {
    if (claim == null) {
      return;
    }
    try {
      authority.failIngestion(claim, code);
    } catch (ApplicationException unavailable) {
      // Persisted processing state is recovered explicitly before scheduling on the next startup.
    }
  }
}
