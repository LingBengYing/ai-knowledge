package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryTraceDomainTest {
  private static final String SHA = "a".repeat(64);
  private static final String OTHER = "b".repeat(64);

  @Test
  void preparedOutcomeBindsTheOriginalQuestionCompleteManifestAndFrozenVersions() {
    var image = new VisualImage("image/png", new byte[] {1, 2});
    var manifest =
        new QueryAttachmentManifest(
            0,
            SHA,
            QueryAttachment.Kind.IMAGE,
            "image-v1",
            OTHER,
            20,
            1,
            List.of(image.sha256()),
            false);
    var prepared =
        new PreparedQuery(
            "  原问题😀\n尾部？", "检索材料完整尾部", List.of(image), List.of(manifest), "prepare-v1");
    var trace = QueryTrace.prepared(prepared, "rank-v1");
    assertEquals(
        ModelValues.sha256(prepared.originalQuestion().getBytes(StandardCharsets.UTF_8)),
        trace.questionSha256());
    assertEquals("prepared", trace.status());
    assertEquals("prepare-v1", trace.preparationRevision());
    assertEquals("rank-v1", trace.rankingRevision());
    assertEquals(prepared.manifestSha256(), trace.manifestSha256());
    assertNull(trace.reasonCode());
    assertEquals(manifest, trace.attachments().getFirst().manifest());
    assertEquals("QueryTrace[redacted]", trace.toString());
    assertEquals("QueryTraceAttachment[redacted]", trace.attachments().getFirst().toString());
    assertThrows(
        ApplicationException.class, () -> QueryTrace.prepared(PreparedQuery.text("问题"), null));
    assertThrows(ApplicationException.class, () -> QueryTrace.prepared(null, null));
  }

  @Test
  void failedCompilationRetainsEveryRequestSourceWithoutInventingCompiledContent() {
    var first = new QueryAttachment("private.png", "image/png", new byte[] {1});
    var last = new QueryAttachment("private.mp4", "video/mp4", new byte[] {2});
    var trace =
        QueryTrace.failed(SHA, List.of(first, last), "prepare-v1", null, "query_attachment_failed");
    assertEquals("failed", trace.status());
    assertEquals("query_attachment_failed", trace.reasonCode());
    assertNull(trace.manifestSha256());
    assertNull(trace.rankingRevision());
    assertEquals(
        List.of(first.sha256(), last.sha256()),
        trace.attachments().stream().map(QueryTraceAttachment::sourceSha256).toList());
    assertEquals(
        List.of(0, 1), trace.attachments().stream().map(QueryTraceAttachment::ordinal).toList());
    assertTrue(trace.attachments().stream().allMatch(item -> item.manifest() == null));
    assertEquals(QueryAttachment.Kind.VIDEO, trace.attachments().getLast().mediaKind());
    assertThrows(
        ApplicationException.class,
        () -> QueryTrace.failed("raw question", List.of(first), "p", null, "failed"));
    assertThrows(
        ApplicationException.class, () -> QueryTrace.failed(SHA, List.of(), "p", null, "failed"));
    assertThrows(
        ApplicationException.class,
        () -> QueryTrace.failed(SHA, List.of(first), "p", null, "raw error\nsecret"));
    assertThrows(
        ApplicationException.class, () -> QueryTrace.failed(SHA, null, "p", null, "failed"));
    assertThrows(
        ApplicationException.class,
        () -> QueryTrace.failed(SHA, List.of(first, first, first, first), "p", null, "failed"));
    assertThrows(
        ApplicationException.class,
        () -> QueryTrace.failed(SHA, Arrays.asList((QueryAttachment) null), "p", null, "failed"));
  }

  @Test
  void attachmentIdentityMustMatchItsManifestAndListsAreImmutable() {
    var manifest =
        new QueryAttachmentManifest(
            0, SHA, QueryAttachment.Kind.AUDIO, "audio-v1", OTHER, 20, 0, List.of(), false);
    var item = new QueryTraceAttachment(0, SHA, QueryAttachment.Kind.AUDIO, manifest);
    var attachments = new ArrayList<>(List.of(item));
    var trace = new QueryTrace(SHA, "prepare-v1", "rank-v1", "prepared", null, OTHER, attachments);
    attachments.clear();
    assertEquals(1, trace.attachments().size());
    assertThrows(UnsupportedOperationException.class, () -> trace.attachments().clear());
    assertThrows(
        ApplicationException.class,
        () -> new QueryTraceAttachment(-1, SHA, QueryAttachment.Kind.AUDIO, null));
    assertThrows(
        ApplicationException.class,
        () -> new QueryTraceAttachment(3, SHA, QueryAttachment.Kind.AUDIO, null));
    assertThrows(
        ApplicationException.class,
        () -> new QueryTraceAttachment(0, "source", QueryAttachment.Kind.AUDIO, null));
    assertThrows(ApplicationException.class, () -> new QueryTraceAttachment(0, SHA, null, null));
    assertThrows(
        ApplicationException.class,
        () -> new QueryTraceAttachment(1, SHA, QueryAttachment.Kind.AUDIO, manifest));
    assertThrows(
        ApplicationException.class,
        () -> new QueryTraceAttachment(0, OTHER, QueryAttachment.Kind.AUDIO, manifest));
    assertThrows(
        ApplicationException.class,
        () -> new QueryTraceAttachment(0, SHA, QueryAttachment.Kind.VIDEO, manifest));
  }

  @Test
  void preparationStatusCannotHideMissingManifestsOrUnboundOrdinalSources() {
    var incomplete = new QueryTraceAttachment(0, SHA, QueryAttachment.Kind.AUDIO, null);
    for (String question : Arrays.asList(null, "question")) {
      assertThrows(
          ApplicationException.class,
          () -> new QueryTrace(question, "p", null, "failed", "failed", null, List.of(incomplete)));
    }
    assertThrows(
        ApplicationException.class,
        () -> new QueryTrace(SHA, null, null, "failed", "failed", null, List.of(incomplete)));
    assertThrows(
        ApplicationException.class,
        () ->
            new QueryTrace(
                SHA, "p", "bad\nversion", "failed", "failed", null, List.of(incomplete)));
    assertThrows(
        ApplicationException.class,
        () -> new QueryTrace(SHA, "p", null, "unknown", "failed", null, List.of(incomplete)));
    assertThrows(
        ApplicationException.class,
        () -> new QueryTrace(SHA, "p", null, "prepared", null, OTHER, List.of(incomplete)));
    assertThrows(
        ApplicationException.class,
        () -> new QueryTrace(SHA, "p", null, "failed", "failed", OTHER, List.of(incomplete)));
    assertThrows(
        ApplicationException.class,
        () -> new QueryTrace(SHA, "p", null, "failed", null, null, List.of(incomplete)));
    assertThrows(
        ApplicationException.class,
        () -> new QueryTrace(SHA, "p", null, "failed", "failed", null, null));
    assertThrows(
        ApplicationException.class,
        () ->
            new QueryTrace(
                SHA, "p", null, "failed", "failed", null, List.of(incomplete, incomplete)));
    assertThrows(
        ApplicationException.class,
        () ->
            new QueryTrace(
                SHA,
                "p",
                null,
                "failed",
                "failed",
                null,
                Arrays.asList((QueryTraceAttachment) null)));
    var manifest =
        new QueryAttachmentManifest(
            0, SHA, QueryAttachment.Kind.AUDIO, "audio-v1", OTHER, 1, 0, List.of(), false);
    assertThrows(
        ApplicationException.class,
        () ->
            new QueryTrace(
                SHA,
                "p",
                null,
                "failed",
                "failed",
                null,
                List.of(new QueryTraceAttachment(0, SHA, QueryAttachment.Kind.AUDIO, manifest))));
    var prepared = List.of(new QueryTraceAttachment(0, SHA, QueryAttachment.Kind.AUDIO, manifest));
    assertThrows(
        ApplicationException.class,
        () -> new QueryTrace(SHA, "p", null, "prepared", "failed", OTHER, prepared));
    assertThrows(
        ApplicationException.class,
        () -> new QueryTrace(SHA, "p", null, "prepared", null, null, prepared));
    assertThrows(
        ApplicationException.class,
        () -> new QueryTrace(SHA, "p", null, "prepared", null, "text", prepared));
    var visuals = new ArrayList<QueryTraceAttachment>();
    for (int ordinal = 0; ordinal < 2; ordinal++) {
      var images =
          List.of(
              Integer.toString(ordinal * 2).repeat(64),
              Integer.toString(ordinal * 2 + 1).repeat(64));
      var visual =
          new QueryAttachmentManifest(
              ordinal, SHA, QueryAttachment.Kind.VIDEO, "video-v1", OTHER, 1, 2, images, false);
      visuals.add(new QueryTraceAttachment(ordinal, SHA, QueryAttachment.Kind.VIDEO, visual));
    }
    assertThrows(
        ApplicationException.class,
        () -> new QueryTrace(SHA, "p", null, "prepared", null, OTHER, visuals));
  }

  @Test
  void traceDraftKeepsLegacyAbsenceButBindsAttachmentQuestionAndRejectsFailedAnswer() {
    var legacy =
        new TraceDraft(SHA, null, "abstained", "insufficient_evidence", "m", "p", "g", List.of());
    assertNull(legacy.queryTrace());
    var failed =
        new QueryTrace(
            SHA,
            "prepare-v1",
            null,
            "failed",
            "query_attachment_failed",
            null,
            List.of(new QueryTraceAttachment(0, SHA, QueryAttachment.Kind.AUDIO, null)));
    assertEquals(failed, legacy.withQueryTrace(failed).queryTrace());
    assertEquals(legacy, legacy.withQueryTrace(null));
    var wrongQuestion =
        new QueryTrace(
            OTHER,
            "prepare-v1",
            null,
            "failed",
            "query_attachment_failed",
            null,
            failed.attachments());
    assertThrows(ApplicationException.class, () -> legacy.withQueryTrace(wrongQuestion));
    var answered =
        new TraceDraft(
            SHA,
            OTHER,
            "answered",
            null,
            "m",
            "p",
            "g",
            List.of(new TraceEvidence(1, "physical", 0, 1, 1, 1, List.of(SHA))));
    assertThrows(ApplicationException.class, () -> answered.withQueryTrace(failed));
  }
}
