package com.evidence.rag.worker.parser;

import com.evidence.rag.model.domain.AudioWaveform;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoAvClip;
import com.evidence.rag.model.domain.VideoAvCompilation;
import com.evidence.rag.model.domain.VideoAvEpoch;
import com.evidence.rag.model.domain.VideoAvFrameTiming;
import com.evidence.rag.model.domain.VideoAvProfile;
import com.evidence.rag.model.domain.VideoAvWindow;
import com.evidence.rag.tool.parser.TextParser;
import com.evidence.rag.tool.parser.VideoInput;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Continuous lossless clips and complete PCM on an exact, shared original-video epoch. */
public final class ProcessVideoAvDecoder implements VideoAvDecoder {
  private static final int MAX_TABLE = 16 * 1024 * 1024;
  private static final String FORMATS = "mov,matroska,webm";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final NativeMediaSession session;
  private final Path ffmpeg;
  private final Path ffprobe;
  private final int chunkSeconds;
  private final String revision;

  public ProcessVideoAvDecoder(Path ffmpeg, Path ffprobe, Duration deadline, int chunkSeconds) {
    this(ffmpeg, ffprobe, deadline, chunkSeconds, null, List.of());
  }

  // Trusted native-protocol fixture uses the same bounded subprocess and cleanup path.
  ProcessVideoAvDecoder(
      Path ffmpeg,
      Path ffprobe,
      Duration deadline,
      int chunkSeconds,
      String fixtureMain,
      List<String> fixtureArgs) {
    if (chunkSeconds < 1 || chunkSeconds > 30) {
      throw new IllegalArgumentException("Invalid AV chunk");
    }
    this.session =
        new NativeMediaSession(ffmpeg, ffprobe, deadline, "video-av", fixtureMain, fixtureArgs);
    this.ffmpeg = ffmpeg;
    this.ffprobe = ffprobe;
    this.chunkSeconds = chunkSeconds;
    revision = profile(session.ffmpegHash(), session.ffprobeHash());
  }

