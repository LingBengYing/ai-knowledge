package com.evidence.rag.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.RagApplication;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.VideoAvCompilation;
import com.evidence.rag.model.domain.VideoAvFact;
import com.evidence.rag.model.domain.VideoAvProofIdentity;
import com.evidence.rag.model.domain.VideoAvRequirement;
import com.evidence.rag.tool.parser.AudioPcm;
import com.evidence.rag.worker.parser.ProcessVideoAvDecoder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real HTTP, SQLite, native video and workers; all provider decisions are synthetic loopback data.
 */
class VideoAvLibraryMainlineNativeIT {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String VISUAL_COLLECTION = "java_video_av_native_visual";
  private static final String AUDIO_COLLECTION = "java_video_av_native_audio";
  private static final String VISUAL_QUESTION = "完整窗口里能看到什么？不要省略画面内容。";
  private static final String AUDIO_QUESTION = "完整窗口里能听到什么？不要忽略静音与末尾样本。";
  private static final String JOINT_QUESTION = "画面与声音是否在同一窗口内共现？\n不能拼接别的窗口。";
  private static final String INDEPENDENT_QUESTION = "分别说明画面和声音属性，不推断二者因果关系。";
  private static final String RELATION_QUESTION = "声音是否由画面中的对象发出？必须有直接关系证据。";
  private static final String VISUAL_FACT = "窗口中有彩色合成测试画面。";
  private static final String AUDIO_FACT = "窗口中有完整合成声音信号。";
  private static final String JOINT_FACT = "同一窗口内有彩色合成画面与声音信号。";
  private static final Set<String> QUESTIONS =
      Set.of(
          VISUAL_QUESTION, AUDIO_QUESTION, JOINT_QUESTION, INDEPENDENT_QUESTION, RELATION_QUESTION);
  @TempDir Path directory;
  private Path ffmpeg;
  private Path ffprobe;
  private Path countedFfmpeg;
  private Path countedFfprobe;
  private Path nativeCalls;
  private int operation;

