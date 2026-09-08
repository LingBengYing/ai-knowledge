package com.evidence.rag.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Bytecode rules shared by the real production gate and deliberately compiled counterexamples. */
final class ArchitectureRules {
  private static final Map<String, Set<String>> ALLOWED =
      Map.ofEntries(
          Map.entry(
              "controller",
              Set.of(
                  "controller",
                  "web",
                  "service",
                  "model.dto",
                  "model.query",
                  "model.vo",
                  "model.domain",
                  "exception",
                  "security.web",
                  "security.authentication")),
          Map.entry(
              "web",
              Set.of(
                  "web",
                  "service",
                  "model.dto",
                  "model.query",
                  "model.vo",
                  "model.domain",
                  "exception",
                  "security.web",
                  "security.authentication")),
          Map.entry(
              "service",
              Set.of(
                  "service",
                  "repository",
                  "security.authorization",
                  "client",
                  "worker",
                  "tool",
                  "model.entity",
                  "model.dto",
                  "model.query",
                  "model.domain",
                  "exception")),
          Map.entry(
              "repository",
              Set.of("repository", "model.entity", "model.query", "model.domain", "exception")),
          Map.entry(
              "security.authentication",
              Set.of("security.authentication", "model.domain", "exception")),
          Map.entry(
              "security.authorization",
              Set.of("security.authorization", "model.domain", "exception")),
          Map.entry(
              "security.web",
              Set.of("security.web", "security.authentication", "model.domain", "exception")),
          Map.entry("client", Set.of("client", "model.domain", "model.dto", "tool", "exception")),
          Map.entry(
              "worker",
              Set.of("worker", "client", "model.domain", "model.dto", "tool", "exception")),
          Map.entry("tool", Set.of("tool", "model.domain")),
          Map.entry("model.domain", Set.of("model.domain", "exception")),
          Map.entry("model.entity", Set.of("model.entity", "model.domain")),
          Map.entry("model.dto", Set.of("model.dto", "model.domain")),
          Map.entry("model.query", Set.of("model.query", "model.domain")),
          Map.entry("model.vo", Set.of("model.vo", "model.domain")),
          Map.entry("job", Set.of("job", "service", "model.domain", "model.dto", "exception")),
          Map.entry(
              "bootstrap",
              Set.of("bootstrap", "service", "model.domain", "model.dto", "exception")),
          Map.entry("exception", Set.of("exception")));
  private static final Set<String> SQL_METHODS =
      Set.of(
          "execute",
          "executeUpdate",
          "executeQuery",
          "prepare",
          "prepareStatement",
          "query",
          "rows",
          "count",
          "sql");
  private static final Map<String, String> STEREOTYPES =
      Map.of(
          "org.springframework.web.bind.annotation.RestController", "controller",
          "org.springframework.stereotype.Controller", "controller",
          "org.springframework.stereotype.Service", "service",
          "org.springframework.stereotype.Repository", "repository",
          "org.springframework.context.annotation.Configuration", "config",
          "org.springframework.boot.context.properties.ConfigurationProperties", "config");

  private ArchitectureRules() {}

  static List<String> violations(JavaClasses classes, String root) {
    var violations = new TreeSet<String>();
    var layerGraph = new HashMap<String, Set<String>>();
    var serviceGraph = new HashMap<String, Set<String>>();
    for (JavaClass type : classes) {
      if (!inside(type.getPackageName(), root)) {
        continue;
      }
      String layer = layer(type, root);
      if (layer == null || type.getSimpleName().equals("ManagementModule")) {
        violations.add("PACKAGE: retired or unclassified production type " + type.getName());
        continue;
      }
      for (var dependency : type.getDirectDependenciesFromSelf()) {
        JavaClass target = dependency.getTargetClass().getBaseComponentType();
        String targetLayer = layer(target, root);
        if (inside(target.getPackageName(), root)) {
          if (targetLayer == null || !allowed(layer, targetLayer, target, root)) {
            violations.add("DEPENDENCY: " + dependency.getDescription());
          }
          if (targetLayer != null && !layer.equals(targetLayer)) {
            layerGraph
                .computeIfAbsent(cycleNode(layer, type), ignored -> new HashSet<>())
                .add(cycleNode(targetLayer, target));
          }
          if (layer.equals("service")
              && "service".equals(targetLayer)
              && type.isTopLevelClass()
              && target.isTopLevelClass()
              && !type.equals(target)) {
            serviceGraph
                .computeIfAbsent(type.getName(), ignored -> new HashSet<>())
                .add(target.getName());
          }
        } else if (forbiddenExternal(layer, target)) {
          violations.add("EXTERNAL: " + dependency.getDescription());
        }
      }
      checkPublicContracts(type, layer, violations);
      checkAnnotations(type, layer, violations);
    }
    addCycles(layerGraph, "LAYER_CYCLE", violations);
    addCycles(serviceGraph, "SERVICE_CYCLE", violations);
    return List.copyOf(violations);
  }

