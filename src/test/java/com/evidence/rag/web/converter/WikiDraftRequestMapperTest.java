package com.evidence.rag.web.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evidence.rag.exception.ApplicationException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class WikiDraftRequestMapperTest {
  @Test
  void acceptsLiteralUnverifiedTextAndExactCasVersion() {
    var create = WikiDraftRequestMapper.create(bytes("{\"title\":\"笔记\",\"body\":\"正文\\n😀\"}"));
    assertEquals("正文\n😀", create.body());
    assertEquals(
        23,
        WikiDraftRequestMapper.update(bytes("{\"version\":23,\"title\":\"标题\",\"body\":\"\"}"))
            .version());
    assertEquals(9007199254740990L, WikiDraftRequestMapper.version("9007199254740990"));
  }

  @Test
  void rejectsDuplicateFieldsForgedSourcesMalformedUtf8AndIncorrectVersions() {
    for (String json :
        List.of(
            "{}",
            "[]",
            "null",
            "{\"title\":\"一\",\"title\":\"二\",\"body\":\"\"}",
            "{\"title\":\"一\",\"body\":\"\",\"sources\":[]}",
            "{\"title\":1,\"body\":\"\"}",
            "{\"title\":\"一\",\"body\":\"\"} {}")) {
      assertThrows(ApplicationException.class, () -> WikiDraftRequestMapper.create(bytes(json)));
    }
    assertThrows(
        ApplicationException.class, () -> WikiDraftRequestMapper.create(new byte[] {(byte) 0xff}));
    assertThrows(
        ApplicationException.class,
        () -> WikiDraftRequestMapper.create(new byte[WikiDraftRequestMapper.MAX_BYTES + 1]));
    assertThrows(
        ApplicationException.class,
        () ->
            WikiDraftRequestMapper.update(
                bytes("{\"version\":1.5,\"title\":\"一\",\"body\":\"\"}")));
    for (String version : List.of("", "1.0", "+1", "-1", "99999999999999999")) {
      assertThrows(ApplicationException.class, () -> WikiDraftRequestMapper.version(version));
    }
    assertThrows(ApplicationException.class, () -> WikiDraftRequestMapper.version(null));
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