  @Test
  void completeVideoAndPcmReachBothSpacesAndIndependentProofWithExactEpochAndRestartSources()
      throws Exception {
    assertEquals("true", System.getenv("RAG_VIDEO_AV_IT_ENABLED"));
    ffmpeg = nativePath("RAG_VIDEO_DECODER_IT_FFMPEG");
    ffprobe = nativePath("RAG_VIDEO_DECODER_IT_FFPROBE");
    nativeCalls = directory.resolve("native-calls.txt");
    countedFfmpeg = countedExecutable("counted-ffmpeg", ffmpeg);
    countedFfprobe = countedExecutable("counted-ffprobe", ffprobe);
    // The six prior exploratory shapes are regenerated locally; no external fixture path is needed.
    var fixtures = new ArrayList<Fixture>();
    fixtures.add(generate("thirty-second", 248, 8, "8", 496001, 6000, null, 16000, 30));
    fixtures.add(generate("off-frame", 75, 25, "25", 48001, 0, null, 16000, 1));
    fixtures.add(
        generate(
            "variable-frame",
            12,
            8,
            "8",
            36001,
            0,
            "setpts='if(lt(N,6),N/8/TB,(0.75+(N-6)/4)/TB)+2/TB'",
            16000,
            1));
    fixtures.add(generate("audio-tail", 8, 8, "8", 64001, 6000, null, 16000, 1));
    fixtures.add(generate("video-only", 32, 8, "8", null, 0, null, 16000, 1));
    fixtures.add(generate("short-audio", 32, 8, "8", 32001, 6000, null, 16000, 1));
    // Real H264 track timescale 30000 with 1001-tick frames; no metadata replacement.
    fixtures.add(
        generate("rational-epoch", 30, 30000.0 / 1001, "30000/1001", 16017, 6000, null, 30000, 1));
    var compiled = new LinkedHashMap<String, VideoAvCompilation>();
    for (var fixture : fixtures) {
      try (var decoder =
          new ProcessVideoAvDecoder(
              countedFfmpeg, countedFfprobe, Duration.ofSeconds(60), fixture.chunk())) {
        var value =
            decoder.decode(
                fixture.path().getFileName().toString(), fixture.mime(), fixture.bytes());
        assertNativeIdentity(fixture, value);
        compiled.put(fixture.name(), value);
      }
    }
    var thirty = compiled.get("thirty-second");
    assertEquals(240, thirty.windows().getFirst().video().frameCount());
    assertEquals(30 * thirty.epoch().ticksPerSecond(), thirty.windows().getFirst().endTick());
    assertTrue(
        thirty.windows().getLast().video() == null && thirty.windows().getLast().audio() != null);
    assertTrue(
        compiled.get("audio-tail").windows().stream()
            .skip(1)
            .allMatch(window -> window.video() == null));
    assertTrue(
        compiled.get("video-only").windows().stream().allMatch(window -> window.audio() == null));
    assertTrue(compiled.get("short-audio").windows().getLast().audio() == null);
    var rational = compiled.get("rational-epoch");
    assertEquals(30000, rational.epoch().sourceTimeBaseDenominator());
    assertEquals(60000, rational.epoch().sourceFirstPts());
    assertEquals(240000, rational.epoch().ticksPerSecond());
    assertTrue(
        rational.windows().stream().anyMatch(window -> window.startTick() % 15 != 0),
        "At least one true frame boundary falls between 16 kHz samples");

    var main = fixture(fixtures, "short-audio");
    var silent = fixture(fixtures, "video-only");
    var rationalSource = fixture(fixtures, "rational-epoch");
    var mainCompilation = compiled.get(main.name());
    Path data = directory.resolve("database");
    JsonNode savedCitation;
    String sourceUrl;
    String contentUrl;
    JsonNode queryCitation;
    try (var remote = new Providers(compiled.values().stream().toList());
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
      try (var app = application(remote, data)) {
        assertFalse(app.containsBean("audioModels"));
        assertFalse(app.containsBean("videoCompilationService"));
        assertTrue(remote.requests.isEmpty(), "Construction does not initialize providers");
        String base = base(app);
        var capabilities = json(http, base, "owner", "GET", "/v1/config", null, null, 200);
        var names = new HashSet<String>();
        capabilities.path("capabilities").forEach(value -> names.add(value.asString()));
        assertTrue(
            names.containsAll(
                Set.of(
                    "video_av_upload", "video_av_index", "video_av_answers", "video_av_sources")));
        assertFalse(
            names.contains("sound_answers")
                || names.contains("audio_answers")
                || names.contains("video_answers"));
        int nativeBeforeUpload = nativeCount();
        var uploaded = upload(http, base, "owner", "原始音画.mov", main.bytes());
        String document = uploaded.path("document_id").asString();
        var silentUpload = upload(http, base, "owner", "silent.mp4", silent.bytes());
        String silentDocument = silentUpload.path("document_id").asString();
        var rationalUpload = upload(http, base, "owner", "rational.mov", rationalSource.bytes());
        String rationalDocument = rationalUpload.path("document_id").asString();
        String privateDocument =
            upload(http, base, "other-owner", "private.mov", main.bytes())
                .path("document_id")
                .asString();
        assertEquals(4, uploaded.size());
        assertEquals(ModelValues.sha256(main.bytes()), uploaded.path("source_sha256").asString());
        assertEquals(main.bytes().length, uploaded.path("size_bytes").asLong());
        assertEquals(nativeBeforeUpload, nativeCount(), "Raw upload is zero decode");
        assertTrue(remote.requests.isEmpty(), "Raw upload is zero model, ASR and projection");
        var missing = json(http, base, "owner", "GET", indexPath(document), null, null, 200);
        assertEquals("missing", missing.path("status").asString());
        assertEquals(14, missing.size());
        assertTrue(missing.path("publication_id").isNull());
        assertEquals(nativeBeforeUpload, nativeCount());
        json(http, base, "owner", "GET", indexPath(privateDocument), null, null, 404);
        query(http, base, "owner", "VISUAL", VISUAL_QUESTION, List.of(privateDocument), 404);
        attachedQuery(
            http,
            base,
            "owner",
            "VISUAL",
            VISUAL_QUESTION,
            List.of(privateDocument),
            List.of(main),
            404);
        assertTrue(remote.requests.isEmpty());
        assertEquals(nativeBeforeUpload, nativeCount());

        var indexed = build(http, base, document, mainCompilation, remote);
        int beforeMissing = remote.requests.size();
        int nativeBeforeMissing = nativeCount();
        var fullMissing = query(http, base, "owner", "JOINT", JOINT_QUESTION, null, 409);
        assertEquals("video_av_index_required", fullMissing.path("error_code").asString());
        var selectedMissing =
            query(
                http,
                base,
                "owner",
                "JOINT",
                JOINT_QUESTION,
                List.of(document, silentDocument),
                409);
        assertEquals("video_av_index_required", selectedMissing.path("error_code").asString());
        var attachedMissing =
            attachedQuery(
                http,
                base,
                "owner",
                "JOINT",
                JOINT_QUESTION,
                List.of(document, silentDocument),
                List.of(main),
                409);
        assertEquals("video_av_index_required", attachedMissing.path("error_code").asString());
        var attachedEmpty =
            attachedQuery(
                http,
                base,
                "owner",
                "VISUAL",
                VISUAL_QUESTION,
                List.of(),
                List.of(main, silent),
                200);
        assertEquals("empty_scope", attachedEmpty.path("result").path("reason_code").asString());
        assertQueryManifests(attachedEmpty, List.of(main, silent), compiled, "VISUAL", false);
        assertQueryTraceStored(data, attachedEmpty, VISUAL_QUESTION, indexed);
        var empty = query(http, base, "owner", "JOINT", JOINT_QUESTION, List.of(), 200);
        assertEquals("abstained", empty.path("status").asString());
        assertEquals("empty_scope", empty.path("reason_code").asString());
        assertTrue(empty.path("citations").isEmpty());
        assertEquals(beforeMissing, remote.requests.size());
        assertEquals(
            nativeBeforeMissing,
            nativeCount(),
            "Missing full scope and explicit empty selection do not decode");
        var silentIndex = build(http, base, silentDocument, compiled.get(silent.name()), remote);
        assertEquals(0, silentIndex.path("audio_window_count").asInt());
        assertTrue(silentIndex.path("video_window_count").asInt() > 0);
        build(http, base, rationalDocument, rational, remote);
        assertTrue(
            remote.drafts.isEmpty() && remote.verifications.isEmpty(),
            "Indexing calls only the two media embeddings; no describe or ASR protocol exists");
        int idempotent = remote.requests.size();
        int idempotentNative = nativeCount();
        assertEquals(
            indexed, json(http, base, "owner", "GET", indexPath(document), null, null, 200));
        assertEquals(
            indexed, json(http, base, "owner", "POST", indexPath(document), null, null, 200));
        assertEquals(idempotent, remote.requests.size());
        assertEquals(idempotentNative, nativeCount());

        remote.expectedScope = Set.of(document);
        int queryMediaStart = remote.media.size();
        remote.textMiss = true;
        var queryAnswer =
            attachedQuery(
                http,
                base,
                "owner",
                "JOINT",
                INDEPENDENT_QUESTION,
                List.of(document),
                List.of(main, rationalSource, fixture(fixtures, "audio-tail")),
                200);
        assertEquals(
            Set.of("mode", "result", "query_attachments"),
            new HashSet<>(queryAnswer.propertyNames()));
        assertEquals("JOINT", queryAnswer.path("mode").asString());
        assertTrue(names.contains("video_av_query_attachments"));
        assertFalse(names.contains("query_attachments"));
        assertAnswered(queryAnswer.path("result"), document, "JOINT");
        assertQueryManifests(
            queryAnswer,
            List.of(main, rationalSource, fixture(fixtures, "audio-tail")),
            compiled,
            "JOINT",
            true);
        assertQueryTraceStored(data, queryAnswer, INDEPENDENT_QUESTION, indexed);
        var expectedQueryMedia = new ArrayList<String>();
        for (var reference : List.of(main, rationalSource, fixture(fixtures, "audio-tail"))) {
          for (var window : compiled.get(reference.name()).windows()) {
            if (window.video() != null) {
              expectedQueryMedia.add("video/mp4:" + window.video().sha256());
            }
            if (window.audio() != null) {
              expectedQueryMedia.add("audio/wav:" + ModelValues.sha256(window.audio().wav()));
            }
          }
        }
        assertEquals(
            expectedQueryMedia,
            remote.media.subList(queryMediaStart, remote.media.size()).stream()
                .map(media -> media.mime() + ":" + ModelValues.sha256(media.bytes()))
                .toList(),
            "All three references and every actual tail window are embedded once, in request order");
        queryCitation = queryAnswer.path("result").path("citations").get(0);
        remote.textMiss = false;

        remote.expectedScope = Set.of(document);
        for (String mode : List.of("AUDIO", "JOINT")) {
          int beforeMissingModality = remote.requests.size();
          var missingModality =
              attachedQuery(
                  http,
                  base,
                  "owner",
                  mode,
                  mode.equals("AUDIO") ? AUDIO_QUESTION : JOINT_QUESTION,
                  List.of(document),
                  List.of(main, silent),
                  200);
          assertEquals(
              "query_modality_missing",
              missingModality.path("result").path("reason_code").asString());
          assertQueryManifests(missingModality, List.of(main, silent), compiled, mode, false);
          assertQueryTraceStored(
              data,
              missingModality,
              mode.equals("AUDIO") ? AUDIO_QUESTION : JOINT_QUESTION,
              indexed);
          assertEquals(
              beforeMissingModality,
              remote.requests.size(),
              "One missing query audio track rejects the whole batch before any provider");
        }
        for (String mode : List.of("VISUAL", "AUDIO")) {
          var references = mode.equals("VISUAL") ? List.of(silent) : List.of(rationalSource);
          var answerWithReference =
              attachedQuery(
                  http,
                  base,
                  "owner",
                  mode,
                  mode.equals("VISUAL") ? VISUAL_QUESTION : AUDIO_QUESTION,
                  List.of(document),
                  references,
                  200);
          assertAnswered(answerWithReference.path("result"), document, mode);
          assertQueryManifests(answerWithReference, references, compiled, mode, true);
        }

        remote.expectedScope = Set.of(document);
        for (var modeAndQuestion :
            List.of(
                new String[] {"VISUAL", VISUAL_QUESTION},
                new String[] {"AUDIO", AUDIO_QUESTION},
                new String[] {"JOINT", JOINT_QUESTION})) {
          int draftStart = remote.drafts.size();
          int verifyStart = remote.verifications.size();
          var answer =
              query(
                  http,
                  base,
                  "owner",
                  modeAndQuestion[0],
                  modeAndQuestion[1],
                  List.of(document),
                  200);
          assertAnswered(answer, document, modeAndQuestion[0]);
          assertTrue(
              remote.drafts.size() > draftStart && remote.verifications.size() > verifyStart);
          assertStages(remote, draftStart, verifyStart, modeAndQuestion[0], modeAndQuestion[1]);
          assertCitation(
              answer.path("citations").get(0),
              uploaded,
              indexed,
              mainCompilation,
              modeAndQuestion[1]);
        }
        var independent =
            query(http, base, "owner", "JOINT", INDEPENDENT_QUESTION, List.of(document), 200);
        assertAnswered(independent, document, "JOINT");
        var independentFacts = independent.path("citations").get(0).path("facts");
        assertEquals(2, independentFacts.size());
        assertEquals("VISUAL", independentFacts.get(0).path("requirement").asString());
        assertEquals("AUDIO", independentFacts.get(1).path("requirement").asString());
        int beforeRelationVerify = remote.verifications.size();
        var relationship =
            query(http, base, "owner", "JOINT", RELATION_QUESTION, List.of(document), 200);
        assertEquals("abstained", relationship.path("status").asString());
        assertEquals("incomplete_evidence", relationship.path("reason_code").asString());
        assertTrue(relationship.path("citations").isEmpty());
        assertTrue(
            remote.verifications.size() > beforeRelationVerify,
            "Two true independent observations cannot become a relational answer after incomplete verification");

        remote.expectedScope = Set.of(silentDocument);
        var visualOnly =
            query(http, base, "owner", "VISUAL", VISUAL_QUESTION, List.of(silentDocument), 200);
        assertAnswered(visualOnly, silentDocument, "VISUAL");
        assertTrue(visualOnly.path("citations").get(0).path("window").path("audio").isNull());
        int beforeNoAudio = remote.requests.size();
        var noAudio =
            query(http, base, "owner", "AUDIO", AUDIO_QUESTION, List.of(silentDocument), 200);
        assertEquals("abstained", noAudio.path("status").asString());
        assertEquals(
            beforeNoAudio,
            remote.requests.size(),
            "No fake waveform is created for an absent audio route");
        remote.expectedScope = Set.of(rationalDocument);
        var rationalAnswer =
            query(http, base, "owner", "JOINT", JOINT_QUESTION, List.of(rationalDocument), 200);
        assertAnswered(rationalAnswer, rationalDocument, "JOINT");
        assertEquals(
            "240000",
            rationalAnswer
                .path("citations")
                .get(0)
                .path("epoch")
                .path("ticks_per_second")
                .asString());
        assertEquals(
            "30000",
            rationalAnswer.path("citations").get(0).path("epoch").path("time_base_den").asString());

        savedCitation = independent.path("citations").get(0);
        sourceUrl = savedCitation.path("source_url").asString();
        contentUrl = savedCitation.path("content_url").asString();
        int beforeSource = remote.requests.size();
        int nativeBeforeSource = nativeCount();
        assertEquals(
            savedCitation,
            json(http, base, "owner", "GET", sourceUrl, null, null, 200).path("citation"));
        assertOriginal(http, base, contentUrl, main.bytes(), "video/quicktime");
        assertEquals(
            queryCitation,
            json(
                    http,
                    base,
                    "owner",
                    "GET",
                    queryCitation.path("source_url").asString(),
                    null,
                    null,
                    200)
                .path("citation"));
        assertOriginal(
            http,
            base,
            queryCitation.path("content_url").asString(),
            main.bytes(),
            "video/quicktime");
        json(http, base, "other-owner", "GET", sourceUrl, null, null, 404);
        assertEquals(beforeSource, remote.requests.size());
        assertEquals(nativeBeforeSource, nativeCount());
        remote.assertHealthy();
      }
      int providerBeforeRestart = remote.requests.size();
      int nativeBeforeRestart = nativeCount();
      try (var restarted = application(remote, data)) {
        assertEquals(
            savedCitation,
            json(http, base(restarted), "owner", "GET", sourceUrl, null, null, 200)
                .path("citation"));
        assertOriginal(http, base(restarted), contentUrl, main.bytes(), "video/quicktime");
        assertEquals(
            queryCitation,
            json(
                    http,
                    base(restarted),
                    "owner",
                    "GET",
                    queryCitation.path("source_url").asString(),
                    null,
                    null,
                    200)
                .path("citation"));
        assertOriginal(
            http,
            base(restarted),
            queryCitation.path("content_url").asString(),
            main.bytes(),
            "video/quicktime");
        assertEquals(
            providerBeforeRestart,
            remote.requests.size(),
            "Restarted immutable source reads make zero provider calls");
        assertEquals(
            nativeBeforeRestart,
            nativeCount(),
            "Restarted immutable source reads invoke neither FFmpeg nor FFprobe");
        remote.assertHealthy();
      }
    }
  }

