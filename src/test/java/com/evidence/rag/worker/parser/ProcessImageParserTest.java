package com.evidence.rag.worker.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.evidence.rag.model.domain.ImageDimensions;
import com.evidence.rag.model.domain.ImageOcrOptions;
import com.evidence.rag.model.domain.ImageTextRegion;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ProcessImageParserTest {
  @Test
  void transfersOriginalBytesToRealChildAndNormalizesItsUnicodeTranscript() throws Exception {
    var bytes = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(new BufferedImage(7, 11, BufferedImage.TYPE_INT_RGB), "png", bytes));
    byte[] original = bytes.toByteArray();
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
    var options = new ImageOcrOptions(Path.of("/synthetic/tesseract"), "eng", "5.5.3");
    try (var parser =
        new ProcessImageParser(
            options, Duration.ofSeconds(5), OcrFixture.class.getName(), List.of(hash))) {
      var image = parser.parse("synthetic.png", "image/png", original);
      var parsed = image.text();
      assertEquals(1, parsed.pages().size());
      assertEquals(1, parsed.pages().getFirst().number());
      assertEquals("😀预算470万元。\n", parsed.pages().getFirst().text());
      assertEquals(1, parsed.segments().size());
      assertEquals(0, parsed.segments().getFirst().start());
      assertEquals(9, parsed.segments().getFirst().end());
      assertEquals("😀预算470万元。", parsed.segments().getFirst().text());
      assertEquals(new ImageDimensions(7, 11), image.dimensions());
      assertEquals(List.of(new ImageTextRegion(0, 9, 0, 0, 7, 11)), image.regions());
    }
  }

  /** A real child at the process seam, not a claim of Tesseract recognition quality. */
  public static final class OcrFixture {
    public static void main(String[] args) throws Exception {
      byte[] original = System.in.readNBytes(10 * 1024 * 1024 + 1);
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
      if (!hash.equals(args[0])
          || System.getenv().keySet().stream()
              .anyMatch(key -> !key.equals("__CF_USER_TEXT_ENCODING"))) {
        System.exit(3);
      }
      System.out.write(
          ("level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\r\n"
                  + "1\t1\t0\t0\t0\t0\t0\t0\t7\t11\t-1\t\r\n"
                  + "2\t1\t1\t0\t0\t0\t0\t0\t7\t11\t-1\t\r\n"
                  + "3\t1\t1\t1\t0\t0\t0\t0\t7\t11\t-1\t\r\n"
                  + "4\t1\t1\t1\t1\t0\t0\t0\t7\t11\t-1\t\r\n"
                  + "5\t1\t1\t1\t1\t1\t0\t0\t7\t11\t95\t😀预算470万元。\r\n")
              .getBytes(StandardCharsets.UTF_8));
    }
  }
}
