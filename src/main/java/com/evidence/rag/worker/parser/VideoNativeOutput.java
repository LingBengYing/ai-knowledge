package com.evidence.rag.worker.parser;

import com.evidence.rag.model.domain.VideoFrame;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.tool.parser.ImageInput;
import com.evidence.rag.tool.parser.TextParser;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bounded native protocol decoding; all times come from integer PTS and their rational time base.
 */
final class VideoNativeOutput {
  private static final long MAX_US = 600_000_000;
  private static final byte[] PNG = {(byte) 0x89, 80, 78, 71, 13, 10, 26, 10};
  private static final JsonMapper JSON =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
  private static final Pattern TIME_BASE = Pattern.compile("config in time_base:\\s*(\\d+/\\d+)");
  private static final Pattern FRAME =
      Pattern.compile(
          "\\bn:\\s*(\\d+)\\s+pts:\\s*(-?\\d+)\\s+pts_time:[^\\s]+\\s+duration:\\s*(\\d+)"
              + "\\s+duration_time:[^\\s]+[^\\r\\n]*?\\bs:(\\d+)x(\\d+)");

  private VideoNativeOutput() {}

  record SubtitleStream(int index, String codec, TimeBase timeBase, String language) {}

  record Streams(
      TimeBase videoTimeBase,
      boolean audio,
      int width,
      int height,
      List<SubtitleStream> subtitles) {

    Streams {
      subtitles = List.copyOf(subtitles);
    }
  }

  record RawFrame(long pts, long duration, int width, int height) {}

  record Timeline(TimeBase timeBase, long firstPts, long durationUs, Map<Long, RawFrame> frames) {
    long originUs() {
      return timeBase.micros(BigInteger.valueOf(firstPts), false);
    }

    String originExpression() {
      return "(" + firstPts + "*" + timeBase.numerator() + "/" + timeBase.denominator() + ")";
    }
  }

  record TimeBase(long numerator, long denominator) {
    static TimeBase parse(String text) {
      if (text == null || !text.matches("[0-9]{1,10}/[0-9]{1,10}")) {
        throw invalid();
      }
      String[] terms = text.split("/");
      long numerator = Long.parseLong(terms[0]);
      long denominator = Long.parseLong(terms[1]);
      if (numerator <= 0 || denominator <= 0) {
        throw invalid();
      }
      return new TimeBase(numerator, denominator);
    }

    long micros(BigInteger ticks, boolean ceiling) {
      BigInteger scaled =
          ticks.multiply(BigInteger.valueOf(numerator)).multiply(BigInteger.valueOf(1_000_000));
      BigInteger[] parts = scaled.divideAndRemainder(BigInteger.valueOf(denominator));
      if (parts[1].signum() != 0 && (ceiling ? scaled.signum() > 0 : scaled.signum() < 0)) {
        parts[0] = parts[0].add(BigInteger.valueOf(ceiling ? 1 : -1));
      }
      return parts[0].longValueExact();
    }

    boolean same(TimeBase other) {
      return BigInteger.valueOf(numerator)
          .multiply(BigInteger.valueOf(other.denominator))
          .equals(BigInteger.valueOf(other.numerator).multiply(BigInteger.valueOf(denominator)));
    }
  }

  static Streams streams(byte[] raw, String mime) {
    return streams(raw, mime, false);
  }

  static Streams streams(byte[] raw, String mime, boolean subtitlesEnabled) {
    try {
      JsonNode root = tree(raw);
      JsonNode values = root.path("streams");
      if (!values.isArray() || values.size() < 1 || values.size() > (subtitlesEnabled ? 6 : 2)) {
        throw unsupported();
      }
      JsonNode video = null;
      boolean audio = false;
      var subtitles = new ArrayList<SubtitleStream>();
      var indices = new HashSet<Long>();
      for (JsonNode stream : values) {
        if (subtitlesEnabled) {
          long index = integer(stream.path("index"));
          if (index < 0 || index > Integer.MAX_VALUE || !indices.add(index)) {
            throw invalid();
          }
        }
        switch (stream.path("codec_type").stringValue()) {
          case "video" -> {
            if (video != null) {
              throw unsupported();
            }
            video = stream;
          }
          case "audio" -> {
            if (audio) {
              throw unsupported();
            }
            audio = true;
          }
          case "subtitle" -> {
            if (!subtitlesEnabled || subtitles.size() == 4) {
              throw unsupported();
            }
            subtitles.add(subtitleStream(stream));
          }
          default -> throw unsupported();
        }
      }
      if (video == null) {
        throw unsupported();
      }
      String format = root.path("format").path("format_name").stringValue();
      var names = format == null ? List.<String>of() : Arrays.asList(format.split(",", -1));
      boolean supported =
          switch (mime) {
            case "video/mp4", "video/quicktime" -> names.contains("mov");
            case "video/webm" -> names.contains("webm");
            case "video/x-matroska" -> names.contains("matroska");
            default -> false;
          };
      if (!supported) {
        throw unsupported();
      }
      int width = dimension(video.path("width"));
      int height = dimension(video.path("height"));
      pixels(width, height);
      return new Streams(
          TimeBase.parse(video.path("time_base").stringValue()),
          audio,
          width,
          height,
          subtitles.stream()
              .sorted(java.util.Comparator.comparingInt(SubtitleStream::index))
              .toList());
    } catch (TextParser.Failure safe) {
      throw safe;
    } catch (RuntimeException malformed) {
      throw invalid();
    }
  }

