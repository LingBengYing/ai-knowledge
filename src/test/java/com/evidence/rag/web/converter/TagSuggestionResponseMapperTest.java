package com.evidence.rag.web.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.IndexTarget;
import com.evidence.rag.model.domain.PublicationVersion;
import com.evidence.rag.model.domain.TagSuggestions;
import com.evidence.rag.model.dto.TagSuggestionResult;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TagSuggestionResponseMapperTest {
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void exposesOnlyVersionBoundCandidatesAndExistingTagsWithCurrentPermission() {
    var suggestions = suggestions(List.of(new TagSuggestions.Candidate(1, "预算")));
    var response =
        TagSuggestionResponseMapper.response(
            new TagSuggestionResult(suggestions, List.of("手工标签"), true));
    var value = json.valueToTree(response);
    assertEquals(
        Set.of(
            "document_id",
            "publication_id",
            "revision_id",
            "source_sha256",
            "synopsis_id",
            "input_fingerprint",
            "model_revision",
            "synopsis_policy_revision",
            "policy_revision",
            "suggestion_fingerprint",
            "existing_tags",
            "can_apply",
            "candidates"),
        new HashSet<>(value.propertyNames()));
    assertEquals("doc", value.path("document_id").asString());
    assertEquals("pub", value.path("publication_id").asString());
    assertEquals("rev", value.path("revision_id").asString());
    assertEquals("a".repeat(64), value.path("source_sha256").asString());
    assertEquals("synopsis", value.path("synopsis_id").asString());
    assertEquals("c".repeat(64), value.path("input_fingerprint").asString());
    assertEquals("model-v1", value.path("model_revision").asString());
    assertEquals("synopsis-policy-v1", value.path("synopsis_policy_revision").asString());
    assertEquals("java-synopsis-tags-v1", value.path("policy_revision").asString());
    assertEquals("d".repeat(64), value.path("suggestion_fingerprint").asString());
    assertEquals("手工标签", value.path("existing_tags").get(0).asString());
    assertTrue(value.path("can_apply").asBoolean());
    var candidate = value.path("candidates").get(0);
    assertEquals(Set.of("ordinal", "tag"), new HashSet<>(candidate.propertyNames()));
    assertEquals(1, candidate.path("ordinal").asInt());
    assertEquals("预算", candidate.path("tag").asString());
    assertFalse(response.toString().contains("手工标签"));
    assertFalse(response.candidates().getFirst().toString().contains("预算"));
  }

  @Test
  void readOnlyOrEmptySuggestionsDoNotInventTagsOrAnApplyPermission() {
    var value =
        json.valueToTree(
            TagSuggestionResponseMapper.response(
                new TagSuggestionResult(suggestions(List.of()), List.of(), false)));
    assertTrue(value.path("candidates").isArray());
    assertTrue(value.path("candidates").isEmpty());
    assertTrue(value.path("existing_tags").isArray());
    assertTrue(value.path("existing_tags").isEmpty());
    assertFalse(value.path("can_apply").asBoolean());
  }

  private static TagSuggestions suggestions(List<TagSuggestions.Candidate> candidates) {
    return new TagSuggestions(
        "synopsis",
        new PublicationVersion(
            "doc",
            "pub",
            "rev",
            "generation",
            "a".repeat(64),
            "parser-v1",
            new IndexTarget("embedding", "projection", "model-v1", 2),
            "b".repeat(64),
            1),
        "c".repeat(64),
        "model-v1",
        "synopsis-policy-v1",
        "d".repeat(64),
        candidates);
  }
}
