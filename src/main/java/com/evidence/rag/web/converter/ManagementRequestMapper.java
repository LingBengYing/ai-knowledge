package com.evidence.rag.web.converter;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.exception.FailureKind;
import com.evidence.rag.model.dto.DocumentActionCommand;
import com.evidence.rag.model.dto.DocumentPatchCommand;
import com.evidence.rag.model.query.DocumentQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict HTTP shape conversion. Business validation remains in the Service. */
public final class ManagementRequestMapper {
  private ManagementRequestMapper() {}

  public static DocumentQuery query(Map<String, String> values) {
    fields(values, Set.of("q", "type", "status", "folder_id", "tag", "sort", "page", "page_size"));
    return new DocumentQuery(
        values.getOrDefault("q", ""),
        values.get("type"),
        values.get("status"),
        values.get("folder_id"),
        values.get("tag"),
        values.getOrDefault("sort", "updated_desc"),
        integer(values.getOrDefault("page", "1")),
        integer(values.getOrDefault("page_size", "20")));
  }

  public static DocumentPatchCommand patch(Map<String, Object> body) {
    fields(body, Set.of("display_name", "folder_id", "tags"));
    return new DocumentPatchCommand(
        body.containsKey("display_name"),
        text(body.get("display_name")),
        body.containsKey("folder_id"),
        text(body.get("folder_id")),
        body.containsKey("tags"),
        strings(body.get("tags")));
  }

  public static DocumentActionCommand action(Map<String, Object> body) {
    fields(body, Set.of("document_ids", "action", "folder_id", "tags"));
    String action = text(body.get("action"));
    // Unimplemented lifecycle operations reject independently of their optional action fields.
    boolean lifecycle = "delete".equals(action) || "reindex".equals(action);
    return new DocumentActionCommand(
        strings(body.get("document_ids")),
        action,
        body.containsKey("folder_id"),
        lifecycle ? null : text(body.get("folder_id")),
        lifecycle ? null : strings(body.get("tags")));
  }

  public static String folderName(Map<String, Object> body) {
    fields(body, Set.of("name"));
    return text(body.get("name"));
  }

  private static void fields(Map<?, ?> values, Set<String> allowed) {
    if (values == null || !allowed.containsAll(values.keySet())) {
      throw invalid();
    }
  }

  private static String text(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof String text) {
      return text;
    }
    throw invalid();
  }

  private static List<String> strings(Object value) {
    if (value == null) {
      return null;
    }
    if (!(value instanceof List<?> list)) {
      throw invalid();
    }
    var result = new ArrayList<String>();
    for (Object item : list) {
      result.add(text(item));
    }
    return result;
  }

  private static int integer(String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException failure) {
      throw invalid();
    }
  }

  private static ApplicationException invalid() {
    return new ApplicationException(FailureKind.INVALID_INPUT, "invalid_request", "请求字段、长度或取值无效。");
  }
}
