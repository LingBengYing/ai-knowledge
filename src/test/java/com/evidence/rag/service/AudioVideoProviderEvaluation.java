package com.evidence.rag.service;

import com.evidence.rag.client.model.AudioModels;
import com.evidence.rag.client.model.FactTextModels;
import com.evidence.rag.client.model.FactVisionModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.model.VisionModels;
import com.evidence.rag.model.domain.DecodedAudio;
import com.evidence.rag.model.domain.DecodedVideo;
import com.evidence.rag.model.domain.GroundingQuote;
import com.evidence.rag.model.domain.GroundingText;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.QuestionFact;
import com.evidence.rag.model.domain.VideoAssessment;
import com.evidence.rag.model.domain.VideoEvidence;
import com.evidence.rag.model.domain.VisualImage;
import com.evidence.rag.tool.answer.QuestionPlanning;
import com.evidence.rag.tool.answer.TextGrounding;
import com.evidence.rag.worker.parser.AudioDecoder;
import com.evidence.rag.worker.parser.ProcessAudioDecoder;
import com.evidence.rag.worker.parser.ProcessVideoDecoder;
import com.evidence.rag.worker.parser.VideoDecoder;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/** Test-only, fixed-sample evaluation; no authority, retrieval, HTTP publication or retries. */
final class AudioVideoProviderEvaluation {
  static final String AUDIO_SHA =
      "55a1ea2b5d3902d9cbc9edf507849fb90c54e53a0c777b779f625e6b3d63d2cb";
  static final String VIDEO_SHA =
      "27ece355eec482193d80e07ee1703c57b8936a7dd7aa0d6d099776d3cc7f5e61";
  static final String AUDIO_QUESTION = "蓝图计划的备用泵编号是什么？";
  static final String VIDEO_QUESTION = "蓝图计划的设备识别码是什么？蓝图计划的巡检窗口是什么？";
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration STAGE_BUDGET = Duration.ofMinutes(10);
  private static final String PREFIX = "RAG_AUDIO_VIDEO_IT_";

  private AudioVideoProviderEvaluation() {}

  static LiveSettings liveSettings(Map<String, String> environment) {
    int approved;
    try {
      approved = Integer.parseInt(required(environment, PREFIX + "APPROVED_CALLS"));
    } catch (NumberFormatException invalid) {
      throw new AssertionError("eval_invalid_approval");
    }
    require(approved >= 1 && approved <= 30, "eval_invalid_approval");
    return new LiveSettings(
        approved,
        required(environment, PREFIX + "API_KEY"),
        required(environment, PREFIX + "ASR_MODEL"),
        required(environment, PREFIX + "VISION_MODEL"),
        required(environment, PREFIX + "GENERATION_MODEL"),
        Path.of(required(environment, "RAG_VIDEO_DECODER_IT_FFMPEG")),
        Path.of(required(environment, "RAG_VIDEO_DECODER_IT_FFPROBE")));
  }

  static String required(Map<String, String> environment, String name) {
    String value = environment.get(name);
    require(value != null && !value.isBlank(), "eval_missing_configuration");
    return value;
  }

  static Prepared prepare(Path ffmpeg, Path ffprobe, int approved) throws IOException {
    require(approved >= 1 && approved <= 30, "eval_invalid_approval");
    byte[] audio = resource("synthetic-audio.wav", AUDIO_SHA);
    byte[] video = resource("synthetic-video.mp4", VIDEO_SHA);
    try (var audioDecoder = new ProcessAudioDecoder(ffmpeg, ffprobe, REQUEST_TIMEOUT);
        var videoDecoder = new ProcessVideoDecoder(ffmpeg, ffprobe, REQUEST_TIMEOUT, 2, true)) {
      var decodedAudio = audioDecoder.decode("synthetic-audio.wav", "audio/wav", audio);
      var decodedVideo = videoDecoder.decode("synthetic-video.mp4", "video/mp4", video);
      require(AUDIO_SHA.equals(decodedAudio.sourceSha256()), "eval_audio_identity");
      require(VIDEO_SHA.equals(decodedVideo.sourceSha256()), "eval_video_identity");
      require(decodedVideo.audio() != null, "eval_missing_video_audio");
      int maximum =
          preflight(
              approved,
              decodedAudio.durationMs(),
              decodedVideo.audio().durationMs(),
              decodedVideo.frames().size(),
              VIDEO_QUESTION);
      return new Prepared(audio, video, decodedAudio, decodedVideo, maximum);
    }
  }

