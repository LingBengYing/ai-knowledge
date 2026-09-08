package com.evidence.rag.model.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Untrusted input stays representable; verified output cannot carry contradictory or unsafe data.
 */
class GroundingDomainTest {
  private static final String PHYSICAL_ID = "physical-fixture";
  private static final String HASH = "a".repeat(64);

  @Test
  void untrustedInputPreservesMalformedValuesForWholeRequestVerificationAndRedactsThem() {
    for (String quote : Arrays.asList(null, "", "\uD83D", "\u0000", "synthetic-private-source")) {
      var input = new GroundingQuote("unknown", quote);
      assertEquals("unknown", input.physicalId());
      assertEquals(quote, input.quote());
      assertEquals("GroundingQuote[redacted]", input.toString());
    }
    assertNull(new GroundingQuote(null, "untrusted").physicalId());
  }

  @Test
  void verifiedQuoteRequiresBoundedNonemptyPhysicalIdentity() {
    for (String id : Arrays.asList(null, "", "bad\nidentity", "i".repeat(129))) {
      invalid(() -> new GroundedQuote(id, 0, 4, "fact", List.of(HASH)));
    }
    assertEquals(
        "i".repeat(128),
        new GroundedQuote("i".repeat(128), 0, 4, "fact", List.of(HASH)).physicalId());
  }

  @Test
  void verifiedQuoteRequiresAnExactNonemptyUnicodeCodePointRange() {
    List<Runnable> invalidQuotes =
        List.of(
            () -> new GroundedQuote(PHYSICAL_ID, -1, 3, "fact", List.of(HASH)),
            () -> new GroundedQuote(PHYSICAL_ID, 4, 4, "", List.of(HASH)),
            () -> new GroundedQuote(PHYSICAL_ID, 5, 4, "fact", List.of(HASH)),
            () -> new GroundedQuote(PHYSICAL_ID, 0, 3, "fact", List.of(HASH)),
            () -> new GroundedQuote(PHYSICAL_ID, 0, 1, null, List.of(HASH)),
            () -> new GroundedQuote(PHYSICAL_ID, 0, 1, " ", List.of(HASH)),
            () -> new GroundedQuote(PHYSICAL_ID, 0, 1, "\u0000", List.of(HASH)),
            () -> new GroundedQuote(PHYSICAL_ID, 0, 1, "\uD83D", List.of(HASH)),
            () -> new GroundedQuote(PHYSICAL_ID, 0, 1, "\uDE00", List.of(HASH)),
            () -> new GroundedQuote(PHYSICAL_ID, 0, 1201, "x".repeat(1201), List.of(HASH)));
    invalidQuotes.forEach(GroundingDomainTest::invalid);
    var exact = new GroundedQuote(PHYSICAL_ID, 10, 13, "😀e\u0301", List.of(HASH));
    assertEquals("😀e\u0301", exact.quote());
    assertEquals(10, exact.start());
    assertEquals(13, exact.end());
    assertEquals(
        1200, new GroundedQuote(PHYSICAL_ID, 0, 1200, "x".repeat(1200), List.of(HASH)).end());
  }

  @Test
  void verifiedQuoteCarriesOnlyOneToEightWellFormedFactHashes() {
    List<List<String>> invalidHashes =
        Arrays.asList(
            null,
            List.of(),
            Collections.singletonList(null),
            List.of("synthetic-private-fact"),
            List.of("A".repeat(64)),
            List.of("a".repeat(63)),
            List.of("g".repeat(64)),
            Collections.nCopies(9, HASH));
    for (var hashes : invalidHashes) {
      invalid(() -> new GroundedQuote(PHYSICAL_ID, 0, 4, "fact", hashes));
    }
    assertEquals(
        8,
        new GroundedQuote(PHYSICAL_ID, 0, 4, "fact", Collections.nCopies(8, HASH))
            .factHashes()
            .size());
  }

  @Test
  void supportedAndRefusedResultsCannotContradictTheirReasonOrEvidence() {
    var quote = validQuote();
    invalid(() -> new GroundingResult(true, "supported", List.of()));
    invalid(() -> new GroundingResult(true, "invalid_quote", List.of(quote)));
    invalid(() -> new GroundingResult(false, "supported", List.of()));
    invalid(() -> new GroundingResult(false, "invalid_quote", List.of(quote)));
    invalid(() -> new GroundingResult(true, "supported", null));
    invalid(() -> new GroundingResult(true, "supported", Collections.singletonList(null)));
    assertEquals(List.of(quote), new GroundingResult(true, "supported", List.of(quote)).quotes());
  }

  @Test
  void refusalReasonIsOneOfTheExistingSafeCodesAndCannotContainSourceText() {
    for (String reason :
        List.of(
            "unsupported_question",
            "incomplete_evidence",
            "conflicting_evidence",
            "unsafe_evidence",
            "invalid_quote")) {
      var result = new GroundingResult(false, reason, List.of());
      assertFalse(result.supported());
      assertEquals(reason, result.reason());
    }
    for (String unsafe : Arrays.asList(null, "", "unknown_reason", "synthetic-private-source")) {
      invalid(() -> new GroundingResult(false, unsafe, List.of()));
    }
  }

  @Test
  void verifiedCollectionsAreSnapshotsAndAutomaticStringsContainNoSourceOrFactText() {
    var hashes = new ArrayList<>(List.of(HASH));
    var quote = new GroundedQuote(PHYSICAL_ID, 0, 4, "fact", hashes);
    hashes.clear();
    assertEquals(List.of(HASH), quote.factHashes());
    assertThrows(UnsupportedOperationException.class, () -> quote.factHashes().add(HASH));
    var quotes = new ArrayList<>(List.of(quote));
    var result = new GroundingResult(true, "supported", quotes);
    quotes.clear();
    assertEquals(List.of(quote), result.quotes());
    assertThrows(UnsupportedOperationException.class, () -> result.quotes().clear());
    assertEquals("GroundedQuote[redacted]", quote.toString());
    assertFalse(result.toString().contains("fact"));
    assertFalse(result.toString().contains(PHYSICAL_ID));
    assertFalse(result.toString().contains(HASH));
  }

  private static GroundedQuote validQuote() {
    return new GroundedQuote(PHYSICAL_ID, 0, 4, "fact", List.of(HASH));
  }

  private static void invalid(Runnable constructor) {
    var error = assertThrows(ApplicationException.class, constructor::run);
    assertEquals(FailureKind.INVALID_INPUT, error.kind());
    assertEquals("invalid_request", error.code());
    assertNull(error.getCause());
    assertFalse(error.toString().contains("synthetic-private"));
  }
}
