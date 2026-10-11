# Knowledge native Agent service

知识库自己的轻量原生工具执行器，参考 Codex 的调用编号、生命周期和上下文管理。
已移除 DB-GPT/ReAct 框架及 Action/Observation 文本转换。不是 Codex 托管服务，
不需要新增 OpenAI 密钥；继续使用 Java 配置的 OpenAI-compatible 模型。
源码参考和许可边界见 [UPSTREAM.md](UPSTREAM.md)。

## 安装与启动

需要 uv 和 CPython 3.10。显式运行 `./bootstrap.sh` 安装锁定依赖；启动不安装依赖。

```sh
./bootstrap.sh
export KNOWLEDGE_JAVA_ORIGIN=http://127.0.0.1:18084
export AGENT_SERVICE_TOKEN='<YOUR_RANDOM_SERVICE_TOKEN_HERE>'
export AGENT_SERVICE_PORT=18112
./run.sh
```

令牌使用至少32个随机字符，Java/Python一致，仅从受保护配置注入。
Python监听127.0.0.1，Java origin只允许带端口的字面loopback HTTP地址。
不读取系统代理、不跟随重定向。`AGENT_RUN_TIMEOUT_SECONDS` 默认180，范围1–300；
`AGENT_PYTHON`可指定隔离解释器。生产须新建干净venv，不能沿用含DB-GPT的旧环境。

## 原生协议 v2

`GET /health` 返回engine为knowledge-native-agent、protocol为2。
`POST /v1/runs`要求Bearer服务令牌：

```json
{"run_id":"00000000-0000-4000-8000-000000000001","question":"整理使用指南","callback_token":"YOUR_JAVA_ISSUED_CALLBACK_TOKEN_HERE","history":[],"summary":""}
```

Java签发短期callback token并绑定run、主体和问题。Python仅调用固定Java origin的
`/internal/knowledge-agent/runs/{run_id}/model|search|read`，不能选择模型、地址或工具。
模型密钥留在Java。模型回调保留原生assistant.tool_calls；执行结果以tool_call_id配对。
整批参数先校验再顺序执行，terminate必须独立。不再转换ReAct文本。

最终结果合同保持：

```json
{"refused":false,"statements":[{"text":"有原文依据的回答","evidence_ids":["source-1"]}],"suggestions":[]}
```

每轮必须search/read，只接受当轮已读取source_id。Java继续核验组织、原文版本、
删除状态和引用。旧历史/摘要只作不可信背景，不构成当轮来源。维护建议不执行写入。

## 对话与上下文压缩

Java SQLite保存完整问题、公开回答、任务状态和引用；支持列表、新建、继续、
改名、删除。对话按主体隔离；免登录入口沿用共享主体。一个对话仅允许一个活动任务。
刷新只GET恢复，服务重启将遗留任务标记中断，不重发模型请求。

达到字符阈值的历史由当前生成模型整理成摘要，保存独立检查点与覆盖轮次，
原始消息不删改。优先保留近期完整轮次；超长历史完整分批整理，全部成功后才
原子安装摘要。压缩走同一模型客户端/计数，不自动重试；字符不是精确token数。
失败保留原记录并明确提示，不静默丢弃历史、不冒用旧引用。
API与配置详见[0070合同](../docs/changes/0070-native-agent-conversations/spec.md)。

## 模型思考与执行详情

0072起，Java→模型使用标准 `stream:true`，Java→浏览器使用 SSE；Python 内部 `/model`
仍接收完整且已校验的 assistant 消息，避免半截工具参数触发执行。Java 消费模型流时即
广播思考与回答预览，不必等待 Python 的一轮返回。最终答案仍须来源校验，预览不作证据。
不新增模型轮次或 Python 依赖，见[流式合同](../docs/changes/0072-agent-sse-streaming/spec.md)。

Java任务事件记录实际查询、读取资料的位置、返回数量和耗时；前端按步骤折叠展示。
模型明确返回的 `reasoning_content` 可在独立“模型思考”区域查看；未返回时不伪造。
该字段在同次原生工具循环中原样回传，兼容需要它的模型协议；`tool_choice=auto`，
不强制关闭或开启供应商思考模式，也不增加用于生成解释的模型请求。

Java只将最多16000码点的展示副本存入对话记录，截取时页面明确提示；这不截断
协议回传原文、不限制模型生成token。思考以纯文本展示，不作为证据，不进入下一轮
对话背景或上下文压缩。详情合同见[0071](../docs/changes/0071-agent-execution-details/spec.md)。

## 边界与验证

Python每次最多8个模型步骤/16工具调用，取消/超时/回调失败停止。只注册3个知识工具，
没有SQL、Shell、文件写入、任意网络或MCP工具。日志/异常不写模型思考、工具原文或凭据。
Python仅有运行内状态，完整会话归Java持久化；发布须配套升级Java/Python/前端。

```sh
.venv/bin/python -m pytest -q
```

测试覆盖原生循环、调用ID、来源校验、取消/无重试、完整历史和本机HTTP。
合成协议fixture不替代真实供应商质量验收。
