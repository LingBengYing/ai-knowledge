package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.VideoAvTestFixture;
import com.evidence.rag.model.domain.VideoAvCompilation;
import com.evidence.rag.model.domain.VideoAvProfile;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.worker.parser.VideoAvDecoder;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class VideoAvCompilationServiceTest {
  @Test
  void decodesCompleteOriginalOnceAndBindsOriginalRevisionWithoutChangingEpochOrTail() {
    var calls = new AtomicInteger();
    var original = VideoAvTestFixture.original();
    var source = VideoAvTestFixture.compilation(original, true);
    try (var service =
        new VideoAvCompilationService(
            decoder(calls, "decoder-v1", source), 1, Duration.ofSeconds(2))) {
      var decoded = service.compile(original, () -> true);
      assertEquals(1, calls.get());
      assertEquals(source.epoch(), decoded.epoch());
      assertEquals(3, decoded.windows().size());
      assertEquals(
          VideoAvProfile.windowId(original.revisionId(), 2), decoded.windows().getLast().id());
      assertEquals(32001, decoded.windows().getLast().audio().endSample());
      assertArrayEquals(new byte[32000], decoded.windows().get(1).audio().pcm());
      assertArrayEquals(new byte[] {9, 0}, decoded.windows().getLast().audio().pcm());
    }
  }

  @Test
  void visualOnlyKeepsAudioAbsentInsteadOfGeneratingSilence() {
    var original = VideoAvTestFixture.original();
    try (var service =
        new VideoAvCompilationService(
            decoder(
                new AtomicInteger(), "decoder-v1", VideoAvTestFixture.compilation(original, false)),
            1,
            Duration.ofSeconds(1))) {
      var result = service.compile(original, () -> true);
      assertFalse(result.hasAudio());
      assertEquals(2, result.windows().size());
      assertEquals(null, result.windows().getLast().audio());
    }
  }

  @Test
  void revokedAuthorityAndCallerInterruptionPreventDecoderAdmission() {
    var calls = new AtomicInteger();
    try (var service =
        new VideoAvCompilationService(
            decoder(
                calls,
                "decoder-v1",
                VideoAvTestFixture.compilation(VideoAvTestFixture.original(), true)),
            1,
            Duration.ofSeconds(1))) {
      assertEquals(
          "parser_cancelled",
          assertThrows(
                  TextParser.Failure.class,
                  () -> service.compile(VideoAvTestFixture.original(), () -> false))
              .code());
      Thread.currentThread().interrupt();
      try {
        assertEquals(
            "parser_interrupted",
            assertThrows(
                    TextParser.Failure.class,
                    () -> service.compile(VideoAvTestFixture.original(), () -> true))
                .code());
      } finally {
        Thread.interrupted();
      }
      assertEquals(0, calls.get());
    }
  }

  @Test
  void changedSourceAndConfiguredWindowBoundRejectWholeDecodedGroup() {
    var original = VideoAvTestFixture.original();
    var source = VideoAvTestFixture.compilation(original, true);
    var corrupt =
        new VideoAvCompilation(
            "b".repeat(64),
            source.decoderRevision(),
            source.epoch(),
            VideoAvTestFixture.compilation(original, false).durationTick(),
            false,
            VideoAvTestFixture.compilation(original, false).windows());
    try (var service =
        new VideoAvCompilationService(
            decoder(new AtomicInteger(), "decoder-v1", corrupt), 1, Duration.ofSeconds(1))) {
      assertEquals(
          "parser_invalid_output",
          assertThrows(TextParser.Failure.class, () -> service.compile(original, () -> true))
              .code());
    }
  }

  @Test
  void profileChangeAndCloseInvalidateFutureWorkWithoutDecoderCalls() {
    var calls = new AtomicInteger();
    var revision = new AtomicReference<>("decoder-v1");
    var nativeDecoder =
        new VideoAvDecoder() {
          public String revision() {
            return revision.get();
          }

          public VideoAvCompilation decode(String f, String m, byte[] b) {
            calls.incrementAndGet();
            return VideoAvTestFixture.compilation(VideoAvTestFixture.original(), false);
          }

          public void close() {}
        };
    var service = new VideoAvCompilationService(nativeDecoder, 1, Duration.ofSeconds(1));
    revision.set("decoder-v2");
    assertFalse(service.configurationCurrent());
    assertEquals(
        "video_av_profile_changed",
        assertThrows(
                TextParser.Failure.class,
                () -> service.compile(VideoAvTestFixture.original(), () -> true))
            .code());
    service.close();
    assertFalse(service.configurationCurrent());
    assertEquals(0, calls.get());
  }

  @Test
  void timeoutInterruptsAndJoinsNativeWorkBeforeReturningFailure() {
    var stopped = new AtomicInteger();
    var nativeDecoder =
        new VideoAvDecoder() {
          public String revision() {
            return "decoder-v1";
          }

          public VideoAvCompilation decode(String f, String m, byte[] b) {
            try {
              Thread.sleep(10000);
            } catch (InterruptedException interrupted) {
              stopped.incrementAndGet();
              Thread.currentThread().interrupt();
            }
            throw new TextParser.Failure("parser_interrupted");
          }

          public void close() {}
        };
    try (var service = new VideoAvCompilationService(nativeDecoder, 1, Duration.ofMillis(50))) {
      assertEquals(
          "parser_timeout",
          assertThrows(
                  TextParser.Failure.class,
                  () -> service.compile(VideoAvTestFixture.original(), () -> true))
              .code());
      assertEquals(1, stopped.get());
    }
  }

  private static VideoAvDecoder decoder(
      AtomicInteger calls, String revision, VideoAvCompilation compilation) {
    return new VideoAvDecoder() {
      public String revision() {
        return revision;
      }

      public VideoAvCompilation decode(String filename, String mime, byte[] source) {
        calls.incrementAndGet();
        return compilation;
      }

      public void close() {}
    };
  }
}
