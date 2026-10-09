# Linux deployment inputs

Prepared for OpenCloudOS 9.4 x86_64, CPython 3.10. The actual target must still be
verified by the deployment owner. This directory contains templates, not permission
to modify any server. The source archive excludes local virtualenvs, tests, data,
provider settings and tokens.

`requirements-linux.lock` is the production-only export of the existing uv.lock.
All registry packages have exact versions and SHA-256 hashes. The export resolves
markers for Linux x86_64 / Python 3.10, retaining greenlet and excluding Windows-only
packages. Regenerate explicitly with `uv export --frozen --no-dev --no-emit-project`
and `export_linux.py`; do not use pip freeze from a development environment.

Prepare a wheelhouse using only compatible CPython 3.10 manylinux x86_64 wheels and
`pip download --require-hashes --only-binary=:all: --no-deps`. No source distribution
may be built on the target. The whole closure is explicitly present in the export.
The target system Python is not changed. An official CPython standalone 3.10 runtime
can be placed at a separate, immutable `/srv/ai-knowledge/runtimes/...` directory.
Its exact download URL and SHA-256 belong in the deployment manifest.

For a fresh extracted release directory, run:

```sh
bash /path/to/release/deploy/install_offline.sh \
  /path/to/release /path/to/standalone/bin/python3.10 /path/to/wheelhouse
```

This creates the release's own .venv and performs hash-checked offline installation,
pip dependency validation and actual upstream Agent import. It does not enable a
service, contact a provider, run a knowledge query, or change Java/Wiki/data.
The standalone Python directory must remain at its final location because the venv
references it. Never move a prepared venv or embed a macOS venv into the Linux release.

Replace `@RELEASE_DIR@` in `ai-knowledge-agent.service.template` with the final absolute
release path. The template listens only on 127.0.0.1:18088 and calls only Java at
127.0.0.1:18084. Put a newly generated service token in `/etc/ai-knowledge/agent.env`
(root-owned mode 0600) and inject the same value into Java through its existing
protected configuration path. Do not put it in a unit, release archive, command line,
logs or documentation. systemd reads EnvironmentFile before applying DynamicUser.
No actual provider key is needed in the Python service.

Required permissions and pre-switch checks:

- Every parent component of the release, .venv and standalone Python must be
  traversable by the **actual active DynamicUser UID/GID**. Source/wheels/modules
  need read permission, directories traversal, and interpreter/shared libraries
  executable/readable permission. Root-only successful import is insufficient.
- Use ordinary public runtime modes (directories 0755, files 0644 and executables
  0755) for the code/runtime where compatible with the existing deployment baseline.
  Keep private config and rollback/backup directories at 0700, secrets 0600. Do not
  weaken private directories just to make a release located beneath them readable.
- Python needs no access to the Java SQLite database, uploads, Milvus credentials,
  model credentials, or Wiki files. Its only temporary directory is systemd's
  private `/run/ai-knowledge-agent` mode0700; Python bytecode writes are disabled.
- Verify actual service User/DynamicUser settings and active UID/GID; do not assume
  a persistent passwd entry exists. Test path traversal and imports using that
  effective identity before switching Java to the Agent.
- Verify port18088 is free, EnvironmentFile has the token, and Java points to the
  same port/token. Check `/health` only for the initial no-model readiness probe.
- The IPAddressDeny/Allow directives require working systemd IP filtering; confirm
  the effective unit behavior on the host. Application clients independently use
  fixed literal loopback origins, `trust_env=False` and no redirects.

Logs go to journald without access logging. The adapter discards upstream message
logs, never exposes Thought, disables DB-GPT operation snapshots and uses only
per-run memory. No SQL, code, shell, arbitrary HTTP, file or MCP tool is registered.
The service restart policy restarts the process only; it cannot replay old tasks.
Stopping a release cancels active Python work; Java remains the authority that
rejects late callbacks/results. Readiness is not a real model quality test.

`package_release.py OUTPUT.tar.gz` creates a deterministic source archive with an
internal SHA256SUMS file. Its destination must not already exist. It includes the
unit and installer templates but excludes this machine's runtime state.
