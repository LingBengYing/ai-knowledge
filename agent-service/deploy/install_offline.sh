#!/usr/bin/env bash
# Run explicitly during deployment. No registry access, no models, no service restart.
set -euo pipefail
if [[ "$#" -ne 3 ]]; then
  echo 'usage: install_offline.sh RELEASE_DIR PYTHON310 WHEELHOUSE' >&2
  exit 2
fi
release_dir="$(cd "$1" && pwd -P)"
python_bin="$2"
wheelhouse_dir="$(cd "$3" && pwd -P)"
if [[ "$release_dir" != /* || "$python_bin" != /* || "$wheelhouse_dir" != /* ]]; then
  echo 'all paths must be absolute' >&2
  exit 2
fi
"$python_bin" -c 'import sys; assert sys.version_info[:2] == (3, 10), "CPython 3.10 required"'
if [[ -e "$release_dir/.venv" ]]; then
  echo 'target venv already exists; use a fresh release directory' >&2
  exit 2
fi
"$python_bin" -m venv "$release_dir/.venv"
"$release_dir/.venv/bin/python" -m pip install --disable-pip-version-check \
  --no-index --no-cache-dir --find-links "$wheelhouse_dir" --require-hashes \
  --only-binary=:all: -r "$release_dir/deploy/requirements-linux.lock"
"$release_dir/.venv/bin/python" -m pip check
# This imports actual DB-GPT without invoking an Agent, model or database connection.
cd "$release_dir"
PYTHONDONTWRITEBYTECODE=1 PYTHONNOUSERSITE=1 "$release_dir/.venv/bin/python" - <<'PY'
from importlib.metadata import version
assert version("dbgpt") == "0.8.2"
assert version("dbgpt-ext") == "0.8.2"
from dbgpt.agent import ConversableAgent
from knowledge_agent.runtime import KnowledgeReActAgent
assert KnowledgeReActAgent.generate_reply is ConversableAgent.generate_reply
print("agent_upstream_import_verified")
PY
