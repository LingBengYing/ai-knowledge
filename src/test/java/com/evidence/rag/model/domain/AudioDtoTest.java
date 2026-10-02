package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.model.dto.AudioAnswerResult;
import com.evidence.rag.model.dto.AudioCitationResult;
import com.evidence.rag.model.dto.AudioSourceResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AudioDtoTest {
  @Test
  void audioCitationUsesOnlyTypedServerTimeAndSnakeCaseSourceFields() {
    var citation = citation();
    var answer = new AudioAnswerResult("trace", "answered", "合成答案", null, List.of(citation));
    var json = JsonMapper.builder().build().valueToTree(answer);
    assertEquals(
        Set.of("answer_id", "status", "answer", "reason", "citations"),
        new HashSet<>(json.propertyNames()));
    var item = json.path("citations").get(0);
    assertEquals(
        Set.of(
            "number",
            "kind",
            "document_id",
            "revision_id",
            "source_sha256",
            "parser_revision",
            "filename",
            "media_type",
            "start_ms",
            "end_ms",
            "quote",
            "quote_sha256",
            "text_origin",
            "time_precision",
            "source_url",
            "content_url"),
        new HashSet<>(item.propertyNames()));
    assertEquals("audio_span", item.path("kind").asString());
    assertEquals(15000, item.path("start_ms").asLong());
    assertEquals(30000, item.path("end_ms").asLong());
    assertEquals("machine_asr", item.path("text_origin").asString());
    assertEquals("server_chunk", item.path("time_precision").asString());
    for (String absent :
        List.of("page", "start", "end", "confidence", "projection_generation_id")) {
      assertFalse(item.has(absent));
    }
    var source = JsonMapper.builder().build().valueToTree(new AudioSourceResult("trace", citation));
    assertEquals(Set.of("answer_id", "citation"), new HashSet<>(source.propertyNames()));
    assertEquals(item, source.path("citation"));
  }

  @Test
  void audioResultsDefensivelyCopyCitationsAndDoNotLogTextOrPaths() {
    var citations = new ArrayList<>(List.of(citation()));
    var answer = new AudioAnswerResult("trace", "answered", "合成答案", null, citations);
    citations.clear();
    assertEquals(1, answer.citations().size());
    assertThrows(UnsupportedOperationException.class, () -> answer.citations().clear());
    assertNull(answer.reason());
    assertEquals("AudioAnswerResult[redacted]", answer.toString());
    assertEquals("AudioCitationResult[redacted]", citation().toString());
    assertEquals(
        "AudioSourceResult[redacted]", new AudioSourceResult("trace", citation()).toString());
  }

  private static AudioCitationResult citation() {
    return new AudioCitationResult(
        1,
        "audio_span",
        "document",
        "revision",
        "a".repeat(64),
        "java-audio-compiler-v1:" + "b".repeat(64),
        "合成会议.wav",
        "audio/wav",
        15000,
        30000,
        "预算为42万元。",
        "c".repeat(64),
        "machine_asr",
        "server_chunk",
        "/v1/audio-sources/trace/1",
        "/v1/audio-sources/trace/1/content");
  }
}
