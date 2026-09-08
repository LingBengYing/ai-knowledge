package com.evidence.rag.client.vector;

import static com.evidence.rag.client.vector.RetrievalProjection.MAX_TEXT_BYTES;

import com.evidence.rag.exception.ProjectionException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fixed collection schema and index contract; never performs remote mutation or schema repair. */
final class MilvusSchema {
  private final MilvusRestProjection.Settings settings;
  private final JsonMapper json;

  MilvusSchema(MilvusRestProjection.Settings settings, JsonMapper json) {
    this.settings = settings;
    this.json = json;
  }

  private String marker() {
    return "evidence-rag-java-text-v1;workspace="
        + settings.workspaceId()
        + ";embedding="
        + settings.embeddingIdentity()
        + ";dim="
        + settings.dimension();
  }

  Map<String, Object> createRequest() {
    var fields = new ArrayList<Map<String, Object>>();
    for (String name : List.of("id", "workspace_id", "document_id", "revision_id", "text")) {
      var parameters = new HashMap<String, Object>();
      parameters.put("max_length", name.equals("text") ? MAX_TEXT_BYTES : 128);
      if (name.equals("text")) {
        parameters.put("enable_analyzer", true);
        parameters.put("analyzer_params", Map.of("type", "standard"));
      }
      fields.add(
          Map.of(
              "fieldName",
              name,
              "dataType",
              "VarChar",
              "isPrimary",
              name.equals("id"),
              "elementTypeParams",
              parameters));
    }
    fields.add(
        Map.of(
            "fieldName",
            "dense",
            "dataType",
            "FloatVector",
            "elementTypeParams",
            Map.of("dim", settings.dimension())));
    fields.add(Map.of("fieldName", "sparse", "dataType", "SparseFloatVector"));
    var body = base();
    body.put("description", marker());
    body.put(
        "schema",
        Map.of(
            "autoID",
            false,
            "enableDynamicField",
            false,
            "fields",
            fields,
            "functions",
            List.of(
                Map.of(
                    "name",
                    "text_bm25",
                    "type",
                    "BM25",
                    "inputFieldNames",
                    List.of("text"),
                    "outputFieldNames",
                    List.of("sparse"),
                    "params",
                    Map.of()))));
    body.put(
        "indexParams",
        List.of(
            Map.of(
                "fieldName",
                "dense",
                "indexName",
                "dense_index",
                "indexType",
                "FLAT",
                "metricType",
                "COSINE"),
            Map.of(
                "fieldName",
                "sparse",
                "indexName",
                "sparse_index",
                "indexType",
                "SPARSE_INVERTED_INDEX",
                "metricType",
                "BM25")));
    body.put("params", Map.of("consistencyLevel", "Strong"));
    return body;
  }

  void validate(JsonNode schema) {
    check(text(schema, "collectionName").equals(settings.collection()));
    check(text(schema, "description").equals(marker()));
    check(text(schema, "consistencyLevel").equals("Strong"));
    checkBoolean(schema, "autoId", false);
    checkBoolean(schema, "enableDynamicField", false);
    JsonNode fields = schema.path("fields");
    check(fields.isArray() && fields.size() == 7);
    var byName = new HashMap<String, JsonNode>();
    for (JsonNode field : fields) {
      check(byName.put(text(field, "name"), field) == null);
    }
    for (String name :
        List.of("id", "workspace_id", "document_id", "revision_id", "text", "dense", "sparse")) {
      JsonNode field = byName.get(name);
      check(field != null);
      checkBoolean(field, "primaryKey", name.equals("id"));
      checkBoolean(field, "autoId", false);
      checkBoolean(field, "nullable", false);
      String expectedType =
          name.equals("dense")
              ? "FloatVector"
              : name.equals("sparse") ? "SparseFloatVector" : "VarChar";
      check(text(field, "type").equals(expectedType));
      Map<String, String> parameters = parameters(field.path("params"));
      if (name.equals("dense")) {
        check(Integer.toString(settings.dimension()).equals(parameters.get("dim")));
      } else if (name.equals("sparse")) {
        checkBoolean(field, "isFunctionOutput", true);
      } else {
        check(
            Integer.toString(name.equals("text") ? MAX_TEXT_BYTES : 128)
                .equals(parameters.get("max_length")));
      }
      if (name.equals("text")) {
        check("true".equals(parameters.get("enable_analyzer")));
        try {
          check(
              json.readTree(parameters.getOrDefault("analyzer_params", "null"))
                  .equals(json.valueToTree(Map.of("type", "standard"))));
        } catch (RuntimeException ignored) {
          throw new ProjectionException("projection_schema_mismatch");
        }
      }
    }
    JsonNode functions = schema.path("functions");
    check(functions.isArray() && functions.size() == 1);
    JsonNode bm25 = functions.get(0);
    check(text(bm25, "name").equals("text_bm25"));
    JsonNode type = bm25.path("type");
    check(
        (type.isIntegralNumber() && type.toString().equals("1"))
            || (type.isString() && type.stringValue().equals("BM25")));
    check(bm25.path("inputFieldNames").equals(json.valueToTree(List.of("text"))));
    check(bm25.path("outputFieldNames").equals(json.valueToTree(List.of("sparse"))));
    check(parameters(bm25.path("params")).isEmpty());
  }

  void validateIndex(JsonNode indexes, String field, String name, String type, String metric) {
    check(indexes.isArray() && indexes.size() == 1);
    JsonNode index = indexes.get(0);
    check(
        text(index, "indexName").equals(name)
            && text(index, "fieldName").equals(field)
            && text(index, "indexType").equals(type)
            && text(index, "metricType").equals(metric)
            && text(index, "indexState").equals("Finished"));
  }

  private static String text(JsonNode object, String field) {
    check(object != null && object.path(field).isString());
    return object.path(field).stringValue();
  }

  private static void checkBoolean(JsonNode object, String field, boolean expected) {
    check(object.path(field).isBoolean() && object.path(field).booleanValue() == expected);
  }

  private static Map<String, String> parameters(JsonNode node) {
    var result = new HashMap<String, String>();
    if (node.isMissingNode() || node.isNull()) {
      return result;
    }
    check(node.isArray());
    for (JsonNode pair : node) {
      check(result.put(text(pair, "key"), text(pair, "value")) == null);
    }
    return result;
  }

  private static void check(boolean valid) {
    if (!valid) {
      throw new ProjectionException("projection_schema_mismatch");
    }
  }

  private Map<String, Object> base() {
    var body = new HashMap<String, Object>();
    body.put("dbName", settings.database());
    body.put("collectionName", settings.collection());
    return body;
  }
}
