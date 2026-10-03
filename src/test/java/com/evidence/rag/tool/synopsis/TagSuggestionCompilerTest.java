package com.evidence.rag.tool.synopsis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.FileSynopsis;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.SynopsisDraft;
import com.evidence.rag.model.domain.SynopsisEvidence;
import com.evidence.rag.model.domain.TagSuggestions;
import com.evidence.rag.model.dto.TagSuggestionResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TagSuggestionCompilerTest {
  @Test
  void termsPrecedeTopicsWithExactDeduplicationAndContiguousOrdinals() {
    var result =
        compile(
            entry(SynopsisDraft.Section.TOPIC, "采购"),
            entry(SynopsisDraft.Section.TERM, " 预算 "),
            entry(SynopsisDraft.Section.TOPIC, "预算"),
            entry(SynopsisDraft.Section.TERM, "预算"),
            entry(SynopsisDraft.Section.TERM, "Budget"),
            entry(SynopsisDraft.Section.TERM, "budget"),
            entry(SynopsisDraft.Section.TOPIC, "计划"));
    assertEquals(List.of("预算", "Budget", "budget", "采购", "计划"), tags(result));
    assertEquals(List.of(1, 2, 3, 4, 5), ordinals(result));
    assertEquals("java-synopsis-tags-v1", result.policyRevision());
  }

  @Test
  void firstEightEligibleDistinctTermsWinWithoutTopicReorderingOrGaps() {
    var entries = new ArrayList<FileSynopsis.Entry>();
    entries.add(entry(SynopsisDraft.Section.TOPIC, "topic-first-in-source"));
    entries.add(entry(SynopsisDraft.Section.TERM, "skip,compound"));
    for (int i = 1; i <= 10; i++) {
      entries.add(entry(SynopsisDraft.Section.TERM, "term-" + i));
      if (i == 1) {
        entries.add(entry(SynopsisDraft.Section.TERM, "term-1"));
      }
    }
    var result = TagSuggestionCompiler.compile("synopsis-1", synopsis(entries));
    assertEquals(
        List.of("term-1", "term-2", "term-3", "term-4", "term-5", "term-6", "term-7", "term-8"),
        tags(result));
    assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8), ordinals(result));
  }

  @Test
  void unicodeLimitUsesWholeCodePointsAndNeverTruncatesLongEntries() {
    String forty = "😀".repeat(39) + "尾";
    String fortyOne = forty + "外";
    var result =
        compile(
            entry(SynopsisDraft.Section.TOPIC, "x".repeat(41)),
            entry(SynopsisDraft.Section.TERM, "\n\t" + forty + "\r\n"),
            entry(SynopsisDraft.Section.TERM, fortyOne),
            entry(SynopsisDraft.Section.TERM, "单"));
    assertEquals(List.of(forty, "单"), tags(result));
    assertEquals(40, result.candidates().getFirst().tag().codePointCount(0, forty.length()));
  }

  @Test
  void separatorsAndInternalControlsAreSkippedWithoutSplittingSentencesOrInventingKeywords() {
    var result =
        compile(
            entry(SynopsisDraft.Section.TERM, "one,two"),
            entry(SynopsisDraft.Section.TERM, "一，二"),
            entry(SynopsisDraft.Section.TERM, "one;two"),
            entry(SynopsisDraft.Section.TERM, "一；二"),
            entry(SynopsisDraft.Section.TERM, "two\nlines"),
            entry(SynopsisDraft.Section.TERM, "two\tparts"),
            entry(SynopsisDraft.Section.TERM, "two\rparts"),
            entry(SynopsisDraft.Section.TERM, "status is not enabled"),
            entry(SynopsisDraft.Section.TOPIC, "检查设备后等待五秒。"),
            entry(SynopsisDraft.Section.TOPIC, "条件：审批后才启动。"));
    assertEquals(List.of("status is not enabled", "检查设备后等待五秒。", "条件：审批后才启动。"), tags(result));
  }

  @Test
  void overviewTimelineAndAllLongItemsProduceNoCandidatesInsteadOfFallbackGuesses() {
    var result =
        compile(
            entry(SynopsisDraft.Section.TERM, "完整材料".repeat(20)),
            entry(SynopsisDraft.Section.TOPIC, "完整材料".repeat(20)),
            entry(SynopsisDraft.Section.TIMELINE, "短时间条目"));
    assertTrue(result.candidates().isEmpty());
    assertTrue(result.suggestionFingerprint().matches("[a-f0-9]{64}"));
  }

  @Test
  void canonicalFingerprintIsStableAndDoesNotDependOnMutableExistingTags() {
    var result =
        compile(entry(SynopsisDraft.Section.TERM, "预算"), entry(SynopsisDraft.Section.TOPIC, "采购"));
    assertEquals(
        "8a93f2dc8c80b58b585e0e61e3c8e0a70b164c11a861385528ece157df9d4287",
        result.suggestionFingerprint());
    assertEquals(result, TagSuggestionCompiler.compile("synopsis-1", baseSynopsis()));
    var before = new TagSuggestionResult(result, List.of("手工标签"), true);
    var after = new TagSuggestionResult(result, List.of("手工标签", "预算", "并发新增"), true);
    assertEquals(before.suggestions().candidates(), after.suggestions().candidates());
    assertEquals(
        before.suggestions().suggestionFingerprint(), after.suggestions().suggestionFingerprint());
  }

  @Test
  void everyPublicationAndSynopsisIdentityFieldChangesTheFingerprint() {
    var original = baseSynopsis();
    String fingerprint =
        TagSuggestionCompiler.compile("synopsis-1", original).suggestionFingerprint();
    assertNotEquals(
        fingerprint, TagSuggestionCompiler.compile("synopsis-2", original).suggestionFingerprint());
    for (int field = 0; field < 12; field++) {
      var changed =
          new FileSynopsis(
              publication(field),
              original.inputFingerprint(),
              original.modelRevision(),
              original.policyRevision(),
              original.entries(),
              null);
      assertNotEquals(
          fingerprint,
          TagSuggestionCompiler.compile("synopsis-1", changed).suggestionFingerprint(),
          "publication field " + field);
    }
    for (var changed :
        List.of(
            new FileSynopsis(
                original.publication(),
                "d".repeat(64),
                original.modelRevision(),
                original.policyRevision(),
                original.entries(),
                null),
            new FileSynopsis(
                original.publication(),
                original.inputFingerprint(),
                "synopsis-v2",
                original.policyRevision(),
                original.entries(),
                null),
            new FileSynopsis(
                original.publication(),
                original.inputFingerprint(),
                original.modelRevision(),
                "synopsis-policy-v2",
                original.entries(),
                null))) {
      assertNotEquals(
          fingerprint,
          TagSuggestionCompiler.compile("synopsis-1", changed).suggestionFingerprint());
    }
  }

  @Test
  void orderedCandidateContentIsBoundWithDefiniteFieldBoundaries() {
    var first =
        compile(
            entry(SynopsisDraft.Section.TERM, "a"),
            entry(SynopsisDraft.Section.TERM, "bc"),
            entry(SynopsisDraft.Section.TOPIC, "topic"));
    var differentBoundaries =
        compile(
            entry(SynopsisDraft.Section.TERM, "ab"),
            entry(SynopsisDraft.Section.TERM, "c"),
            entry(SynopsisDraft.Section.TOPIC, "topic"));
    var reordered =
        compile(
            entry(SynopsisDraft.Section.TERM, "bc"),
            entry(SynopsisDraft.Section.TERM, "a"),
            entry(SynopsisDraft.Section.TOPIC, "topic"));
    assertNotEquals(first.suggestionFingerprint(), differentBoundaries.suggestionFingerprint());
    assertNotEquals(first.suggestionFingerprint(), reordered.suggestionFingerprint());
  }

  @Test
  void unavailableSynopsisCannotBePassedOffAsAvailableEmptySuggestions() {
    var unavailable =
        new FileSynopsis(
            publication(-1),
            "c".repeat(64),
            "synopsis-v1",
            "synopsis-policy-v1",
            List.of(),
            "model_failure");
    assertThrows(
        ApplicationException.class, () -> TagSuggestionCompiler.compile("synopsis-1", unavailable));
    assertThrows(
        ApplicationException.class, () -> TagSuggestionCompiler.compile("synopsis-1", null));
    assertThrows(
        ApplicationException.class, () -> TagSuggestionCompiler.compile("", baseSynopsis()));
  }

  private static TagSuggestions compile(FileSynopsis.Entry... entries) {
    return TagSuggestionCompiler.compile("synopsis-1", synopsis(List.of(entries)));
  }

  private static FileSynopsis baseSynopsis() {
    return synopsis(
        List.of(entry(SynopsisDraft.Section.TERM, "预算"), entry(SynopsisDraft.Section.TOPIC, "采购")));
  }

  private static FileSynopsis synopsis(List<FileSynopsis.Entry> selected) {
    var entries = new ArrayList<FileSynopsis.Entry>();
    entries.add(entry(SynopsisDraft.Section.OVERVIEW, "短总览不能变成标签"));
    entries.addAll(selected);
    return new FileSynopsis(
        publication(-1), "c".repeat(64), "synopsis-v1", "synopsis-policy-v1", entries, null);
  }

  private static FileSynopsis.Entry entry(SynopsisDraft.Section section, String text) {
    boolean timeline = section == SynopsisDraft.Section.TIMELINE;
    var time = timeline ? new SynopsisEvidence.TimeRange(1, 2) : null;
    var reference =
        new FileSynopsis.Reference(
            "source",
            "a".repeat(64),
            timeline ? SynopsisEvidence.Kind.AUDIO_TRANSCRIPT : SynopsisEvidence.Kind.TEXT,
            time);
    return new FileSynopsis.Entry(
        new SynopsisDraft.Item(section, text, List.of("source")), List.of(reference), time);
  }

  private static PublicationVersion publication(int changed) {
    return new PublicationVersion(
        changed == 0 ? "other-document" : "document",
        changed == 1 ? "other-publication" : "publication",
        changed == 2 ? "other-revision" : "revision",
        changed == 3 ? "other-generation" : "generation",
        (changed == 4 ? "d" : "a").repeat(64),
        changed == 5 ? "parser-v2" : "parser-v1",
        new IndexTarget(
            changed == 6 ? "other-embedding" : "embedding",
            changed == 7 ? "other-projection" : "projection",
            changed == 8 ? "embedding-v2" : "embedding-v1",
            changed == 9 ? 4 : 3),
        (changed == 10 ? "e" : "b").repeat(64),
        changed == 11 ? 2 : 1);
  }

  private static List<String> tags(TagSuggestions value) {
    return value.candidates().stream().map(TagSuggestions.Candidate::tag).toList();
  }

  private static List<Integer> ordinals(TagSuggestions value) {
    return value.candidates().stream().map(TagSuggestions.Candidate::ordinal).toList();
  }
}