  private record Fixture(
      String name, Path path, String mime, byte[] bytes, byte[] expectedPcm, int chunk) {}

  private Fixture generate(
      String name,
      int frameCount,
      double fpsValue,
      String fps,
      Integer samples,
      int delayedSamples,
      String videoFilter,
      int timescale,
      int chunk)
      throws Exception {
    Path source = directory.resolve(name + (samples == null ? ".mp4" : ".mov"));
    var command =
        new ArrayList<>(
            List.of(
                ffmpeg.toString(),
                "-hide_banner",
                "-nostdin",
                "-v",
                "error",
                "-y",
                "-copyts",
                "-f",
                "lavfi",
                "-i",
                "testsrc2=size=64x48:rate="
                    + fps
                    + ":duration="
                    + String.format(Locale.ROOT, "%.9f", frameCount / fpsValue)));
    byte[] inputPcm = samples == null ? null : pcm(samples);
    if (inputPcm != null) {
      Path audio = directory.resolve(name + ".s16le");
      Files.write(audio, inputPcm);
      command.addAll(List.of("-f", "s16le", "-ar", "16000", "-ac", "1", "-i", audio.toString()));
    }
    command.addAll(
        List.of(
            "-map",
            "0:v:0",
            "-vf",
            videoFilter == null ? "settb=expr=1/" + timescale + ",setpts=PTS+2/TB" : videoFilter,
            "-c:v",
            "libx264",
            "-threads",
            "1",
            "-fps_mode",
            "passthrough",
            "-enc_time_base:v",
            "1/" + timescale,
            "-preset",
            "veryfast",
            "-qp",
            "0",
            "-bf",
            "0",
            "-pix_fmt",
            "yuv420p",
            "-video_track_timescale",
            Integer.toString(timescale)));
    if (inputPcm != null) {
      command.addAll(
          List.of(
              "-map",
              "1:a:0",
              "-af",
              "asetpts=PTS+" + Double.toString(2 + delayedSamples / 16000.0) + "/TB",
              "-c:a",
              "pcm_s16le"));
    }
    command.addAll(List.of("-avoid_negative_ts", "disabled", source.toString()));
    invoke(command);
    byte[] expected = inputPcm == null ? null : new byte[delayedSamples * 2 + inputPcm.length];
    if (expected != null) {
      System.arraycopy(inputPcm, 0, expected, delayedSamples * 2, inputPcm.length);
    }
    return new Fixture(
        name,
        source,
        samples == null ? "video/mp4" : "video/quicktime",
        Files.readAllBytes(source),
        expected,
        chunk);
  }

  private static Fixture fixture(List<Fixture> fixtures, String name) {
    return fixtures.stream().filter(item -> item.name().equals(name)).findFirst().orElseThrow();
  }

