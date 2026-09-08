package com.evidence.rag.model.entity;

import com.evidence.rag.model.domain.Actor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Persisted audit envelope; only field names and canonical value hashes are retained. */
public record AuditEventEntity(
    String id,
    String workspaceId,
    String actorId,
    String entityId,
    String action,
    String fieldsJson,
    String beforeSha256,
    String afterSha256,
    String createdAt) {
  public static AuditEventEntity create(
      Actor actor,
      String entityId,
      String action,
      Object before,
      Object after,
      Set<String> fields) {
    String fieldJson =
        "["
            + String.join(",", fields.stream().sorted().map(field -> "\"" + field + "\"").toList())
            + "]";
    return new AuditEventEntity(
        UUID.randomUUID().toString(),
        actor.workspaceId(),
        actor.principalId(),
        entityId,
        action,
        fieldJson,
        digest(before),
        digest(after),
        Instant.now().toString());
  }

  private static String digest(Object object) {
    return sha256(canonical(object).getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static String canonical(Object value) {
    if (value == null) {
      return "N";
    }
    if (value instanceof Map<?, ?> map) {
      var sorted = new TreeMap<String, Object>();
      map.forEach((key, item) -> sorted.put((String) key, item));
      var output = new StringBuilder("M").append(sorted.size()).append(':');
      sorted.forEach((key, item) -> output.append(canonical(key)).append(canonical(item)));
      return output.toString();
    }
    if (value instanceof List<?> list) {
      var output = new StringBuilder("L").append(list.size()).append(':');
      list.forEach(item -> output.append(canonical(item)));
      return output.toString();
    }
    String text = value.toString();
    return "S" + text.length() + ":" + text;
  }
}
