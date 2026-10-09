# Upstream source and boundary

Runtime: published `dbgpt==0.8.2` and `dbgpt-ext==0.8.2`, Python 3.10.
Source: https://github.com/eosphoros-ai/DB-GPT
Release package: https://pypi.org/project/dbgpt/0.8.2/
License: MIT, retained in the upstream wheel's dist-info license file.
Core wheel SHA-256: `54e6b115521eb711ec04eddbd4d436a326c87e3644d643a7a8a904bb454a9b83`.
Full direct and transitive versions, artifact URLs and hashes are in `uv.lock`.

The runtime inherits upstream `ReActAgent` and executes upstream
`ConversableAgent.generate_reply`, `ReActAction`, `ToolPack`, `FunctionTool`,
`AgentMemory`, and `ReActOutputParser`. It does not reimplement an agent loop.
The policy subclass provides a single-request model transport hook (upstream's
hook retries), rejects undeclared actions, suppresses raw console output, and
turns off upstream operation snapshot writes. Structured result validation is
application code. Source authority and actual model credentials remain in Java.

The published Agent imports DB-GPT's entire resource namespace, which imports
`dbgpt_ext.datasource.schema` even without a database resource. Accordingly the
matching extension wheel, SQLAlchemy and sqlparse are import dependencies. No
DB-GPT database connector, Text-to-SQL agent, database resource, MCP server,
shell, code, file or network tool is registered or started. The only tools are
knowledge_search, knowledge_read and upstream terminate. This is a component
integration, not a fork that deletes upstream SQL files.

The package metadata does not declare every dependency required by this import
graph. Explicit core import dependencies are pinned in pyproject.toml. MCP and
Pydantic are pinned to compatible versions; DB-GPT's broad optional extras
otherwise resolve newer incompatible versions. An install/import success is not
proof of model reasoning quality or production readiness.
