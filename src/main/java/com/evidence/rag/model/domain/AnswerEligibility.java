package com.evidence.rag.model.domain;

/** Local, non-I/O eligibility for committing an answer under the authority transaction. */
public enum AnswerEligibility {
  ELIGIBLE,
  PROCESSING_TIMEOUT,
  CONFIGURATION_CHANGED
}
