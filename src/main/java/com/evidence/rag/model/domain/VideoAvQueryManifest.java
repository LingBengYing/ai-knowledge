package com.evidence.rag.model.domain;

import java.nio.charset.StandardCharsets;

/** Complete request-only video preparation identity; never a library publication or evidence. */
public record VideoAvQueryManifest(
    int ordinal,
    String sourceSha256,
    String mediaKind,
    String compilerRevision,
    String contentSha256,
    Integer windowCount,
    Integer visualWindowCount,
    Integer audioWindowCount,
    Boolean audioPresent,
    VideoAvMode usedMode,
    String status) {
  public VideoAvQueryManifest {
    VideoAvProfile.hash(sourceSha256);
    ModelValues.identifier(compilerRevision, 200);
    if (ordinal < 0 || ordinal >= 3 || !"video".equals(mediaKind) || usedMode == null) {
      throw ModelValues.invalid();
    }
    if ("prepared".equals(status)) {
      VideoAvProfile.hash(contentSha256);
      if (windowCount == null
          || windowCount < 1
          || windowCount > 1201
          || visualWindowCount == null
          || visualWindowCount < 1
          || visualWindowCount > windowCount
          || audioWindowCount == null
          || audioWindowCount < 0
          || audioWindowCount > windowCount
          || visualWindowCount + audioWindowCount < windowCount
          || audioPresent == null
          || audioPresent != (audioWindowCount > 0)
          || usedMode != VideoAvMode.VISUAL && audioWindowCount == 0) {
        throw ModelValues.invalid();
      }
    } else if (!"not_prepared".equals(status)
        || contentSha256 != null
        || windowCount != null
        || visualWindowCount != null
        || audioWindowCount != null
        || audioPresent != null) {
      throw ModelValues.invalid();
    }
  }

  public static VideoAvQueryManifest prepared(
      int ordinal, VideoAvMode mode, String compilerRevision, VideoAvCompilation compilation) {
    if (compilation == null) {
      throw ModelValues.invalid();
    }
    ModelValues.identifier(compilerRevision, 200);
    String content =
        ModelValues.sha256(
            ("java-video-av-query-content-v1\0"
                    + compilation.sourceSha256()
                    + "\0"
                    + compilerRevision
                    + "\0"
                    + VideoAvProfile.windowManifestSha256(compilation))
                .getBytes(StandardCharsets.UTF_8));
    int visual = 0;
    int audio = 0;
    for (var window : compilation.windows()) {
      if (window.video() != null) {
        visual++;
      }
      if (window.audio() != null) {
        audio++;
      }
    }
    return new VideoAvQueryManifest(
        ordinal,
        compilation.sourceSha256(),
        "video",
        compilerRevision,
        content,
        compilation.windows().size(),
        visual,
        audio,
        compilation.hasAudio(),
        mode,
        "prepared");
  }

  public static VideoAvQueryManifest notPrepared(
      int ordinal, VideoAvMode mode, String compilerRevision, String sourceSha256) {
    return new VideoAvQueryManifest(
        ordinal,
        sourceSha256,
        "video",
        compilerRevision,
        null,
        null,
        null,
        null,
        null,
        mode,
        "not_prepared");
  }

  @Override
  public String toString() {
    return "VideoAvQueryManifest[redacted]";
  }
}
