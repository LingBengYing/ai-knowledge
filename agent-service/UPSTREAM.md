# Source reference and implementation boundary

This application owns its small native knowledge-tool executor. It does **not**
run Codex, embed Codex models, or depend on DB-GPT. No upstream source is vendored.

Architecture reference: [openai/codex](https://github.com/openai/codex), pinned to
`cc7ba33601286a751477832b01de7b6f8de0dd9c` (inspected 2026-10-11).
Upstream is Apache-2.0; see its [LICENSE](https://github.com/openai/codex/blob/cc7ba33601286a751477832b01de7b6f8de0dd9c/LICENSE).
If source is copied in a future change, retain license/notice text and record
copied files and modifications. This change reimplements patterns, not Rust code.

Inspected references:

- [tools/router.rs](https://github.com/openai/codex/blob/cc7ba33601286a751477832b01de7b6f8de0dd9c/codex-rs/core/src/tools/router.rs): named tool registry, native function-call IDs and dispatch.
- [tools/parallel.rs](https://github.com/openai/codex/blob/cc7ba33601286a751477832b01de7b6f8de0dd9c/codex-rs/core/src/tools/parallel.rs): retain call IDs in completion/failure outputs; lifecycle and cancellation.
- [context_manager/history.rs](https://github.com/openai/codex/blob/cc7ba33601286a751477832b01de7b6f8de0dd9c/codex-rs/core/src/context_manager/history.rs): model-window history and retained history are separate; replacement/checkpoint semantics.

Local choices: standard OpenAI-compatible Chat Completions, sequential execution
after whole-batch validation, only knowledge_search/knowledge_read/terminate,
Java-owned credentials/source authority, SQLite-owned original conversation
turns plus a separate summary checkpoint. Summary is untrusted background, never
current evidence. Current sources must be searched and read on every run.

Codex's complete Rust/Responses environment, shell, file, network and approval
tools are not imported. Existing provider capability still determines reasoning
and answer quality; replacing a framework is not a quality benchmark.
