package com.evidence.rag.service;

import com.evidence.rag.client.model.HierarchicalSynopsisModels;
import com.evidence.rag.client.model.TextModels;
import com.evidence.rag.model.domain.DerivedSynopsisNode;
import com.evidence.rag.model.domain.FileSynopsis;
import com.evidence.rag.model.domain.ModelValues;
import com.evidence.rag.model.domain.SynopsisBatch;
import com.evidence.rag.model.domain.SynopsisDraft;
import com.evidence.rag.model.domain.SynopsisDraft.Section;
import com.evidence.rag.model.domain.SynopsisEvidence;
import com.evidence.rag.model.domain.SynopsisFileInput;
import com.evidence.rag.model.domain.SynopsisReductionInput;
import com.evidence.rag.model.domain.SynopsisReductionInput.Stage;
import com.evidence.rag.tool.parser.ImageInput;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/** Complete-file navigation synthesis; derived nodes never become original evidence. */
public final class HierarchicalSynopsisService {
  public static final String POLICY_REVISION = "java-file-synopsis-v2-hierarchy-original-review";
  private final HierarchicalSynopsisModels models;
  private final long budgetNanos;

  public HierarchicalSynopsisService(HierarchicalSynopsisModels models, Duration budget) {
    if (models == null
        || budget == null
        || budget.compareTo(Duration.ofMillis(10)) < 0
        || budget.compareTo(Duration.ofMinutes(10)) > 0) {
      throw ModelValues.invalid();
    }
    this.models = models;
    budgetNanos = budget.toNanos();
  }

