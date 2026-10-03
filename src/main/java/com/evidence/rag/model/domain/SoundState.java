package com.evidence.rag.model.domain;

/** Safe current source/profile state; a missing publication is explicit. */
public record SoundState(
    DocumentOriginal original,
    IndexTarget target,
    String profileFingerprint,
    SoundPublication publication) {
  public SoundState {
    SoundProfile.hash(profileFingerprint);
    if (original == null
        || !"audio".equals(original.documentType())
        || target == null
        || publication != null
            && (!publication.documentId().equals(original.documentId())
                || !publication.sourceRevisionId().equals(original.revisionId())
                || !publication.sourceSha256().equals(original.sourceSha256())
                || !publication.filename().equals(original.filename())
                || !publication.mediaType().equals(original.mediaType())
                || publication.sizeBytes() != original.sizeBytes()
                || !publication.target().equals(target)
                || !publication.profileFingerprint().equals(profileFingerprint))) {
      throw ModelValues.invalid();
    }
  }

  @Override
  public String toString() {
    return "SoundState[redacted]";
  }
}