  private static boolean allowed(String origin, String target, JavaClass targetClass, String root) {
    return origin.equals("config")
        || origin.equals("application")
        // Security errors must remain safe even when the generic exception resolver fails.
        || (origin.equals("security.web")
            && targetClass.getName().equals(root + ".web.HttpProblemMapper"))
        || ALLOWED.getOrDefault(origin, Set.of()).contains(target);
  }

  private static String cycleNode(String layer, JavaClass type) {
    // HTTP adapters share leaf mappers; collapsing all of web can invent a nonexistent cycle.
    return layer.equals("web") || layer.equals("security.web") ? type.getName() : layer;
  }

  private static String layer(JavaClass type, String root) {
    String name = type.getPackageName();
    if (name.equals(root)) {
      return type.getName().equals(root + ".RagApplication")
              || type.getName().startsWith(root + ".RagApplication$")
          ? "application"
          : null;
    }
    if (!name.startsWith(root + ".")) {
      return null;
    }
    String suffix = name.substring(root.length() + 1);
    if (inside(suffix, "config")) {
      return "config";
    }
    return ALLOWED.keySet().stream()
        .filter(candidate -> inside(suffix, candidate))
        .findFirst()
        .orElse(null);
  }

  private static boolean forbiddenExternal(String layer, JavaClass type) {
    if (type.isPrimitive()) {
      return false;
    }
    String name = type.getName();
    if (layer.equals("model.domain") || layer.equals("exception")) {
      return !name.startsWith("java.") || jdbc(name) || http(name);
    }
    if (!layer.equals("repository") && jdbc(name)) {
      return true;
    }
    if (Set.of(
                "service",
                "repository",
                "model.entity",
                "model.dto",
                "model.query",
                "model.vo",
                "security.authentication",
                "security.authorization",
                "tool",
                "job",
                "bootstrap")
            .contains(layer)
        && http(name)) {
      return true;
    }
    if (Set.of(
                "service",
                "repository",
                "client",
                "worker",
                "tool",
                "security.authentication",
                "security.authorization")
            .contains(layer)
        && (name.equals("org.springframework.context.ApplicationContext")
            || name.startsWith("org.springframework.core.env.")
            || name.startsWith("org.springframework.boot.context.properties.bind."))) {
      return true;
    }
    if (layer.startsWith("model.")
        && (name.startsWith("org.springframework.")
            || name.startsWith("org.hibernate.")
            || name.startsWith("jakarta.persistence."))) {
      return true;
    }
    if (layer.equals("tool") || layer.equals("security.authorization")) {
      return name.startsWith("java.net.http.")
          || name.startsWith("java.nio.file.")
          || name.equals("java.net.Socket")
          || name.equals("java.net.URLConnection")
          || name.equals("java.lang.ProcessBuilder")
          || name.equals("java.lang.Process")
          || name.equals("java.io.File")
          || name.equals("java.io.FileInputStream")
          || name.equals("java.io.FileOutputStream");
    }
    return false;
  }

  private static boolean http(String name) {
    return name.startsWith("jakarta.servlet.")
        || name.startsWith("javax.servlet.")
        || name.startsWith("org.springframework.http.")
        || name.startsWith("org.springframework.web.")
        || name.startsWith("java.net.http.")
        || name.startsWith("org.apache.hc.")
        || name.startsWith("okhttp3.");
  }

  private static boolean jdbc(String name) {
    return name.startsWith("java.sql.")
        || name.startsWith("javax.sql.")
        || name.startsWith("org.springframework.jdbc.")
        || name.startsWith("org.sqlite.");
  }

