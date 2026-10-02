package com.evidence.rag.model.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.VideoTraceEvidence;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class VideoTraceCitationEntityTest {
  private static final String HASH = "a".repeat(64);
  private static final String GROUP = "video-group-" + HASH;
  private static final String FRAME = "video-frame-" + HASH;
  private static final String SPAN = "video-transcript-" + HASH;

  @Test
  void requiresPublicationGroupParentAndCompilationSeals() {
    var evidence = visual();
    assertThrows(
        ApplicationException.class,
        () ->
            new TraceVideoCitationEntity(
                null, "publication", GROUP, FRAME, null, HASH, HASH, HASH, null, null));
    assertThrows(
        ApplicationException.class,
        () ->
            new TraceVideoCitationEntity(
                evidence, "", GROUP, FRAME, null, HASH, HASH, HASH, null, null));
    for (String group : Arrays.asList(null, "scene-one")) {
      assertThrows(
          ApplicationException.class,
          () ->
              new TraceVideoCitationEntity(
                  evidence, "publication", group, FRAME, null, HASH, HASH, HASH, null, null));
    }
    for (String hash : Arrays.asList(null, "unsealed")) {
      assertThrows(
          ApplicationException.class,
          () ->
              new TraceVideoCitationEntity(
                  evidence, "publication", GROUP, FRAME, null, hash, HASH, HASH, null, null));
      assertThrows(
          ApplicationException.class,
          () ->
              new TraceVideoCitationEntity(
                  evidence, "publication", GROUP, FRAME, null, HASH, hash, HASH, null, null));
    }
  }

  @Test
  void visualSealRequiresOnlyTheRealFrameIdentityAndBytesHash() {
    var valid = entity(visual(), FRAME, null, HASH, null, null);
    assertEquals(FRAME, valid.frameId());
    assertEquals(HASH, valid.frameSha256());
    assertEquals("TraceVideoCitationEntity[redacted]", valid.toString());
    for (String frame : Arrays.asList(null, SPAN)) {
      assertThrows(
          ApplicationException.class, () -> entity(visual(), frame, null, HASH, null, null));
    }
    for (String hash : Arrays.asList(null, "frame-caption")) {
      assertThrows(
          ApplicationException.class, () -> entity(visual(), FRAME, null, hash, null, null));
    }
    assertThrows(ApplicationException.class, () -> entity(visual(), FRAME, SPAN, HASH, null, null));
    assertThrows(ApplicationException.class, () -> entity(visual(), FRAME, null, HASH, HASH, null));
  }

  @Test
  void transcriptSealRequiresSpanTextAndExactQuoteHashesWithoutFrameFields() {
    var transcript =
        new VideoTraceEvidence(
            1, VideoTraceEvidence.Kind.TRANSCRIPT, "physical-span", 4, 8, 0.8, 0.9, HASH);
    var valid = entity(transcript, null, SPAN, null, HASH, HASH);
    assertEquals(SPAN, valid.transcriptSpanId());
    assertEquals(HASH, valid.quoteSha256());
    for (String span : Arrays.asList(null, FRAME)) {
      assertThrows(
          ApplicationException.class, () -> entity(transcript, null, span, null, HASH, HASH));
    }
    for (String hash : Arrays.asList(null, "unsealed-text")) {
      assertThrows(
          ApplicationException.class, () -> entity(transcript, null, SPAN, null, hash, HASH));
      assertThrows(
          ApplicationException.class, () -> entity(transcript, null, SPAN, null, HASH, hash));
    }
    assertThrows(
        ApplicationException.class, () -> entity(transcript, FRAME, SPAN, null, HASH, HASH));
    assertThrows(
        ApplicationException.class, () -> entity(transcript, null, SPAN, HASH, HASH, HASH));
  }

  private static VideoTraceEvidence visual() {
    return new VideoTraceEvidence(
        1, VideoTraceEvidence.Kind.VISUAL, "physical-frame", null, null, 0.8, 0.9, HASH);
  }

  private static TraceVideoCitationEntity entity(
      VideoTraceEvidence evidence,
      String frame,
      String span,
      String frameHash,
      String spanHash,
      String quoteHash) {
    return new TraceVideoCitationEntity(
        evidence, "publication", GROUP, frame, span, HASH, HASH, frameHash, spanHash, quoteHash);
  }
}
