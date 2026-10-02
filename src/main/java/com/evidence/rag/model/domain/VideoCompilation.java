package com.evidence.rag.model.domain;

import java.util.List;

/** Complete video processing product; captions do not replace original frames or transcription. */
public record VideoCompilation(
    String sourceSha256,
    String decoderRevision,
    String compilerRevision,
    long timelineOriginUs,
    long durationUs,
    List<VideoFrameRecall> frames,
    AudioTranscription audio,
    VideoOcrCompilation ocr,
    VideoSubtitleCompilation subtitles) {
  public VideoCompilation(
      String sourceSha256,
      String decoderRevision,
      String compilerRevision,
      long timelineOriginUs,
      long durationUs,
      List<VideoFrameRecall> frames,
      AudioTranscription audio,
      VideoOcrCompilation ocr) {
    this(
        sourceSha256,
        decoderRevision,
        compilerRevision,
        timelineOriginUs,
        durationUs,
        frames,
        audio,
        ocr,
        null);
  }

  public VideoCompilation(
      String sourceSha256,
      String decoderRevision,
      String compilerRevision,
      long timelineOriginUs,
      long durationUs,
      List<VideoFrameRecall> frames,
      AudioTranscription audio) {
    this(
        sourceSha256,
        decoderRevision,
        compilerRevision,
        timelineOriginUs,
        durationUs,
        frames,
        audio,
        null);
  }

  public VideoCompilation {
    if (frames == null || frames.stream().anyMatch(frame -> frame == null)) {
      throw ModelValues.invalid();
    }
    DecodedVideo.validatedFrames(
        sourceSha256,
        decoderRevision,
        durationUs,
        frames.stream().map(VideoFrameRecall::frame).toList());
    ModelValues.identifier(compilerRevision, 200);
    frames = List.copyOf(frames);
    DecodedVideo.validateSubtitles(subtitles, timelineOriginUs, durationUs);
    if (audio != null
        && (!sourceSha256.equals(audio.sourceSha256())
            || !decoderRevision.equals(audio.decoderRevision())
            || audio.durationMs() > (durationUs + 999) / 1000)) {
      throw ModelValues.invalid();
    }
    if (ocr != null) {
      if (ocr.frames().size() != frames.size()) {
        throw ModelValues.invalid();
      }
      for (int ordinal = 0; ordinal < frames.size(); ordinal++) {
        var frame = frames.get(ordinal).frame();
        var frameOcr = ocr.frames().get(ordinal);
        if (!frame.image().sha256().equals(frameOcr.frameSha256())
            || frame.width() != frameOcr.dimensions().width()
            || frame.height() != frameOcr.dimensions().height()) {
          throw ModelValues.invalid();
        }
      }
    }
  }

  @Override
  public String toString() {
    return "VideoCompilation[redacted]";
  }
}
