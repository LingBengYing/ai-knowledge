package com.evidence.rag.config;

import com.evidence.rag.client.model.AudioModels;
import com.evidence.rag.client.model.OpenAiCompatibleAudioModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.service.AudioCompilationService;
import com.evidence.rag.worker.parser.AudioDecoder;
import com.evidence.rag.worker.parser.ProcessAudioDecoder;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Explicit audio composition; disabled by default and never transcribes at startup. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rag.audio", name = "enabled", havingValue = "true")
public class AudioConfiguration {
  @Bean(destroyMethod = "close")
  ProcessAudioDecoder audioDecoder(Environment environment) {
    try {
      requireLocalIngestion(environment);
      return new ProcessAudioDecoder(
          Path.of(environment.getRequiredProperty("rag.audio.ffmpeg-executable")),
          Path.of(environment.getRequiredProperty("rag.audio.ffprobe-executable")),
          Duration.ofMillis(
              environment.getProperty("rag.audio.decode-deadline-ms", Long.class, 30000L)));
    } catch (RuntimeException invalid) {
      throw invalidConfiguration();
    }
  }

  @Bean(destroyMethod = "close")
  OpenAiCompatibleAudioModels audioModels(Environment environment) {
    try {
      requireLocalIngestion(environment);
      var endpoint =
          new OpenAiCompatibleModels.Endpoint(
              URI.create(environment.getRequiredProperty("rag.audio.base-url")),
              environment.getRequiredProperty("rag.audio.model"),
              environment.getRequiredProperty("rag.audio.api-key"));
      return new OpenAiCompatibleAudioModels(
          new OpenAiCompatibleAudioModels.Configuration(
              endpoint,
              Duration.ofMillis(
                  environment.getProperty("rag.audio.deadline-ms", Long.class, 30000L)),
              environment.getProperty("rag.audio.max-response-bytes", Integer.class, 65536),
              environment.getProperty("rag.audio.allow-loopback-http", Boolean.class, false)));
    } catch (RuntimeException invalid) {
      throw invalidConfiguration();
    }
  }

  @Bean
  AudioCompilationService audioCompilationService(
      AudioDecoder decoder, AudioModels models, Environment environment) {
    try {
      requireLocalIngestion(environment);
      return new AudioCompilationService(
          decoder,
          models,
          environment.getProperty("rag.audio.chunk-seconds", Integer.class, 15),
          Duration.ofMillis(
              environment.getProperty("rag.audio.compilation-budget-ms", Long.class, 600000L)));
    } catch (RuntimeException invalid) {
      throw invalidConfiguration();
    }
  }

  private static void requireLocalIngestion(Environment environment) {
    String address = environment.getProperty("server.address");
    String mode = environment.getProperty("rag.environment", "development");
    if (!("127.0.0.1".equals(address) || "::1".equals(address))
        || !("development".equals(mode) || "test".equals(mode))
        || !environment.getProperty("rag.ingestion.enabled", Boolean.class, false)) {
      throw invalidConfiguration();
    }
  }

  private static IllegalArgumentException invalidConfiguration() {
    return new IllegalArgumentException("Invalid local audio configuration");
  }
}
