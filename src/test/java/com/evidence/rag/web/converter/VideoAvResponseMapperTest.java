package com.evidence.rag.web.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.model.domain.DocumentOriginal;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoAvState;
import com.evidence.rag.model.dto.VideoAvAnswerResult;
import com.evidence.rag.model.dto.VideoAvAudioResult;
import com.evidence.rag.model.dto.VideoAvCitationResult;
import com.evidence.rag.model.dto.VideoAvEpochResult;
import com.evidence.rag.model.dto.VideoAvFactResult;
import com.evidence.rag.model.dto.VideoAvSourceResult;
import com.evidence.rag.model.dto.VideoAvVideoResult;
import com.evidence.rag.model.dto.VideoAvWindowResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class VideoAvResponseMapperTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void uploadAndMissingIndexExposeOnlyFrozenMetadata() {
    byte[] bytes = new byte[] {1, 2, 3};
    var original =
        new DocumentOriginal(
            "doc",
            "revision",
            "片段.mp4",
            "video",
            "video/mp4",
            ModelValues.sha256(bytes),
            bytes.length,
            bytes);
    var visual = new IndexTarget("embedding-v1", "visual-v1", "embedding-v1", 3);
    var audio = new IndexTarget("embedding-v1", "audio-v1", "embedding-v1", 3);
    var upload = JSON.valueToTree(VideoAvResponseMapper.upload(original));
    assertEquals(
        Set.of("document_id", "source_revision_id", "source_sha256", "size_bytes"),
        new HashSet<>(upload.propertyNames()));
    var result =
        VideoAvResponseMapper.index(
            new VideoAvState(original, visual, audio, null), "profile-v1", "analysis-v1");
    assertEquals("missing", result.status());
    assertNull(result.publicationId());
    assertNull(result.generationId());
    assertNull(result.manifestSha256());
    assertEquals(0, result.windowCount());
    assertEquals(0, result.videoWindowCount());
    assertEquals(0, result.audioWindowCount());
    assertEquals("analysis-v1", result.modelRevision());
    assertEquals("embedding-v1", result.embeddingModelRevision());
    var tree = JSON.valueToTree(result);
    assertEquals(14, tree.size());
    assertFalse(tree.has("filename"));
    assertEquals("VideoAvIndexResult[redacted]", result.toString());
  }

  @Test
  void exactNestedSourceKeepsLongPrecisionAndMediaAbsenceWithoutLeakingBytes() {
    var facts =
        new ArrayList<>(
            List.of(new VideoAvFactResult("id", "A bell is visible.", "VISUAL", true, false)));
    var epoch = new VideoAvEpochResult("9007199254740993", "1", "30000", "240000");
    var video = new VideoAvVideoResult("clip", 2, "frames", "3", "8000");
    var window = new VideoAvWindowResult("window", 0, "0", "8000", 0, 34, video, null);
    var citation =
        new VideoAvCitationResult(
            1,
            "video_av_window",
            "VISUAL",
            "doc",
            "rev",
            "source",
            "pub",
            "profile",
            "decoder",
            "片段.mp4",
            "video/mp4",
            epoch,
            window,
            facts,
            "facts",
            "analysis",
            "policy",
            "server_window",
            "/v1/video-av-sources/trace/1",
            "/v1/video-av-sources/trace/1/content");
    facts.clear();
    assertEquals(1, citation.facts().size());
    assertThrows(UnsupportedOperationException.class, () -> citation.facts().clear());
    var source = JSON.valueToTree(new VideoAvSourceResult("trace", citation));
    assertEquals(2, source.size());
    var tree = source.path("citation");
    assertEquals(20, tree.size());
    assertEquals("9007199254740993", tree.path("epoch").path("pts").asString());
    assertEquals(4, tree.path("epoch").size());
    assertEquals(8, tree.path("window").size());
    assertEquals(5, tree.path("window").path("video").size());
    assertEquals(true, tree.path("window").path("audio").isNull());
    assertEquals(5, tree.path("facts").get(0).size());
    assertEquals("VideoAvCitationResult[redacted]", citation.toString());
    var audio =
        JSON.valueToTree(
            new VideoAvAudioResult("pcm", "wav", "9007199254740993", "9007199254741000", 16000));
    assertEquals(5, audio.size());
    assertEquals("9007199254740993", audio.path("start_sample").asString());
  }

  @Test
  void answerCopiesCitationsAndRetainsSevenFieldEnvelope() {
    var citations = new ArrayList<VideoAvCitationResult>();
    var answer =
        new VideoAvAnswerResult(
            "trace", "abstained", "JOINT", "", "no_evidence", citations, "java-video-av-answer-v1");
    assertThrows(UnsupportedOperationException.class, () -> answer.citations().clear());
    assertEquals(7, JSON.valueToTree(answer).size());
    assertEquals("VideoAvAnswerResult[redacted]", answer.toString());
  }
}
