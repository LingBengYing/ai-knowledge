package com.evidence.rag.model.domain;

/**
 * Immutable manifest entry and its authority-derived processing state. A {@code duplicate} entry
 * names the live document it repeats, or the earlier ordinal of the same manifest (and that file's
 * document once it is uploaded). {@code relativePath} is the optional folder-relative path the file
 * was selected with; {@code startedAt} is when it was received (its parsing task was created) and
 * {@code finishedAt} when its maintained state last became indexed, failed or cancelled.
 */
public record BatchImportFile(
    int ordinal,
    String filename,
    long sizeBytes,
    String sourceSha256,
    String state,
    String documentId,
    String revisionId,
    String ingestionTaskId,
    String indexingTaskId,
    String errorCode,
    String duplicateDocumentId,
    Integer duplicateOrdinal,
    String relativePath,
    String startedAt,
    String finishedAt) {
  public record Manifest(
      String filename, long sizeBytes, String sourceSha256, String relativePath) {
    public Manifest(String filename, long sizeBytes, String sourceSha256) {
      this(filename, sizeBytes, sourceSha256, null);
    }
  }

  /** The longest folder-relative path a manifest file may carry, in code points. */
  public static final int MAX_RELATIVE_PATH = 1024;

  /**
   * A folder-relative path as the manifest may carry it: {@code /}-separated, non-empty segments,
   * none of them {@code .} or {@code ..}, no backslash, control or unpaired surrogate characters,
   * at most {@link #MAX_RELATIVE_PATH} code points, ending in the file's own name. A path equal to
   * the bare filename says nothing more and is dropped. Returns the path to keep, or null.
   */
  public static String relativePath(String path, String filename) {
    if (path == null) return null;
    if (filename == null
        || path.isEmpty()
        || path.codePointCount(0, path.length()) > MAX_RELATIVE_PATH
        || path.indexOf('\\') >= 0
        || path.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xD800 && c <= 0xDFFF))
      throw ModelValues.invalid();
    String[] segments = path.split("/", -1);
    for (String segment : segments)
      if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
        throw ModelValues.invalid();
    if (!segments[segments.length - 1].equals(filename)) throw ModelValues.invalid();
    return segments.length == 1 ? null : path;
  }
}
