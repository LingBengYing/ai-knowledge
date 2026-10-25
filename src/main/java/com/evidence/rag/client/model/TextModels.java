package com.evidence.rag.client.model;

import com.evidence.rag.model.dto.AgentMessage;
import com.evidence.rag.model.dto.AgentModelDelta;
import java.util.List;
import java.util.function.Consumer;

/** Model seam. Callers own authorization, factual proof and authoritative source locators. */
public interface TextModels {
  String KNOWLEDGE_ANSWER_PROMPT_REVISION = "java-knowledge-answer-v2-shared-contexts";
  String WIKI_COMPILATION_PROMPT_REVISION = "java-wiki-compilation-v1-review-draft";
  String WIKI_REVISION_PROMPT_REVISION = "java-wiki-section-revision-v1-bound-targets";

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

  /** Revises exactly the supplied section slots; the caller retains all other sections. */
  default WikiRevisionDraft reviseWiki(
      String title, List<Evidence> evidence, List<WikiSectionTarget> targets) {
    throw new Failure("model_wiki_revision_unavailable");
  }

  /** Native tool turn. Does not change index identity or existing prompt contracts. */
  default AgentMessage agentChat(List<AgentMessage> messages) {
    throw new Failure("model_agent_unavailable");
  }

  /** Native model increments are previews; tools execute only after the complete turn validates. */
  default AgentMessage agentChat(List<AgentMessage> messages, Consumer<AgentModelDelta> listener) {
    return agentChat(messages);
  }

  /** Derived conversation memory only; never original knowledge evidence or citation authority. */
  default String compactConversation(String previousSummary, List<AgentMessage> history) {
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

  record WikiSectionTarget(
      String sectionId,
      String heading,
      String body,
      List<String> evidenceIds,
      List<List<String>> requiredEvidenceGroups) {
    public WikiSectionTarget {
      evidenceIds = List.copyOf(evidenceIds);
      requiredEvidenceGroups = requiredEvidenceGroups.stream().map(List::copyOf).toList();
    }

    @Override
    public String toString() {
      return "WikiSectionTarget[redacted]";
    }
  }

  record WikiRevisionDraft(List<WikiSectionRevision> sections) {
    public WikiRevisionDraft {
      sections = List.copyOf(sections);
    }

    @Override
    public String toString() {
      return "WikiRevisionDraft[redacted]";
    }
  }

  record WikiSectionRevision(
      String sectionId, String heading, String body, List<String> evidenceIds) {
    public WikiSectionRevision {
      evidenceIds = List.copyOf(evidenceIds);
    }

    @Override
    public String toString() {
      return "WikiSectionRevision[redacted]";
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
    private final java.time.Duration retryAfter;

    public Failure(String code) {
      this(code, null);
    }

    public Failure(String code, Integer httpStatus) {
      this(code, httpStatus, null);
    }

    /** {@code retryAfter} is the provider's bounded Retry-After wait, when it sent one. */
    public Failure(String code, Integer httpStatus, java.time.Duration retryAfter) {
      super("模型调用或配置未通过安全校验。", null, false, true);
      this.code = code;
      this.httpStatus =
          httpStatus != null && httpStatus >= 100 && httpStatus <= 599 ? httpStatus : null;
      this.retryAfter =
          retryAfter != null
                  && !retryAfter.isNegative()
                  && retryAfter.compareTo(java.time.Duration.ofDays(1)) <= 0
              ? retryAfter
              : null;
    }

    /**
     * Rate limits, provider-side errors, timeouts and broken connections may succeed when the same
     * request is sent again; invalid input, configuration or responses never do.
     */
    public boolean isTransient() {
      return switch (code) {
        case "model_timeout", "model_transport_failed" -> true;
        case "model_http_failed" ->
            httpStatus != null && (httpStatus == 429 || httpStatus >= 500 && httpStatus <= 599);
        default -> false;
      };
    }

    public java.time.Duration retryAfter() {
      return retryAfter;
    }

    public String code() {
      return code;
    }

    public Integer httpStatus() {
      return httpStatus;
    }
  }
}