  static int preflight(int approved, long audioMs, long videoAudioMs, int frames, String question) {
    require(approved >= 1 && approved <= 30, "eval_invalid_approval");
    require(audioMs > 0 && audioMs <= 30_000, "eval_audio_duration");
    require(videoAudioMs > 0 && videoAudioMs <= 30_000, "eval_video_audio_duration");
    require(frames > 0 && frames <= 128, "eval_frame_count");
    var plan = QuestionPlanning.plan(question);
    require(plan.isPresent() && plan.orElseThrow().facts().size() == 2, "eval_fact_count");
    // Audio ASR/extract (2), video ASR (1), all descriptions (F), two draft/verify/extract (6).
    int maximum = frames + 9;
    require(maximum <= approved, "eval_insufficient_approval");
    return maximum;
  }

  static Result evaluate(
      Prepared prepared,
      Budget budget,
      AudioModels audio,
      TextModels text,
      FactTextModels factText,
      VisionModels vision,
      FactVisionModels factVision) {
    require(budget.calls() == 0 && prepared.maximumCalls() <= budget.limit, "eval_budget_state");
    var countedAudio = new CountedAudio(audio, budget);
    var countedText = new CountedText(text, factText, budget);
    var countedVision = new CountedVision(vision, factVision, budget);
    var audioCompiler =
        new AudioCompilationService(prepared.audioDecoder(), countedAudio, 30, STAGE_BUDGET);
    var compiledAudio =
        audioCompiler.compile(
            "synthetic-audio.wav", "audio/wav", prepared.audioBytes(), () -> true);
    require(compiledAudio.spans().size() == 1, "eval_audio_incomplete");
    String transcript = compiledAudio.spans().getFirst().text();
    var material =
        new GroundingText(
            "eval-audio-span",
            "eval-audio-context",
            transcript,
            ModelValues.sha256(transcript.getBytes(StandardCharsets.UTF_8)),
            0,
            transcript.codePointCount(0, transcript.length()));
    var extraction =
        countedText.extract(
            AUDIO_QUESTION, List.of(new TextModels.Evidence(material.physicalId(), transcript)));
    require(!extraction.refused(), "eval_audio_refused");
    var grounded =
        new TextGrounding()
            .verifyText(
                AUDIO_QUESTION,
                List.of(material),
                extraction.quotes().stream()
                    .map(q -> new GroundingQuote(q.evidenceId(), q.quote()))
                    .toList());
    require(grounded.supported(), "eval_audio_not_grounded");
    require(
        audioGold(String.join(" ", grounded.quotes().stream().map(q -> q.quote()).toList())),
        "eval_audio_gold_mismatch");

    var videoCompiler =
        new VideoCompilationService(
            prepared.videoDecoder(),
            new AudioTranscriptionService(countedAudio, 30, STAGE_BUDGET),
            countedVision,
            null,
            STAGE_BUDGET,
            true);
    var compiledVideo =
        videoCompiler.compile(
            "synthetic-video.mp4", "video/mp4", prepared.videoBytes(), () -> true);
    require(compiledVideo.audio().spans().size() == 1, "eval_video_audio_incomplete");
    require(
        compiledVideo.frames().size() == prepared.video().frames().size(),
        "eval_frames_incomplete");
    String revision = "audio-video-provider-eval-v1";
    var evidence = VideoEvidence.fromCompilation(revision, compiledVideo);
    var group =
        evidence.groups().stream()
            .filter(g -> g.frameId() != null && g.transcriptSpanId() != null)
            .findFirst()
            .orElseThrow(() -> new AssertionError("eval_no_intersection"));
    // Deliberately one fixed group only: a failed joint proof is not retried on other frames.
    var assessment =
        new VideoAssessmentService(countedText, countedVision, STAGE_BUDGET)
            .assess(
                VIDEO_QUESTION,
                revision,
                compiledVideo,
                group.id(),
                VideoAssessment.Mode.JOINT,
                () -> true);
    require(assessment.supported(), "eval_joint_not_supported");
    require(
        assessment.factIds().size() == 2 && assessment.proofs().size() == 2,
        "eval_joint_incomplete");
    var identifier = assessment.proofs().getFirst();
    var window = assessment.proofs().getLast();
    require(identifier.visualSupport() == 1, "eval_identifier_not_visual");
    require(window.transcriptSupport() == 1, "eval_window_not_transcript");
    require(
        identifierGold(String.join(" ", identifier.visualClaims())),
        "eval_identifier_gold_mismatch");
    require(
        windowGold(
            String.join(" ", window.transcriptQuotes().stream().map(q -> q.quote()).toList())),
        "eval_window_gold_mismatch");
    require(budget.calls() <= prepared.maximumCalls(), "eval_request_count");
    return new Result(
        budget.calls(),
        prepared.maximumCalls(),
        compiledAudio.durationMs(),
        compiledVideo.durationUs(),
        compiledVideo.frames().size(),
        group.startUs(),
        group.endUs(),
        compiledAudio.decoderRevision(),
        compiledVideo.decoderRevision(),
        audio.revision(),
        text.revision(),
        vision.revision(),
        assessment);
  }

