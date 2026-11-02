package com.evidence.rag.web.converter;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.VideoAvMode;
import com.evidence.rag.model.dto.VideoAvAnswerCommand;
import com.evidence.rag.model.dto.VideoAvQueryCommand;
import com.evidence.rag.service.VideoAvCompilationService;
import java.util.Set;

/** Exact complete video references, independently admitted without decoding or storing them. */
public final class VideoAvQueryRequestMapper {
  public static final int MAX_REQUEST_BYTES = 28 * 1024 * 1024;
  private static final MediaAnswerRequests REQUESTS =
      new MediaAnswerRequests(MAX_REQUEST_BYTES, "视频音画参考问答请求字段或取值无效。", "视频音画参考问答请求超过接收限额。");

  private VideoAvQueryRequestMapper() {}

  public static VideoAvQueryCommand attached(byte[] body) {
    var node = REQUESTS.parse(body, MAX_REQUEST_BYTES);
    REQUESTS.shape(
        node,
        Set.of("question", "mode", "attachments"),
        Set.of("question", "mode", "attachments", "document_ids"));
    if (!node.path("mode").isString()
        || !node.path("attachments").isArray()
        || node.path("attachments").isEmpty()
        || node.path("attachments").size() > 3) {
      throw REQUESTS.invalid();
    }
    VideoAvMode mode;
    try {
      mode = VideoAvMode.valueOf(node.path("mode").stringValue());
    } catch (IllegalArgumentException failure) {
      throw REQUESTS.invalid();
    }
    var command = new VideoAvAnswerCommand(REQUESTS.answer(node), mode);
    try {
      return new VideoAvQueryCommand(
          command,
          REQUESTS.attachments(
              node.path("attachments"), VideoAvCompilationService::validateQueryInput));
    } catch (ApplicationException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw REQUESTS.invalid();
    }
  }
}
