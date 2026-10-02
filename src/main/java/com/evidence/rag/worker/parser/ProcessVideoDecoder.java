package com.evidence.rag.worker.parser;

import com.evidence.rag.model.domain.DecodedAudio;
import com.evidence.rag.model.domain.DecodedVideo;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoSubtitleCompilation;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.tool.parser.VideoInput;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Actual frame timestamps and complete aligned audio over one bounded native session. */
public final class ProcessVideoDecoder implements VideoDecoder {
  private static final String FORMATS = "mov,matroska,webm";
  private static final int MAX_PROBE_BYTES = 64 * 1024;
  private static final int MAX_TIMELINE_BYTES = 8 * 1024 * 1024;
  private static final int MAX_PNG_BYTES = 32 * 1024 * 1024;
  private static final int MAX_FRAME_METADATA_BYTES = 1024 * 1024;
  private static final int MAX_SUBTITLE_PROBE_BYTES = 16 * 1024 * 1024;
  private static final String PROTOCOL =
      "java-video-decoder-v1:actual-pts:scene-0.3:interval-first-real:png:video-epoch:complete-16khz-mono-s16le";
  private static final String SUBTITLE_PROTOCOL =
      "java-video-decoder-v2:actual-pts:scene-0.3:interval-first-real:png:video-epoch:complete-16khz-mono-s16le:all-text-subtitle-packets:mov_text-subrip-webvtt:subtitle-payload-utf8-v1";
  private final NativeMediaSession session;
  private final int intervalSeconds;
  private final String decoderRevision;
  private final boolean subtitlesEnabled;

  public ProcessVideoDecoder(Path ffmpeg, Path ffprobe, Duration deadline, int intervalSeconds) {
    this(ffmpeg, ffprobe, deadline, intervalSeconds, null, List.of());
  }

  public ProcessVideoDecoder(
      Path ffmpeg, Path ffprobe, Duration deadline, int intervalSeconds, boolean subtitlesEnabled) {
    this(ffmpeg, ffprobe, deadline, intervalSeconds, subtitlesEnabled, null, List.of());
  }

  // Trusted test seam: the replacement is a real child, not an in-process decoded-result mock.
  ProcessVideoDecoder(
      Path ffmpeg,
      Path ffprobe,
      Duration deadline,
      int intervalSeconds,
      String fixtureMain,
      List<String> fixtureArgs) {
    this(ffmpeg, ffprobe, deadline, intervalSeconds, false, fixtureMain, fixtureArgs);
  }

