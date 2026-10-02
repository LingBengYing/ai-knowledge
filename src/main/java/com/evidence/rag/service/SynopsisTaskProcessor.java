package com.evidence.rag.service;

import com.evidence.rag.exception.ApplicationException;
import com.evidence.rag.model.domain.Actor;
import com.evidence.rag.model.domain.SynopsisClaim;
import com.evidence.rag.model.dto.SynopsisTaskResult;
import java.util.Objects;
import java.util.Optional;

/** Executes one fenced synopsis claim outside authority transactions, without automatic retries. */
public final class SynopsisTaskProcessor {
  private final SynopsisLibraryService library;
  private final SynopsisService generator;
  private final HierarchicalSynopsisService hierarchyGenerator;
  private final String workspace;

  public SynopsisTaskProcessor(
      SynopsisLibraryService library,
      SynopsisService generator,
      HierarchicalSynopsisService hierarchyGenerator,
      String workspace) {
    this.library = Objects.requireNonNull(library);
    this.generator = Objects.requireNonNull(generator);
    this.hierarchyGenerator = Objects.requireNonNull(hierarchyGenerator);
    this.workspace = com.evidence.rag.model.domain.ModelValues.identifier(workspace, 200);
  }

  public SynopsisTaskProcessor(
      SynopsisLibraryService library, SynopsisService generator, String workspace) {
    this.library = Objects.requireNonNull(library);
    this.generator = Objects.requireNonNull(generator);
    this.hierarchyGenerator = null;
    this.workspace = com.evidence.rag.model.domain.ModelValues.identifier(workspace, 200);
  }

  public SynopsisTaskResult create(Actor actor, String documentId) {
    return library.create(actor, documentId);
  }

  public Optional<SynopsisClaim> claim() {
    return library.claim(workspace);
  }

  public boolean isCurrent(SynopsisClaim claim) {
    return library.current(claim);
  }

  public void process(SynopsisClaim claim) {
    try {
      if (!library.current(claim)) {
        fail(claim, "source_changed");
        return;
      }
      if (!claim.input().bounded() && hierarchyGenerator == null) {
        fail(claim, "configuration_changed");
        return;
      }
      var result =
          claim.input().bounded()
              ? generator.generate(claim.input().toBounded(), () -> library.current(claim))
              : hierarchyGenerator.generate(claim.input(), () -> library.current(claim));
      if (!library.complete(claim, result)) {
        fail(claim, "source_changed");
      }
    } catch (RuntimeException failure) {
      failUnexpected(claim);
    }
  }

  public void failUnexpected(SynopsisClaim claim) {
    fail(claim, Thread.currentThread().isInterrupted() ? "worker_interrupted" : "synopsis_failed");
  }

  private void fail(SynopsisClaim claim, String code) {
    if (claim == null) {
      return;
    }
    try {
      library.fail(claim, code);
    } catch (ApplicationException unavailable) {
      // Startup recovery handles a persisted processing task; private errors are never logged.
    }
  }
}
