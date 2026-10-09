package com.evidence.rag.tool.retrieval;

import com.evidence.rag.model.domain.RetrievalSettings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * Deterministic final selection; scores retain their configured scale and text is not rewritten.
 */
public final class RetrievalSelection {
  private RetrievalSelection() {}

  public static <T> List<T> select(
      List<T> candidates,
      RetrievalSettings settings,
      ToDoubleFunction<T> score,
      Function<T, String> originalText) {
    var sorted = candidates.stream().sorted(Comparator.comparingDouble(score).reversed()).toList();
    var selected = new ArrayList<T>();
    var seen = new HashSet<String>();
    for (var candidate : sorted) {
      if (settings.scoreThresholdEnabled()
          && score.applyAsDouble(candidate) < settings.scoreThreshold()) {
        continue;
      }
      if (seen.add(originalText.apply(candidate))) {
        selected.add(candidate);
        if (selected.size() == settings.topK()) {
          break;
        }
      }
    }
    return List.copyOf(selected);
  }
}