  ProcessVideoDecoder(
      Path ffmpeg,
      Path ffprobe,
      Duration deadline,
      int intervalSeconds,
      boolean subtitlesEnabled,
      String fixtureMain,
      List<String> fixtureArgs) {
    if (intervalSeconds < 1 || intervalSeconds > 30) {
      throw new IllegalArgumentException("Invalid video selection interval");
    }
    session = new NativeMediaSession(ffmpeg, ffprobe, deadline, "video", fixtureMain, fixtureArgs);
    this.intervalSeconds = intervalSeconds;
    this.subtitlesEnabled = subtitlesEnabled;
    decoderRevision =
        (subtitlesEnabled ? "java-video-decoder-v2:" : "java-video-decoder-v1:")
            + ModelValues.sha256(
                ((subtitlesEnabled ? SUBTITLE_PROTOCOL : PROTOCOL)
                        + "\u0000"
                        + intervalSeconds
                        + "\u0000"
                        + session.ffmpegHash()
                        + "\u0000"
                        + session.ffprobeHash())
                    .getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String revision() {
    return decoderRevision;
  }

  @Override
  public DecodedVideo decode(String filename, String mime, byte[] source) {
    return session.execute(
        filename,
        source,
        original -> VideoInput.validateEnvelope(filename, mime, original),
        job -> {
          var probe =
              job.probe(
                  "probe",
                  List.of(
                      "-v",
                      "error",
                      "-threads",
                      "1",
                      "-protocol_whitelist",
                      "file,pipe",
                      "-format_whitelist",
                      FORMATS,
                      "-show_entries",
                      subtitlesEnabled
                          ? "stream=index,codec_type,codec_name,width,height,time_base,sample_rate:stream_tags=language:format=format_name"
                          : "stream=index,codec_type,width,height,time_base,sample_rate:format=format_name",
                      "-of",
                      "json",
                      job.input.toString()),
                  MAX_PROBE_BYTES);
          var streams =
              VideoNativeOutput.streams(
                  probe, VideoInput.canonicalMime(filename), subtitlesEnabled);
          var frameTable =
              job.probe(
                  "timeline",
                  List.of(
                      "-v",
                      "error",
                      "-threads",
                      "1",
                      "-protocol_whitelist",
                      "file,pipe",
                      "-format_whitelist",
                      FORMATS,
                      "-select_streams",
                      "v:0",
                      "-show_frames",
                      "-show_streams",
                      "-show_entries",
                      "stream=time_base:frame=pts,duration,width,height",
                      "-of",
                      "json",
                      job.input.toString()),
                  MAX_TIMELINE_BYTES);
          var timeline = VideoNativeOutput.timeline(frameTable, streams);
          VideoSubtitleCompilation subtitles = null;
          if (subtitlesEnabled) {
            byte[] subtitlePackets =
                job.probe(
                    "subtitles",
                    List.of(
                        "-v",
                        "error",
                        "-threads",
                        "1",
                        "-protocol_whitelist",
                        "file,pipe",
                        "-format_whitelist",
                        FORMATS,
                        "-select_streams",
                        "s",
                        "-show_streams",
                        "-show_packets",
                        "-show_data",
                        "-show_entries",
                        "stream=index,codec_name,codec_type,time_base:stream_tags=language:packet=stream_index,pts,duration,size,data",
                        "-of",
                        "json",
                        job.input.toString()),
                    MAX_SUBTITLE_PROBE_BYTES);
            subtitles = VideoSubtitleNativeOutput.read(subtitlePackets, streams, timeline);
          }
          if (streams.audio()) {
            var audioFrames =
                job.probe(
                    "audio-timeline",
                    List.of(
                        "-v",
                        "error",
                        "-threads",
                        "1",
                        "-protocol_whitelist",
                        "file,pipe",
                        "-format_whitelist",
                        FORMATS,
                        "-select_streams",
                        "a:0",
                        "-show_frames",
                        "-show_streams",
                        "-show_entries",
                        "stream=time_base,sample_rate:frame=pts,nb_samples",
                        "-of",
                        "json",
                        job.input.toString()),
                    MAX_TIMELINE_BYTES);
            VideoNativeOutput.audioStart(audioFrames, timeline);
          }
          var selected =
              job.decode(
                  "frames",
                  List.of(
                      "-hide_banner",
                      "-nostdin",
                      "-v",
                      "info",
                      "-xerror",
                      "-copyts",
                      "-protocol_whitelist",
                      "file,pipe",
                      "-format_whitelist",
                      FORMATS,
                      "-threads",
                      "1",
                      "-i",
                      job.input.toString(),
                      "-map",
                      "0:v:0",
                      "-filter_threads",
                      "1",
                      "-vf",
                      "select='isnan(prev_selected_t)+gt(scene,0.3)+gte(t-prev_selected_t,"
                          + intervalSeconds
                          + ")',showinfo",
                      "-an",
                      "-sn",
                      "-dn",
                      "-c:v",
                      "png",
                      "-threads",
                      "1",
                      "-fps_mode",
                      "passthrough",
                      "-f",
                      "image2pipe",
                      "pipe:1"),
                  MAX_PNG_BYTES,
                  MAX_FRAME_METADATA_BYTES);
          var frames = VideoNativeOutput.selected(selected, timeline);
          String sourceHash = ModelValues.sha256(job.source);
          DecodedAudio audio = null;
          long durationUs =
              Math.max(timeline.durationUs(), subtitles == null ? 0 : subtitles.endUs());
          if (streams.audio()) {
            // Keep every decoded sample; a later audio tail extends the complete video timeline.
            String filter =
                "asetpts=PTS-"
                    + timeline.originExpression()
                    + "/TB,aresample=16000:async=1:first_pts=0";
            byte[] pcm =
                job.decode(
                        "audio",
                        List.of(
                            "-nostdin",
                            "-v",
                            "error",
                            "-xerror",
                            "-copyts",
                            "-protocol_whitelist",
                            "file,pipe",
                            "-format_whitelist",
                            FORMATS,
                            "-threads",
                            "1",
                            "-i",
                            job.input.toString(),
                            "-map",
                            "0:a:0",
                            "-vn",
                            "-sn",
                            "-dn",
                            "-af",
                            filter,
                            "-ac",
                            "1",
                            "-c:a",
                            "pcm_s16le",
                            "-f",
                            "s16le",
                            "pipe:1"),
                        DecodedAudio.MAX_BYTES,
                        MAX_PROBE_BYTES)
                    .stdout();
            if (pcm.length < 2 || pcm.length % 2 != 0) {
              throw new TextParser.Failure("parser_invalid_output");
            }
            audio = new DecodedAudio(sourceHash, decoderRevision, pcm);
            long audioEndUs = (pcm.length / 2L * 1_000_000 + 15_999) / 16_000;
            durationUs = Math.max(durationUs, audioEndUs);
          }
          job.checkCancelled();
          return new DecodedVideo(
              sourceHash,
              decoderRevision,
              timeline.originUs(),
              durationUs,
              frames,
              audio,
              subtitles);
        });
  }

  @Override
  public void close() {
    session.close();
  }
}
