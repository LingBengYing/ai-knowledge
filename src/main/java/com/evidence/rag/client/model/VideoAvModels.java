package com.evidence.rag.client.model;

import com.evidence.rag.model.domain.VideoAvEpoch;
import com.evidence.rag.model.domain.VideoAvFact;
import com.evidence.rag.model.domain.VideoAvMode;
import com.evidence.rag.model.domain.VideoAvRequirement;
import com.evidence.rag.model.domain.VideoAvWindow;
import java.util.List;

/** Full original video/audio proof; proposed claims never replace the independent media check. */
public interface VideoAvModels {
  Draft draft(String fullQuestion, VideoAvWindow window, VideoAvEpoch epoch, VideoAvMode mode);

  Verification verify(
      String fullQuestion,
      VideoAvWindow window,
      VideoAvEpoch epoch,
      VideoAvMode mode,
      List<VideoAvFact> facts);

  String revision();

  record Claim(String text, VideoAvRequirement requirement) {
    @Override
    public String toString() {
      return "Claim[redacted]";
    }
  }

  record Draft(boolean complete, List<Claim> claims) {
    public Draft {
      claims = List.copyOf(claims);
    }

    @Override
    public String toString() {
      return "Draft[redacted]";
    }
  }

  record Support(
      String id, boolean supported, boolean visualContribution, boolean audioContribution) {
    @Override
    public String toString() {
      return "Support[redacted]";
    }
  }

  record Verification(boolean complete, List<Support> support) {
    public Verification {
      support = List.copyOf(support);
    }

    @Override
    public String toString() {
      return "Verification[redacted]";
    }
  }
}
