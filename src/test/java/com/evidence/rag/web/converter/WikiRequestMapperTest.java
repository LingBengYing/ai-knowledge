package com.evidence.rag.web.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WikiRequestMapperTest {
  private static final String PROPOSAL =
      """
      {"base_version":0,"title":"灯塔","kind":"topic","document_ids":["doc-1"],
      "generation_method":"extractive"}
      """;

  @Test
  void parsesOnlyExplicitGenerationAndDocumentIdentities() {
    var command = WikiRequestMapper.proposal(bytes(PROPOSAL));
    assertNull(command.pageId());
    assertEquals("灯塔", command.title());
    assertEquals(List.of("doc-1"), command.documentIds());
    assertEquals("extractive", command.generationMethod());
    var update =
        PROPOSAL.replace("\"base_version\":0", "\"page_id\":\"page-1\",\"base_version\":3");
    assertEquals(3, WikiRequestMapper.proposal(bytes(update)).baseVersion());
    assertThrows(UnsupportedOperationException.class, () -> command.documentIds().add("another"));
  }

  @Test
  void rejectsUnknownSourceLocationsImplicitModelGenerationAndDuplicateFields() {
    for (String invalid :
        List.of(
            PROPOSAL.replace("\"kind\":\"topic\",", ""),
            PROPOSAL.replace("\"generation_method\":\"extractive\"", "\"locator\":{\"page\":1}"),
            PROPOSAL.replace("\"base_version\":0", "\"base_version\":0,\"base_version\":1"),
            PROPOSAL.replace("[\"doc-1\"]", "[\"doc-1\",12]"),
            PROPOSAL + "{}",
            "[]")) {
      assertThrows(ApplicationException.class, () -> WikiRequestMapper.proposal(bytes(invalid)));
    }
  }

  @Test
  void rejectsInvalidValuesWithoutDefaultingToAllSourcesOrPaidGeneration() {
    for (String invalid :
        List.of(
            PROPOSAL.replace("[\"doc-1\"]", "[]"),
            PROPOSAL.replace("[\"doc-1\"]", "[\"doc-1\",\"doc-1\"]"),
            PROPOSAL.replace("extractive", "auto"),
            PROPOSAL.replace("\"base_version\":0", "\"base_version\":1"))) {
      assertThrows(ApplicationException.class, () -> WikiRequestMapper.proposal(bytes(invalid)));
    }
  }

  @Test
  void acceptsExactReviewBodiesAndValidatesPaginationShapes() {
    assertEquals(3, WikiRequestMapper.accept(bytes("{\"base_version\":3}")));
    WikiRequestMapper.dismiss(bytes("{}"));
    assertThrows(
        ApplicationException.class,
        () -> WikiRequestMapper.accept(bytes("{\"base_version\":3.0}")));
    assertThrows(
        ApplicationException.class,
        () -> WikiRequestMapper.dismiss(bytes("{\"status\":\"accepted\"}")));
    WikiRequestMapper.query(Map.of("limit", new String[] {"20"}), Set.of("limit"));
    assertThrows(
        ApplicationException.class,
        () -> WikiRequestMapper.query(Map.of("limit", new String[] {"2", "3"}), Set.of("limit")));
    assertThrows(
        ApplicationException.class,
        () ->
            WikiRequestMapper.query(Map.of("workspace", new String[] {"other"}), Set.of("limit")));
    assertEquals(20, WikiRequestMapper.number(null, 20));
    assertEquals(0, WikiRequestMapper.number("0", 20));
    assertThrows(ApplicationException.class, () -> WikiRequestMapper.number("-1", 20));
    assertThrows(ApplicationException.class, () -> WikiRequestMapper.number("9999999999", 20));
  }

  @Test
  void rejectsMalformedUtf8AndOverlargeBodies() {
    assertThrows(
        ApplicationException.class,
        () -> WikiRequestMapper.proposal(new byte[] {(byte) 0xc3, 0x28}));
    assertEquals(
        "wiki_request_too_large",
        assertThrows(
                ApplicationException.class,
                () -> WikiRequestMapper.proposal(new byte[WikiRequestMapper.MAX_BYTES + 1]))
            .code());
    assertThrows(ApplicationException.class, () -> WikiRequestMapper.proposal(null));
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
