# Local verification — 2026-10-09

Scope: Python component only, no provider calls, production data, deployments or Git writes.

- Fixed upstream `dbgpt==0.8.2` and `dbgpt-ext==0.8.2`; upstream wheel remains unmodified.
- First reproducible red state: service absent, then real upstream execution failed because its
  per-conversation GptsMemory queue was not initialized (5 behavioral failures / 2 passes).
  Initialized the upstream queue, with a per-run in-memory buffer and shared bounded executor.
  No failing test was removed or weakened.
- Final `python -m pytest -q`: **14 passed**, 2 upstream deprecation warnings, 15.45 seconds.
  Includes actual TCP service-to-callback traffic, real DB-GPT search/read/terminate loop,
  evidence and suggestion identity rejection, no-read rejection, unauthorized calls,
  duplicate run rejection, unknown-tool stop, no retry after callback failure, eight-step
  limit, timeout, cancellation, fixed tool registry, disabled operation snapshots, and
  Java's per-read / suggestion-document limit of 32.
- Asserted method identity: `KnowledgeReActAgent.generate_reply is
  ConversableAgent.generate_reply`. The test runs the upstream orchestration implementation;
  model replies and evidence are explicitly synthetic callback fixtures.
- `uv sync --frozen --offline` succeeded against `uv.lock` in the isolated interpreter
  environment; exploratory packages no longer needed were removed.
- `uv pip check`: 79 installed packages, all compatible.
- `python -m compileall -q knowledge_agent tests`, `bash -n bootstrap.sh run.sh` and scoped
  `git diff --check` succeeded.

The service uses source identity checks, not an independent semantic judge. These results
prove local protocol execution and bounded integration behavior. They do not prove real
provider planning/writing quality, source-entailment quality, production operation, Java
cross-stack UI behavior, or restart recovery of unfinished tasks. Root's change verification
records the separate Java/Python/browser integration result.
