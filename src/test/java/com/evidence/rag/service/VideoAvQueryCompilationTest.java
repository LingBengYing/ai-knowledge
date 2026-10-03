package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.VideoAvTestFixture;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.QueryAttachment;
import com.evidence.rag.model.domain.VideoAvCompilation;
import com.evidence.rag.model.domain.VideoAvProfile;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.parser.VideoAvDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class VideoAvQueryCompilationTest {
  @Test
  void completeQueryHasStableEphemeralWindowIdentityAndRetainsEveryByteAndEpoch() {
    var calls = new AtomicInteger();
    var original = VideoAvTestFixture.original();
    var nativeResult = VideoAvTestFixture.compilation(original, true);
    try (var service = compiler(calls, nativeResult)) {
      var attachment = new QueryAttachment("参考+视频.mp4", "video/mp4", original.content());
      var result = service.compileQuery(attachment, () -> true);
      assertEquals(1, calls.get());
      assertEquals(nativeResult.epoch(), result.epoch());
      assertEquals(nativeResult.durationTick(), result.durationTick());
      assertEquals(nativeResult.windows().size(), result.windows().size());
      String revision =
          "query-"
              + ModelValues.sha256(
                  (attachment.sha256() + "\0" + service.revision())
                      .getBytes(StandardCharsets.UTF_8));
      for (int i = 0; i < result.windows().size(); i++) {
        var actual = result.windows().get(i);
        var expected = nativeResult.windows().get(i);
        assertEquals(VideoAvProfile.windowId(revision, i), actual.id());
        assertNotEquals(expected.id(), actual.id());
        assertEquals(expected.startTick(), actual.startTick());
        assertEquals(expected.endTick(), actual.endTick());
        if (expected.video() != null) {
          assertArrayEquals(expected.video().content(), actual.video().content());
          assertEquals(expected.video().frames(), actual.video().frames());
        }
        if (expected.audio() != null) {
          assertArrayEquals(expected.audio().wav(), actual.audio().wav());
        }
      }
      assertEquals(32001, result.windows().getLast().audio().endSample());
      var renamed =
          service.compileQuery(
              new QueryAttachment("other.mp4", "video/mp4", original.content()), () -> true);
      assertEquals(
          result.windows().stream().map(w -> w.id()).toList(),
          renamed.windows().stream().map(w -> w.id()).toList());
      var library = service.compile(original, () -> true);
      assertEquals(
          VideoAvProfile.windowId(original.revisionId(), 0), library.windows().getFirst().id());
      assertEquals(3, calls.get());
    }
  }

  @Test
  void absentAudioRemainsAbsentAndInvalidKindMagicOrScopeNeverDecode() {
    var calls = new AtomicInteger();
    try (var service =
        compiler(calls, VideoAvTestFixture.compilation(VideoAvTestFixture.original(), false))) {
      var input = new QueryAttachment("clip.mp4", "video/mp4", VideoAvTestFixture.raw());
      assertFalse(service.compileQuery(input, () -> true).hasAudio());
      assertEquals(1, calls.get());
      assertThrows(
          RuntimeException.class,
          () ->
              service.compileQuery(
                  new QueryAttachment("a.wav", "audio/wav", new byte[] {1}), () -> true));
      assertThrows(
          RuntimeException.class,
          () ->
              service.compileQuery(
                  new QueryAttachment("a.mp4", "video/mp4", new byte[] {1}), () -> true));
      assertEquals(
          "parser_cancelled",
          assertThrows(TextParser.Failure.class, () -> service.compileQuery(input, () -> false))
              .code());
      assertEquals(1, calls.get());
    }
  }

  @Test
  void httpAdmissionChecksContainerWithoutACompilerInstanceOrDecoder() {
    VideoAvCompilationService.validateQueryInput(
        new QueryAttachment("clip.mp4", "video/mp4", VideoAvTestFixture.raw()));
    assertThrows(
        TextParser.Failure.class,
        () ->
            VideoAvCompilationService.validateQueryInput(
                new QueryAttachment("clip.mp4", "video/mp4", new byte[16])));
    assertThrows(
        RuntimeException.class,
        () ->
            VideoAvCompilationService.validateQueryInput(
                new QueryAttachment("clip.wav", "audio/wav", VideoAvTestFixture.raw())));
  }

  @Test
  void changedNativeSourceOrDecoderAndCancellationRejectWholeQuery() {
    var original = VideoAvTestFixture.original();
    var value = VideoAvTestFixture.compilation(original, false);
    for (var bad :
        List.of(
            new VideoAvCompilation(
                "a".repeat(64),
                value.decoderRevision(),
                value.epoch(),
                value.durationTick(),
                false,
                value.windows()),
            new VideoAvCompilation(
                value.sourceSha256(),
                "other-decoder",
                value.epoch(),
                value.durationTick(),
                false,
                value.windows()))) {
      try (var service = compiler(new AtomicInteger(), bad)) {
        assertEquals(
            "parser_invalid_output",
            assertThrows(
                    TextParser.Failure.class,
                    () ->
                        service.compileQuery(
                            new QueryAttachment("a.mp4", "video/mp4", original.content()),
                            () -> true))
                .code());
      }
    }
  }

  private static VideoAvCompilationService compiler(AtomicInteger calls, VideoAvCompilation value) {
    return new VideoAvCompilationService(
        new VideoAvDecoder() {
          public String revision() {
            return "decoder-v1";
          }

          public VideoAvCompilation decode(String filename, String mediaType, byte[] source) {
            calls.incrementAndGet();
            assertArrayEquals(VideoAvTestFixture.raw(), source);
            return value;
          }

          public void close() {}
        },
        1,
        Duration.ofSeconds(2));
  }
}
