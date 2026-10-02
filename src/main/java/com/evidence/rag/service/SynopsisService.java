package com.evidence.rag.service;

import com.evidence.rag.client.model.SynopsisModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.model.domain.FileSynopsis;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.SynopsisDraft;
import com.evidence.rag.model.domain.SynopsisDraft.Section;
import com.evidence.rag.model.domain.SynopsisEvidence;
import com.evidence.rag.model.domain.SynopsisInput;
import com.evidence.rag.tool.parser.ImageInput;
import com.evidence.rag.tool.parser.TextParser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Compiles a navigation-only synopsis; authority, persistence and admission belong to its caller.
 */
public final class SynopsisService {
  public static final String POLICY_REVISION = "java-file-synopsis-v1-original-support";
  private final SynopsisModels models;
  private final long budgetNanos;

  public SynopsisService(SynopsisModels models, Duration budget) {
    if (models == null
        || budget == null
        || budget.compareTo(Duration.ofMillis(10)) < 0
        || budget.compareTo(Duration.ofMinutes(10)) > 0) {
      throw ModelValues.invalid();
    }
    this.models = models;
    budgetNanos = budget.toNanos();
  }

  public FileSynopsis generate(SynopsisInput input, BooleanSupplier current) {
    long started = System.nanoTime();
    if (input == null || current == null) {
      throw ModelValues.invalid();
    }
    String revision = ModelValues.identifier(models.revision(), 200);
    String fingerprint = input.fingerprint();
    try {
      check(current, started, revision);
      validateImages(input);
      check(current, started, revision);
      SynopsisDraft draft = models.draft(input);
      check(current, started, revision);
      if (draft == null) {
        throw new Stopped("model_failure");
      }
      if (draft.refused()) {
        throw new Stopped("model_refused");
      }
      var evidence = new LinkedHashMap<String, SynopsisEvidence>();
      for (var source : input.evidence()) {
        evidence.put(source.id(), source);
      }
      validateDraft(draft, evidence);
      var entries = new ArrayList<FileSynopsis.Entry>();
      for (var item : draft.items()) {
        // Only the sources actually cited by this item may establish its claims.
        List<SynopsisEvidence> cited = item.evidenceIds().stream().map(evidence::get).toList();
        check(current, started, revision);
        boolean supported = models.verify(item, cited);
        check(current, started, revision);
        if (!supported) {
          throw new Stopped("unsupported_claims");
        }
        var references =
            cited.stream()
                .map(
                    source ->
                        new FileSynopsis.Reference(
                            source.id(), source.sha256(), source.kind(), source.time()))
                .toList();
        SynopsisEvidence.TimeRange interval = null;
        if (item.section() == Section.TIMELINE) {
          interval =
              new SynopsisEvidence.TimeRange(
                  cited.stream().mapToLong(s -> s.time().startUs()).min().orElseThrow(),
                  cited.stream().mapToLong(s -> s.time().endUs()).max().orElseThrow());
        }
        entries.add(new FileSynopsis.Entry(item, references, interval));
      }
      check(current, started, revision);
      return result(input, fingerprint, revision, entries, null);
    } catch (Stopped stopped) {
      return result(input, fingerprint, revision, List.of(), stopped.reason);
    } catch (TextModels.Failure failure) {
      if ("model_interrupted".equals(failure.code())) {
        Thread.currentThread().interrupt();
      }
      return result(
          input,
          fingerprint,
          revision,
          List.of(),
          Thread.currentThread().isInterrupted() ? "processing_interrupted" : "model_failure");
    } catch (CancellationException cancelled) {
      return result(input, fingerprint, revision, List.of(), "processing_interrupted");
    }
  }

  private static FileSynopsis result(
      SynopsisInput input,
      String fingerprint,
      String revision,
      List<FileSynopsis.Entry> entries,
      String reason) {
    return new FileSynopsis(
        input.publication(), fingerprint, revision, POLICY_REVISION, entries, reason);
  }

  private static void validateDraft(SynopsisDraft draft, Map<String, SynopsisEvidence> evidence) {
    boolean media = evidence.values().stream().anyMatch(s -> s.time() != null);
    if (draft.items().stream().filter(i -> i.section() == Section.OVERVIEW).count() != 1
        || draft.items().stream().noneMatch(i -> i.section() == Section.TOPIC)
        || draft.items().stream().noneMatch(i -> i.section() == Section.TERM)
        || media != draft.items().stream().anyMatch(i -> i.section() == Section.TIMELINE)) {
      throw new Stopped("incomplete_evidence");
    }
    for (var item : draft.items()) {
      for (String id : item.evidenceIds()) {
        var source = evidence.get(id);
        if (source == null || (item.section() == Section.TIMELINE && source.time() == null)) {
          throw new Stopped("incomplete_evidence");
        }
      }
    }
  }

  private static void validateImages(SynopsisInput input) {
    for (var evidence : input.evidence()) {
      if (evidence.content() instanceof SynopsisEvidence.Image original) {
        var image = original.image();
        try {
          ImageInput.validateEnvelope(
              image.mediaType().equals("image/png") ? "source.png" : "source.jpg",
              image.mediaType(),
              image.content());
        } catch (TextParser.Failure invalid) {
          throw ModelValues.invalid();
        }
      }
    }
  }

  private void check(BooleanSupplier current, long started, String revision) {
    deadline(started);
    boolean allowed;
    try {
      allowed = current.getAsBoolean();
    } catch (CancellationException cancelled) {
      throw cancelled;
    } catch (RuntimeException unavailable) {
      throw new Stopped("source_changed");
    }
    if (!allowed) {
      throw new Stopped("source_changed");
    }
    if (!revision.equals(models.revision())) {
      throw new Stopped("configuration_changed");
    }
    deadline(started);
  }

  private void deadline(long started) {
    if (Thread.currentThread().isInterrupted()) {
      throw new Stopped("processing_interrupted");
    }
    if (System.nanoTime() - started >= budgetNanos) {
      throw new Stopped("processing_timeout");
    }
  }

  private static final class Stopped extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String reason;

    private Stopped(String reason) {
      super(null, null, false, false);
      this.reason = reason;
    }
  }
}
