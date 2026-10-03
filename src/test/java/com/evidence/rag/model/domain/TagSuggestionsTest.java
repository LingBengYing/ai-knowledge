package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class TagSuggestionsTest {
  private static final String HASH = "a".repeat(64);

  @Test
  void candidatesAreCanonicalWholeLabelsAndCannotUseSeparatorsOrControls() {
    String unicode = "😀".repeat(40);
    assertEquals(unicode, new TagSuggestions.Candidate(1, unicode).tag());
    for (String tag :
        Arrays.asList(
            null,
            "",
            " ",
            " padded ",
            "a,b",
            "a，b",
            "a;b",
            "a；b",
            "a\nb",
            "\ud800",
            unicode + "外")) {
      assertThrows(ApplicationException.class, () -> new TagSuggestions.Candidate(1, tag));
    }
    assertThrows(ApplicationException.class, () -> new TagSuggestions.Candidate(0, "预算"));
    assertThrows(ApplicationException.class, () -> new TagSuggestions.Candidate(9, "预算"));
  }

  @Test
  void candidateSetKeepsContiguousUniqueOrdinalsAndCannotExposeCallerMutation() {
    var source = new ArrayList<>(List.of(new TagSuggestions.Candidate(1, "private-label")));
    var value = suggestions(source);
    source.clear();
    assertEquals(List.of(new TagSuggestions.Candidate(1, "private-label")), value.candidates());
    assertThrows(UnsupportedOperationException.class, () -> value.candidates().clear());
    assertEquals("TagSuggestions[redacted]", value.toString());
    assertEquals("TagSuggestions.Candidate[redacted]", value.candidates().getFirst().toString());
    assertEquals("java-synopsis-tags-v1", value.policyRevision());
    assertEquals(List.of(), suggestions(List.of()).candidates());
    assertThrows(
        ApplicationException.class,
        () -> suggestions(List.of(new TagSuggestions.Candidate(2, "预算"))));
    assertThrows(
        ApplicationException.class,
        () ->
            suggestions(
                List.of(
                    new TagSuggestions.Candidate(1, "预算"), new TagSuggestions.Candidate(2, "预算"))));
    assertThrows(
        ApplicationException.class,
        () -> suggestions(Arrays.asList((TagSuggestions.Candidate) null)));
    assertThrows(ApplicationException.class, () -> suggestions(null));
  }

  @Test
  void missingOrMalformedSourceIdentitiesCannotCreateSuggestionMetadata() {
    var candidates = List.of(new TagSuggestions.Candidate(1, "预算"));
    assertThrows(
        ApplicationException.class,
        () ->
            new TagSuggestions(
                null, publication(), HASH, "model-v1", "policy-v1", HASH, candidates));
    assertThrows(
        ApplicationException.class,
        () ->
            new TagSuggestions("synopsis", null, HASH, "model-v1", "policy-v1", HASH, candidates));
    assertThrows(
        ApplicationException.class,
        () ->
            new TagSuggestions(
                "synopsis",
                publication(),
                "A".repeat(64),
                "model-v1",
                "policy-v1",
                HASH,
                candidates));
    assertThrows(
        ApplicationException.class,
        () ->
            new TagSuggestions(
                "synopsis", publication(), null, "model-v1", "policy-v1", HASH, candidates));
    assertThrows(
        ApplicationException.class,
        () ->
            new TagSuggestions("synopsis", publication(), HASH, "", "policy-v1", HASH, candidates));
    assertThrows(
        ApplicationException.class,
        () ->
            new TagSuggestions("synopsis", publication(), HASH, "model-v1", "", HASH, candidates));
    assertThrows(
        ApplicationException.class,
        () ->
            new TagSuggestions(
                "synopsis", publication(), HASH, "model-v1", "policy-v1", "malformed", candidates));
    assertThrows(
        ApplicationException.class,
        () ->
            new TagSuggestions(
                "synopsis", publication(), HASH, "model-v1", "policy-v1", null, candidates));
    var oversized = new ArrayList<TagSuggestions.Candidate>();
    for (int i = 0; i < 9; i++) {
      oversized.add(new TagSuggestions.Candidate(1, "tag-" + i));
    }
    assertThrows(ApplicationException.class, () -> suggestions(oversized));
  }

  private static TagSuggestions suggestions(List<TagSuggestions.Candidate> candidates) {
    return new TagSuggestions(
        "synopsis", publication(), HASH, "model-v1", "policy-v1", HASH, candidates);
  }

  private static PublicationVersion publication() {
    return new PublicationVersion(
        "document",
        "publication",
        "revision",
        "generation",
        HASH,
        "parser-v1",
        new IndexTarget("embedding", "projection", "embedding-v1", 3),
        HASH,
        1);
  }
}
