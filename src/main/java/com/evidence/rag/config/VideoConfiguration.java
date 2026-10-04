package com.evidence.rag.config;

import com.evidence.rag.client.model.FactTextModels;
import com.evidence.rag.client.model.OpenAiCompatibleAudioModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.model.OpenAiCompatibleVisionModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.client.vector.RetrievalProjection;
import com.evidence.rag.model.domain.ImageOcrOptions;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.service.AudioTranscriptionService;
import com.evidence.rag.service.EvidenceService;
import com.evidence.rag.service.VideoAnswerProposalService;
import com.evidence.rag.service.VideoCompilationService;
import com.evidence.rag.worker.parser.ProcessImageParser;
import com.evidence.rag.worker.parser.ProcessVideoDecoder;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Explicit local video composition, independent of audio and visual answer switches. */
@Configuration(proxyBeanMethods = false)
@Conditional(MediaModulesCondition.class)
@ConditionalOnProperty(prefix = "rag.video", name = "enabled", havingValue = "true")
public class VideoConfiguration {
  @Bean(destroyMethod = "close")
  VideoResources videoResources(Environment environment) {
    ProcessVideoDecoder decoder = null;
    OpenAiCompatibleAudioModels audio = null;
    OpenAiCompatibleVisionModels vision = null;
    ProcessImageParser ocr = null;
    try {
      String address = environment.getProperty("server.address");
      String mode = environment.getProperty("rag.environment", "development");
      if (!("127.0.0.1".equals(address) || "::1".equals(address))
          || !("development".equals(mode) || "test".equals(mode))
          || !environment.getProperty("rag.ingestion.enabled", Boolean.class, false)) {
        throw new IllegalArgumentException();
      }
      boolean subtitles =
          environment.getProperty("rag.video.subtitles.enabled", Boolean.class, false);
      decoder =
          new ProcessVideoDecoder(
              Path.of(environment.getRequiredProperty("rag.video.ffmpeg-executable")),
              Path.of(environment.getRequiredProperty("rag.video.ffprobe-executable")),
              duration(environment, "rag.video.decode-deadline-ms", 30000),
              environment.getProperty("rag.video.frame-interval-seconds", Integer.class, 10),
              subtitles);
      if (environment.getProperty("rag.video.ocr.enabled", Boolean.class, false)) {
        Path executable = Path.of(environment.getProperty("rag.video.ocr.executable", ""));
        if (!executable.isAbsolute()
            || !Files.isRegularFile(executable)
            || !Files.isExecutable(executable)) {
          throw new IllegalArgumentException();
        }
        ocr =
            new ProcessImageParser(
                new ImageOcrOptions(
                    executable,
                    environment.getProperty("rag.video.ocr.language", "eng"),
                    environment.getProperty("rag.video.ocr.revision", "")),
                duration(environment, "rag.video.ocr.deadline-ms", 30000));
      }
      audio =
          new OpenAiCompatibleAudioModels(
              new OpenAiCompatibleAudioModels.Configuration(
                  endpoint(environment, "rag.video.asr"),
                  duration(environment, "rag.video.asr.deadline-ms", 30000),
                  environment.getProperty("rag.video.asr.max-response-bytes", Integer.class, 65536),
                  environment.getProperty(
                      "rag.video.asr.allow-loopback-http", Boolean.class, false)));
      vision =
          new OpenAiCompatibleVisionModels(
              new OpenAiCompatibleVisionModels.Configuration(
                  endpoint(environment, "rag.video.vision"),
                  duration(environment, "rag.video.vision.deadline-ms", 30000),
                  environment.getProperty(
                      "rag.video.vision.max-response-bytes", Integer.class, 262144),
                  environment.getProperty(
                      "rag.video.vision.allow-loopback-http", Boolean.class, false)));
      Duration budget = duration(environment, "rag.video.compilation-budget-ms", 600000);
      var transcription =
          new AudioTranscriptionService(
              audio, environment.getProperty("rag.video.chunk-seconds", Integer.class, 15), budget);
      return new VideoResources(
          decoder,
          audio,
          vision,
          ocr,
          subtitles
              ? new VideoCompilationService(decoder, transcription, vision, ocr, budget, true)
              : ocr == null
                  ? new VideoCompilationService(decoder, transcription, vision, budget)
                  : new VideoCompilationService(decoder, transcription, vision, ocr, budget));
    } catch (RuntimeException invalid) {
      if (ocr != null) {
        ocr.close();
      }
      if (vision != null) {
        vision.close();
      }
      if (audio != null) {
        audio.close();
      }
      if (decoder != null) {
        decoder.close();
      }
      throw new IllegalArgumentException("Invalid local video configuration");
    }
  }

  @Bean
  VideoCompilationService videoCompilationService(VideoResources resources) {
    return resources.compiler;
  }

  @Bean
  @ConditionalOnProperty(prefix = "rag.answers", name = "enabled", havingValue = "true")
  @Conditional(LegacyTextCondition.class)
  VideoAnswerProposalService videoAnswerProposalService(
      VideoResources resources,
      EvidenceService evidence,
      TextModels models,
      RetrievalProjection projection,
      TextAdapterSettings settings,
      AnswersSettings limits) {
    if (!(models instanceof FactTextModels facts)) {
      throw new IllegalArgumentException("Video answers require fact-scoped text models");
    }
    var target =
        new IndexTarget(
            settings.projection().embeddingIdentity(),
            settings.projection().identity(),
            models.revision(),
            settings.projection().dimension());
    return new VideoAnswerProposalService(
        evidence,
        models,
        facts,
        resources.vision,
        projection,
        target,
        Duration.ofMillis(limits.timeoutMs()));
  }

  private static Duration duration(Environment environment, String key, long defaultMillis) {
    return Duration.ofMillis(environment.getProperty(key, Long.class, defaultMillis));
  }

  private static OpenAiCompatibleModels.Endpoint endpoint(Environment environment, String prefix) {
    return new OpenAiCompatibleModels.Endpoint(
        URI.create(environment.getRequiredProperty(prefix + ".base-url")),
        environment.getRequiredProperty(prefix + ".model"),
        environment.getRequiredProperty(prefix + ".api-key"));
  }

  /** Owns only the video resources; never registers competing audio, vision or OCR beans. */
  static final class VideoResources implements AutoCloseable {
    final ProcessVideoDecoder decoder;
    final OpenAiCompatibleAudioModels audio;
    final OpenAiCompatibleVisionModels vision;
    final ProcessImageParser ocr;
    final VideoCompilationService compiler;

    VideoResources(
        ProcessVideoDecoder decoder,
        OpenAiCompatibleAudioModels audio,
        OpenAiCompatibleVisionModels vision,
        ProcessImageParser ocr,
        VideoCompilationService compiler) {
      this.decoder = decoder;
      this.audio = audio;
      this.vision = vision;
      this.ocr = ocr;
      this.compiler = compiler;
    }

    @Override
    public void close() {
      try {
        if (ocr != null) {
          ocr.close();
        }
      } finally {
        try {
          vision.close();
        } finally {
          try {
            audio.close();
          } finally {
            decoder.close();
          }
        }
      }
    }
  }
}
