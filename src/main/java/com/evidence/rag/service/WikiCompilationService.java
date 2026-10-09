package com.evidence.rag.service;

import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.FileSynopsis;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.SynopsisEvidence;
import com.evidence.rag.model.domain.WikiCompilationInput;
import com.evidence.rag.model.domain.WikiContent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles review-only page sections; authority, transactions and publication belong to the caller.
 */
public final class WikiCompilationService {
  public static final String POLICY_REVISION = "java-wiki-compilation-v1-original-text-review";

  public List<WikiContent.Section> compile(
      TextModels models, String title, List<WikiCompilationInput> inputs) {
    if (models == null) {
      throw ModelValues.invalid();
    }
    ModelValues.label(title, 200);
    var originals = originals(inputs);
    var draft =
        models.compileWiki(
            title,
            originals.values().stream()
                .map(item -> new TextModels.Evidence(item.source().id(), item.text()))
                .toList());
    if (draft == null || draft.sections().isEmpty()) {
      throw new TextModels.Failure("model_invalid_response");
    }
    var sections = new ArrayList<WikiContent.Section>();
    for (var section : draft.sections()) {
      if (section == null
          || section.evidenceIds().isEmpty()
          || new HashSet<>(section.evidenceIds()).size() != section.evidenceIds().size()
          || section.evidenceIds().stream().anyMatch(id -> !originals.containsKey(id))) {
        throw new TextModels.Failure("model_invalid_response");
      }
      try {
        sections.add(
            new WikiContent.Section(
                "section-" + (sections.size() + 1),
                section.heading(),
                section.body(),
                section.evidenceIds().stream().map(id -> originals.get(id).source()).toList()));
      } catch (ApplicationException invalidDraft) {
        throw new TextModels.Failure("model_invalid_response");
      }
    }
    return List.copyOf(sections);
  }

  /** Preserves every textual segment verbatim. This is not an AI synthesis or a factual verdict. */
  public List<WikiContent.Section> extract(List<WikiCompilationInput> inputs) {
    var sections = new ArrayList<WikiContent.Section>();
    for (var original : originals(inputs).values()) {
      int ordinal = sections.size() + 1;
      sections.add(
          new WikiContent.Section(
              "section-" + ordinal,
              "原文摘编 " + ordinal,
              original.text(),
              List.of(original.source())));
    }
    return List.copyOf(sections);
  }

  private static Map<String, Original> originals(List<WikiCompilationInput> inputs) {
    if (inputs == null || inputs.isEmpty()) {
      throw ModelValues.invalid();
    }
    var originals = new LinkedHashMap<String, Original>();
    var publications = new HashSet<String>();
    for (var input : inputs) {
      if (input == null || !publications.add(input.publication().documentId())) {
        throw ModelValues.invalid();
      }
      int before = originals.size();
      for (var evidence : input.evidence()) {
        // Image pixels remain outside this explicitly text-only compilation policy.
        if (evidence.content() instanceof SynopsisEvidence.Text text) {
          String alias = "source-" + (originals.size() + 1);
          var reference =
              new FileSynopsis.Reference(
                  evidence.id(), evidence.sha256(), evidence.kind(), evidence.time());
          originals.put(
              alias,
              new Original(
                  new WikiContent.Source(alias, input.publication(), reference), text.text()));
        }
      }
      if (originals.size() == before) {
        throw new ApplicationException(
            FailureKind.INVALID_INPUT,
            "wiki_text_unavailable",
            "所选资料没有可编译的文字、OCR、转录或字幕；本切不支持纯图像知识编译。");
      }
    }
    return originals;
  }

  private record Original(WikiContent.Source source, String text) {
    @Override
    public String toString() {
      return "WikiCompilationOriginal[redacted]";
    }
  }
}
