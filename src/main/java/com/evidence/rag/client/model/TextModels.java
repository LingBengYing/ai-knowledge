package com.evidence.rag.client.model;

import com.evidence.rag.model.dto.AgentMessage;
import java.util.List;

/** Model seam. Callers own authorization, factual proof and authoritative source locators. */
public interface TextModels {
  String KNOWLEDGE_ANSWER_PROMPT_REVISION = "java-knowledge-answer-v2-shared-contexts";
  String WIKI_COMPILATION_PROMPT_REVISION = "java-wiki-compilation-v1-review-draft";

  List<List<Double>> embed(List<String> texts);

  List<Ranked> rerank(String query, List<String> texts);

  Extraction extract(String query, List<Evidence> evidence);

  /** Answers from original retrieved snippets; no independent semantic verification. */
  default Synthesis answerKnowledge(String question, List<SynthesisEvidence> evidence) {
    throw new Failure("model_knowledge_answer_unavailable");
  }

  /** Produces a derived draft for human review, never authoritative answer evidence. */
  default WikiDraft compileWiki(String title, List<Evidence> evidence) {
    throw new Failure("model_wiki_compilation_unavailable");
  }

  /** Plain bounded Agent turn. Does not change index identity or existing prompt contracts. */
  default String agentChat(List<AgentMessage> messages) {
    throw new Failure("model_agent_unavailable");
  }

  String revision();

  record Ranked(int index, double score) {}

  record WikiDraft(List<WikiSectionDraft> sections) {
    public WikiDraft {
      sections = List.copyOf(sections);
    }

    @Override
    public String toString() {
      return "WikiDraft[redacted]";
    }
  }

  record WikiSectionDraft(String heading, String body, List<String> evidenceIds) {
    public WikiSectionDraft {
      evidenceIds = List.copyOf(evidenceIds);
    }

    @Override
    public String toString() {
      return "WikiSectionDraft[redacted]";
    }
  }

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

  record SynthesisEvidence(
      String id, String quote, String context, List<String> requiredEvidenceIds) {
    public SynthesisEvidence {
      requiredEvidenceIds = List.copyOf(requiredEvidenceIds);
    }

    public SynthesisEvidence(String id, String quote, String context) {
      this(id, quote, context, List.of());
    }

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
