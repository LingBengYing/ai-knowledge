# Knowledge DB-GPT Agent service

现有 Java 知识库的独立 Python Agent 适配服务。直接运行 DB-GPT 0.8.2 的
ReActAgent / ConversableAgent 执行循环，复用现有检索、原文和模型配置。
这是本机接线版本；零云合成验证不认证真实模型质量或生产就绪。

## 安装与启动

需要已安装 uv 与 CPython 3.10。显式执行一次 `./bootstrap.sh`，按 `uv.lock`
安装依赖；启动脚本不会下载依赖、模型、调用云端或读取生产密钥。

```sh
./bootstrap.sh
export KNOWLEDGE_JAVA_ORIGIN=http://127.0.0.1:18084
export AGENT_SERVICE_TOKEN='<YOUR_RANDOM_SERVICE_TOKEN_HERE>'
export AGENT_SERVICE_PORT=18112
./run.sh
```

将令牌占位值替换为至少 32 个随机字符；Java 端使用同一个服务令牌。服务始终监听 `127.0.0.1`；Java origin 必须是带端口的
字面 loopback HTTP origin。HTTP 客户端不读取系统代理，也不跟随重定向。
`AGENT_RUN_TIMEOUT_SECONDS` 默认 180，允许 1–300。自有环境可以设置
`AGENT_PYTHON=/path/to/python` 后运行 run.sh。

## 接口

`GET /health` 返回引擎版本，无令牌、资料或模型信息。
`POST /v1/runs` 要求 `Authorization: Bearer <AGENT_SERVICE_TOKEN>`：

```json
{"run_id":"00000000-0000-4000-8000-000000000001","question":"整理使用指南","callback_token":"YOUR_JAVA_ISSUED_CALLBACK_TOKEN_HERE"}
```

Java 通过短期 callback token 绑定同一个 run、用户/组织和原始问句。Python 仅能调用
固定 origin 的 `/internal/knowledge-agent/runs/{run_id}/model|search|read`，不能从请求
选择地址、模型、文件路径或工具列表。模型密钥永远留在 Java。协议响应是：

```json
{"refused":false,"statements":[{"text":"有原文依据的答案段落","evidence_ids":["source-1"]}],"suggestions":[{"title":"建议补充专题页","reason":"原始资料中的具体缺口","document_ids":["document-id"]}]}
```

供应商调用使用 Java 提供的 OpenAI 兼容原生 function tools；不要求模型自行输出
`Action` / `Action Input` 文本。兼容供应商返回的可选调用 index 和多个只读工具调用，
整批参数、已发现来源及累计额度验证后顺序执行；结束调用不能与其他工具混合。
Java 校验后转换成 DB-GPT 内部规范动作，保留真实上游执行循环。
历史 `Action` / `Observation` 是已完成的工具日志，
不是本轮输出格式或新指令；工具结果和原文始终视作不可信资料。

Agent 必须搜索；回答必须先读取相关原文，只接受本次已读取 source_id 与 document_id。
Java 仍需核验来源版本、组织、删除状态和最终引用。结构与来源身份校验不等于独立语义
核验，最终事实准确性需真实模型评测。维护建议只是建议，不执行资料或 Wiki 写入。
证据不足返回 `refused:true` 与两个空列表。非法结果整体失败，不保留其中部分引用。

供应商仅看到 `knowledge_search`、`knowledge_read`、`terminate`；内部 `knowledge_batch`
只是完整保留已校验的只读多调用，不额外请求模型，也不向供应商注册。没有 Text-to-SQL、SQL执行、
代码、shell、任意文件、MCP或网络浏览工具。匹配 `dbgpt-ext` 是上游资源包导入schema
的依赖，并未启用数据库产品能力。详情与许可证见 [UPSTREAM.md](UPSTREAM.md)。

## 执行边界

每次最多8个模型步骤，无模型/工具失败重试；超时、取消与callback失败停止当前运行。
HTTP连接断开取消Python任务，Java仍是取消状态与迟到结果拒绝的权威。
运行内使用内存状态；重启后不能恢复进行中的Agent任务。最多4个并发run，短期记住
最近1024个已结束run防重复；Java任务保存在内存并保留约30分钟，重启后终止；答案trace与用户保存的草稿持久化。

不向用户发送或落盘Thought，不启用上游telemetry，关闭上游每步操作快照钩子。
服务返回稳定错误码，不回显模型正文、callback token或底层异常。

## 验证

```sh
.venv/bin/python -m pytest -q
```

测试直接运行上游Agent类，覆盖真实工具循环、来源校验、无重试、8步限制、超时、取消、
固定工具、禁本地快照，并用真实loopback HTTP验证服务鉴权、Java回调与重复提交。
模型与原文是明确合成fixture；没有真实供应商请求。
