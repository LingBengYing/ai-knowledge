package com.evidence.rag.client.model;

import com.evidence.rag.model.domain.SynopsisDraft;
import com.evidence.rag.model.domain.SynopsisEvidence;
import com.evidence.rag.model.domain.SynopsisInput;
import java.util.List;

/** Original-evidence synopsis seam; callers own complete input, authority and publication. */
public interface SynopsisModels {
  SynopsisDraft draft(SynopsisInput input);

  boolean verify(SynopsisDraft.Item item, List<SynopsisEvidence> citedEvidence);

  String revision();
}
