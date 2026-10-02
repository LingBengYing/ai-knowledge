package com.evidence.rag.service;

import com.evidence.rag.model.domain.VisualImage;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/** No business data or rendered text: blue circle left, red square right, on white. */
final class VisualSyntheticFixture {
  private VisualSyntheticFixture() {}

  static VisualImage image(String format) throws IOException {
    var canvas = new BufferedImage(640, 320, BufferedImage.TYPE_INT_RGB);
    var graphics = canvas.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, 640, 320);
      graphics.setColor(Color.BLUE);
      graphics.fillOval(80, 80, 160, 160);
      graphics.setColor(Color.RED);
      graphics.fillRect(400, 80, 160, 160);
    } finally {
      graphics.dispose();
    }
    var bytes = new ByteArrayOutputStream();
    if (!ImageIO.write(canvas, format, bytes)) {
      throw new IOException("Synthetic image encoder unavailable");
    }
    return new VisualImage("image/" + format, bytes.toByteArray());
  }
}
