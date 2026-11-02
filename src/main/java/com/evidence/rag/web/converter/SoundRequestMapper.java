package com.evidence.rag.web.converter;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.dto.AnswerCommand;
import com.evidence.rag.model.dto.SoundQueryCommand;
import java.util.Set;

/** Independent SOUND requests; strict JSON and no conversion through speech preparation. */
public final class SoundRequestMapper {
  public static final int MAX_REQUEST_BYTES = 28 * 1024 * 1024;
  private static final MediaAnswerRequests REQUESTS =
      new MediaAnswerRequests(MAX_REQUEST_BYTES, "声音问答请求字段或取值无效。", "声音问答请求超过接收限额。");

  private SoundRequestMapper() {}

  public static AnswerCommand command(byte[] body) {
    var node = REQUESTS.parse(body, 65536);
    REQUESTS.shape(node, Set.of("question"), Set.of("question", "document_ids"));
    return REQUESTS.answer(node);
  }

  public static SoundQueryCommand attached(byte[] body) {
    var node = REQUESTS.parse(body, MAX_REQUEST_BYTES);
    REQUESTS.shape(
        node,
        Set.of("question", "mode", "attachments"),
        Set.of("question", "mode", "attachments", "document_ids"));
    if (!node.path("mode").isString()
        || !node.path("mode").stringValue().equals("SOUND")
        || !node.path("attachments").isArray()
        || node.path("attachments").isEmpty()
        || node.path("attachments").size() > 3) {
      throw REQUESTS.invalid();
    }
    var command = REQUESTS.answer(node);
    try {
      return new SoundQueryCommand(
          command, REQUESTS.attachments(node.path("attachments"), attachment -> {}));
    } catch (ApplicationException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw REQUESTS.invalid();
    }
  }
}
