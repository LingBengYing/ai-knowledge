package com.evidence.rag.client.model;

import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Strict provider-response reading and the stable failure codes shared by the model Adapters. */
final class ModelJson {
  private ModelJson() {}

  static String string(JsonNode node) {
    if (!node.isString()) {
      throw invalidResponse();
    }
    return node.stringValue();
  }

  /** Requires an object whose field names are exactly {@code fields}. */
  static void exactFields(JsonNode node, Set<String> fields) {
    if (!node.isObject() || !new HashSet<>(node.propertyNames()).equals(fields)) {
      throw invalidResponse();
    }
  }

  static TextModels.Failure invalidInput() {
    return new TextModels.Failure("model_invalid_input");
  }

  static TextModels.Failure invalidResponse() {
    return new TextModels.Failure("model_invalid_response");
  }

  static TextModels.Failure invalidConfiguration() {
    return new TextModels.Failure("model_invalid_configuration");
  }
}
