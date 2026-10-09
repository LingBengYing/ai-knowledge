#!/usr/bin/env bash
set -euo pipefail
umask 077

agent_mode="${1:-run}"
if [[ $# -gt 1 || ( "$agent_mode" != run && "$agent_mode" != --build-only && "$agent_mode" != --help ) ]]; then
  echo "Usage: bash scripts/run-agent-integration.sh [--build-only|--help]" >&2
  exit 2
fi
if [[ "$agent_mode" == --help ]]; then
  echo "Build a NEW isolated Java snapshot and run real DB-GPT with synthetic local providers."
  echo "Requires preinstalled agent-service environment; set AGENT_PYTHON to its interpreter."
  echo "Overrides: AGENT_JAVA_HOME, AGENT_MAVEN, AGENT_PYTHON, AGENT_TEMP_ROOT, AGENT_WEB_PORT, AGENT_JAVA_PORT, AGENT_SERVICE_PORT."
  echo "No cloud requests, credentials, old data, or automatic downloads are used."
  exit 0
fi
agent_project="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
agent_java_home="${AGENT_JAVA_HOME:-/Applications/PyCharm.app/Contents/jbr/Contents/Home}"
agent_maven="${AGENT_MAVEN:-/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn}"
agent_python="${AGENT_PYTHON:-$agent_project/agent-service/.venv/bin/python}"
agent_node="$(command -v node || true)"
agent_temp="${AGENT_TEMP_ROOT:-${TMPDIR:-/tmp}}"
for agent_executable in "$agent_java_home/bin/java" "$agent_maven" "$agent_node"; do
  if [[ ! -x "$agent_executable" ]]; then
    echo "Required runtime executable unavailable: $agent_executable" >&2
    exit 1
  fi
done
if [[ "$agent_mode" == run && ! -x "$agent_python" ]]; then
  echo "Install agent-service dependencies first and set AGENT_PYTHON; startup never downloads packages." >&2
  exit 1
fi
if [[ "$agent_temp" != /* || ! -d "$agent_temp" || ! -w "$agent_temp" ]]; then
  echo "AGENT_TEMP_ROOT must be an existing writable absolute directory." >&2
  exit 1
fi
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS MAVEN_OPTS MAVEN_ARGS
agent_runtime="$(mktemp -d "$agent_temp/dbgpt-0057-integration.XXXXXX")"
agent_runtime="$(cd "$agent_runtime" && pwd -P)"
agent_build="$agent_runtime/build"
echo "New isolated runtime: $agent_runtime"
cd "$agent_project"
MAVEN_SKIP_RC=true JAVA_HOME="$agent_java_home" "$agent_maven" -o \
  -s "$agent_project/.mvn/settings.xml" -gs "$agent_project/.mvn/settings.xml" \
  "-Drag.build.directory=$agent_build" -Dtest=WikiWorkflowHttpTest test

"$agent_node" --input-type=module - "$agent_build" <<'NODE'
import fs from 'node:fs';
import path from 'node:path';
const build = process.argv[2];
const xml = fs.readFileSync(path.join(build, 'surefire-reports/TEST-com.evidence.rag.support.WikiWorkflowHttpTest.xml'), 'utf8');
const attrs = tag => Object.fromEntries([...tag.matchAll(/([\w.:-]+)="([^"]*)"/g)].map(m => [m[1], m[2]]));
const suite = attrs(xml.match(/<testsuite\b[^>]*>/)?.[0] ?? '');
if (!(Number(suite.tests) > 0) || ['failures','errors','skipped'].some(k => suite[k] !== '0')) throw new Error('Required existing workflow regression failed');
const cp = [...xml.matchAll(/<property\b[^>]*\/?>/g)].map(m => attrs(m[0])).find(p => p.name === 'java.class.path')?.value;
if (!cp) throw new Error('Missing runtime classpath');
const entities = { amp: '&', quot: '"', apos: "'", lt: '<', gt: '>' };
const decoded = cp.replace(/&([^;]+);/g, (_, e) => {
  if (Object.hasOwn(entities, e)) return entities[e];
  if (/^#x[0-9a-f]+$/i.test(e)) return String.fromCodePoint(parseInt(e.slice(2),16));
  if (/^#\d+$/.test(e)) return String.fromCodePoint(Number(e.slice(1)));
  throw new Error('Invalid XML entity');
});
const entries = decoded.split(path.delimiter).filter(Boolean);
if (entries[0] !== path.join(build,'test-classes') || entries[1] !== path.join(build,'classes') || entries.some(p => !path.isAbsolute(p) || !fs.existsSync(p))) throw new Error('Classpath must use new snapshot');
if (!fs.existsSync(path.join(build,'test-classes/com/evidence/rag/support/DbGptLocalIntegrationServer.class'))) throw new Error('Integration launcher missing');
fs.writeFileSync(path.join(build,'classpath.txt'), entries.join(path.delimiter), {mode:0o600});
NODE
if [[ "$agent_mode" == --build-only ]]; then
  echo "BUILD_ONLY_VERIFIED: new test-source snapshot and existing Wiki workflow verified; no service started."
  exit 0
fi
exec "$agent_node" "$agent_project/scripts/agent-integration-runtime.mjs" \
  "$agent_runtime" "$agent_java_home/bin/java" "$agent_python"
