package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.DocumentSelection;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.support.AnswerTestContext;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Cancellation raised during computation uses the same safe durable rejection as interruption. */
class AnswerCancellationTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void cancellationCannotSkipTheTraceOrReleaseAnAnswer(boolean interrupted) {
    try (var fixture = new AnswerTestContext(directory, Duration.ofSeconds(10), 1)) {
      String document = fixture.publish("cancel.txt", "星港计划的预算为1200万元。");
      fixture.models.onExtract =
          () -> {
            if (interrupted) {
              Thread.currentThread().interrupt();
            }
            throw new CancellationException("synthetic-private-cancellation");
          };
      var result =
          fixture.answers.answer(
              fixture.owner,
              new AnswerCommand("星港计划的预算是多少？", DocumentSelection.selected(List.of(document))));
      assertEquals("abstained", result.status());
      assertEquals(interrupted ? "processing_timeout" : "upstream_unavailable", result.reason());
      assertTrue(result.citations().isEmpty());
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM query_traces"));
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM query_trace_evidence"));
      assertEquals(
          0, fixture.scalar("SELECT COUNT(*) FROM query_traces WHERE answer_sha256 IS NOT NULL"));
      assertEquals(List.of("embed", "rerank", "extract"), fixture.models.calls);
    }
  }
}
