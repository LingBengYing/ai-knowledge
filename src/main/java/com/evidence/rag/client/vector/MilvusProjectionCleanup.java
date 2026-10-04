package com.evidence.rag.client.vector;

import com.evidence.rag.exception.ProjectionException;
import com.evidence.rag.model.domain.ProjectionAttempt;
import com.evidence.rag.model.domain.QualifiedProjectionTarget;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Exact configured targets only; current configuration is never used to guess a past profile. */
public final class MilvusProjectionCleanup implements ProjectionCleanup {
  private final Map<String, MilvusRestProjection.Settings> configured;
  private final Function<QualifiedProjectionTarget, Optional<MilvusRestProjection.Settings>> registered;

  public MilvusProjectionCleanup(List<MilvusRestProjection.Settings> targets) {
    this(targets, ignored -> Optional.empty());
  }

  public MilvusProjectionCleanup(
      List<MilvusRestProjection.Settings> targets,
      Function<QualifiedProjectionTarget, Optional<MilvusRestProjection.Settings>> registered) {
    var values = new LinkedHashMap<String, MilvusRestProjection.Settings>();
    for (var target : List.copyOf(targets)) {
      var old = values.putIfAbsent(target.identity(), target);
      if (old != null && !qualified(old).equals(qualified(target))) {
        throw new IllegalArgumentException("Conflicting cleanup target");
      }
    }
    configured = Map.copyOf(values);
    this.registered = Objects.requireNonNull(registered);
  }

  public static QualifiedProjectionTarget qualified(MilvusRestProjection.Settings settings) {
    return new QualifiedProjectionTarget(
        settings.endpoint().resolve("/").toASCIIString(),
        settings.database(),
        settings.collection(),
        settings.workspaceId(),
        settings.embeddingIdentity(),
        settings.dimension(),
        settings.identity());
  }

  public Optional<MilvusRestProjection.Settings> settingsFor(QualifiedProjectionTarget target) {
    var settings = configured.get(target.projectionIdentity());
    if (settings == null) {
      settings = registered.apply(target).orElse(null);
    }
    return settings != null && qualified(settings).equals(target)
        ? Optional.of(settings)
        : Optional.empty();
  }

  @Override
  public Result clean(List<ProjectionAttempt> attempts) {
    if (attempts == null || attempts.isEmpty()) {
      throw new IllegalArgumentException("A remote cleanup needs registered attempts");
    }
    var first = attempts.getFirst();
    if (attempts.stream()
        .anyMatch(
            a ->
                !Objects.equals(a.target(), first.target())
                    || !a.workspaceId().equals(first.workspaceId())
                    || !a.documentId().equals(first.documentId()))) {
      throw new IllegalArgumentException("Cleanup targets cannot be mixed");
    }
    var settings = settingsFor(first.target()).orElse(null);
    if (settings == null) {
      return new Result("blocked", "blocked", "blocked", "cleanup_target_unknown");
    }
    // This development cut has authorization for synthetic loopback resources only.
    String host = settings.endpoint().getHost();
    if (!settings.allowLoopbackHttp() || !List.of("127.0.0.1", "::1", "[::1]").contains(host)) {
      return new Result("blocked", "blocked", "blocked", "cleanup_target_unknown");
    }
    try (var projection = new MilvusRestProjection(settings)) {
      projection.deleteDocument(
          first.workspaceId(),
          first.documentId(),
          attempts.stream().map(ProjectionAttempt::generationId).distinct().toList());
      return new Result("completed", "blocked", "blocked", "cleanup_remote_unverified");
    } catch (ProjectionException unavailable) {
      return new Result("failed", "blocked", "blocked", "cleanup_projection_failed");
    }
  }
}
