package com.evidence.rag.client.model;

import com.evidence.rag.model.domain.SynopsisBatch;
import com.evidence.rag.model.domain.SynopsisBatchReview;
import com.evidence.rag.model.domain.SynopsisDraft;
import com.evidence.rag.model.domain.SynopsisEvidence;
import com.evidence.rag.model.domain.SynopsisReductionInput;
import java.util.List;

/** Hierarchical proposals and full-original review, distinct from bounded whole-file drafting. */
public interface HierarchicalSynopsisModels {
  SynopsisDraft draftLeaf(SynopsisBatch batch);

  SynopsisDraft reduce(SynopsisReductionInput input);

  SynopsisBatchReview review(SynopsisBatch originalBatch, List<SynopsisDraft.Item> finalItems);

  boolean verify(SynopsisDraft.Item item, List<SynopsisEvidence> originalCitations);

  String revision();
}
