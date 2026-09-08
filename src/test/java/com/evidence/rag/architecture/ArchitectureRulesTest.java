package com.evidence.rag.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchitectureRulesTest {
  private static final String FIXTURE_ROOT = "example.architecture";
  @TempDir Path directory;

  @Test
  void productionBytecodeObeysApprovedLayers() throws Exception {
    // Resolve only the application's production location; never import target/test-classes.
    Class<?> application =
        Class.forName("com.evidence.rag.RagApplication", false, getClass().getClassLoader());
    var location = application.getProtectionDomain().getCodeSource().getLocation();
    JavaClasses classes = new ClassFileImporter().importUrl(location);
    assertFalse(
        classes.isEmpty(), "The production architecture gate must not pass on an empty import");
    assertTrue(classes.contain("com.evidence.rag.RagApplication"));
    assertFalse(classes.contain(ArchitectureRulesTest.class));
    var violations = ArchitectureRules.violations(classes, "com.evidence.rag");
    assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
  }

  @Test
  void fullQualifiedStaticCallsCannotHideForbiddenLayerDependencies() throws IOException {
    for (String forbidden : List.of("repository", "client", "worker", "tool", "config")) {
      var sources = baseSources();
      sources.put(
          "controller.Sample",
          "public class Sample { public String get() { return "
              + FIXTURE_ROOT
              + "."
              + forbidden
              + ".Sample.value(); } }");
      assertViolation(sources, "DEPENDENCY", "controller.Sample");
    }
  }

  @Test
  void reverseDependenciesAndSecurityLayerLeaksAreRejected() throws IOException {
    for (var pair :
        List.of(
            List.of("service", "controller"),
            List.of("service", "web"),
            List.of("service", "config"),
            List.of("repository", "service"),
            List.of("repository", "web"),
            List.of("repository", "client"),
            List.of("repository", "model.vo"),
            List.of("client", "service"),
            List.of("client", "repository"),
            List.of("client", "config"),
            List.of("worker", "service"),
            List.of("worker", "repository"),
            List.of("worker", "config"),
            List.of("model.domain", "controller"),
            List.of("model.domain", "config"),
            List.of("security.authentication", "service"),
            List.of("security.authentication", "repository"),
            List.of("security.authorization", "service"),
            List.of("security.authorization", "repository"),
            List.of("security.web", "repository"),
            List.of("job", "repository"),
            List.of("bootstrap", "repository"),
            List.of("tool", "client"),
            List.of("model.dto", "model.entity"))) {
      var sources = baseSources();
      sources.put(
          pair.getFirst() + ".Sample",
          "public class Sample { private "
              + FIXTURE_ROOT
              + "."
              + pair.get(1)
              + ".Sample forbidden; }");
      assertViolation(sources, "DEPENDENCY", pair.getFirst() + ".Sample");
    }
  }

  @Test
  void servletHttpJdbcAndFrameworkDependenciesCannotEnterInnerLayers() throws IOException {
    for (var pair :
        List.of(
            List.of("service", "jakarta.servlet.http.HttpServletRequest"),
            List.of("service", "org.springframework.http.HttpStatus"),
            List.of("service", "java.sql.Connection"),
            List.of("model.domain", "jakarta.servlet.http.HttpServletRequest"),
            List.of("model.domain", "org.springframework.http.ResponseEntity<String>"),
            List.of("model.domain", "tools.jackson.databind.JsonNode"),
            List.of("security.authentication", "jakarta.servlet.http.HttpServletRequest"),
            List.of("security.authorization", "java.net.http.HttpClient"),
            List.of("tool", "java.nio.file.Path"))) {
      var sources = baseSources();
      sources.put(
          pair.getFirst() + ".Sample",
          "public class Sample { private " + pair.get(1) + " forbidden; }");
      assertViolation(sources, "EXTERNAL", pair.getFirst() + ".Sample");
    }
  }

  @Test
  void repositoryRejectsJdbcInGenericReturnParameterFieldAndThrowsClause() throws IOException {
    for (String member :
        List.of(
            "public java.sql.Connection get() { return null; }",
            "public java.util.List<java.sql.ResultSet> get() { return null; }",
            "public void put(java.sql.Connection value) {}",
            "public void run() throws java.sql.SQLException {}",
            "public java.sql.Statement statement;",
            "protected java.sql.Connection get() { return null; }")) {
      var sources = baseSources();
      sources.put("repository.Sample", "public class Sample { " + member + " }");
      assertViolation(sources, "JDBC_SURFACE", "repository.Sample");
    }
    var sources = baseSources();
    sources.put(
        "repository.Sample",
        "public class Sample { public java.util.List<String> query(String sql) { return java.util.List.of(); } }");
    assertViolation(sources, "SQL_SURFACE", "repository.Sample");
  }

  @Test
  void serviceRejectsRawAndNestedUntypedResultsWithoutBanningLocalMaps() throws IOException {
    for (String type :
        List.of(
            "java.util.Map",
            "java.util.Map<String,Object>",
            "java.util.List<java.util.Map<String,Object>>",
            "java.util.HashMap<String,Object>",
            "tools.jackson.databind.JsonNode")) {
      var sources = baseSources();
      sources.put(
          "service.Sample",
          "public class Sample { public " + type + " result() { return null; } }");
      assertViolation(sources, "SERVICE_RESULT", "service.Sample");
    }
  }

  @Test
  void entityAndInternalClaimCannotBeHttpResponseTypes() throws IOException {
    for (String type :
        List.of(FIXTURE_ROOT + ".model.entity.Sample", FIXTURE_ROOT + ".model.domain.IndexClaim")) {
      var sources = baseSources();
      sources.put("model.domain.IndexClaim", "public record IndexClaim(String token) {}");
      sources.put(
          "controller.Sample",
          "public class Sample { public java.util.List<" + type + "> result() { return null; } }");
      assertViolation(sources, "HTTP_RESULT", "controller.Sample");
    }
  }

  @Test
  void retiredPackagesAndFacadeAreNotHiddenByEmptyLayerRules() throws IOException {
    for (String legacy :
        List.of(
            "management",
            "ingestion",
            "indexing",
            "corpus",
            "models",
            "retrieval",
            "shared",
            "security",
            "util")) {
      var sources = baseSources();
      sources.put(legacy + ".Retired", "public class Retired {}");
      assertViolation(sources, "PACKAGE", legacy + ".Retired");
    }
    var sources = baseSources();
    sources.put("service.ManagementModule", "public class ManagementModule {}");
    assertViolation(sources, "PACKAGE", "service.ManagementModule");
  }

  @Test
  void stereotypesBeanFactoriesAndFieldInjectionHaveRealBytecodeChecks() throws IOException {
    var wrongStereotype = baseSources();
    wrongStereotype.put(
        "repository.Sample", "@org.springframework.stereotype.Service public class Sample {}");
    assertViolation(wrongStereotype, "ANNOTATION", "repository.Sample");
    var fieldInjection = baseSources();
    fieldInjection.put(
        "service.Sample",
        "public class Sample { @org.springframework.beans.factory.annotation.Autowired private String value; }");
    assertViolation(fieldInjection, "FIELD_INJECTION", "service.Sample");
    var beanOutsideConfig = baseSources();
    beanOutsideConfig.put(
        "service.Sample",
        "public class Sample { @org.springframework.context.annotation.Bean public String value() { return "
            + '"'
            + "x"
            + '"'
            + "; } }");
    assertViolation(beanOutsideConfig, "BEAN_LOCATION", "service.Sample");
  }

  @Test
  void classAndLayerCyclesAreRejectedEvenWhenIndividualServiceEdgesAreAllowed() throws IOException {
    var sources = baseSources();
    sources.put(
        "service.Sample",
        "public class Sample { private " + FIXTURE_ROOT + ".service.Other next; }");
    sources.put(
        "service.Other",
        "public class Other { private " + FIXTURE_ROOT + ".service.Sample previous; }");
    assertViolation(sources, "SERVICE_CYCLE", "service.Sample");
    sources = baseSources();
    sources.put(
        "service.Sample",
        "public class Sample { private " + FIXTURE_ROOT + ".repository.Sample next; }");
    sources.put(
        "repository.Sample",
        "public class Sample { private " + FIXTURE_ROOT + ".service.Sample previous; }");
    assertViolation(sources, "LAYER_CYCLE", "service");
  }

  @Test
  void legitimateLayeredFixtureUsesTheSameRulesAndPasses() throws IOException {
    var sources = baseSources();
    sources.put(
        "repository.Sample",
        "@org.springframework.stereotype.Repository public class Sample { private java.sql.Connection connection; public "
            + FIXTURE_ROOT
            + ".model.entity.Sample find("
            + FIXTURE_ROOT
            + ".model.query.Sample query) { return new "
            + FIXTURE_ROOT
            + ".model.entity.Sample(); } }");
    sources.put(
        "service.Sample",
        "@org.springframework.stereotype.Service public class Sample { private final "
            + FIXTURE_ROOT
            + ".repository.Sample repository; public Sample("
            + FIXTURE_ROOT
            + ".repository.Sample repository) { this.repository=repository; } public "
            + FIXTURE_ROOT
            + ".model.dto.Sample result(java.util.Map<String,String> typedLookup) { java.util.Map<String,Object> local=new java.util.HashMap<>(); local.put("
            + '"'
            + "key"
            + '"'
            + ", typedLookup); return new "
            + FIXTURE_ROOT
            + ".model.dto.Sample(); } }");
    sources.put(
        "controller.Sample",
        "@org.springframework.web.bind.annotation.RestController public class Sample { private final "
            + FIXTURE_ROOT
            + ".service.Sample service; public Sample("
            + FIXTURE_ROOT
            + ".service.Sample service) { this.service=service; } public "
            + FIXTURE_ROOT
            + ".model.dto.Sample result() { return service.result(java.util.Map.of()); } }");
    sources.put(
        "worker.Sample",
        "public class Sample { private " + FIXTURE_ROOT + ".client.Sample client; }");
    sources.put(
        "job.Sample",
        "public class Sample { private " + FIXTURE_ROOT + ".service.Sample service; }");
    sources.put(
        "web.HttpProblemMapper",
        "public final class HttpProblemMapper { public static int status() { return 401; } }");
    sources.put(
        "web.Sample",
        "public class Sample { private " + FIXTURE_ROOT + ".security.web.Sample identityReader; }");
    sources.put(
        "security.web.Sample",
        "public class Sample { private jakarta.servlet.http.HttpServletRequest request; private "
            + FIXTURE_ROOT
            + ".security.authentication.Sample authentication; public int failure() { return "
            + FIXTURE_ROOT
            + ".web.HttpProblemMapper.status(); } }");
    sources.put(
        "config.Sample",
        "@org.springframework.context.annotation.Configuration public class Sample { @org.springframework.context.annotation.Bean public "
            + FIXTURE_ROOT
            + ".repository.Sample repository() { return new "
            + FIXTURE_ROOT
            + ".repository.Sample(); } }");
    var violations = ArchitectureRules.violations(compile(sources), FIXTURE_ROOT);
    assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
  }

  private void assertViolation(Map<String, String> sources, String code, String offender)
      throws IOException {
    var violations = ArchitectureRules.violations(compile(sources), FIXTURE_ROOT);
    assertTrue(
        violations.stream().anyMatch(v -> v.startsWith(code + ":") && v.contains(offender)),
        () -> "Expected " + code + " for " + offender + ", got " + violations);
  }

  private JavaClasses compile(Map<String, String> bodies) throws IOException {
    var compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "Architecture rule counterexamples require the same JDK as the build");
    Path sourceRoot = Files.createTempDirectory(directory, "source-");
    Path output = Files.createTempDirectory(directory, "classes-");
    var sources = new ArrayList<Path>();
    for (var entry : bodies.entrySet()) {
      String fullName = FIXTURE_ROOT + "." + entry.getKey();
      Path file = sourceRoot.resolve(fullName.replace('.', '/') + ".java");
      Files.createDirectories(file.getParent());
      Files.writeString(
          file,
          "package " + fullName.substring(0, fullName.lastIndexOf('.')) + ";\n" + entry.getValue(),
          StandardCharsets.UTF_8);
      sources.add(file);
    }
    var diagnostics = new DiagnosticCollector<JavaFileObject>();
    try (var files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
      boolean success =
          compiler
              .getTask(
                  null,
                  files,
                  diagnostics,
                  List.of(
                      "--release",
                      "21",
                      "-proc:none",
                      "-g:none",
                      "-classpath",
                      System.getProperty("java.class.path"),
                      "-d",
                      output.toString()),
                  null,
                  files.getJavaFileObjectsFromPaths(sources))
              .call();
      assertTrue(
          success,
          () ->
              "Invalid architecture fixture, not an architecture violation: "
                  + diagnostics.getDiagnostics());
    }
    var imported = new ClassFileImporter().importPath(output);
    assertEquals(
        bodies.size(),
        imported.size(),
        "Fixture import must not accidentally select production classes");
    return imported;
  }

  private static Map<String, String> baseSources() {
    var sources = new LinkedHashMap<String, String>();
    for (String layer :
        List.of(
            "controller",
            "web",
            "service",
            "repository",
            "client",
            "worker",
            "tool",
            "job",
            "bootstrap",
            "config",
            "security.authentication",
            "security.authorization",
            "security.web",
            "model.entity",
            "model.dto",
            "model.query",
            "model.vo",
            "model.domain")) {
      sources.put(
          layer + ".Sample",
          "public class Sample { public static String value() { return "
              + '"'
              + "ok"
              + '"'
              + "; } }");
    }
    return sources;
  }
}
