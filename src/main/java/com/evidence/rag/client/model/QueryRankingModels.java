package com.evidence.rag.client.model;

import com.evidence.rag.model.domain.PreparedQuery;
import com.evidence.rag.model.domain.QueryRankCandidate;
import java.util.List;

/** Auxiliary relevance matching; query attachments never establish library facts. */
public interface QueryRankingModels {
  List<TextModels.Ranked> rank(PreparedQuery query, List<QueryRankCandidate> candidates);

  String revision();
}