  private String profile(String ffmpegHash, String ffprobeHash) {
    return "java-video-av-decoder-v1:"
        + ModelValues.sha256(
            ("actual-contiguous-pts-lcm16k-floor-v1\0libx264-qp0-bf0-g1-native-format-rgba-framehash-v1\0full-aligned-pcm-no-tail-padding\0clip8m-total64m-source20m-600s-windows1201\0"
                    + chunkSeconds
                    + "\0"
                    + ffmpegHash
                    + "\0"
                    + ffprobeHash)
                .getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String revision() {
    try {
      return profile(
          ModelValues.sha256(Files.readAllBytes(ffmpeg)),
          ModelValues.sha256(Files.readAllBytes(ffprobe)));
    } catch (IOException invalid) {
      throw new TextParser.Failure("parser_failed");
    }
  }

  @Override
  public VideoAvCompilation decode(String filename, String mime, byte[] source) {
    if (!revision.equals(revision())) {
      throw invalid();
    }
    return session.execute(
        filename,
        source,
        bytes -> VideoInput.validateEnvelope(filename, mime, bytes),
        job -> {
          var streams = streams(job, job.input);
          var timeline = timeline(job, job.input, streams);
          var raw =
              timeline.frames().values().stream()
                  .sorted(Comparator.comparingLong(VideoNativeOutput.RawFrame::pts))
                  .toList();
          var tb = timeline.timeBase();
          var n = BigInteger.valueOf(tb.numerator());
          var d = BigInteger.valueOf(tb.denominator());
          var gcd = n.gcd(d);
          n = n.divide(gcd);
          d = d.divide(gcd);
          if (d.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw unsupported();
          }
          long rate =
              d.divide(d.gcd(BigInteger.valueOf(16000)))
                  .multiply(BigInteger.valueOf(16000))
                  .longValueExact();
          var epoch =
              new VideoAvEpoch(timeline.firstPts(), n.longValueExact(), d.longValueExact(), rate);
          long factor =
              Math.multiplyExact(
                  epoch.sourceTimeBaseNumerator(), rate / epoch.sourceTimeBaseDenominator());
          long previous = raw.getFirst().pts();
          for (var f : raw) {
            if (f.pts() != previous
                || Math.multiplyExact(f.duration(), factor) > epoch.durationLimit(chunkSeconds)) {
              throw unsupported();
            }
            previous = Math.addExact(f.pts(), f.duration());
          }
          var hashes = frameHashes(job, job.input, raw.size());
          String sourceSha = ModelValues.sha256(job.source);
          byte[] pcm = null;
          if (streams.audio()) {
            var audioTable =
                job.probe(
                    "av-audio-timeline",
                    probeArgs(
                        job.input, "a:0", "stream=time_base,sample_rate:frame=pts,nb_samples"),
                    MAX_TABLE);
            VideoNativeOutput.audioStart(audioTable, timeline);
            pcm =
                job.decode(
                        "av-audio",
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
                            "asetpts=PTS-"
                                + timeline.originExpression()
                                + "/TB,aresample=16000:async=1:first_pts=0",
                            "-ac",
                            "1",
                            "-c:a",
                            "pcm_s16le",
                            "-f",
                            "s16le",
                            "pipe:1"),
                        19200000,
                        65536)
                    .stdout();
            if (pcm.length < 2 || pcm.length % 2 != 0) {
              throw invalid();
            }
          }
          long videoEnd = Math.multiplyExact(previous - timeline.firstPts(), factor);
          long sampleCount = pcm == null ? 0 : pcm.length / 2L;
          long duration = Math.max(videoEnd, Math.multiplyExact(sampleCount, rate / 16000));
          if (duration > epoch.durationLimit(600)) {
            throw unsupported();
          }
          var windows = new ArrayList<VideoAvWindow>();
          long cursor = 0, totalBytes = 0;
          int firstFrame = 0;
          while (cursor < duration) {
            job.checkCancelled();
            if (windows.size() >= VideoAvCompilation.MAX_WINDOWS) {
              throw unsupported();
            }
            long end = Math.min(duration, Math.addExact(cursor, epoch.durationLimit(chunkSeconds)));
            int after = firstFrame;
            if (cursor < videoEnd) {
              if (videoEnd <= end) {
                end = videoEnd;
                after = raw.size();
              } else {
                while (after < raw.size()
                    && Math.multiplyExact(raw.get(after).pts() - timeline.firstPts(), factor)
                        <= end) {
                  after++;
                }
                after--;
                if (after <= firstFrame) {
                  throw unsupported();
                }
                end = Math.multiplyExact(raw.get(after).pts() - timeline.firstPts(), factor);
              }
            }
            VideoAvClip clip = null;
            if (after > firstFrame) {
              clip =
                  clip(
                      job,
                      raw,
                      hashes,
                      firstFrame,
                      after,
                      timeline.firstPts(),
                      cursor,
                      end,
                      factor,
                      epoch);
              totalBytes += clip.content().length;
              if (totalBytes > VideoAvCompilation.MAX_CLIP_BYTES) {
                throw unsupported();
              }
              firstFrame = after;
            }
            long startSample = Math.min(sampleCount, epoch.sampleAt(cursor));
            long endSample = Math.min(sampleCount, epoch.sampleAt(end));
            AudioWaveform audio =
                endSample > startSample
                    ? new AudioWaveform(
                        sourceSha,
                        revision,
                        startSample,
                        endSample,
                        Arrays.copyOfRange(
                            pcm, Math.toIntExact(startSample * 2), Math.toIntExact(endSample * 2)))
                    : null;
            windows.add(
                new VideoAvWindow(
                    VideoAvProfile.windowId(sourceSha, windows.size()),
                    windows.size(),
                    cursor,
                    end,
                    clip,
                    audio));
            cursor = end;
          }
          job.checkCancelled();
          return new VideoAvCompilation(
              sourceSha, revision, epoch, duration, streams.audio(), windows);
        });
  }

  private VideoAvClip clip(
      NativeMediaSession.Job<?> job,
      List<VideoNativeOutput.RawFrame> frames,
      List<String> hashes,
      int first,
      int after,
      long sourceFirst,
      long start,
      long end,
      long factor,
      VideoAvEpoch epoch)
      throws IOException, InterruptedException {
    Path path = job.directory.resolve("clip.mp4");
    try {
      long cutPts = Math.addExact(sourceFirst, start / factor);
      long endPts = Math.addExact(sourceFirst, end / factor);
      String tb = epoch.sourceTimeBaseNumerator() + "/" + epoch.sourceTimeBaseDenominator();
      job.decode(
          "av-clip",
          List.of(
              "-hide_banner",
              "-nostdin",
              "-v",
              "error",
              "-xerror",
              "-y",
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
              "-an",
              "-sn",
              "-dn",
              "-filter_threads",
              "1",
              "-vf",
              "trim=start_pts=" + cutPts + ":end_pts=" + endPts + ",setpts=PTS-" + cutPts,
              "-c:v",
              "libx264",
              "-preset",
              "veryfast",
              "-qp",
              "0",
              "-bf",
              "0",
              "-g",
              "1",
              "-threads",
              "1",
              "-fps_mode",
              "passthrough",
              "-enc_time_base",
              tb,
              "-video_track_timescale",
              Long.toString(epoch.sourceTimeBaseDenominator()),
              "-movflags",
              "+faststart",
              "-fs",
              Integer.toString(VideoAvClip.MAX_BYTES + 1),
              path.toString()),
          0,
          65536);
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
          || Files.size(path) < 1
          || Files.size(path) > VideoAvClip.MAX_BYTES) {
        throw unsupported();
      }
      var outputStreams = streams(job, path);
      if (outputStreams.audio()) {
        throw invalid();
      }
      var output = timeline(job, path, outputStreams);
      var actual =
          output.frames().values().stream()
              .sorted(Comparator.comparingLong(VideoNativeOutput.RawFrame::pts))
              .toList();
      var pixels = frameHashes(job, path, actual.size());
      if (actual.size() != after - first) {
        throw invalid();
      }
      var timings = new ArrayList<VideoAvFrameTiming>();
      for (int i = 0; i < actual.size(); i++) {
        var src = frames.get(first + i);
        var dst = actual.get(i);
        long local = Math.multiplyExact(src.pts() - sourceFirst, factor) - start;
        long duration = Math.multiplyExact(src.duration(), factor);
        if (local != ticks(dst.pts(), output.timeBase(), epoch)
            || duration != ticks(dst.duration(), output.timeBase(), epoch)
            || src.width() != dst.width()
            || src.height() != dst.height()
            || !hashes.get(first + i).equals(pixels.get(i))) {
          throw invalid();
        }
        timings.add(
            new VideoAvFrameTiming(
                first + i, local, duration, src.width(), src.height(), pixels.get(i)));
      }
      byte[] bytes = Files.readAllBytes(path);
      long firstTick = timings.getFirst().localTick();
      var last = timings.getLast();
      return new VideoAvClip(
          bytes,
          ModelValues.sha256(bytes),
          firstTick,
          Math.addExact(last.localTick(), last.durationTick()),
          timings,
          VideoAvProfile.framesManifestSha256(timings));
    } finally {
      Files.deleteIfExists(path);
    }
  }

