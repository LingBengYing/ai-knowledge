package com.evidence.rag.web.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.dto.VoiceQuestionResult;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class VoiceQuestionResponseMapperTest {
  @Test
  void exposesExactlyCompleteTranscriptAndBoundIdentitiesWithoutAnswerOrLibraryFields() {
    String transcript = " 首段\r\n\n尾段𠮷？ ";
    String digest = ModelValues.sha256(transcript.getBytes(StandardCharsets.UTF_8));
    var response =
        VoiceQuestionResponseMapper.response(
            new VoiceQuestionResult(
                transcript,
                digest,
                "a".repeat(64),
                "decoder-v1",
                "model-v1",
                "compiler-v1",
                10001,
                "java-voice-question-v1"));
    var value = JsonMapper.builder().build().valueToTree(response);
    assertEquals(
        Set.of(
            "transcript",
            "transcript_sha256",
            "source_sha256",
            "decoder_revision",
            "model_revision",
            "compiler_revision",
            "duration_ms",
            "policy_revision"),
        new HashSet<>(value.propertyNames()));
    assertEquals(transcript, value.path("transcript").asString());
    assertEquals(digest, value.path("transcript_sha256").asString());
    assertEquals("a".repeat(64), value.path("source_sha256").asString());
    assertEquals("decoder-v1", value.path("decoder_revision").asString());
    assertEquals("model-v1", value.path("model_revision").asString());
    assertEquals("compiler-v1", value.path("compiler_revision").asString());
    assertEquals(10001, value.path("duration_ms").asLong());
    assertEquals("java-voice-question-v1", value.path("policy_revision").asString());
    assertFalse(response.toString().contains("首段"));
    assertFalse(response.toString().contains("尾段"));
  }
}
