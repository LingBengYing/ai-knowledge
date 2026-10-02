package com.evidence.rag.support;

import com.evidence.rag.client.model.HierarchicalSynopsisModels;
import com.evidence.rag.model.domain.SynopsisBatch;
import com.evidence.rag.model.domain.SynopsisBatchReview;
import com.evidence.rag.model.domain.SynopsisDraft;
import com.evidence.rag.model.domain.SynopsisDraft.Item;
import com.evidence.rag.model.domain.SynopsisDraft.Section;
import com.evidence.rag.model.domain.SynopsisEvidence;
import com.evidence.rag.model.domain.SynopsisReductionInput;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/** Deterministic local test adapter; it makes no claim about real model quality. */
public class HierarchyFixtureModels implements HierarchicalSynopsisModels {
  public final List<SynopsisBatch> leaves = new ArrayList<>();
  public final List<SynopsisReductionInput> reductions = new ArrayList<>();
  public final List<List<SynopsisEvidence>> verified = new ArrayList<>();
  public final List<SynopsisBatch> reviewed = new ArrayList<>();
  public String modelRevision = "hierarchy-fixture-v1";
  public Consumer<String> afterCall = operation -> {};

  @Override
  public SynopsisDraft draftLeaf(SynopsisBatch batch) {
    leaves.add(batch);
    afterCall.accept("leaf");
    return draft(
        batch.evidence().getFirst().id(),
        batch.evidence().getLast().id(),
        batch.evidence().stream().anyMatch(e -> e.time() != null));
  }

  @Override
  public SynopsisDraft reduce(SynopsisReductionInput input) {
    reductions.add(input);
    afterCall.accept("reduce");
    var items = input.nodes().stream().flatMap(n -> n.items().stream()).toList();
    return draft(
        items.getFirst().evidenceIds().getFirst(),
        items.getLast().evidenceIds().getLast(),
        items.stream().anyMatch(i -> i.section() == Section.TIMELINE));
  }

  @Override
  public boolean verify(Item item, List<SynopsisEvidence> originals) {
    verified.add(List.copyOf(originals));
    afterCall.accept("verify");
    return true;
  }

  @Override
  public SynopsisBatchReview review(SynopsisBatch batch, List<Item> items) {
    reviewed.add(batch);
    afterCall.accept("review");
    return new SynopsisBatchReview(
        true,
        IntStream.range(0, items.size())
            .mapToObj(i -> new SynopsisBatchReview.ItemReview(i, true))
            .toList());
  }

  @Override
  public String revision() {
    return modelRevision;
  }

  public static SynopsisDraft draft(String first, String last, boolean timed) {
    var items =
        new ArrayList<>(
            List.of(
                new Item(Section.OVERVIEW, "合成文件概览", List.of(first)),
                new Item(Section.TOPIC, "末尾主题", List.of(last)),
                new Item(Section.TERM, "合成术语", List.of(last))));
    if (timed) {
      items.add(new Item(Section.TIMELINE, "末尾步骤", List.of(last)));
    }
    return new SynopsisDraft(false, items);
  }
}
