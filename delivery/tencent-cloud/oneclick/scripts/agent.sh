#!/bin/sh
set -eu
set -a
. /run/config/common.env
set +a
exec /app/.venv/bin/python -m knowledge_agent
