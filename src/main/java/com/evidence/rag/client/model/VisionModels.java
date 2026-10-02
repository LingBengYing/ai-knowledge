package com.evidence.rag.client.model;

import com.evidence.rag.model.domain.VisualImage;
import java.util.List;

/** Actual-image model seam; callers own authorization, source identity and assessment policy. */
public interface VisionModels {
  Description describe(VisualImage image);

  Draft draft(String question, VisualImage image);

  Verification verify(String question, VisualImage image, List<String> claims);

  String revision();

  record Description(String recallText) {
    @Override
    public String toString() {
      return "Description[redacted]";
    }
  }

  record Draft(boolean refused, List<String> claims) {
    public Draft {
      claims = List.copyOf(claims);
    }

    @Override
    public String toString() {
      return "Draft[redacted]";
    }
  }

  record Verification(boolean complete, List<Boolean> supported) {
    public Verification {
      supported = List.copyOf(supported);
    }

    @Override
    public String toString() {
      return "Verification[redacted]";
    }
  }
}
