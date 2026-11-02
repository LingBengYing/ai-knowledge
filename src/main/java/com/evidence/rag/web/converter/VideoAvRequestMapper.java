package com.evidence.rag.web.converter;

import com.evidence.rag.model.domain.VideoAvMode;
import com.evidence.rag.model.dto.VideoAvAnswerCommand;
import java.util.Set;

/** Exact independent video audiovisual input; no attachment or implicit mode conversion. */
public final class VideoAvRequestMapper {
  public static final int MAX_REQUEST_BYTES = 65536;
  private static final MediaAnswerRequests REQUESTS =
      new MediaAnswerRequests(MAX_REQUEST_BYTES, "视频音画问答请求字段或取值无效。", "视频音画问答请求超过接收限额。");

  private VideoAvRequestMapper() {}

  public static VideoAvAnswerCommand command(byte[] body) {
    var node = REQUESTS.parse(body, MAX_REQUEST_BYTES);
    REQUESTS.shape(node, Set.of("question", "mode"), Set.of("question", "mode", "document_ids"));
    if (!node.path("mode").isString()) {
      throw REQUESTS.invalid();
    }
    VideoAvMode mode;
    try {
      mode = VideoAvMode.valueOf(node.path("mode").stringValue());
    } catch (IllegalArgumentException failure) {
      throw REQUESTS.invalid();
    }
    return new VideoAvAnswerCommand(REQUESTS.answer(node), mode);
  }
}