  static void report(Result result, PrintStream output) {
    output.printf(
        "audio_video_eval phase=result calls=%d maximum=%d audio_sha=%s video_sha=%s "
            + "audio_ms=%d video_us=%d frames=%d group_start_us=%d group_end_us=%d "
            + "audio_score=1 visual_score=1 transcript_score=1%n",
        result.calls(),
        result.maximumCalls(),
        AUDIO_SHA,
        VIDEO_SHA,
        result.audioMs(),
        result.videoUs(),
        result.frames(),
        result.groupStartUs(),
        result.groupEndUs());
    output.printf(
        "audio_video_eval audio_decoder_revision=%s video_decoder_revision=%s "
            + "asr_revision=%s text_revision=%s vision_revision=%s%n",
        result.audioDecoderRevision(),
        result.videoDecoderRevision(),
        result.audioRevision(),
        result.textRevision(),
        result.visionRevision());
  }

  static boolean audioGold(String quote) {
    return identifierToken(quote).matches(".*(?<![A-Z0-9])AU731(?![A-Z0-9]).*");
  }

  static boolean identifierGold(String quote) {
    return identifierToken(quote).matches(".*(?<![A-Z0-9])V314(?![A-Z0-9]).*");
  }

  private static String identifierToken(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .toUpperCase(Locale.ROOT)
        .replace("七", "7")
        .replace("三", "3")
        .replace("一", "1")
        .replace("四", "4")
        .replaceAll("[\\p{P}\\s]+", "");
  }

  static boolean windowGold(String quote) {
    String compact = Normalizer.normalize(quote, Normalizer.Form.NFKC).replaceAll("\\s+", "");
    return compact.matches(".*(?:周二|星期二)(?:上午)?(?:08:30|8:30|八点三十分|8点30分)(?![0-9]).*");
  }

  private static byte[] resource(String filename, String expectedSha) throws IOException {
    try (var stream =
        AudioVideoProviderEvaluation.class.getResourceAsStream(
            "/multimodal-provider/" + filename)) {
      require(stream != null, "eval_missing_fixture");
      byte[] bytes = stream.readNBytes(20 * 1024 * 1024 + 1);
      require(
          bytes.length <= 20 * 1024 * 1024 && expectedSha.equals(ModelValues.sha256(bytes)),
          "eval_fixture_identity");
      return bytes;
    }
  }

  static void require(boolean condition, String code) {
    if (!condition) {
      throw new AssertionError(code);
    }
  }

  record LiveSettings(
      int approved,
      String key,
      String asrModel,
      String visionModel,
      String generationModel,
      Path ffmpeg,
      Path ffprobe) {
    @Override
    public String toString() {
      return "LiveSettings[redacted]";
    }
  }

  record Prepared(
      byte[] audioBytes,
      byte[] videoBytes,
      DecodedAudio audio,
      DecodedVideo video,
      int maximumCalls) {
    Prepared {
      audioBytes = audioBytes.clone();
      videoBytes = videoBytes.clone();
    }

    @Override
    public byte[] audioBytes() {
      return audioBytes.clone();
    }

    @Override
    public byte[] videoBytes() {
      return videoBytes.clone();
    }

    AudioDecoder audioDecoder() {
      return new AudioDecoder() {
        @Override
        public String revision() {
          return audio.decoderRevision();
        }

        @Override
        public DecodedAudio decode(String filename, String mime, byte[] source) {
          require(
              "synthetic-audio.wav".equals(filename)
                  && "audio/wav".equals(mime)
                  && audio.sourceSha256().equals(ModelValues.sha256(source)),
              "eval_cached_audio_identity");
          return audio;
        }

        @Override
        public void close() {}
      };
    }

    VideoDecoder videoDecoder() {
      return new VideoDecoder() {
        @Override
        public String revision() {
          return video.decoderRevision();
        }

        @Override
        public DecodedVideo decode(String filename, String mime, byte[] source) {
          require(
              "synthetic-video.mp4".equals(filename)
                  && "video/mp4".equals(mime)
                  && video.sourceSha256().equals(ModelValues.sha256(source)),
              "eval_cached_video_identity");
          return video;
        }

        @Override
        public void close() {}
      };
    }

    @Override
    public String toString() {
      return "Prepared[redacted]";
    }
  }

