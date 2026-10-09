# PDF worker measurement path regression

## RED and cause

The completed release regression reported both existing `PdfWorkerLifetimeProtocolTest` cases failing at the same measurement assertion: expected source `target/jacoco.exec`, actual the explicitly selected isolated Maven build directory's `jacoco.exec`. An independent complete-file run reproduced **2 tests / 2 failures / 0 errors / 0 skipped** at 2026-10-08 21:06:13 +08:00, before either worker lifecycle was exercised.

The project already supports `rag.build.directory`. The test, not JaCoCo, assumed the default `target`. This is a test configuration defect, not a PDF parsing or worker lifetime regression. The [0023 contract](../0023-scanned-pdf/spec.md) and [measurement plan](../0023-scanned-pdf/plan.md) still require the actual instrumented child to append to the independently verified coverage destination and exit before the next append.

## Minimal repair

- POM: only add Surefire system property `rag.test.build.directory=${project.build.directory}`. The JaCoCo plugin, thresholds, exclusions, JVM agent, and production build configuration are unchanged.
- Test: require the trusted Maven-supplied property to be nonblank and absolute, then compare the parsed agent destination against `<configured build directory>/jacoco.exec`. The actual destination is not used as its own expected value.
- A new negative case passes a different temporary expected directory and proves the measurement rejects the real agent destination there.
- Both original cases and all agent-file, append, dump-on-exit, file-output, execution-data growth, prior prefix SHA, complete PDF locator, parent identity, worker exit, and native-child lifecycle assertions remain unchanged.

Only `pom.xml` and `src/test/java/com/evidence/rag/worker/parser/PdfWorkerLifetimeProtocolTest.java` were modified for this repair; no production Java changes.

## Verification

The absolute-path-scoped Spotless pass processed exactly one Java file successfully at 21:06:56 +08:00.

An initial sandboxed related run encountered macOS `ProcessHandle.descendants` / `sysctl` permission denial. Its reports remain archived at `/private/tmp/keyword-hotfix-0051-pdf-sandbox-failure-210734.tar.gz`. With authorized local native-process access, the **same source and same test command** passed at **2026-10-08 21:08:15 +08:00**:

- `PdfWorkerLifetimeProtocolTest`: 3 passed, including both original complete worker protocols.
- `ProcessPdfOcrTest`: 5 passed, including deadline, interruption, close, and abrupt-parent-exit lifecycle checks.
- `PdfOcrCompilerTest`: 4 passed.
- Total: **12 tests, 0 failures, 0 errors, 0 skipped**.

Command, from the isolated source:

```sh
JAVA_HOME=/Applications/PyCharm.app/Contents/jbr/Contents/Home \
mvn \
  -o -ntp -s .mvn/settings.xml -gs .mvn/settings.xml \
  -Drag.build.directory=/private/tmp/keyword-hotfix-0051-migrations.1d2ZqX \
  -Dtest=PdfWorkerLifetimeProtocolTest,ProcessPdfOcrTest,PdfOcrCompilerTest test
```

Final reports are under `/private/tmp/keyword-hotfix-0051-migrations.1d2ZqX/surefire-reports`. This is the related local measurement and lifecycle verification, not the final complete release result. No cloud, remote, Git, service, or old-document operations were performed.
