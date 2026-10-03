package com.evidence.rag.model.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.TagSuggestions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class TagSuggestionDtoTest {
  private static final String HASH = "a".repeat(64);

  @Test
  void confirmationCopiesOnlyFingerprintAndUniqueServerOrdinalsWithoutReordering() {
    var selected = new ArrayList<>(List.of(3, 1, 8));
    var command = new TagSuggestionApplyCommand(HASH, selected);
    selected.clear();
    assertEquals(List.of(3, 1, 8), command.ordinals());
    assertEquals(HASH, command.suggestionFingerprint());
    assertThrows(UnsupportedOperationException.class, () -> command.ordinals().clear());
    assertEquals("TagSuggestionApplyCommand[redacted]", command.toString());
  }

  @Test
  void confirmationRequiresLowercaseFingerprintAndNonEmptyBoundedDistinctSelection() {
    for (String hash : Arrays.asList(null, "", "a".repeat(63), "a".repeat(65), "A".repeat(64))) {
      assertThrows(
          ApplicationException.class, () -> new TagSuggestionApplyCommand(hash, List.of(1)));
    }
    for (List<Integer> selected :
        Arrays.<List<Integer>>asList(
            null,
            List.of(),
            Arrays.asList((Integer) null),
            List.of(0),
            List.of(9),
            List.of(1, 1),
            List.of(1, 2, 3, 4, 5, 6, 7, 8, 1))) {
      assertThrows(ApplicationException.class, () -> new TagSuggestionApplyCommand(HASH, selected));
    }
    assertEquals(
        List.of(1, 2, 3, 4, 5, 6, 7, 8),
        new TagSuggestionApplyCommand(HASH, List.of(1, 2, 3, 4, 5, 6, 7, 8)).ordinals());
  }

  @Test
  void existingTagsAndPermissionsStaySeparateFromImmutableSuggestionIdentity() {
    var tags = new ArrayList<>(List.of("manual-private-label"));
    var suggestions = suggestions();
    var result = new TagSuggestionResult(suggestions, tags, false);
    tags.clear();
    assertEquals(List.of("manual-private-label"), result.existingTags());
    assertEquals(suggestions, result.suggestions());
    assertFalse(result.canApply());
    assertThrows(UnsupportedOperationException.class, () -> result.existingTags().clear());
    assertEquals("TagSuggestionResult[redacted]", result.toString());
    assertThrows(ApplicationException.class, () -> new TagSuggestionResult(null, List.of(), true));
    assertThrows(
        ApplicationException.class, () -> new TagSuggestionResult(suggestions, null, true));
    assertThrows(
        ApplicationException.class,
        () -> new TagSuggestionResult(suggestions, Arrays.asList((String) null), true));
  }

  private static TagSuggestions suggestions() {
    var publication =
        new PublicationVersion(
            "document",
            "publication",
            "revision",
            "generation",
            HASH,
            "parser-v1",
            new IndexTarget("embedding", "projection", "embedding-v1", 3),
            HASH,
            1);
    return new TagSuggestions(
        "synopsis",
        publication,
        HASH,
        "model-v1",
        "policy-v1",
        HASH,
        List.of(new TagSuggestions.Candidate(1, "预算")));
  }
}