  static SubtitleStream subtitleStream(JsonNode stream) {
    long index = integer(stream.path("index"));
    String codec = stream.path("codec_name").stringValue();
    if (index < 0
        || index > Integer.MAX_VALUE
        || !"subtitle".equals(stream.path("codec_type").stringValue())) {
      throw invalid();
    }
    if (!List.of("mov_text", "subrip", "webvtt").contains(codec)) {
      throw unsupported();
    }
    JsonNode tags = stream.path("tags");
    JsonNode language = tags.path("language");
    if ((!tags.isMissingNode() && !tags.isObject())
        || (!language.isMissingNode() && !language.isString())) {
      throw invalid();
    }
    return new SubtitleStream(
        (int) index,
        codec,
        TimeBase.parse(stream.path("time_base").stringValue()),
        language.isMissingNode() ? null : language.stringValue());
  }

  static Timeline timeline(byte[] raw, Streams streams) {
    try {
      JsonNode root = tree(raw);
      var timeBase = TimeBase.parse(root.path("streams").path(0).path("time_base").stringValue());
      if (!timeBase.same(streams.videoTimeBase())) {
        throw invalid();
      }
      JsonNode values = root.path("frames");
      if (!values.isArray() || values.isEmpty()) {
        throw invalid();
      }
      var frames = new LinkedHashMap<Long, RawFrame>();
      Long previous = null;
      long first = 0;
      long endUs = 0;
      for (JsonNode frame : values) {
        long pts = integer(frame.path("pts"));
        long duration = integer(frame.path("duration"));
        int width = dimension(frame.path("width"));
        int height = dimension(frame.path("height"));
        if (duration <= 0
            || previous != null && pts <= previous
            || width != streams.width()
            || height != streams.height()) {
          throw invalid();
        }
        if (previous == null) {
          first = pts;
        }
        BigInteger relativeEnd =
            BigInteger.valueOf(pts)
                .add(BigInteger.valueOf(duration))
                .subtract(BigInteger.valueOf(first));
        long frameEndUs = timeBase.micros(relativeEnd, true);
        if (frameEndUs < 1 || frameEndUs > MAX_US || frameEndUs < endUs) {
          throw invalid();
        }
        endUs = frameEndUs;
        frames.put(pts, new RawFrame(pts, duration, width, height));
        previous = pts;
      }
      return new Timeline(timeBase, first, endUs, Map.copyOf(frames));
    } catch (TextParser.Failure safe) {
      throw safe;
    } catch (RuntimeException malformed) {
      throw invalid();
    }
  }

  static void audioStart(byte[] raw, Timeline video) {
    try {
      JsonNode root = tree(raw);
      var timeBase = TimeBase.parse(root.path("streams").path(0).path("time_base").stringValue());
      JsonNode frames = root.path("frames");
      if (!frames.isArray() || frames.isEmpty()) {
        throw invalid();
      }
      long pts = integer(frames.path(0).path("pts"));
      if (integer(frames.path(0).path("nb_samples")) < 1) {
        throw invalid();
      }
      BigInteger audioTime =
          BigInteger.valueOf(pts)
              .multiply(BigInteger.valueOf(timeBase.numerator()))
              .multiply(BigInteger.valueOf(video.timeBase().denominator()));
      BigInteger videoTime =
          BigInteger.valueOf(video.firstPts())
              .multiply(BigInteger.valueOf(video.timeBase().numerator()))
              .multiply(BigInteger.valueOf(timeBase.denominator()));
      if (audioTime.compareTo(videoTime) < 0) {
        throw unsupported();
      }
    } catch (TextParser.Failure safe) {
      throw safe;
    } catch (RuntimeException malformed) {
      throw invalid();
    }
  }

