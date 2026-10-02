package com.evidence.rag.tool.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageInputTest {
  @Test
  void validPngAndJpegPreserveTheirRealTypeAndOriginalDimensions() throws Exception {
    for (String format : new String[] {"png", "jpeg"}) {
      var bytes = new ByteArrayOutputStream();
      assertTrue(
          ImageIO.write(new BufferedImage(7, 11, BufferedImage.TYPE_INT_RGB), format, bytes));
      byte[] content = bytes.toByteArray();
      String filename = "synthetic." + format;
      String mime = "image/" + format;

      ImageInput.validateEnvelope(filename, mime, content);

      assertTrue(ImageInput.isImageName(filename));
      assertEquals(mime, ImageInput.canonicalMime(filename));
      var dimensions = ImageInput.inspect(content);
      assertEquals(7, dimensions.width());
      assertEquals(11, dimensions.height());
    }
  }

  @Test
  void animatedPngIsRejectedEvenWhenItsFirstFrameHasValidDimensions() throws Exception {
    var staticPng = new ByteArrayOutputStream();
    assertTrue(
        ImageIO.write(new BufferedImage(7, 11, BufferedImage.TYPE_INT_RGB), "png", staticPng));
    byte[] png = staticPng.toByteArray();
    var animated = new ByteArrayOutputStream();
    // Preserve the actual signature/IHDR and duplicate the first frame with valid APNG controls.
    animated.write(png, 0, 33);
    writeChunk(animated, "acTL", ByteBuffer.allocate(8).putInt(2).putInt(0).array());
    writeChunk(animated, "fcTL", frameControl(0));
    var compressedFrame = new ByteArrayOutputStream();
    for (int offset = 33; offset < png.length; ) {
      int size = ByteBuffer.wrap(png, offset, 4).getInt();
      String type = new String(png, offset + 4, 4, StandardCharsets.US_ASCII);
      if (type.equals("IDAT")) {
        compressedFrame.write(png, offset + 8, size);
        animated.write(png, offset, size + 12);
      }
      offset += size + 12;
    }
    assertTrue(compressedFrame.size() > 0);
    writeChunk(animated, "fcTL", frameControl(1));
    byte[] secondFrame =
        ByteBuffer.allocate(4 + compressedFrame.size())
            .putInt(2)
            .put(compressedFrame.toByteArray())
            .array();
    writeChunk(animated, "fdAT", secondFrame);
    writeChunk(animated, "IEND", new byte[0]);

    var failure =
        assertThrows(
            TextParser.Failure.class,
            () ->
                ImageInput.validateEnvelope("synthetic.png", "image/png", animated.toByteArray()));
    assertEquals("unsupported_document", failure.code());
  }

  private static byte[] frameControl(int sequence) {
    return ByteBuffer.allocate(26)
        .putInt(sequence)
        .putInt(7)
        .putInt(11)
        .putInt(0)
        .putInt(0)
        .putShort((short) 1)
        .putShort((short) 10)
        .put((byte) 0)
        .put((byte) 0)
        .array();
  }

  private static void writeChunk(ByteArrayOutputStream output, String type, byte[] data)
      throws Exception {
    byte[] name = type.getBytes(StandardCharsets.US_ASCII);
    var checksum = new CRC32();
    checksum.update(name);
    checksum.update(data);
    var encoded = new DataOutputStream(output);
    encoded.writeInt(data.length);
    encoded.write(name);
    encoded.write(data);
    encoded.writeInt((int) checksum.getValue());
  }
}
