package com.evidence.rag.web.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.domain.QueryAttachmentManifest;
import com.evidence.rag.model.domain.SoundProfile;
import com.evidence.rag.model.domain.SoundPublication;
import com.evidence.rag.model.domain.SoundSpan;
import com.evidence.rag.model.domain.SoundState;
import com.evidence.rag.model.dto.SoundAnswerResult;
import com.evidence.rag.model.dto.SoundAttachmentAnswerResult;
import com.evidence.rag.model.dto.SoundQueryManifestResult;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SoundResponseMapperTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void missingIndexReportsPinnedSafeMetadataAndNoPhantomPublication() {
    byte[] content = new byte[] {1, 2};
    var original =
        new DocumentOriginal(
            "doc",
            "source",
            "bell.wav",
            "audio",
            "audio/wav",
            ModelValues.sha256(content),
            content.length,
            content);
    var target = new IndexTarget("embedding-v1", "a".repeat(64), "embedding-v1", 2);
    var value =
        SoundResponseMapper.index(
            new SoundState(original, target, "b".repeat(64), null), "sound-v1");
    var body = JSON.readTree(JSON.writeValueAsString(value));
    assertEquals(12, body.size());
    assertEquals("missing", body.path("status").asString());
    assertEquals("source", body.path("source_revision_id").asString());
    assertEquals("sound-v1", body.path("model_revision").asString());
    assertEquals("embedding-v1", body.path("embedding_model_revision").asString());
    assertTrue(body.path("publication_id").isNull());
    assertTrue(body.path("generation_id").isNull());
    assertTrue(body.path("manifest_sha256").isNull());
    assertEquals(0, body.path("span_count").intValue());
    assertEquals("SoundIndexResult[redacted]", value.toString());
  }

  @Test
  void availableIndexUsesTheSealedPublicationAndCompleteSpanCount() {
    byte[] content = new byte[] {1, 2};
    var original =
        new DocumentOriginal(
            "doc",
            "source",
            "bell.wav",
            "audio",
            "audio/wav",
            ModelValues.sha256(content),
            content.length,
            content);
    var target = new IndexTarget("embedding-v1", "a".repeat(64), "embedding-v1", 2);
    String generation = UUID.randomUUID().toString();
    String id = SoundProfile.spanId("source", 0);
    var span =
        new SoundSpan(
            id,
            0,
            0,
            1,
            "b".repeat(64),
            "",
            SoundProfile.physicalSegmentId(generation, id),
            "c".repeat(64));
    String profile = SoundProfile.fingerprint(target, "sound-v1", "decoder-v1", 15);
    String manifest = SoundProfile.manifestSha256("org", "doc", generation, List.of(span));
    var publication =
        new SoundPublication(
            "publication",
            "org",
            "doc",
            "source",
            original.sourceSha256(),
            original.filename(),
            original.mediaType(),
            original.sizeBytes(),
            generation,
            target,
            "sound-v1",
            "decoder-v1",
            15,
            1,
            List.of(span),
            manifest,
            profile,
            "2026-10-03T00:00:00Z");
    var result =
        SoundResponseMapper.index(
            new SoundState(original, target, profile, publication), "sound-v1");
    assertEquals("available", result.status());
    assertEquals("publication", result.publicationId());
    assertEquals(generation, result.generationId());
    assertEquals(manifest, result.manifestSha256());
    assertEquals(1, result.spanCount());
    assertEquals(12, JSON.readTree(JSON.writeValueAsString(result)).size());
  }

  @Test
  void soundManifestHasNineHashOnlyFieldsAndCannotMasqueradeAsSpeechPreparation() {
    var raw =
        new QueryAttachmentManifest(
            0,
            "a".repeat(64),
            QueryAttachment.Kind.AUDIO,
            "sound-compiler-v1",
            "b".repeat(64),
            0,
            0,
            List.of(),
            false);
    var value = SoundQueryManifestResult.from(raw);
    var body = JSON.readTree(JSON.writeValueAsString(value));
    assertEquals(
        Set.of(
            "ordinal",
            "source_sha256",
            "media_kind",
            "compiler_revision",
            "content_sha256",
            "text_code_points",
            "visual_count",
            "selected_image_sha256",
            "visual_sampled"),
        new HashSet<>(body.propertyNames()));
    assertEquals("audio", body.path("media_kind").asString());
    assertEquals(0, body.path("text_code_points").intValue());
    assertFalse(body.path("visual_sampled").booleanValue());
    var speech =
        new QueryAttachmentManifest(
            0,
            "a".repeat(64),
            QueryAttachment.Kind.AUDIO,
            "speech-v1",
            "b".repeat(64),
            12,
            0,
            List.of(),
            false);
    assertThrows(ApplicationException.class, () -> SoundQueryManifestResult.from(speech));
  }

  @Test
  void answerAndAttachmentEnvelopesKeepTheirExactIndependentContracts() {
    var plain =
        new SoundAnswerResult(
            "trace", "abstained", "", "no_evidence", List.of(), "java-sound-answer-v1");
    var attached =
        new SoundAttachmentAnswerResult(
            "trace",
            "abstained",
            "",
            "no_evidence",
            List.of(),
            "java-sound-answer-v1",
            "SOUND",
            List.of());
    var plainJson = JSON.readTree(JSON.writeValueAsString(plain));
    var attachedJson = JSON.readTree(JSON.writeValueAsString(attached));
    assertEquals(
        Set.of("answer_id", "status", "answer", "reason_code", "citations", "policy_revision"),
        new HashSet<>(plainJson.propertyNames()));
    assertEquals(8, attachedJson.size());
    assertEquals("SOUND", attachedJson.path("mode").asString());
    assertTrue(attachedJson.path("attachment_manifest").isEmpty());
    assertFalse(attachedJson.has("result"));
    assertFalse(attachedJson.has("query_attachments"));
  }
}
