package com.evidence.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.ImageDimensions;
import com.evidence.rag.model.domain.ImageOcrOptions;
import com.evidence.rag.model.domain.ImageTextRegion;
import com.evidence.rag.model.domain.ParsedImage;
import com.evidence.rag.repository.IngestionRepository;
import com.evidence.rag.repository.ManagementRepository;
import com.evidence.rag.security.authorization.DocumentPermissionPolicy;
import com.evidence.rag.support.PublishedCorpusFixture;
import com.evidence.rag.tool.parser.TextParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Authority acceptance of controlled OCR results; does not claim real OCR recognition quality. */
class ImageRegionServiceTest {
  private static final Actor OWNER = new Actor("org", "owner");
  private static final String TEXT = "Budget 650 USD\n";
  private static final ImageTextRegion AMOUNT = new ImageTextRegion(7, 10, 35, 0, 55, 10);
  private static final List<ImageTextRegion> REGIONS =
      List.of(
          new ImageTextRegion(0, 6, 0, 0, 30, 10),
          AMOUNT,
          new ImageTextRegion(11, 14, 60, 0, 85, 10));
  @TempDir Path directory;

  @ParameterizedTest
  @MethodSource("invalidRegions")
  void incompleteOrInvalidWordCoverageCannotCommitAnyPartialImage(List<ImageTextRegion> regions)
      throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var ingestion = ingestion(fixture);
      var task = ingestion.uploadDocument(OWNER, "budget.png", "image/png", image());
      var claim = ingestion.claimIngestion(OWNER.workspaceId()).orElseThrow();

      var failure =
          assertThrows(
              ApplicationException.class,
              () -> ingestion.completeImageIngestion(claim, parsed(regions)));

      assertEquals("parser_output_invalid", failure.code());
      assertEquals("processing", ingestion.ingestionStatus(OWNER, task.taskId()).state());
      assertThrows(
          ApplicationException.class, () -> ingestion.parsedEvidence(OWNER, task.documentId()));
      // The same valid attempt can still commit: no leaked page/segment/region blocks its write.
      assertTrue(ingestion.completeImageIngestion(claim, parsed(REGIONS)));
    }
  }

  @Test
  void declaredDimensionsMustMatchOriginalAndTextCompletionCannotBypassRegions() throws Exception {
    try (var fixture = new PublishedCorpusFixture(directory)) {
      var ingestion = ingestion(fixture);
      ingestion.uploadDocument(OWNER, "budget.png", "image/png", image());
      var claim = ingestion.claimIngestion(OWNER.workspaceId()).orElseThrow();
      var valid = parsed(REGIONS);

      assertEquals(
          "parser_output_invalid",
          assertThrows(
                  ApplicationException.class,
                  () ->
                      ingestion.completeImageIngestion(
                          claim,
                          new ParsedImage(valid.text(), new ImageDimensions(99, 60), REGIONS)))
              .code());
      assertEquals(
          "parser_output_invalid",
          assertThrows(
                  ApplicationException.class,
                  () -> ingestion.completeIngestion(claim, valid.text()))
              .code());
      assertTrue(ingestion.completeImageIngestion(claim, valid));
    }
  }

  private static Stream<List<ImageTextRegion>> invalidRegions() {
    return Stream.of(
        List.of(),
        REGIONS.subList(1, 3),
        List.of(REGIONS.getFirst(), REGIONS.getLast()),
        REGIONS.subList(0, 2),
        List.of(REGIONS.getFirst(), new ImageTextRegion(5, 10, 35, 0, 55, 10), REGIONS.getLast()),
        List.of(REGIONS.getFirst(), new ImageTextRegion(7, 10, 35, 0, 101, 10), REGIONS.getLast()),
        List.of(REGIONS.getFirst(), new ImageTextRegion(7, 11, 35, 0, 55, 10), REGIONS.getLast()));
  }

  private static IngestionService ingestion(PublishedCorpusFixture fixture) {
    return new IngestionService(
        fixture.authority.store(),
        new IngestionRepository(fixture.authority.store()),
        new ManagementRepository(fixture.authority.store()),
        new DocumentPermissionPolicy(),
        new ImageOcrOptions(Path.of("/synthetic/tesseract"), "eng", "5.5.3"));
  }

  private static ParsedImage parsed(List<ImageTextRegion> regions) {
    return new ParsedImage(
        new TextParser().parse("ocr.txt", "text/plain", TEXT.getBytes(StandardCharsets.UTF_8)),
        new ImageDimensions(100, 60),
        regions);
  }

  private static byte[] image() throws Exception {
    var output = new ByteArrayOutputStream();
    assertTrue(
        ImageIO.write(new BufferedImage(100, 60, BufferedImage.TYPE_INT_RGB), "png", output));
    return output.toByteArray();
  }
}
