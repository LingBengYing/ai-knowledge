package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.domain.DocumentSelection;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvidenceDomainTest {
  @Test
  void directSelectionRejectsIdsOutsideProjectionCompatibleGrammar() {
    for (String invalid :
        List.of("../private", "has space", "/root", "*", "资料", ".hidden", "-prefix")) {
      var problem =
          assertThrows(
              ApplicationException.class,
              () -> DocumentSelection.selected(List.of(invalid)),
              invalid);
      assertEquals(FailureKind.INVALID_INPUT, problem.kind());
    }
  }

  @Test
  void selectionKeepsExplicitEmptyAndDefensivelyCopiesValidIds() {
    var ids = new ArrayList<>(List.of("doc:1", "doc_2-v1.txt"));
    var selection = DocumentSelection.selected(ids);
    ids.clear();
    assertEquals(List.of("doc:1", "doc_2-v1.txt"), selection.documentIds());
    assertEquals(false, DocumentSelection.selected(List.of()).all());
    assertEquals(true, DocumentSelection.allDocuments().all());
    assertThrows(UnsupportedOperationException.class, () -> selection.documentIds().clear());
  }
}