  public FileSynopsis generate(SynopsisFileInput input, BooleanSupplier current) {
    if (input == null || current == null) {
      throw ModelValues.invalid();
    }
    long started = System.nanoTime();
    String revision = ModelValues.identifier(models.revision(), 200);
    try {
      check(current, started, revision);
      var originals = new LinkedHashMap<String, SynopsisEvidence>();
      for (var evidence : input.evidence()) {
        originals.put(evidence.id(), evidence);
        if (evidence.content() instanceof SynopsisEvidence.Image original) {
          var image = original.image();
          ImageInput.validateEnvelope(
              image.mediaType().equals("image/png") ? "source.png" : "source.jpg",
              image.mediaType(),
              image.content());
        }
      }
      var batches = SynopsisBatch.partition(input);
      var nodes = new ArrayList<DerivedSynopsisNode>();
      for (var batch : batches) {
        check(current, started, revision);
        var draft = models.draftLeaf(batch);
        check(current, started, revision);
        validate(
            draft,
            originals,
            batch.evidence().stream().map(SynopsisEvidence::id).collect(Collectors.toSet()),
            false);
        nodes.add(node(0, batch.fromOrdinal(), batch.endOrdinal(), draft));
      }
      int level = 1;
      while (nodes.size() > 3) {
        var parents = new ArrayList<DerivedSynopsisNode>();
        for (int from = 0; from < nodes.size(); from += 3) {
          var children = List.copyOf(nodes.subList(from, Math.min(from + 3, nodes.size())));
          if (children.size() == 1) {
            parents.add(children.getFirst());
          } else {
            var reduction =
                new SynopsisReductionInput(
                    input.publication(), input.fingerprint(), Stage.INTERMEDIATE, children);
            check(current, started, revision);
            var draft = models.reduce(reduction);
            check(current, started, revision);
            validate(draft, originals, lineage(children), false);
            parents.add(
                node(
                    level,
                    children.getFirst().fromOrdinal(),
                    children.getLast().endOrdinal(),
                    draft));
          }
        }
        nodes = parents;
        level++;
      }
      check(current, started, revision);
      var draft =
          models.reduce(
              new SynopsisReductionInput(
                  input.publication(), input.fingerprint(), Stage.FINAL, nodes));
      check(current, started, revision);
      validate(draft, originals, lineage(nodes), true);
      var entries = new ArrayList<FileSynopsis.Entry>();
      for (var item : draft.items()) {
        var cited = item.evidenceIds().stream().map(originals::get).toList();
        checkProofBound(cited);
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
      // Independent original-batch review catches context/conditions lost in derived candidates.
      for (var batch : batches) {
        check(current, started, revision);
        var review = models.review(batch, draft.items());
        check(current, started, revision);
        if (review == null
            || !review.complete()
            || review.items().size() != draft.items().size()
            || review.items().stream().anyMatch(i -> i.index() >= draft.items().size())) {
          throw new Stopped("incomplete_evidence");
        }
        if (review.items().stream().anyMatch(i -> !i.compatible())) {
          throw new Stopped("unsupported_claims");
        }
      }
      check(current, started, revision);
      return result(input, revision, entries, null);
    } catch (Stopped stopped) {
      return result(input, revision, List.of(), stopped.reason);
    } catch (TextModels.Failure failure) {
      if ("model_interrupted".equals(failure.code())) {
        Thread.currentThread().interrupt();
      }
      return result(
          input,
          revision,
          List.of(),
          Thread.currentThread().isInterrupted() ? "processing_interrupted" : "model_failure");
    } catch (CancellationException cancelled) {
      return result(input, revision, List.of(), "processing_interrupted");
    }
  }

  private static DerivedSynopsisNode node(int level, int from, int end, SynopsisDraft draft) {
    return new DerivedSynopsisNode(
        "synopsis-node-" + level + "-" + from + "-" + end, from, end, draft.items());
  }

  private static Set<String> lineage(List<DerivedSynopsisNode> nodes) {
    return nodes.stream()
        .flatMap(n -> n.items().stream())
        .flatMap(i -> i.evidenceIds().stream())
        .collect(Collectors.toSet());
  }

  private static void validate(
      SynopsisDraft draft,
      Map<String, SynopsisEvidence> originals,
      Set<String> allowed,
      boolean complete) {
    if (draft == null) {
      throw new Stopped("model_failure");
    }
    if (draft.refused()) {
      throw new Stopped("model_refused");
    }
    if (draft.items().isEmpty() || (!complete && draft.items().size() > 16)) {
      throw new Stopped("incomplete_evidence");
    }
    for (var item : draft.items()) {
      for (String id : item.evidenceIds()) {
        var original = originals.get(id);
        if (!allowed.contains(id)
            || original == null
            || (item.section() == Section.TIMELINE && original.time() == null)) {
          throw new Stopped("incomplete_evidence");
        }
      }
    }
    if (complete
        && (draft.items().stream().filter(i -> i.section() == Section.OVERVIEW).count() != 1
            || draft.items().stream().noneMatch(i -> i.section() == Section.TOPIC)
            || draft.items().stream().noneMatch(i -> i.section() == Section.TERM)
            || originals.values().stream().anyMatch(e -> e.time() != null)
                != draft.items().stream().anyMatch(i -> i.section() == Section.TIMELINE))) {
      throw new Stopped("incomplete_evidence");
    }
  }

  private static void checkProofBound(List<SynopsisEvidence> cited) {
    long points = 0, bytes = 0;
    int images = 0;
    for (var source : cited) {
      switch (source.content()) {
        case SynopsisEvidence.Text text ->
            points += text.text().codePointCount(0, text.text().length());
        case SynopsisEvidence.Image image -> {
          images++;
          bytes += image.image().content().length;
        }
      }
    }
    if (points > SynopsisBatch.MAX_TEXT_CODE_POINTS
        || images > SynopsisBatch.MAX_IMAGES
        || bytes > SynopsisBatch.MAX_IMAGE_BYTES) {
      throw new Stopped("incomplete_evidence");
    }
  }

  private static FileSynopsis result(
      SynopsisFileInput input, String revision, List<FileSynopsis.Entry> entries, String reason) {
    return new FileSynopsis(
        input.publication(), input.fingerprint(), revision, POLICY_REVISION, entries, reason);
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
