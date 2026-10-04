#!/usr/bin/env bash
set -euo pipefail

# Start the real backend for the independent frontend without requiring model credentials.
rag_workspace_project="$(cd "$(dirname "$0")" && pwd)"
export RAG_DATA_DIRECTORY="${RAG_DATA_DIRECTORY:-$rag_workspace_project/.data/workspace}"
exec bash "$rag_workspace_project/run-dev.sh" --spring.profiles.active=workspace "$@"