  private static void checkPublicContracts(JavaClass type, String layer, Set<String> violations) {
    if (layer.equals("repository")) {
      for (var member : type.getMembers()) {
        if ((member.getModifiers().contains(JavaModifier.PUBLIC)
                || member.getModifiers().contains(JavaModifier.PROTECTED))
            && (member.getAllInvolvedRawTypes().stream()
                    .anyMatch(t -> jdbc(t.getBaseComponentType().getName()))
                || (member instanceof JavaCodeUnit code
                    && code.getExceptionTypes().stream().anyMatch(t -> jdbc(t.getName()))))) {
          violations.add("JDBC_SURFACE: " + member.getFullName());
        }
      }
      for (var method : type.getMethods()) {
        if (method.getModifiers().contains(JavaModifier.PUBLIC)
            && SQL_METHODS.contains(method.getName())
            && method.getRawParameterTypes().stream()
                .anyMatch(t -> t.getName().equals("java.lang.String"))) {
          violations.add("SQL_SURFACE: " + method.getFullName());
        }
      }
    }
    if (layer.equals("service")) {
      for (var method : type.getMethods()) {
        if (method.getModifiers().contains(JavaModifier.PUBLIC)
            && untypedResult(method.getReturnType())) {
          violations.add("SERVICE_RESULT: " + method.getFullName());
        }
      }
    }
    if (layer.equals("controller") || layer.equals("web")) {
      for (var method : type.getMethods()) {
        if (method.getModifiers().contains(JavaModifier.PUBLIC)
            && method.getReturnType().getAllInvolvedRawTypes().stream()
                .anyMatch(
                    t ->
                        t.getPackageName().contains(".model.entity")
                            || t.getSimpleName().endsWith("Claim"))) {
          violations.add("HTTP_RESULT: " + method.getFullName());
        }
      }
    }
  }

  private static boolean untypedResult(JavaType type) {
    return type.getAllInvolvedRawTypes().stream()
        .anyMatch(
            t ->
                t.isAssignableTo(Map.class)
                    || t.getName().equals("com.fasterxml.jackson.databind.JsonNode")
                    || t.getName().equals("tools.jackson.databind.JsonNode"));
  }

  private static void checkAnnotations(JavaClass type, String layer, Set<String> violations) {
    for (var annotation : type.getAnnotations()) {
      String expected = STEREOTYPES.get(annotation.getRawType().getName());
      if (expected != null && !expected.equals(layer)) {
        violations.add("ANNOTATION: " + type.getName() + " must be in " + expected);
      }
    }
    for (var field : type.getFields()) {
      for (var annotation : field.getAnnotations()) {
        if (Set.of(
                "org.springframework.beans.factory.annotation.Autowired",
                "jakarta.inject.Inject",
                "javax.inject.Inject",
                "jakarta.annotation.Resource",
                "javax.annotation.Resource")
            .contains(annotation.getRawType().getName())) {
          violations.add("FIELD_INJECTION: " + field.getFullName());
        }
      }
    }
    for (JavaCodeUnit code : type.getCodeUnits()) {
      if (code.isAnnotatedWith("org.springframework.context.annotation.Bean")
          && !layer.equals("config")
          && !layer.equals("application")) {
        violations.add("BEAN_LOCATION: " + code.getFullName());
      }
    }
  }

  private static boolean inside(String value, String prefix) {
    return value.equals(prefix) || value.startsWith(prefix + ".");
  }

  private static void addCycles(
      Map<String, Set<String>> graph, String code, Set<String> violations) {
    for (String start : new TreeSet<>(graph.keySet())) {
      findCycle(start, start, graph, new LinkedHashSet<>(), code, violations);
    }
  }

  private static void findCycle(
      String start,
      String current,
      Map<String, Set<String>> graph,
      LinkedHashSet<String> path,
      String code,
      Set<String> violations) {
    if (!path.add(current)) {
      return;
    }
    for (String next : new TreeSet<>(graph.getOrDefault(current, Set.of()))) {
      if (next.equals(start)) {
        var cycle = new ArrayList<>(path);
        cycle.add(start);
        violations.add(code + ": " + String.join(" -> ", cycle));
      } else if (!path.contains(next)) {
        findCycle(start, next, graph, path, code, violations);
      }
    }
    path.remove(current);
  }
}
