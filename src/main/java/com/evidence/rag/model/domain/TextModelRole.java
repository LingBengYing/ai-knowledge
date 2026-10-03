package com.evidence.rag.model.domain;

import com.evidence.rag.exception.ModelConfigurationInputException;
import java.util.Locale;

public enum TextModelRole {
  EMBEDDING,
  RERANK,
  GENERATION,
  PROJECTION;

  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static TextModelRole parse(String value) {
    for (var role : values()) {
      if (role.wire().equals(value)) {
        return role;
      }
    }
    throw new ModelConfigurationInputException("role");
  }
}