  static List<VideoFrame> selected(NativeMediaSession.Output output, Timeline timeline) {
    try {
      String stderr = new String(output.stderr(), StandardCharsets.UTF_8);
      var configured = TIME_BASE.matcher(stderr);
      if (!configured.find()
          || !TimeBase.parse(configured.group(1)).same(timeline.timeBase())
          || configured.find()) {
        throw invalid();
      }
      var points = new ArrayList<RawFrame>();
      var matcher = FRAME.matcher(stderr);
      long previous = 0;
      while (matcher.find()) {
        int index = Integer.parseInt(matcher.group(1));
        long pts = Long.parseLong(matcher.group(2));
        long duration = Long.parseLong(matcher.group(3));
        int width = Integer.parseInt(matcher.group(4));
        int height = Integer.parseInt(matcher.group(5));
        RawFrame expected = timeline.frames().get(pts);
        if (index != points.size()
            || index >= 128
            || expected == null
            || expected.duration() != duration
            || expected.width() != width
            || expected.height() != height
            || (index == 0 ? pts != timeline.firstPts() : pts <= previous)) {
          throw invalid();
        }
        points.add(expected);
        previous = pts;
      }
      List<byte[]> pngs = pngs(output.stdout());
      if (points.isEmpty() || points.size() != pngs.size()) {
        throw invalid();
      }
      var result = new ArrayList<VideoFrame>();
      for (int index = 0; index < points.size(); index++) {
        var point = points.get(index);
        byte[] png = pngs.get(index);
        var dimensions = ImageInput.inspect(png);
        if (dimensions.width() != point.width() || dimensions.height() != point.height()) {
          throw invalid();
        }
        BigInteger relative =
            BigInteger.valueOf(point.pts()).subtract(BigInteger.valueOf(timeline.firstPts()));
        long presentation = timeline.timeBase().micros(relative, false);
        long end =
            timeline.timeBase().micros(relative.add(BigInteger.valueOf(point.duration())), true);
        result.add(
            new VideoFrame(
                index,
                presentation,
                end - presentation,
                new VisualImage("image/png", png),
                point.width(),
                point.height()));
      }
      return List.copyOf(result);
    } catch (TextParser.Failure safe) {
      throw invalid();
    } catch (RuntimeException malformed) {
      throw invalid();
    }
  }

  private static List<byte[]> pngs(byte[] data) {
    var images = new ArrayList<byte[]>();
    int cursor = 0;
    while (cursor < data.length) {
      int start = cursor;
      if (images.size() >= 128
          || data.length - cursor < PNG.length
          || !Arrays.equals(PNG, 0, PNG.length, data, cursor, cursor + PNG.length)) {
        throw invalid();
      }
      cursor += PNG.length;
      boolean ended = false;
      while (!ended) {
        if (data.length - cursor < 12) {
          throw invalid();
        }
        long length = Integer.toUnsignedLong(ByteBuffer.wrap(data, cursor, 4).getInt());
        if (length > data.length - cursor - 12 || length > ImageInput.MAX_BYTES) {
          throw invalid();
        }
        var crc = new CRC32();
        crc.update(data, cursor + 4, (int) length + 4);
        long expectedCrc =
            Integer.toUnsignedLong(ByteBuffer.wrap(data, cursor + 8 + (int) length, 4).getInt());
        if (crc.getValue() != expectedCrc) {
          throw invalid();
        }
        ended =
            data[cursor + 4] == 'I'
                && data[cursor + 5] == 'E'
                && data[cursor + 6] == 'N'
                && data[cursor + 7] == 'D';
        if (ended && length != 0) {
          throw invalid();
        }
        cursor += (int) length + 12;
        if (cursor - start > ImageInput.MAX_BYTES) {
          throw invalid();
        }
      }
      images.add(Arrays.copyOfRange(data, start, cursor));
    }
    return images;
  }

  private static JsonNode tree(byte[] raw) {
    JsonNode root = JSON.readTree(raw);
    if (root == null || !root.isObject()) {
      throw invalid();
    }
    return root;
  }

  private static long integer(JsonNode node) {
    if (!node.isIntegralNumber() || !node.canConvertToLong()) {
      throw invalid();
    }
    return node.longValue();
  }

  private static int dimension(JsonNode node) {
    long value = integer(node);
    if (value < 1 || value > 12_000_000) {
      throw invalid();
    }
    return (int) value;
  }

  private static void pixels(int width, int height) {
    if ((long) width * height > ImageInput.MAX_PIXELS) {
      throw unsupported();
    }
  }

  private static TextParser.Failure invalid() {
    return new TextParser.Failure("parser_invalid_output");
  }

  private static TextParser.Failure unsupported() {
    return new TextParser.Failure("unsupported_document");
  }
}
