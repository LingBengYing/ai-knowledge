package com.evidence.rag.support;

import com.evidence.rag.model.domain.ImageDimensions;
import com.evidence.rag.model.domain.ImageTextRegion;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoCompilation;
import com.evidence.rag.model.domain.VideoFrameOcr;
import com.evidence.rag.model.domain.VideoOcrCompilation;
import com.evidence.rag.model.domain.VideoOcrSegment;
import com.evidence.rag.model.domain.VideoSubtitleCompilation;
import com.evidence.rag.model.domain.VideoSubtitleCue;
import com.evidence.rag.model.domain.VideoSubtitleTrack;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Synthetic compiled media only; no claim about codec decoding or model quality. */
public final class VideoSubtitleCompilationFixture {
  public static final String COMPILER = "java-video-compiler-v3:" + "e".repeat(64);
  public static final byte[] ORIGINAL = "0000ftypisom00000000".getBytes(StandardCharsets.UTF_8);
  public static final String SOURCE = ModelValues.sha256(ORIGINAL);

  private VideoSubtitleCompilationFixture() {}

  public static VideoCompilation compilation(boolean withOcr) {
    return compilation(withOcr, tracks());
  }

  public static VideoCompilation compilation(boolean withOcr, List<VideoSubtitleTrack> tracks) {
    var base = VideoCompilationFixture.compilation(false);
    var dimensions = new ImageDimensions(2, 2);
    var ocr =
        withOcr
            ? new VideoOcrCompilation(
                "ocr-v1",
                List.of(
                    new VideoFrameOcr(
                        0,
                        base.frames().getFirst().frame().image().sha256(),
                        dimensions,
                        "屏幕文字",
                        List.of(new VideoOcrSegment(0, 0, 4, "屏幕文字")),
                        List.of(new ImageTextRegion(0, 4, 0, 0, 2, 2))),
                    new VideoFrameOcr(
                        1,
                        base.frames().get(1).frame().image().sha256(),
                        dimensions,
                        "",
                        List.of(),
                        List.of())))
            : null;
    return new VideoCompilation(
        SOURCE,
        "decoder-v2",
        COMPILER,
        2_000_000,
        4_000_000,
        base.frames(),
        null,
        ocr,
        new VideoSubtitleCompilation(2, 1, 1, tracks));
  }

  public static List<VideoSubtitleTrack> tracks() {
    return List.of(
        new VideoSubtitleTrack(
            1,
            "mov_text",
            1,
            1000,
            null,
            List.of(
                new VideoSubtitleCue(0, 0, 0, "", "a".repeat(64)),
                new VideoSubtitleCue(1, 2500, 1000, "预算😀42万元。", "b".repeat(64)),
                new VideoSubtitleCue(2, 3500, 2000, "补充条件：须经审批。", "c".repeat(64)),
                new VideoSubtitleCue(3, 5500, 0, "", "d".repeat(64)))),
        new VideoSubtitleTrack(
            3,
            "subrip",
            1,
            1000,
            "eng",
            List.of(new VideoSubtitleCue(0, 2500, 1000, "English only", "f".repeat(64)))));
  }
}