  private static byte[] pcm(int samples) {
    var data = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i < samples; i++) {
      data.putShort(
          (short)
              (i == samples - 1
                  ? -12345
                  : i < samples / 3 ? 12000 : i < 2 * samples / 3 ? 0 : -9000));
    }
    return data.array();
  }

  private void assertNativeIdentity(Fixture fixture, VideoAvCompilation compiled) throws Exception {
    var source = probe(fixture.path());
    var stream = source.path("streams").get(0);
    String[] tb = stream.path("time_base").asString().split("/");
    long num = Long.parseLong(tb[0]);
    long den = Long.parseLong(tb[1]);
    long gcd = BigInteger.valueOf(num).gcd(BigInteger.valueOf(den)).longValueExact();
    num /= gcd;
    den /= gcd;
    var epoch = compiled.epoch();
    assertEquals(num, epoch.sourceTimeBaseNumerator());
    assertEquals(den, epoch.sourceTimeBaseDenominator());
    var sourceFrames = source.path("frames");
    assertFalse(sourceFrames.isEmpty());
    assertEquals(sourceFrames.get(0).path("pts").asLong(), epoch.sourceFirstPts());
    assertEquals(
        BigInteger.valueOf(2).multiply(BigInteger.valueOf(den)),
        BigInteger.valueOf(epoch.sourceFirstPts()).multiply(BigInteger.valueOf(num)),
        "The native file really starts at the nonzero two-second epoch");
    byte[] originalPixels = pixels(fixture.path());
    assertEquals(sourceFrames.size() * 64 * 48 * 4, originalPixels.length);
    var allPixels = new ByteArrayOutputStream();
    var allPcm = new ByteArrayOutputStream();
    int sourceOrdinal = 0;
    long cursor = 0;
    for (var window : compiled.windows()) {
      assertEquals(cursor, window.startTick());
      assertTrue(
          window.endTick() > cursor
              && window.endTick() - cursor <= fixture.chunk() * epoch.ticksPerSecond());
      cursor = window.endTick();
      if (window.video() != null) {
        var video = window.video();
        assertEquals(ModelValues.sha256(video.content()), video.sha256());
        Path clip = directory.resolve(fixture.name() + "-oracle-" + window.ordinal() + ".mp4");
        Files.write(clip, video.content());
        var actual = probe(clip);
        assertEquals(
            1,
            JSON.readTree(
                    invoke(
                        List.of(
                            ffprobe.toString(),
                            "-v",
                            "error",
                            "-show_entries",
                            "stream=codec_type",
                            "-of",
                            "json",
                            clip.toString())))
                .path("streams")
                .size(),
            "Actual encoded clip contains only one video track");
        String[] outTb = actual.path("streams").get(0).path("time_base").asString().split("/");
        assertEquals(video.frameCount(), actual.path("frames").size());
        byte[] actualPixels = pixels(clip);
        assertArrayEquals(
            Arrays.copyOfRange(
                originalPixels,
                sourceOrdinal * 64 * 48 * 4,
                (sourceOrdinal + video.frameCount()) * 64 * 48 * 4),
            actualPixels,
            "Every decoded source pixel appears exactly once in a continuous real MP4");
        allPixels.writeBytes(actualPixels);
        for (int frameIndex = 0; frameIndex < video.frameCount(); frameIndex++) {
          var frame = video.frames().get(frameIndex);
          var originalFrame = sourceFrames.get(sourceOrdinal);
          var actualFrame = actual.path("frames").get(frameIndex);
          assertEquals(sourceOrdinal, frame.sourceOrdinal());
          long sourceTick =
              rationalTicks(
                  originalFrame.path("pts").asLong() - epoch.sourceFirstPts(),
                  num,
                  den,
                  epoch.ticksPerSecond());
          long duration =
              rationalTicks(
                  originalFrame.path("duration").asLong(), num, den, epoch.ticksPerSecond());
          assertEquals(sourceTick, window.startTick() + frame.localTick());
          assertEquals(duration, frame.durationTick());
          assertEquals(
              frame.localTick(),
              rationalTicks(
                  actualFrame.path("pts").asLong(),
                  Long.parseLong(outTb[0]),
                  Long.parseLong(outTb[1]),
                  epoch.ticksPerSecond()));
          assertEquals(
              duration,
              rationalTicks(
                  actualFrame.path("duration").asLong(),
                  Long.parseLong(outTb[0]),
                  Long.parseLong(outTb[1]),
                  epoch.ticksPerSecond()));
          assertEquals(
              ModelValues.sha256(
                  Arrays.copyOfRange(
                      actualPixels, frameIndex * 64 * 48 * 4, (frameIndex + 1) * 64 * 48 * 4)),
              frame.pixelSha256());
          sourceOrdinal++;
        }
      }
      if (window.audio() != null) {
        var audio = window.audio();
        assertNotNull(fixture.expectedPcm());
        assertEquals(allPcm.size() / 2L, audio.startSample());
        assertEquals(epoch.sampleAt(window.startTick()), audio.startSample());
        assertArrayEquals(
            Arrays.copyOfRange(
                fixture.expectedPcm(),
                Math.toIntExact(audio.startSample() * 2),
                Math.toIntExact(audio.endSample() * 2)),
            audio.pcm());
        assertArrayEquals(AudioPcm.wav(audio.pcm(), 0, audio.pcm().length), audio.wav());
        allPcm.writeBytes(audio.pcm());
      }
    }
    assertEquals(sourceFrames.size(), sourceOrdinal);
    assertEquals(compiled.durationTick(), cursor);
    assertArrayEquals(originalPixels, allPixels.toByteArray());
    assertArrayEquals(
        fixture.expectedPcm() == null ? new byte[0] : fixture.expectedPcm(),
        allPcm.toByteArray(),
        "Every actual PCM sample, leading delay, silence and one-sample tail is retained without padded tail");
    if (fixture.expectedPcm() != null) {
      assertEquals(
          -12345,
          ByteBuffer.wrap(allPcm.toByteArray())
              .order(ByteOrder.LITTLE_ENDIAN)
              .getShort(allPcm.size() - 2));
    }
  }

  private static long rationalTicks(long value, long numerator, long denominator, long rate) {
    var result =
        BigInteger.valueOf(value)
            .multiply(BigInteger.valueOf(numerator))
            .multiply(BigInteger.valueOf(rate))
            .divideAndRemainder(BigInteger.valueOf(denominator));
    assertEquals(BigInteger.ZERO, result[1], "Native times must map exactly to the common axis");
    return result[0].longValueExact();
  }

  private JsonNode probe(Path source) throws Exception {
    return JSON.readTree(
        invoke(
            List.of(
                ffprobe.toString(),
                "-v",
                "error",
                "-threads",
                "1",
                "-select_streams",
                "v:0",
                "-show_frames",
                "-show_streams",
                "-show_entries",
                "stream=time_base,width,height:frame=pts,duration,width,height",
                "-of",
                "json",
                source.toString())));
  }

  private byte[] pixels(Path source) throws Exception {
    return invoke(
        List.of(
            ffmpeg.toString(),
            "-nostdin",
            "-v",
            "error",
            "-xerror",
            "-threads",
            "1",
            "-i",
            source.toString(),
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
            "rawvideo",
            "pipe:1"));
  }

  private byte[] invoke(List<String> command) throws Exception {
    Path out = directory.resolve("oracle-" + (++operation) + ".stdout");
    Path err = directory.resolve("oracle-" + operation + ".stderr");
    var builder =
        new ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectOutput(out.toFile())
            .redirectError(err.toFile());
    builder.environment().clear();
    var process = builder.start();
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Owned fixture process must terminate");
      throw new AssertionError("Native fixture command exceeded its deadline");
    }
    assertEquals(0, process.exitValue(), Files.readString(err));
    return Files.readAllBytes(out);
  }

  private Path countedExecutable(String name, Path actual) throws IOException {
    Path wrapper = directory.resolve(name).toAbsolutePath().normalize();
    Files.writeString(
        wrapper,
        "#!/bin/sh\nprintf '%s\\n' call >> "
            + shellQuote(nativeCalls.toString())
            + "\nexec "
            + shellQuote(actual.toString())
            + " \"$@\"\n");
    Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwx------"));
    return wrapper;
  }

  private static String shellQuote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  private int nativeCount() throws IOException {
    return Files.exists(nativeCalls) ? Files.readAllLines(nativeCalls).size() : 0;
  }

  private static Path nativePath(String name) throws IOException {
    String configured = System.getenv(name);
    assertNotNull(configured, "Native executable path must be explicitly supplied");
    Path supplied = Path.of(configured);
    assertTrue(supplied.isAbsolute());
    Path path = supplied.normalize().toRealPath();
    assertTrue(Files.isRegularFile(path) && Files.isExecutable(path));
    return path;
  }

  private ConfigurableApplicationContext application(Providers remote, Path data) {
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    var application = new SpringApplication(RagApplication.class);
    application.setEnvironment(environment);
    String decoderRevision;
    try (var decoder =
        new ProcessVideoAvDecoder(countedFfmpeg, countedFfprobe, Duration.ofSeconds(60), 1)) {
      decoderRevision = decoder.revision();
    }
    return application.run(
        "--server.port=0",
        "--server.address=127.0.0.1",
        "--rag.environment=test",
        "--rag.workspace-id=org-main",
        "--rag.auth-mode=development_headers",
        "--rag.data-directory=" + data,
        "--rag.ingestion.enabled=true",
        "--rag.indexing.enabled=false",
        "--rag.answers.enabled=false",
        "--rag.visual.enabled=false",
        "--rag.audio.enabled=false",
        "--rag.video.enabled=false",
        "--rag.query-attachments.enabled=false",
        "--rag.image-ocr.enabled=false",
        "--rag.pdf-ocr.enabled=false",
        "--rag.image-embedding.enabled=false",
        "--rag.audio-embedding.enabled=false",
        "--rag.sound.enabled=false",
        "--rag.video-av.enabled=true",
        "--rag.video-av.ffmpeg-executable=" + countedFfmpeg,
        "--rag.video-av.ffprobe-executable=" + countedFfprobe,
        "--rag.video-av.decoder-revision=" + decoderRevision,
        "--rag.video-av.chunk-seconds=1",
        "--rag.video-av.decode-deadline-ms=60000",
        "--rag.video-av.compilation-budget-ms=120000",
        "--rag.video-av.processing-timeout-ms=120000",
        "--rag.video-av.max-concurrent=2",
        "--rag.video-av.base-url=" + remote.endpoint(),
        "--rag.video-av.model=fixture-video-av",
        "--rag.video-av.api-key=synthetic-fixture-credential",
        "--rag.video-av.revision=fixture-video-av-v1",
        "--rag.video-av.allow-loopback-http=true",
        "--rag.video-av.embedding.base-url=" + remote.endpoint(),
        "--rag.video-av.embedding.model=fixture-video-av-embedding",
        "--rag.video-av.embedding.api-key=synthetic-fixture-credential",
        "--rag.video-av.embedding.revision=fixture-video-av-embedding-v1",
        "--rag.video-av.embedding.dimensions=2",
        "--rag.video-av.embedding.allow-loopback-http=true",
        "--rag.video-av.milvus.endpoint=" + remote.endpoint(),
        "--rag.video-av.milvus.token=synthetic-fixture-credential",
        "--rag.video-av.milvus.video-collection=" + VISUAL_COLLECTION,
        "--rag.video-av.milvus.audio-collection=" + AUDIO_COLLECTION,
        "--rag.video-av.milvus.allow-loopback-http=true");
  }

  private static String base(ConfigurableApplicationContext app) {
    return "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port");
  }

  private static String indexPath(String document) {
    return "/v1/documents/" + document + "/video-av-index";
  }

  private static JsonNode build(
      HttpClient http, String base, String document, VideoAvCompilation expected, Providers remote)
      throws Exception {
    int before = remote.media.size();
    int beforeDrafts = remote.drafts.size();
    int beforeVerifications = remote.verifications.size();
    var result = json(http, base, "owner", "POST", indexPath(document), null, null, 200);
    remote.assertHealthy();
    assertEquals("available", result.path("status").asString());
    assertEquals(14, result.size());
    assertEquals(expected.windows().size(), result.path("window_count").asInt());
    assertEquals(
        expected.windows().stream().filter(window -> window.video() != null).count(),
        result.path("video_window_count").asLong());
    assertEquals(
        expected.windows().stream().filter(window -> window.audio() != null).count(),
        result.path("audio_window_count").asLong());
    assertEquals(result.path("publication_id"), result.path("generation_id"));
    assertTrue(result.path("manifest_sha256").asString().matches("[a-f0-9]{64}"));
    assertEquals(beforeDrafts, remote.drafts.size());
    assertEquals(beforeVerifications, remote.verifications.size());
    var expectedMedia = new ArrayList<String>();
    for (var window : expected.windows()) {
      if (window.video() != null) {
        expectedMedia.add("video/mp4:" + ModelValues.sha256(window.video().content()));
      }
      if (window.audio() != null) {
        expectedMedia.add("audio/wav:" + ModelValues.sha256(window.audio().wav()));
      }
    }
    var actualMedia =
        remote.media.subList(before, remote.media.size()).stream()
            .map(value -> value.mime() + ":" + ModelValues.sha256(value.bytes()))
            .sorted()
            .toList();
    assertEquals(
        expectedMedia.stream().sorted().toList(),
        actualMedia,
        "The worker sends every complete real clip and every complete waveform including silent windows and tails exactly once");
    for (String collection : List.of(VISUAL_COLLECTION, AUDIO_COLLECTION)) {
      var rows =
          remote.rows.getOrDefault(collection, Map.of()).values().stream()
              .filter(row -> row.path("document_id").asString().equals(document))
              .toList();
      var shas =
          expected.windows().stream()
              .filter(
                  window ->
                      collection.equals(VISUAL_COLLECTION)
                          ? window.video() != null
                          : window.audio() != null)
              .map(
                  window ->
                      collection.equals(VISUAL_COLLECTION)
                          ? window.video().sha256()
                          : window.audio().pcmSha256())
              .toList();
      assertEquals(shas.size(), rows.size());
      assertEquals(
          shas.stream().sorted().toList(),
          rows.stream().map(row -> row.path("text").asString()).sorted().toList());
    }
    return result;
  }

  private static void assertStages(
      Providers remote, int draftStart, int verifyStart, String mode, String question) {
    var drafts = remote.drafts.subList(draftStart, remote.drafts.size());
    var verifications = remote.verifications.subList(verifyStart, remote.verifications.size());
    assertEquals(drafts.size(), verifications.size());
    for (int i = 0; i < drafts.size(); i++) {
      var draft = drafts.get(i);
      var verify = verifications.get(i);
      assertEquals(question, draft.input().path("question").asString());
      assertEquals(question, verify.input().path("question").asString());
      assertEquals(mode, draft.input().path("mode").asString());
      assertEquals(draft.input().path("epoch"), verify.input().path("epoch"));
      assertEquals(draft.input().path("window"), verify.input().path("window"));
      assertArrayEquals(draft.video(), verify.video());
      assertArrayEquals(draft.wav(), verify.wav());
      assertEquals(mode.equals("AUDIO"), draft.video() == null);
      assertEquals(mode.equals("VISUAL"), draft.wav() == null);
      assertFalse(draft.input().has("claims"));
      assertTrue(verify.input().path("claims").isArray());
    }
  }

  private static void assertAnswered(JsonNode answer, String document, String mode) {
    assertEquals(7, answer.size());
    assertEquals("answered", answer.path("status").asString(), answer.toString());
    assertEquals(mode, answer.path("mode").asString());
    assertEquals(1, answer.path("citations").size());
    assertEquals(document, answer.path("citations").get(0).path("document_id").asString());
    assertTrue(answer.path("reason_code").isNull());
    assertEquals("java-video-av-answer-v1", answer.path("policy_revision").asString());
  }

  private static void assertCitation(
      JsonNode citation,
      JsonNode upload,
      JsonNode index,
      VideoAvCompilation compilation,
      String question) {
    assertEquals(20, citation.size());
    assertEquals("video_av_window", citation.path("kind").asString());
    assertEquals(upload.path("document_id"), citation.path("document_id"));
    assertEquals(upload.path("source_revision_id"), citation.path("revision_id"));
    assertEquals(upload.path("source_sha256"), citation.path("source_sha256"));
    assertEquals(index.path("publication_id"), citation.path("publication_id"));
    assertEquals(index.path("profile_fingerprint"), citation.path("profile_fingerprint"));
    assertEquals(index.path("model_revision"), citation.path("analysis_model_revision"));
    assertEquals("server_window", citation.path("time_precision").asString());
    var epoch = citation.path("epoch");
    assertEquals(4, epoch.size());
    assertEquals(Long.toString(compilation.epoch().sourceFirstPts()), epoch.path("pts").asString());
    assertEquals(
        Long.toString(compilation.epoch().sourceTimeBaseNumerator()),
        epoch.path("time_base_num").asString());
    assertEquals(
        Long.toString(compilation.epoch().sourceTimeBaseDenominator()),
        epoch.path("time_base_den").asString());
    assertEquals(
        Long.toString(compilation.epoch().ticksPerSecond()),
        epoch.path("ticks_per_second").asString());
    var window = citation.path("window");
    assertEquals(8, window.size());
    var expected = compilation.windows().get(window.path("ordinal").asInt());
    assertEquals(Long.toString(expected.startTick()), window.path("start_tick").asString());
    assertEquals(Long.toString(expected.endTick()), window.path("end_tick").asString());
    assertEquals(
        compilation.epoch().startMs(expected.startTick()), window.path("start_ms").asLong());
    assertEquals(compilation.epoch().endMs(expected.endTick()), window.path("end_ms").asLong());
    if (expected.video() == null) {
      assertTrue(window.path("video").isNull());
    } else {
      assertEquals(5, window.path("video").size());
      assertEquals(expected.video().sha256(), window.path("video").path("clip_sha256").asString());
      assertEquals(
          expected.video().framesManifestSha256(),
          window.path("video").path("frames_manifest_sha256").asString());
      assertEquals(expected.video().frameCount(), window.path("video").path("frame_count").asInt());
    }
    if (expected.audio() == null) {
      assertTrue(window.path("audio").isNull());
    } else {
      assertEquals(5, window.path("audio").size());
      assertEquals(
          expected.audio().pcmSha256(), window.path("audio").path("pcm_sha256").asString());
      assertEquals(
          ModelValues.sha256(expected.audio().wav()),
          window.path("audio").path("wav_sha256").asString());
      assertEquals(
          Long.toString(expected.audio().startSample()),
          window.path("audio").path("start_sample").asString());
      assertEquals(
          Long.toString(expected.audio().endSample()),
          window.path("audio").path("end_sample").asString());
    }
    var facts = new ArrayList<VideoAvFact>();
    for (var item : citation.path("facts")) {
      assertEquals(5, item.size());
      var requirement = VideoAvRequirement.valueOf(item.path("requirement").asString());
      assertEquals(
          VideoAvProofIdentity.stableFactId(
              ModelValues.sha256(question.getBytes(StandardCharsets.UTF_8)),
              facts.size(),
              item.path("text").asString(),
              requirement),
          item.path("id").asString());
      facts.add(
          new VideoAvFact(
              item.path("id").asString(),
              item.path("text").asString(),
              requirement,
              item.path("visual_contribution").asBoolean(),
              item.path("audio_contribution").asBoolean()));
    }
    assertEquals(VideoAvProofIdentity.factsSha256(facts), citation.path("facts_sha256").asString());
  }

  private static JsonNode query(
      HttpClient http,
      String base,
      String actor,
      String mode,
      String question,
      List<String> documents,
      int expected)
      throws Exception {
    var body = new LinkedHashMap<String, Object>();
    body.put("question", question);
    body.put("mode", mode);
    if (documents != null) {
      body.put("document_ids", documents);
    }
    return json(
        http,
        base,
        actor,
        "POST",
        "/v1/video-av-answers",
        JSON.writeValueAsBytes(body),
        "application/json",
        expected);
  }

  private static JsonNode attachedQuery(
      HttpClient http,
      String base,
      String actor,
      String mode,
      String question,
      List<String> documents,
      List<Fixture> references,
      int expected)
      throws Exception {
    var body = new LinkedHashMap<String, Object>();
    body.put("question", question);
    body.put("mode", mode);
    if (documents != null) {
      body.put("document_ids", documents);
    }
    body.put(
        "attachments",
        references.stream()
            .map(
                reference ->
                    Map.of(
                        "filename", reference.path().getFileName().toString(),
                        "media_type", reference.mime(),
                        "content_base64", Base64.getEncoder().encodeToString(reference.bytes())))
            .toList());
    return json(
        http,
        base,
        actor,
        "POST",
        "/v1/video-av-query-answers",
        JSON.writeValueAsBytes(body),
        "application/json",
        expected);
  }

  private static void assertQueryManifests(
      JsonNode answer,
      List<Fixture> references,
      Map<String, VideoAvCompilation> compiled,
      String mode,
      boolean prepared) {
    assertEquals(mode, answer.path("mode").asString());
    assertEquals(mode, answer.path("result").path("mode").asString());
    assertEquals(references.size(), answer.path("query_attachments").size());
    for (int ordinal = 0; ordinal < references.size(); ordinal++) {
      var receipt = answer.path("query_attachments").get(ordinal);
      var reference = references.get(ordinal);
      assertEquals(
          Set.of(
              "ordinal",
              "source_sha256",
              "media_kind",
              "compiler_revision",
              "content_sha256",
              "window_count",
              "visual_window_count",
              "audio_window_count",
              "audio_present",
              "used_mode",
              "status"),
          new HashSet<>(receipt.propertyNames()));
      assertEquals(ordinal, receipt.path("ordinal").asInt());
      assertEquals(ModelValues.sha256(reference.bytes()), receipt.path("source_sha256").asString());
      assertEquals("video", receipt.path("media_kind").asString());
      assertEquals(mode, receipt.path("used_mode").asString());
      assertTrue(
          receipt.path("compiler_revision").asString().startsWith("java-video-av-compilation-v1:"));
      assertEquals(prepared ? "prepared" : "not_prepared", receipt.path("status").asString());
      if (prepared) {
        var compilation = compiled.get(reference.name());
        assertTrue(receipt.path("content_sha256").asString().matches("[a-f0-9]{64}"));
        assertEquals(compilation.windows().size(), receipt.path("window_count").asInt());
        assertEquals(
            compilation.windows().stream().filter(window -> window.video() != null).count(),
            receipt.path("visual_window_count").asInt());
        assertEquals(
            compilation.windows().stream().filter(window -> window.audio() != null).count(),
            receipt.path("audio_window_count").asInt());
        assertEquals(compilation.hasAudio(), receipt.path("audio_present").asBoolean());
      } else {
        for (String key :
            List.of(
                "content_sha256",
                "window_count",
                "visual_window_count",
                "audio_window_count",
                "audio_present")) {
          assertTrue(
              receipt.path(key).isNull(), "Not-prepared inputs must not invent media metadata");
        }
      }
    }
  }

  private static void assertQueryTraceStored(
      Path data, JsonNode answer, String question, JsonNode indexed) throws Exception {
    String trace = answer.path("result").path("answer_id").asString();
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + data.resolve("java-library.db"));
        var header =
            connection.prepareStatement(
                "SELECT * FROM video_av_query_preparations WHERE trace_id=?");
        var children =
            connection.prepareStatement(
                "SELECT * FROM video_av_query_attachments WHERE trace_id=? ORDER BY ordinal")) {
      header.setString(1, trace);
      try (var row = header.executeQuery()) {
        assertTrue(row.next(), "HTTP query must persist its preparation beside the terminal trace");
        assertEquals(answer.path("query_attachments").size(), row.getInt("attachment_count"));
        assertEquals(answer.path("mode").asString(), row.getString("mode"));
        assertEquals(
            ModelValues.sha256(question.getBytes(StandardCharsets.UTF_8)),
            row.getString("question_sha256"));
        assertEquals("java-video-av-query-preparation-v1", row.getString("preparation_revision"));
        assertEquals(
            indexed.path("embedding_model_revision").asString(),
            row.getString("embedding_revision"));
        assertEquals(
            indexed.path("profile_fingerprint").asString(), row.getString("profile_fingerprint"));
        assertTrue(row.getString("manifest_sha256").matches("[a-f0-9]{64}"));
        assertEquals(
            8,
            row.getMetaData().getColumnCount(),
            "Preparation records cannot contain a question or media payload");
        assertFalse(row.next());
      }
      children.setString(1, trace);
      try (var rows = children.executeQuery()) {
        int count = 0;
        while (rows.next()) {
          var expected = answer.path("query_attachments").get(count++);
          assertEquals(12, rows.getMetaData().getColumnCount());
          assertEquals(expected.path("ordinal").asInt(), rows.getInt("ordinal"));
          for (String key :
              List.of("source_sha256", "compiler_revision", "media_kind", "used_mode", "status")) {
            assertEquals(expected.path(key).asString(), rows.getString(key));
          }
          assertEquals(
              expected.path("content_sha256").isNull()
                  ? null
                  : expected.path("content_sha256").asString(),
              rows.getString("content_sha256"));
          for (String key : List.of("window_count", "visual_window_count", "audio_window_count")) {
            if (expected.path(key).isNull()) {
              assertEquals(null, rows.getObject(key));
            } else {
              assertEquals(expected.path(key).asInt(), rows.getInt(key));
            }
          }
          if (expected.path("audio_present").isNull()) {
            assertEquals(null, rows.getObject("audio_present"));
          } else {
            assertEquals(
                expected.path("audio_present").asBoolean(), rows.getBoolean("audio_present"));
          }
        }
        assertEquals(
            answer.path("query_attachments").size(),
            count,
            "Prepared and not-prepared terminal traces keep every requested input identity");
      }
    }
  }

  private static JsonNode upload(
      HttpClient http, String base, String actor, String filename, byte[] source) throws Exception {
    var request =
        builder(base, actor, "/v1/video-av-documents")
            .header(
                "X-Filename",
                URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20"))
            .header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray(source))
            .build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(201, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
    return JSON.readTree(response.body());
  }

  private static void assertOriginal(
      HttpClient http, String base, String contentUrl, byte[] original, String mime)
      throws Exception {
    var response = request(http, base, "owner", "GET", contentUrl, null, null);
    assertEquals(200, response.statusCode());
    assertArrayEquals(original, response.body());
    assertEquals(mime, response.headers().firstValue("Content-Type").orElseThrow());
    assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
    var range =
        http.send(
            builder(base, "owner", contentUrl).header("Range", "bytes=7-31").GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(206, range.statusCode());
    assertEquals(
        "bytes 7-31/" + original.length, range.headers().firstValue("Content-Range").orElseThrow());
    assertArrayEquals(Arrays.copyOfRange(original, 7, 32), range.body());
  }

  private static JsonNode json(
      HttpClient http,
      String base,
      String actor,
      String method,
      String path,
      byte[] body,
      String type,
      int status)
      throws Exception {
    var response = request(http, base, actor, method, path, body, type);
    assertEquals(
        status, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
    return JSON.readTree(response.body());
  }

  private static HttpRequest.Builder builder(String base, String actor, String path) {
    return HttpRequest.newBuilder(URI.create(base + path))
        .timeout(Duration.ofSeconds(130))
        .header("Origin", base)
        .header("X-Workspace-Id", "org-main")
        .header("X-Principal-Id", actor);
  }

  private static HttpResponse<byte[]> request(
      HttpClient http,
      String base,
      String actor,
      String method,
      String path,
      byte[] body,
      String type)
      throws Exception {
    var request = builder(base, actor, path);
    if (type != null) {
      request.header("Content-Type", type);
    }
    return http.send(
        request
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body))
            .build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }

  private static final class Providers implements AutoCloseable {
    private static final Pattern SCOPE =
        Pattern.compile(
            "\\(document_id == \"([A-Za-z0-9._:-]+)\" && revision_id == \"([A-Za-z0-9._:-]+)\"\\)");
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, JsonNode> schemas = new ConcurrentHashMap<>();
    private final Map<String, Map<String, JsonNode>> rows = new ConcurrentHashMap<>();
    private final List<Call> requests = new CopyOnWriteArrayList<>();
    private final List<Media> media = new CopyOnWriteArrayList<>();
    private final List<String> textQuestions = new CopyOnWriteArrayList<>();
    private final List<Assessment> drafts = new CopyOnWriteArrayList<>();
    private final List<Assessment> verifications = new CopyOnWriteArrayList<>();
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();
    private final Map<String, byte[]> expectedMedia = new HashMap<>();
    private final List<VideoAvCompilation> expectedCompilations;
    private volatile Set<String> expectedScope = Set.of();
    private volatile boolean textMiss;

    private record Call(String path, JsonNode body) {}

    private record Media(String mime, byte[] bytes) {}

    private record Assessment(JsonNode input, byte[] video, byte[] wav) {}

    Providers(List<VideoAvCompilation> expectedCompilations) throws IOException {
      this.expectedCompilations = List.copyOf(expectedCompilations);
      for (var compilation : expectedCompilations) {
        for (var window : compilation.windows()) {
          if (window.video() != null) {
            expectedMedia.put("video/mp4:" + window.video().sha256(), window.video().content());
          }
          if (window.audio() != null) {
            expectedMedia.put(
                "audio/wav:" + ModelValues.sha256(window.audio().wav()), window.audio().wav());
          }
        }
      }
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext("/", this::serve);
      server.start();
    }

    URI endpoint() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    void assertHealthy() {
      assertTrue(failures.isEmpty(), failures.toString());
    }

    private void serve(HttpExchange exchange) throws IOException {
      try (exchange) {
        try {
          assertEquals("POST", exchange.getRequestMethod());
          assertEquals(null, exchange.getRequestURI().getRawQuery());
          String path = exchange.getRequestURI().getPath();
          byte[] serialized = exchange.getRequestBody().readAllBytes();
          JsonNode body = JSON.readTree(serialized);
          requests.add(new Call(path, body));
          Object response;
          if (path.equals("/v1beta/models/fixture-video-av-embedding:embedContent")) {
            googleHeaders(exchange);
            assertTrue(serialized.length <= 14 * 1024 * 1024);
            response = embed(body);
          } else if (path.equals("/v1beta/interactions")) {
            googleHeaders(exchange);
            assertTrue(serialized.length <= 14 * 1024 * 1024);
            response = interaction(body);
          } else {
            assertTrue(
                path.startsWith("/v2/vectordb/"),
                "No ASR, describe, chat or unknown route is permitted: " + path);
            assertEquals(
                "Bearer synthetic-fixture-credential",
                exchange.getRequestHeaders().getFirst("Authorization"));
            response = Map.of("code", 0, "data", milvus(path, body));
          }
          byte[] bytes = JSON.writeValueAsBytes(response);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
        } catch (Throwable failure) {
          failures.add(failure);
          exchange.sendResponseHeaders(500, -1);
        }
      }
    }

    private static void googleHeaders(HttpExchange exchange) {
      assertEquals(
          "synthetic-fixture-credential", exchange.getRequestHeaders().getFirst("x-goog-api-key"));
      assertEquals(null, exchange.getRequestHeaders().getFirst("Authorization"));
    }

    private Object embed(JsonNode body) {
      assertEquals(Set.of("content", "embedContentConfig"), new HashSet<>(body.propertyNames()));
      assertEquals(Set.of("parts"), new HashSet<>(body.path("content").propertyNames()));
      assertEquals(1, body.path("content").path("parts").size());
      assertEquals(
          Set.of("outputDimensionality", "autoTruncate"),
          new HashSet<>(body.path("embedContentConfig").propertyNames()));
      assertEquals(2, body.path("embedContentConfig").path("outputDimensionality").asInt());
      assertFalse(body.path("embedContentConfig").path("autoTruncate").asBoolean());
      var part = body.path("content").path("parts").get(0);
      if (part.has("text")) {
        assertEquals(Set.of("text"), new HashSet<>(part.propertyNames()));
        assertTrue(
            QUESTIONS.contains(part.path("text").asString()),
            "The exact whole question, including its final condition, must be embedded without a prefix");
        textQuestions.add(part.path("text").asString());
      } else {
        assertEquals(Set.of("inlineData"), new HashSet<>(part.propertyNames()));
        var raw = part.path("inlineData");
        assertEquals(Set.of("mimeType", "data"), new HashSet<>(raw.propertyNames()));
        String mime = raw.path("mimeType").asString();
        byte[] bytes = Base64.getDecoder().decode(raw.path("data").asString());
        assertMedia(mime, bytes);
        media.add(new Media(mime, bytes));
      }
      return Map.of(
          "embedding",
          Map.of("values", textMiss && part.has("text") ? List.of(0.0, 1.0) : List.of(1.0, 0.0)),
          "usageMetadata",
          Map.of());
    }

    private void assertMedia(String mime, byte[] actual) {
      var expected = expectedMedia.get(mime + ":" + ModelValues.sha256(actual));
      assertNotNull(
          expected,
          "A provider must receive one complete actual prepared material, not a sampled image, excerpt or transcoded substitute");
      assertArrayEquals(expected, actual);
      if (mime.equals("audio/wav")) {
        byte[] pcm = Arrays.copyOfRange(actual, 44, actual.length);
        assertArrayEquals(AudioPcm.wav(pcm, 0, pcm.length), actual);
      } else {
        assertEquals("video/mp4", mime);
      }
    }

    private Object interaction(JsonNode body) throws Exception {
      assertEquals(
          Set.of(
              "model",
              "store",
              "stream",
              "background",
              "system_instruction",
              "input",
              "generation_config",
              "response_format"),
          new HashSet<>(body.propertyNames()));
      assertEquals("fixture-video-av", body.path("model").asString());
      assertFalse(body.path("store").asBoolean());
      assertFalse(body.path("stream").asBoolean());
      assertFalse(body.path("background").asBoolean());
      var parts = body.path("input");
      assertTrue(parts.isArray() && parts.size() >= 2 && parts.size() <= 3);
      var last = parts.get(parts.size() - 1);
      assertEquals(Set.of("type", "text"), new HashSet<>(last.propertyNames()));
      assertEquals("text", last.path("type").asString());
      var input = JSON.readTree(last.path("text").asString());
      String question = input.path("question").asString();
      String mode = input.path("mode").asString();
      assertTrue(QUESTIONS.contains(question));
      assertTrue(Set.of("VISUAL", "AUDIO", "JOINT").contains(mode));
      var allowed = new HashSet<>(Set.of("question", "mode", "epoch", "window"));
      if (!mode.equals("AUDIO")) {
        allowed.add("video");
      }
      if (!mode.equals("VISUAL")) {
        allowed.add("audio");
      }
      if (input.has("claims")) {
        allowed.add("claims");
      }
      assertEquals(allowed, new HashSet<>(input.propertyNames()));
      byte[] video = null;
      byte[] wav = null;
      for (int i = 0; i < parts.size() - 1; i++) {
        var part = parts.get(i);
        if (part.path("type").asString().equals("video")) {
          assertEquals(
              Set.of("type", "mime_type", "data", "processing"),
              new HashSet<>(part.propertyNames()));
          assertEquals(
              JSON.valueToTree(Map.of("type", "static", "fps", 1)), part.path("processing"));
          assertEquals("video/mp4", part.path("mime_type").asString());
          assertEquals(null, video);
          video = Base64.getDecoder().decode(part.path("data").asString());
          assertMedia("video/mp4", video);
        } else {
          assertEquals(Set.of("type", "mime_type", "data"), new HashSet<>(part.propertyNames()));
          assertEquals("audio", part.path("type").asString());
          assertEquals("audio/wav", part.path("mime_type").asString());
          assertEquals(null, wav);
          wav = Base64.getDecoder().decode(part.path("data").asString());
          assertMedia("audio/wav", wav);
        }
      }
      assertEquals(mode.equals("AUDIO"), video == null);
      assertEquals(mode.equals("VISUAL"), wav == null);
      assertPreparedWindow(input, video, wav);
      assertEquals(
          JSON.valueToTree(
              Map.of(
                  "max_output_tokens", 8192, "thinking_summaries", "none", "tool_choice", "none")),
          body.path("generation_config"));
      var format = body.path("response_format");
      assertEquals(Set.of("type", "mime_type", "schema"), new HashSet<>(format.propertyNames()));
      assertEquals("text", format.path("type").asString());
      assertEquals("application/json", format.path("mime_type").asString());
      assertEquals("object", format.path("schema").path("type").asString());
      assertFalse(format.path("schema").path("additionalProperties").asBoolean());
      boolean verify = input.has("claims");
      Object result;
      if (verify) {
        assertTrue(
            body.path("system_instruction")
                .asString()
                .contains("Relationship questions require JOINT relational facts"));
        var expected = claims(mode, question);
        assertEquals(expected.size(), input.path("claims").size());
        var support = new ArrayList<Object>();
        for (int i = 0; i < expected.size(); i++) {
          var claim = input.path("claims").get(i);
          assertEquals(Set.of("id", "text", "requirement"), new HashSet<>(claim.propertyNames()));
          assertEquals(expected.get(i).get("text"), claim.path("text").asString());
          String requirement = expected.get(i).get("requirement");
          assertEquals(requirement, claim.path("requirement").asString());
          String id =
              VideoAvProofIdentity.stableFactId(
                  ModelValues.sha256(question.getBytes(StandardCharsets.UTF_8)),
                  i,
                  claim.path("text").asString(),
                  VideoAvRequirement.valueOf(requirement));
          assertEquals(id, claim.path("id").asString());
          support.add(
              Map.of(
                  "id",
                  id,
                  "supported",
                  true,
                  "visual_contribution",
                  !requirement.equals("AUDIO"),
                  "audio_contribution",
                  !requirement.equals("VISUAL")));
        }
        verifications.add(new Assessment(input, video, wav));
        result = Map.of("complete", !question.equals(RELATION_QUESTION), "support", support);
      } else {
        assertTrue(body.path("system_instruction").asString().contains("Do not assign fact IDs"));
        drafts.add(new Assessment(input, video, wav));
        result = Map.of("complete", true, "claims", claims(mode, question));
      }
      return Map.of(
          "id",
          "synthetic-av-interaction",
          "model",
          "fixture-video-av",
          "status",
          "completed",
          "steps",
          List.of(
              Map.of("type", "processing_call", "id", "media"),
              Map.of("type", "processing_result", "call_id", "media"),
              Map.of(
                  "type",
                  "model_output",
                  "content",
                  List.of(Map.of("type", "text", "text", JSON.writeValueAsString(result))))));
    }

    private void assertPreparedWindow(JsonNode input, byte[] video, byte[] wav) {
      var epoch = input.path("epoch");
      var time = input.path("window");
      assertEquals(
          Set.of("pts", "time_base_num", "time_base_den", "ticks_per_second"),
          new HashSet<>(epoch.propertyNames()));
      assertEquals(Set.of("start_tick", "end_tick"), new HashSet<>(time.propertyNames()));
      for (var compilation : expectedCompilations) {
        var actualEpoch = compilation.epoch();
        if (!Long.toString(actualEpoch.sourceFirstPts()).equals(epoch.path("pts").asString())
            || !Long.toString(actualEpoch.sourceTimeBaseNumerator())
                .equals(epoch.path("time_base_num").asString())
            || !Long.toString(actualEpoch.sourceTimeBaseDenominator())
                .equals(epoch.path("time_base_den").asString())
            || !Long.toString(actualEpoch.ticksPerSecond())
                .equals(epoch.path("ticks_per_second").asString())) {
          continue;
        }
        for (var window : compilation.windows()) {
          if (!Long.toString(window.startTick()).equals(time.path("start_tick").asString())
              || !Long.toString(window.endTick()).equals(time.path("end_tick").asString())
              || video != null
                  && (window.video() == null || !Arrays.equals(video, window.video().content()))
              || wav != null
                  && (window.audio() == null || !Arrays.equals(wav, window.audio().wav()))) {
            continue;
          }
          if (video != null) {
            var metadata = input.path("video");
            assertEquals(
                Set.of("first_local_tick", "end_local_tick", "clip_sha256"),
                new HashSet<>(metadata.propertyNames()));
            assertEquals(window.video().sha256(), metadata.path("clip_sha256").asString());
            assertEquals(
                Long.toString(window.video().firstLocalTick()),
                metadata.path("first_local_tick").asString());
            assertEquals(
                Long.toString(window.video().endLocalTick()),
                metadata.path("end_local_tick").asString());
          }
          if (wav != null) {
            var metadata = input.path("audio");
            var audio = window.audio();
            assertEquals(
                Set.of(
                    "start_sample", "end_sample", "sample_rate", "first_local_tick", "pcm_sha256"),
                new HashSet<>(metadata.propertyNames()));
            assertEquals(
                Long.toString(audio.startSample()), metadata.path("start_sample").asString());
            assertEquals(Long.toString(audio.endSample()), metadata.path("end_sample").asString());
            assertEquals(16000, metadata.path("sample_rate").asInt());
            assertEquals(audio.pcmSha256(), metadata.path("pcm_sha256").asString());
            assertEquals(
                BigInteger.valueOf(audio.startSample())
                    .multiply(BigInteger.valueOf(actualEpoch.ticksPerSecond() / 16000))
                    .subtract(BigInteger.valueOf(window.startTick()))
                    .toString(),
                metadata.path("first_local_tick").asString());
          }
          return;
        }
      }
      throw new AssertionError(
          "The model request must bind a complete real window and its exact original epoch");
    }

    private static List<Map<String, String>> claims(String mode, String question) {
      if (mode.equals("VISUAL")) {
        return List.of(Map.of("text", VISUAL_FACT, "requirement", "VISUAL"));
      }
      if (mode.equals("AUDIO")) {
        return List.of(Map.of("text", AUDIO_FACT, "requirement", "AUDIO"));
      }
      if (question.equals(INDEPENDENT_QUESTION) || question.equals(RELATION_QUESTION)) {
        return List.of(
            Map.of("text", VISUAL_FACT, "requirement", "VISUAL"),
            Map.of("text", AUDIO_FACT, "requirement", "AUDIO"));
      }
      return List.of(Map.of("text", JOINT_FACT, "requirement", "JOINT"));
    }

    private Object milvus(String path, JsonNode body) {
      String collection = body.path("collectionName").asString();
      assertTrue(Set.of(VISUAL_COLLECTION, AUDIO_COLLECTION).contains(collection));
      assertEquals("default", body.path("dbName").asString());
      if (path.endsWith("collections/has")) {
        return Map.of("has", schemas.containsKey(collection));
      }
      if (path.endsWith("collections/create")) {
        assertFalse(schemas.containsKey(collection));
        schemas.put(collection, body);
        rows.put(collection, new ConcurrentHashMap<>());
        return Map.of();
      }
      if (path.endsWith("collections/describe")) {
        return description(schemas.get(collection));
      }
      if (path.endsWith("indexes/describe")) {
        boolean dense = body.path("indexName").asString().equals("dense_index");
        return List.of(
            Map.of(
                "indexName",
                dense ? "dense_index" : "sparse_index",
                "fieldName",
                dense ? "dense" : "sparse",
                "indexType",
                dense ? "FLAT" : "SPARSE_INVERTED_INDEX",
                "metricType",
                dense ? "COSINE" : "BM25",
                "indexState",
                "Finished"));
      }
      if (path.endsWith("collections/load")) {
        return Map.of();
      }
      if (path.endsWith("entities/upsert")) {
        var ids = new ArrayList<String>();
        for (var row : body.path("data")) {
          String id = row.path("id").asString();
          assertEquals("org-main", row.path("workspace_id").asString());
          assertTrue(row.path("text").asString().matches("[0-9a-f]{64}"));
          rows.get(collection).put(id, row);
          ids.add(id);
        }
        return Map.of("upsertCount", ids.size(), "upsertIds", ids);
      }
      if (path.endsWith("entities/query")) {
        String filter = body.path("filter").asString();
        Set<String> ids = new HashSet<>();
        String revision = null;
        if (filter.startsWith("id in [")) {
          JSON.readTree(filter.substring(6)).forEach(id -> ids.add(id.asString()));
        } else {
          var matcher = Pattern.compile("revision_id == \"([^\"]+)\"").matcher(filter);
          assertTrue(
              matcher.matches(),
              "Receipt query must specify an exact generation or complete ID set");
          revision = matcher.group(1);
        }
        var selected = new ArrayList<Object>();
        for (var row : rows.get(collection).values()) {
          if ((!ids.isEmpty() && !ids.contains(row.path("id").asString()))
              || (revision != null && !revision.equals(row.path("revision_id").asString()))) {
            continue;
          }
          var fields = new LinkedHashMap<String, Object>();
          body.path("outputFields")
              .forEach(field -> fields.put(field.asString(), row.path(field.asString())));
          selected.add(fields);
        }
        return selected;
      }
      if (path.endsWith("entities/search")) {
        return search(collection, body);
      }
      throw new AssertionError("Unexpected Milvus route " + path);
    }

    private List<Object> search(String collection, JsonNode request) {
      assertEquals("dense", request.path("annsField").asString());
      assertEquals(1, request.path("data").size());
      assertEquals(64, request.path("limit").asInt());
      String filter = request.path("filter").asString();
      assertTrue(filter.contains("workspace_id == \"org-main\""));
      var scope = new HashMap<String, String>();
      var matcher = SCOPE.matcher(filter);
      while (matcher.find()) {
        scope.put(matcher.group(1), matcher.group(2));
      }
      assertFalse(scope.isEmpty(), "Every dense route applies the complete authorized scope");
      assertEquals(expectedScope, scope.keySet());
      var query = request.path("data").get(0);
      var scored = new ArrayList<Scored>();
      for (var row : rows.get(collection).values()) {
        if (!row.path("workspace_id").asString().equals("org-main")
            || !row.path("revision_id")
                .asString()
                .equals(scope.get(row.path("document_id").asString()))) {
          continue;
        }
        double dot =
            row.path("dense").get(0).asDouble() * query.get(0).asDouble()
                + row.path("dense").get(1).asDouble() * query.get(1).asDouble();
        if (dot > 0.5) {
          scored.add(new Scored(row, dot));
        }
      }
      scored.sort(
          Comparator.comparingDouble(Scored::similarity)
              .reversed()
              .thenComparing(value -> value.row().path("id").asString()));
      var result = new ArrayList<Object>();
      for (var hit : scored) {
        var row = hit.row();
        result.add(
            Map.of(
                "id",
                row.path("id").asString(),
                "workspace_id",
                "org-main",
                "document_id",
                row.path("document_id").asString(),
                "revision_id",
                row.path("revision_id").asString(),
                "distance",
                hit.similarity()));
      }
      return result;
    }

    private record Scored(JsonNode row, double similarity) {}

    private static Map<String, Object> description(JsonNode creation) {
      var fields = new ArrayList<Object>();
      for (var field : creation.path("schema").path("fields")) {
        var params = new ArrayList<Object>();
        for (var entry : field.path("elementTypeParams").properties()) {
          params.add(
              Map.of(
                  "key",
                  entry.getKey(),
                  "value",
                  entry.getValue().isString()
                      ? entry.getValue().asString()
                      : entry.getValue().toString()));
        }
        var value = new LinkedHashMap<String, Object>();
        value.put("name", field.path("fieldName").asString());
        value.put("type", field.path("dataType").asString());
        value.put("primaryKey", field.path("isPrimary").asBoolean(false));
        value.put("autoId", false);
        value.put("nullable", false);
        value.put("params", params);
        if (field.path("fieldName").asString().equals("sparse")) {
          value.put("isFunctionOutput", true);
        }
        fields.add(value);
      }
      return Map.of(
          "collectionName",
          creation.path("collectionName"),
          "description",
          creation.path("description"),
          "consistencyLevel",
          "Strong",
          "autoId",
          false,
          "enableDynamicField",
          false,
          "fields",
          fields,
          "functions",
          List.of(
              Map.of(
                  "name",
                  "text_bm25",
                  "type",
                  "BM25",
                  "inputFieldNames",
                  List.of("text"),
                  "outputFieldNames",
                  List.of("sparse"))));
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