  private static long ticks(long pts, VideoNativeOutput.TimeBase tb, VideoAvEpoch epoch) {
    var value =
        BigInteger.valueOf(pts)
            .multiply(BigInteger.valueOf(tb.numerator()))
            .multiply(BigInteger.valueOf(epoch.ticksPerSecond()))
            .divideAndRemainder(BigInteger.valueOf(tb.denominator()));
    if (value[1].signum() != 0) {
      throw invalid();
    }
    return value[0].longValueExact();
  }

  private static List<String> frameHashes(NativeMediaSession.Job<?> job, Path input, int expected)
      throws IOException, InterruptedException {
    byte[] raw =
        job.decode(
                "av-framehash",
                List.of(
                    "-nostdin",
                    "-v",
                    "error",
                    "-xerror",
                    "-copyts",
                    "-protocol_whitelist",
                    "file,pipe",
                    "-threads",
                    "1",
                    "-i",
                    input.toString(),
                    "-map",
                    "0:v:0",
                    "-an",
                    "-sn",
                    "-dn",
                    "-filter_threads",
                    "1",
                    "-pix_fmt",
                    "rgba",
                    "-c:v",
                    "rawvideo",
                    "-threads",
                    "1",
                    "-fps_mode",
                    "passthrough",
                    "-f",
                    "framehash",
                    "-hash",
                    "sha256",
                    "pipe:1"),
                MAX_TABLE,
                65536)
            .stdout();
    var result = new ArrayList<String>();
    for (String line : new String(raw, StandardCharsets.UTF_8).split("\\R")) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      String[] parts = line.split(",", -1);
      if (parts.length != 6
          || !parts[0].strip().equals("0")
          || !parts[5].strip().matches("[a-f0-9]{64}")) {
        throw invalid();
      }
      result.add(parts[5].strip());
    }
    if (result.size() != expected) {
      throw invalid();
    }
    return List.copyOf(result);
  }

  private static VideoNativeOutput.Streams streams(NativeMediaSession.Job<?> job, Path path)
      throws IOException, InterruptedException {
    byte[] raw =
        job.probe(
            "av-streams",
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
                "stream=codec_type,width,height,time_base,sample_rate:format=format_name",
                "-of",
                "json",
                path.toString()),
            65536);
    var root = JSON.readTree(raw);
    var filtered = JSON.createArrayNode();
    for (JsonNode stream : root.path("streams")) {
      if ("video".equals(stream.path("codec_type").stringValue())
          || "audio".equals(stream.path("codec_type").stringValue())) {
        filtered.add(stream);
      }
    }
    ((tools.jackson.databind.node.ObjectNode) root).set("streams", filtered);
    return VideoNativeOutput.streams(
        JSON.writeValueAsBytes(root),
        path.getFileName().toString().endsWith("clip.mp4") ? "video/mp4" : formatMime(root));
  }

  private static String formatMime(JsonNode root) {
    String format = root.path("format").path("format_name").stringValue();
    if (format == null) {
      throw invalid();
    }
    if (format.contains("mov")) {
      return "video/mp4";
    }
    if (format.contains("matroska")) {
      return "video/x-matroska";
    }
    if (format.contains("webm")) {
      return "video/webm";
    }
    throw unsupported();
  }

  private static VideoNativeOutput.Timeline timeline(
      NativeMediaSession.Job<?> job, Path path, VideoNativeOutput.Streams streams)
      throws IOException, InterruptedException {
    return VideoNativeOutput.timeline(
        job.probe(
            "av-timeline",
            probeArgs(path, "v:0", "stream=time_base:frame=pts,duration,width,height"),
            MAX_TABLE),
        streams);
  }

  private static List<String> probeArgs(Path path, String select, String entries) {
    return List.of(
        "-v",
        "error",
        "-threads",
        "1",
        "-protocol_whitelist",
        "file,pipe",
        "-format_whitelist",
        FORMATS,
        "-select_streams",
        select,
        "-show_frames",
        "-show_streams",
        "-show_entries",
        entries,
        "-of",
        "json",
        path.toString());
  }

  private static TextParser.Failure unsupported() {
    return new TextParser.Failure("unsupported_document");
  }

  private static TextParser.Failure invalid() {
    return new TextParser.Failure("parser_invalid_output");
  }

  @Override
  public void close() {
    session.close();
  }
}