  record Result(
      int calls,
      int maximumCalls,
      long audioMs,
      long videoUs,
      int frames,
      long groupStartUs,
      long groupEndUs,
      String audioDecoderRevision,
      String videoDecoderRevision,
      String audioRevision,
      String textRevision,
      String visionRevision,
      VideoAssessment assessment) {}

  static final class Budget {
    private final int limit;
    private final PrintStream output;
    private int calls;
    private boolean stopped;

    Budget(int limit, PrintStream output) {
      require(limit >= 1 && limit <= 30 && output != null, "eval_invalid_budget");
      this.limit = limit;
      this.output = output;
    }

    synchronized int calls() {
      return calls;
    }

    synchronized <T> T call(String phase, Supplier<T> delegate) {
      require(!stopped && calls < limit, "eval_budget_stopped");
      int ordinal = ++calls;
      long started = System.nanoTime();
      output.printf("audio_video_eval request=%d phase=%s started%n", ordinal, phase);
      try {
        return delegate.get();
      } catch (RuntimeException failure) {
        stopped = true;
        // No provider body, exception message or cause escapes the evaluation reporter.
        output.printf("audio_video_eval request=%d phase=%s code=model_failure%n", ordinal, phase);
        throw new AssertionError("eval_model_failure");
      } finally {
        output.printf(
            "audio_video_eval request=%d elapsed_ms=%d%n",
            ordinal, (System.nanoTime() - started) / 1_000_000);
      }
    }
  }

  static final class CountedAudio implements AudioModels {
    private final AudioModels delegate;
    private final Budget budget;

    CountedAudio(AudioModels delegate, Budget budget) {
      this.delegate = delegate;
      this.budget = budget;
    }

    @Override
    public String revision() {
      return delegate.revision();
    }

    @Override
    public Transcript transcribe(byte[] wav) {
      return budget.call("asr", () -> delegate.transcribe(wav));
    }

    @Override
    public void close() {
      /* The caller owns the underlying adapter. */
    }
  }

  static final class CountedText implements TextModels, FactTextModels {
    private final TextModels delegate;
    private final FactTextModels facts;
    private final Budget budget;

    CountedText(TextModels delegate, FactTextModels facts, Budget budget) {
      this.delegate = delegate;
      this.facts = facts;
      this.budget = budget;
      require(delegate.revision().equals(facts.revision()), "eval_text_revision");
    }

    @Override
    public List<List<Double>> embed(List<String> texts) {
      throw new AssertionError("eval_unexpected_embedding");
    }

    @Override
    public List<Ranked> rerank(String query, List<String> texts) {
      throw new AssertionError("eval_unexpected_reranking");
    }

    @Override
    public Extraction extract(String question, List<Evidence> evidence) {
      return budget.call("audio_extract", () -> delegate.extract(question, evidence));
    }

    @Override
    public Extraction extractFact(String question, QuestionFact fact, List<Evidence> evidence) {
      return budget.call("video_extract_fact", () -> facts.extractFact(question, fact, evidence));
    }

    @Override
    public String revision() {
      return delegate.revision();
    }
  }

  static final class CountedVision implements VisionModels, FactVisionModels {
    private final VisionModels delegate;
    private final FactVisionModels facts;
    private final Budget budget;

    CountedVision(VisionModels delegate, FactVisionModels facts, Budget budget) {
      this.delegate = delegate;
      this.facts = facts;
      this.budget = budget;
      require(delegate.revision().equals(facts.revision()), "eval_vision_revision");
    }

    @Override
    public Description describe(VisualImage image) {
      return budget.call("video_describe", () -> delegate.describe(image));
    }

    @Override
    public Draft draft(String question, VisualImage image) {
      throw new AssertionError("eval_unexpected_whole_question_draft");
    }

    @Override
    public Verification verify(String question, VisualImage image, List<String> claims) {
      throw new AssertionError("eval_unexpected_whole_question_verify");
    }

    @Override
    public Draft draftFact(String question, QuestionFact fact, VisualImage image) {
      return budget.call("video_draft_fact", () -> facts.draftFact(question, fact, image));
    }

    @Override
    public Verification verifyFact(
        String question, QuestionFact fact, VisualImage image, List<String> claims) {
      return budget.call(
          "video_verify_fact", () -> facts.verifyFact(question, fact, image, claims));
    }

    @Override
    public String revision() {
      return delegate.revision();
    }
  }
}
