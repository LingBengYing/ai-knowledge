package com.evidence.rag.service;

import com.evidence.rag.client.model.OpenAiCompatibleAudioModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels;
import com.evidence.rag.client.model.OpenAiCompatibleModels.Endpoint;
import com.evidence.rag.client.model.OpenAiCompatibleVisionModels;
import java.net.URI;
import org.junit.jupiter.api.Test;

/** Explicit-only synthetic provider evaluation. Never run using a historical or text-only grant. */
class AudioVideoModelsLiveIT {
  @Test
  void fixedSyntheticAudioAndVideoRequireOriginalEvidenceAndBothModalities() throws Exception {
    var settings = AudioVideoProviderEvaluation.liveSettings(System.getenv());
    // The complete input and worst-case grant are validated before constructing any adapter.
    var prepared =
        AudioVideoProviderEvaluation.prepare(
            settings.ffmpeg(), settings.ffprobe(), settings.approved());
    var endpoint = URI.create("https://api.siliconflow.cn/v1");
    var generation = new Endpoint(endpoint, settings.generationModel(), settings.key());
    try (var audio =
            new OpenAiCompatibleAudioModels(
                new OpenAiCompatibleAudioModels.Configuration(
                    new Endpoint(endpoint, settings.asrModel(), settings.key()),
                    AudioVideoProviderEvaluation.REQUEST_TIMEOUT,
                    1_048_576,
                    false));
        var vision =
            new OpenAiCompatibleVisionModels(
                new OpenAiCompatibleVisionModels.Configuration(
                    new Endpoint(endpoint, settings.visionModel(), settings.key()),
                    AudioVideoProviderEvaluation.REQUEST_TIMEOUT,
                    1_048_576,
                    false));
        var text =
            new OpenAiCompatibleModels(
                new OpenAiCompatibleModels.Configuration(
                    generation,
                    generation,
                    generation,
                    2,
                    AudioVideoProviderEvaluation.REQUEST_TIMEOUT,
                    1_048_576,
                    false))) {
      var budget = new AudioVideoProviderEvaluation.Budget(settings.approved(), System.out);
      var result =
          AudioVideoProviderEvaluation.evaluate(
              prepared, budget, audio, text, text, vision, vision);
      AudioVideoProviderEvaluation.report(result, System.out);
    }
  }
}
