package com.evidence.rag.worker.indexing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.VideoAvTestFixture;
import com.evidence.rag.model.domain.VerifiedRevision;
import com.evidence.rag.model.domain.VideoAvReceipt;
import com.evidence.rag.model.domain.VideoAvRoute;
import com.evidence.rag.model.domain.VideoAvRouteReceipt;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class VideoAvIndexProtocolTest {
  static VideoAvIndexProtocol.Request request() {
    return request(true);
  }

  static VideoAvIndexProtocol.Request request(boolean audio) {
    return VideoAvIndexProtocol.request(
        VideoAvTestFixture.models(),
        VideoAvTestFixture.projection(VideoAvRoute.VISUAL),
        VideoAvTestFixture.projection(VideoAvRoute.AUDIO),
        VideoAvTestFixture.BUDGET,
        VideoAvTestFixture.claim(audio));
  }

  static VideoAvReceipt receipt(VideoAvIndexProtocol.Request request) {
    return VideoAvTestFixture.receipt(request.claim());
  }

  @Test
  void completeSourceEpochPixelsSilentWindowAndOneSampleTailRoundTrip() throws Exception {
    var request = request();
    var bytes = packet(request);
    var decoded = VideoAvIndexProtocol.readRequest(new ByteArrayInputStream(bytes));
    assertArrayEquals(request.claim().original().content(), decoded.claim().original().content());
    assertEquals(request.parent(), decoded.parent());
    assertEquals(request.claim().compilation().epoch(), decoded.claim().compilation().epoch());
    assertEquals(720000, decoded.claim().compilation().epoch().ticksPerSecond());
    assertEquals(3, decoded.claim().compilation().windows().size());
    assertEquals(
        request.claim().compilation().windows().getFirst().video().frames(),
        decoded.claim().compilation().windows().getFirst().video().frames());
    assertArrayEquals(
        new byte[32000], decoded.claim().compilation().windows().get(1).audio().pcm());
    assertArrayEquals(
        new byte[] {9, 0}, decoded.claim().compilation().windows().getLast().audio().pcm());
  }

  @Test
  void float32CompleteDualReceiptAndVisualOnlyAbsenceRoundTrip() throws Exception {
    for (boolean audio : List.of(true, false)) {
      var request = request(audio);
      var good = receipt(request);
      assertEquals(good, VideoAvIndexProtocol.decode(VideoAvIndexProtocol.encode(good), request));
      if (!audio) {
        assertEquals(0, good.audioReceipt().count());
        assertEquals(null, good.audioReceipt().verified());
        assertNotEquals(
            good.visualReceipt().manifestSha256(), good.audioReceipt().manifestSha256());
      }
    }
  }

  @Test
  void generationReplayAndSwappedRolePhysicalIdentityRejectEntireGroup() throws Exception {
    var request = request();
    var good = receipt(request);
    assertThrows(
        ProcessVideoAvIndexer.Failure.class,
        () -> VideoAvIndexProtocol.decode(VideoAvIndexProtocol.encode(good), request()));
    var entries = new ArrayList<>(good.entries());
    var first = entries.getFirst();
    entries.set(
        0,
        new VideoAvReceipt.Entry(
            first.windowId(),
            first.route(),
            "seg-" + "c".repeat(64),
            first.vector(),
            first.entrySha256()));
    assertThrows(
        ProcessVideoAvIndexer.Failure.class,
        () ->
            VideoAvIndexProtocol.verify(
                request.claim(),
                new VideoAvReceipt(entries, good.visualReceipt(), good.audioReceipt())));
    Collections.reverse(entries);
    assertThrows(
        ProcessVideoAvIndexer.Failure.class,
        () ->
            VideoAvIndexProtocol.verify(
                request.claim(),
                new VideoAvReceipt(entries, good.visualReceipt(), good.audioReceipt())));
  }

  @Test
  void tailVectorDigestDimensionAndUnknownWindowAreIndependentBindings() {
    var request = request();
    var good = receipt(request);
    var tail = good.entries().getLast();
    for (var changed :
        List.of(
            new VideoAvReceipt.Entry(
                tail.windowId(),
                tail.route(),
                tail.physicalSegmentId(),
                List.of(0.5, 0.5),
                tail.entrySha256()),
            new VideoAvReceipt.Entry(
                tail.windowId(),
                tail.route(),
                tail.physicalSegmentId(),
                List.of(0.25, 0.75, 0.5),
                tail.entrySha256()),
            new VideoAvReceipt.Entry(
                "unknown-window",
                tail.route(),
                tail.physicalSegmentId(),
                tail.vector(),
                tail.entrySha256()))) {
      var entries = new ArrayList<>(good.entries());
      entries.set(entries.size() - 1, changed);
      assertThrows(
          ProcessVideoAvIndexer.Failure.class,
          () ->
              VideoAvIndexProtocol.verify(
                  request.claim(),
                  new VideoAvReceipt(entries, good.visualReceipt(), good.audioReceipt())));
    }
  }

  @Test
  void wrongProjectionManifestAndAbsenceIdentityCannotSeal() {
    var request = request();
    var good = receipt(request);
    var role = good.visualReceipt();
    for (var changed :
        List.of(
            new VideoAvRouteReceipt(
                VideoAvRoute.VISUAL,
                role.count(),
                role.manifestSha256(),
                new VerifiedRevision("b".repeat(64), role.manifestSha256(), role.count())),
            new VideoAvRouteReceipt(
                VideoAvRoute.VISUAL,
                role.count(),
                "c".repeat(64),
                new VerifiedRevision(
                    request.claim().targets().visual().projectionIdentity(),
                    "c".repeat(64),
                    role.count())))) {
      assertThrows(
          ProcessVideoAvIndexer.Failure.class,
          () ->
              VideoAvIndexProtocol.verify(
                  request.claim(),
                  new VideoAvReceipt(good.entries(), changed, good.audioReceipt())));
    }
    var visualOnly = request(false);
    var noAudio = receipt(visualOnly);
    assertThrows(
        ProcessVideoAvIndexer.Failure.class,
        () ->
            VideoAvIndexProtocol.verify(
                visualOnly.claim(),
                new VideoAvReceipt(
                    noAudio.entries(),
                    noAudio.visualReceipt(),
                    new VideoAvRouteReceipt(VideoAvRoute.AUDIO, 0, "d".repeat(64), null))));
  }

  @Test
  void duplicateOrOmittedMediaEntriesAndNonFiniteVectorsReject() {
    var good = receipt(request());
    var first = good.entries().getFirst();
    var entries = new ArrayList<>(good.entries());
    entries.set(entries.size() - 1, first);
    assertThrows(
        RuntimeException.class,
        () -> new VideoAvReceipt(entries, good.visualReceipt(), good.audioReceipt()));
    assertThrows(
        RuntimeException.class,
        () ->
            new VideoAvReceipt(
                good.entries().subList(0, good.entries().size() - 1),
                good.visualReceipt(),
                good.audioReceipt()));
    assertThrows(
        RuntimeException.class,
        () ->
            new VideoAvReceipt.Entry(
                first.windowId(),
                first.route(),
                first.physicalSegmentId(),
                List.of(Double.NaN, 0.5),
                first.entrySha256()));
  }

  @Test
  void truncatedRequestWrongHeaderTrailingBytesAndMalformedUtf8Reject() throws Exception {
    var bytes = packet(request());
    assertThrows(
        Exception.class,
        () ->
            VideoAvIndexProtocol.readRequest(
                new ByteArrayInputStream(Arrays.copyOf(bytes, bytes.length - 1))));
    var bad = bytes.clone();
    bad[0] ^= 1;
    assertThrows(
        Exception.class, () -> VideoAvIndexProtocol.readRequest(new ByteArrayInputStream(bad)));
    var trailing = Arrays.copyOf(bytes, bytes.length + 1);
    assertThrows(
        Exception.class,
        () -> VideoAvIndexProtocol.readRequest(new ByteArrayInputStream(trailing)));
    var utf = bytes.clone();
    int filename = index(utf, "clip.mp4".getBytes(StandardCharsets.UTF_8));
    utf[filename] = (byte) 0xc3;
    assertThrows(
        Exception.class, () -> VideoAvIndexProtocol.readRequest(new ByteArrayInputStream(utf)));
  }

  @Test
  void untrustedFrameCountAndOriginalBlobLengthAreBoundedBeforeAllocation() throws Exception {
    var request = request();
    var bytes = packet(request);
    var frames = bytes.clone();
    byte[] hash =
        request
            .claim()
            .compilation()
            .windows()
            .getFirst()
            .video()
            .framesManifestSha256()
            .getBytes(StandardCharsets.UTF_8);
    int frameCount = index(frames, hash) + hash.length;
    ByteBuffer.wrap(frames).putInt(frameCount, 1000001);
    assertThrows(
        Exception.class, () -> VideoAvIndexProtocol.readRequest(new ByteArrayInputStream(frames)));
    var source = bytes.clone();
    int sha =
        index(source, request.claim().original().sourceSha256().getBytes(StandardCharsets.UTF_8));
    ByteBuffer.wrap(source).putInt(sha + 64, 20 * 1024 * 1024 + 1);
    assertThrows(
        Exception.class, () -> VideoAvIndexProtocol.readRequest(new ByteArrayInputStream(source)));
  }

  @Test
  void workerFailureTimeoutUnknownStatusAndTrailingSuccessStayFailClosed() throws Exception {
    var request = request();
    assertEquals(
        "video_av_index_failed",
        assertThrows(
                ProcessVideoAvIndexer.Failure.class,
                () -> VideoAvIndexProtocol.decode(VideoAvIndexProtocol.failure(1), request))
            .code());
    assertEquals(
        "video_av_index_timeout",
        assertThrows(
                ProcessVideoAvIndexer.Failure.class,
                () -> VideoAvIndexProtocol.decode(VideoAvIndexProtocol.failure(2), request))
            .code());
    assertThrows(
        Exception.class,
        () -> VideoAvIndexProtocol.decode(VideoAvIndexProtocol.failure(3), request));
    var complete = VideoAvIndexProtocol.encode(receipt(request));
    assertThrows(
        Exception.class,
        () -> VideoAvIndexProtocol.decode(Arrays.copyOf(complete, complete.length + 1), request));
  }

  private static byte[] packet(VideoAvIndexProtocol.Request request) throws Exception {
    var output = new ByteArrayOutputStream();
    VideoAvIndexProtocol.writeRequest(output, request);
    return output.toByteArray();
  }

  private static int index(byte[] bytes, byte[] target) {
    outer:
    for (int i = 0; i <= bytes.length - target.length; i++) {
      for (int j = 0; j < target.length; j++) {
        if (bytes[i + j] != target[j]) {
          continue outer;
        }
      }
      return i;
    }
    throw new AssertionError("Fixture token absent");
  }
}
