package com.evidence.rag.client.model;

import java.util.List;

/** Model seam. Callers own authorization, factual proof and authoritative source locators. */
public interface TextModels {
  String SYNTHESIS_PROMPT_REVISION = "java-text-synthesis-v2";

  List<List<Double>> embed(List<String> texts);

  List<Ranked> rerank(String query, List<String> texts);

  Extraction extract(String query, List<Evidence> evidence);

  default Synthesis synthesize(String question, List<SynthesisEvidence> evidence) {
    throw new Failure("model_synthesis_unavailable");
  }

  default boolean verifySynthesis(
      String question, Synthesis synthesis, List<SynthesisEvidence> evidence) {
    throw new Failure("model_synthesis_unavailable");
  }

  String revision();

  record Ranked(int index, double score) {}

  record Evidence(String id, String text) {
    @Override
    public String toString() {
      return "Evidence[redacted]";
    }
  }

  record Quote(String evidenceId, String quote) {
    @Override
    public String toString() {
      return "Quote[redacted]";
    }
  }

  record Extraction(List<Quote> quotes, boolean refused) {
    public Extraction {
      quotes = List.copyOf(quotes);
    }

    @Override
    public String toString() {
      return "Extraction[redacted]";
    }
  }

  record SynthesisEvidence(String id, String quote, String context) {
    @Override
    public String toString() {
      return "SynthesisEvidence[redacted]";
    }
  }

  record Statement(String text, List<String> evidenceIds) {
    public Statement {
      evidenceIds = List.copyOf(evidenceIds);
    }

    @Override
    public String toString() {
      return "Statement[redacted]";
    }
  }

  record Synthesis(boolean refused, List<Statement> statements) {
    public Synthesis {
      statements = List.copyOf(statements);
    }

    @Override
    public String toString() {
      return "Synthesis[redacted]";
    }
  }

  final class Failure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;
    private final Integer httpStatus;

    public Failure(String code) {
      this(code, null);
    }

    public Failure(String code, Integer httpStatus) {
      super("模型调用或配置未通过安全校验。", null, false, true);
      this.code = code;
      this.httpStatus =
          httpStatus != null && httpStatus >= 100 && httpStatus <= 599 ? httpStatus : null;
    }

    public String code() {
      return code;
    }

    public Integer httpStatus() {
      return httpStatus;
    }
  }
}
