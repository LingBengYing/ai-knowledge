#!/usr/bin/env bash
set -euo pipefail

# Load each run from an immutable copy: Maven must never replace a live JVM's jar.
rag_project_dir="$(cd "$(dirname "$0")" && pwd)"
rag_built_jar="$rag_project_dir/target/rag-java-0.1.0-SNAPSHOT.jar"
if [[ ! -f "$rag_built_jar" ]]; then
  echo "Build java-backend with mvn verify before starting." >&2
  exit 1
fi
rag_runtime_dir="$(mktemp -d "${TMPDIR:-/tmp}/evidence-rag-java.XXXXXX")"
cp "$rag_built_jar" "$rag_runtime_dir/app.jar"
export RAG_DATA_DIRECTORY="${RAG_DATA_DIRECTORY:-$rag_project_dir/.data}"
rag_java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
exec "$rag_java" -jar "$rag_runtime_dir/app.jar" "$@"
