# 0057 本机运行

## 实际结构

```text
问答页面 → Java Agent 任务接口 → 独立 Python / DB-GPT ReActAgent
                  ↑                        │
                  └─ 私有 model/search/read 回调 ┘
                  │
            原检索设置、资料原文、模型配置、答案 trace / 引用
```

Python 直接依赖上游 `dbgpt[agent]==0.8.2`。它负责模型规划、工具选择、循环与当前任务记忆；Java 保留资料、索引、模型密钥、引用和 Wiki 数据。没有部署 DB-GPT 整个平台，也没有自己重写一份 DB-GPT 执行器。依赖细节、适配范围及安装方式见 `agent-service/README.md`。

## 新数据与本机模型替身

在后端仓库运行：

```sh
AGENT_PYTHON=/absolute/path/to/agent-venv/bin/python bash scripts/run-agent-integration.sh
```

该脚本生成全新的临时构建和数据目录，先执行现有 Wiki HTTP 工作流回归，再启动 Python、测试源码中的真实 Java 应用与前端。仅模型响应和向量服务为本机替身；正式运行代码中的 DB-GPT 循环、Java检索/原文/引用、SQLite 与前端均真实执行。默认端口分别为18112、18111、18110，端口被占用即退出，不停止其他服务。

启动脚本不安装包、不下载模型、不使用云配置、不读写旧资料。Java 使用独立编译快照，不在运行 jar 上重新打包。Python 解释器须提前安装本切锁定依赖。`--build-only` 只构建和验证，不启动服务。自定义路径/端口见脚本 `--help`。

终端出现 READY 后，可用新的空数据实例执行验收：

```sh
node scripts/verify-agent-integration.mjs http://127.0.0.1:18111 /absolute/runtime/acceptance.json
```

验收会新建两份合成 TXT 并解析、索引，执行真实 Agent 多轮搜索/读取，验证两个原文引用、维护建议不写知识页、草稿和取消。仅允许空资料库执行种子步骤。替身返回预设合成答案，不用于验证模型推理能力、相关性或防幻觉效果。

在启动终端输入 `stats` 可读取该进程的 Agent 模型/工具计数；`restart` 仅重启该脚本持有的 Java 子进程；`stop` 停止该脚本持有的三个进程。重启后以以下只读命令验证原答案来源和草稿持久化：

```sh
node scripts/verify-agent-integration.mjs http://127.0.0.1:18111 /absolute/runtime/acceptance.json --read-only
```

## 接入实际环境

默认关闭 Agent。实际部署时需配置 Python 固定 Java loopback origin、Java 固定 Python loopback origin及一致的私密 service token，并显式启用 `rag.knowledge-agent.enabled=true`。服务 token 通过环境/私密配置注入，不写 URL、页面、仓库或命令行参数。具体变量见 Agent README。Java 的现有模型配置无需复制给 Python。

本切未执行真实模型请求或生产切换。启用真实环境后，一次任务最多8次规划模型调用，检索还可能触发既有 embedding/rerank 调用；8不是总云请求上限。后续真实质量验收须单独明确调用范围和预算。

运行中任务状态仅驻留当前 Java 进程，重启后任务轮询返回不存在，不自动恢复或重跑；已保存答案引用与草稿按原机制持久化。首切维护建议由用户阅读决定后续整理，Agent不能自动采纳或改写知识页。
