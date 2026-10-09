# 0051 historical migration fixture verification

## Scope and original RED

This is a test-only release-blocker repair in the isolated hotfix source. No production repository, schema, migration, data, model configuration, or running service was changed by this work.

The original full regression evidence is `/private/tmp/keyword-hotfix-0051-full.log` and `/private/tmp/keyword-hotfix-0051-full/surefire-reports`. The earliest common failures were `ReindexSqlFixture` expecting a fresh version 25 database while production already creates version 29, and `VideoAvMigrationTest.restoreVersionNineteen` only recognizing the historical downgrade chain through version 25. The production repository implementation matched the online source baseline before the separately coordinated formatting pass. These failures did not establish a production database regression.

## Repair and preserved contracts

- `HistoricalSchemaV25Fixture` obtains the physical version 25 schema from the actual version 25 backup produced by production migrations starting with the existing version 1 fixture. It restores that complete historical schema before the existing version 25-to-24 and older fixture steps. It does not merely change a version number.
- Shared rows are preserved and compared, including original byte blobs and publication identity. Restoration is transactional and checks exact schema objects plus foreign-key integrity.
- Restoration refuses persisted post-version-25 authority, populated new columns, initialized runtime selection, version 4 video compilation, and prepared indexing jobs. Only the untouched generated runtime-selection singleton may be removed. It cannot silently discard newer test data to make an old fixture pass.
- Existing version 23/24-to-25 object-equality assertions compare against the real intermediate version 25 backup, while the final database is still required to reach version 29. This preserves the old migration contract without incorrectly requiring later intentionally changed guards to remain byte-identical.
- Historical backup counts include the four additional real migration stages. Existing original image/audio vector guards must remain individually present, in addition to checking the current total guard count. Assertions and test identities were not removed, skipped, or weakened.
- Three new tests cover physical schema restoration with publication and original-byte preservation, refusal of persisted unified-answer traces, and refusal of initialized runtime selection.

The exact 33-file Java test manifest is `../migration-files.json` relative to the isolated source root: `<PRIVATE_WORKSPACE>/.local/hotfix-0051-20261008/source`. The private absolute path is redacted. Two files are new; all listed Java paths are under `src/test/java/com/evidence/rag/repository/`. Files changed by other agents are excluded.

## Final GREEN

At **2026-10-08 20:45:05 +08:00**, JDK 21 offline Maven completed the entire related repository test-file selection:

- **187 tests; 0 failures; 0 errors; 0 skipped.**
- Full files include the historical migrations, reindex authority/format migrations, document cleanup permission boundary, OCR/video source repositories, synopsis repository, and the new fixture tests.
- Reports: `/private/tmp/keyword-hotfix-0051-migrations.1d2ZqX/surefire-reports`.
- All 33 owned Java files received a scoped Spotless pass, with absolute-path file expressions, immediately before this final run.

Run from `<PRIVATE_WORKSPACE>/.local/hotfix-0051-20261008/source` (private absolute path redacted):

```sh
JAVA_HOME=/Applications/PyCharm.app/Contents/jbr/Contents/Home \
mvn \
  -o -ntp -s .mvn/settings.xml -gs .mvn/settings.xml \
  -Drag.build.directory=/private/tmp/keyword-hotfix-0051-migrations.1d2ZqX \
  '-Dtest=com.evidence.rag.repository.*MigrationTest,com.evidence.rag.repository.Reindex*Test,com.evidence.rag.repository.VideoOcrRepositoryTest,com.evidence.rag.repository.VideoTraceRepositoryTest,com.evidence.rag.repository.SynopsisRepositoryTest,com.evidence.rag.repository.DocumentCleanupPermissionBoundaryTest,com.evidence.rag.repository.HistoricalSchemaV25FixtureTest' \
  test
```

An earlier unqualified `Reindex*Test` selection also selected web/service files; its loopback permission errors were an execution-environment issue, not migration evidence. The final command explicitly selects repository packages and does not skip any selected test. A newly added explicit audio guard-name assertion initially used `identity` instead of the real original guard `sealed`; it was corrected against production DDL, then the whole audio migration file passed in the final run.

## Boundary

This result certifies the repaired local historical fixtures and the complete directly related test files. It is not the full release regression, browser acceptance, cloud-provider quality, remote database migration, or deployment result. Cloud requests, Git writes, remote operations, and old document changes performed by this subtask: **0**. Parent task owns final merge, full formatting/regression, and release decisions.
