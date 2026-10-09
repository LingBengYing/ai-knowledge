#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
# Explicit install step only. Never run this implicitly from the service launcher.
exec uv sync --frozen --python 3.10 "$@"
