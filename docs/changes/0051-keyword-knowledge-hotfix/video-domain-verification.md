# VideoDomainTest release regression repair

## Contract and RED

The accepted [0048 spec](../0048-product-help/spec.md), section “语音/字幕独立视频入库”, explicitly permits null frame captions only for the new version 4 text-evidence compiler. Versions 1–3 still require real captions. Production `VideoFrameRecall` therefore stores an optional caption, while `VideoCompilation`, which knows the compiler identity, enforces the mode for every frame.

The unchanged old `VideoDomainTest.framesRejectMissingBytesInvalidGeometryAndNonPositiveTiming` still expected the inner frame record alone to reject null recall. Both the full release run and an isolated complete-file run reproduced this exact failure. The isolated RED ended at 2026-10-08 20:51:31 +08:00: **13 tests, 1 failure, 0 errors, 0 skipped**, original line 122. Original release evidence remains `/private/tmp/keyword-hotfix-0051-release-verify.log` and its matching `surefire-reports` directory.

## Test-only change

Only `src/test/java/com/evidence/rag/model/domain/VideoDomainTest.java` changes:

- The original missing-caption rejection is preserved at the enclosing legacy `VideoCompilation` boundary. Existing test names and all frame bytes, geometry, timeline, audio identity, and byte-budget rejection assertions remain.
- A version 4 positive case verifies both complete frame identities and timing, immutable frame collection, original image SHA, unchanged aligned ASR, and no invented captions.
- Versions 1, 2 and 3 each accept complete captions and reject missing first, last, or every caption.
- Version 4 rejects mixed or complete caption presence and malformed version 4 identities.

No production source, configuration, database, fixture resource, or accepted contract was changed. The cause was a stale assertion location after the explicit versioned contract evolution, not a production relaxation of legacy caption requirements.

## GREEN

Scoped Spotless processed exactly the one absolute-path-matched Java file successfully at 20:52:22 +08:00. The same full-file JDK 21 offline Maven test command then passed at **2026-10-08 20:53:06 +08:00: 16 tests, 0 failures, 0 errors, 0 skipped** (the original 13 plus 3 explicit mode-contract tests).

Run from the isolated source root:

```sh
JAVA_HOME=/Applications/PyCharm.app/Contents/jbr/Contents/Home \
mvn \
  -o -ntp -s .mvn/settings.xml -gs .mvn/settings.xml \
  -Drag.build.directory=/private/tmp/keyword-hotfix-0051-migrations.1d2ZqX \
  -Dtest=com.evidence.rag.model.domain.VideoDomainTest test
```

Reports: `/private/tmp/keyword-hotfix-0051-migrations.1d2ZqX/surefire-reports/com.evidence.rag.model.domain.VideoDomainTest.txt` and its matching XML. This validates the domain contract only; it is not a cloud/video quality or full-release result. No model, remote, Git, service, or old-data operations were performed.
