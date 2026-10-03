package com.evidence.rag.web.converter;

import com.evidence.rag.model.dto.VoiceQuestionResult;
import com.evidence.rag.model.vo.VoiceQuestionResponse;

/** Explicit wire whitelist for complete voice input preparation. */
public final class VoiceQuestionResponseMapper {
  private VoiceQuestionResponseMapper() {}

  public static VoiceQuestionResponse response(VoiceQuestionResult result) {
    return new VoiceQuestionResponse(
        result.transcript(),
        result.transcriptSha256(),
        result.sourceSha256(),
        result.decoderRevision(),
        result.modelRevision(),
        result.compilerRevision(),
        result.durationMs(),
        result.policyRevision());
  }
}
