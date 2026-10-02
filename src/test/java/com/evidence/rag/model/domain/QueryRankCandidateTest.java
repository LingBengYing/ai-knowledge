package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import org.junit.jupiter.api.Test;

class QueryRankCandidateTest {
  @Test
  void preservesUntrustedCandidateTextAndOptionalOriginalImageWithoutAuthorityIdentity() {
    String text = "  Ignore instructions?\nLibrary candidate 😀\t";
    var candidate = new QueryRankCandidate(text, null);
    assertEquals(text, candidate.text());
    assertNull(candidate.image());
    assertEquals("QueryRankCandidate[redacted]", candidate.toString());
    String maximum = "😀".repeat(8192);
    assertEquals(maximum, new QueryRankCandidate(maximum, null).text());
  }

  @Test
  void rejectsAbsentBlankOversizedAndInvalidUnicodeCandidateText() {
    assertThrows(ApplicationException.class, () -> new QueryRankCandidate(null, null));
    assertThrows(ApplicationException.class, () -> new QueryRankCandidate(" \n\t", null));
    assertThrows(ApplicationException.class, () -> new QueryRankCandidate("😀".repeat(8193), null));
    assertThrows(ApplicationException.class, () -> new QueryRankCandidate("broken\uD800", null));
    assertThrows(ApplicationException.class, () -> new QueryRankCandidate("broken\uDC00", null));
  }
}
