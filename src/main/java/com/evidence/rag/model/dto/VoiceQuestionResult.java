package com.evidence.rag.model.dto;

import com.evidence.rag.model.domain.ModelValues;
import java.nio.charset.StandardCharsets;

/** Complete machine transcription to review as user input; it is not answer evidence. */
public record VoiceQuestionResult(
    String transcript,
    String transcriptSha256,
    String sourceSha256,
    String decoderRevision,
    String modelRevision,
    String compilerRevision,
    long durationMs,
    String policyRevision) {
  public static final String POLICY_REVISION = "java-voice-question-v1";
  public static final int MAX_TRANSCRIPT_BYTES = 65536;

  public VoiceQuestionResult {
    ModelValues.identifier(decoderRevision, 200);
    ModelValues.identifier(modelRevision, 200);
    ModelValues.identifier(compilerRevision, 200);
    if (transcript == null
        || transcript.isBlank()
        || transcript.getBytes(StandardCharsets.UTF_8).length > MAX_TRANSCRIPT_BYTES
        || transcript
            .codePoints()
            .anyMatch(
                c ->
                    (c >= 0xD800 && c <= 0xDFFF)
                        || (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
        || !ModelValues.sha256(transcript.getBytes(StandardCharsets.UTF_8)).equals(transcriptSha256)
        || sourceSha256 == null
        || !sourceSha256.matches("[a-f0-9]{64}")
        || durationMs < 1
        || durationMs > 600_000
        || !POLICY_REVISION.equals(policyRevision)) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "VoiceQuestionResult[redacted]";
  }
}
