#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
AGENT_PYTHON="${AGENT_PYTHON:-.venv/bin/python}"
if [[ ! -x "$AGENT_PYTHON" ]]; then
  echo 'Agent environment missing. Run bootstrap.sh explicitly first.' >&2
  exit 1
fi
exec "$AGENT_PYTHON" -m knowledge_agent
