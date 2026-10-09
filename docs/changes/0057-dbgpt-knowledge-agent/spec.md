# 0057 行为合同

## 执行与数据责任

- Python 服务复用 DB-GPT 的 Agent、Action/Tool 和 LLM Interface；只挂载知识检索与原文阅读工具，不开放 SQL、Shell、代码执行或任意 HTTP 工具。
- Java 是原资料、已发布证据、组织身份、检索设置、模型配置、正式引用和 Wiki 版本的权威。Python 不复制资料库/向量索引、不保存服务商密钥。
- Java 在开始任务时固定当前组织全库 scope、模型和检索配置，每次工具调用及提交时校验有效性。每轮搜索使用当前已捕获的 TopK、阈值和检索方式，不回到全文旁路。
- 搜索返回服务器分配 source_id 和摘要；read 只接收本任务 source_id 并回读完整选中原文。最终陈述必须关联本任务实际读取的来源；由 Java 分配正式引用序号并保存原有知识答案 trace。模型不可生成 locator。
- 普通综合回答保留0053的原文综合合同，不重新引入手写字段证明或二次模型语义核验。文档与工具结果都是不可信数据。
- 模型规划使用当前 Java generation Adapter，新增独立 Agent 调用方法，不改变旧模型/index 指纹或 max_tokens 规则。禁止自动重试真实模型请求。

## 公共接口

仅本机显式配置 `rag.knowledge-agent.enabled=true` 启用；默认关闭，前端可使用现有普通问答。

- `GET /v1/knowledge-agent/config` → `{enabled,engine,max_steps}`。
- `POST /v1/knowledge-agent/runs`，`{question,request_id}` → HTTP202，任务状态。
- `GET /v1/knowledge-agent/runs/{id}` → 任务状态；不得触发模型。
- `POST /v1/knowledge-agent/runs/{id}/cancel`，`{}` → 当前任务状态；不保证撤回已经发出的远端 HTTP，但必须阻止迟到结果发布和后续调用。
- 任务字段：`id,status,events,result,suggestions,error`；状态为 running/completed/failed/cancelled，事件仅含顺序号、固定类型和安全阶段消息；`result` 复用 KnowledgeAnswerResult；不公开思维链、原模型响应或私密配置。
- 原组织鉴权保持，任务读取/取消限发起 workspace+principal；资料检索仍为组织全库共享。request_id 在发起人范围内幂等、防重复、并发及总时限有界。Agent 失败不得静默改走旧问答。首切任务状态为当前运行进程内，重启后失效明确返回不可用/不存在，不自动重跑；正式答案引用按原持久 trace 回读。

## Java/Python 私有接口

Python 固定配置 Java origin，Java 固定配置 Python origin，首切限定 loopback。共享服务 token 仅用于 Java 启动 Python 执行；每次执行使用独立随机 callback token，绑定任务身份与生命周期，不接受 body 声明 actor、来源 locator、模型地址或任意回调 URL。

- Python `POST /v1/runs` Bearer service token，`{run_id,question,callback_token}`，同步返回 `{refused,statements:[{text,evidence_ids}],suggestions:[{title,reason,document_ids}]}`。
- Java `POST /internal/knowledge-agent/runs/{id}/model` Bearer callback token，`{messages:[{role,content}]}` → `{content}`。
- Java 同前缀 `/search`，`{query}` → `{sources:[{source_id,title,kind,excerpt,document_id}]}`。
- Java 同前缀 `/read`，`{source_ids}` → `{sources:[{source_id,title,kind,text,document_id}]}`。
- callbacks 拒绝非loopback、错token、过期/已取消/终态任务和非法形状。内部路径不加入浏览器代理白名单。
- 一次任务最多8次规划模型调用、16次检索/读取工具调用；稳定候选池最多64个来源，单次read最多32个，可分批读取。最终最多64条陈述、8条建议，每条建议关联最多32份实际读取资料、理由最多2000字符。检索触发的embedding/rerank调用另计，不能将8步称为总模型HTTP预算。

## 前端与维护建议

只改问答路径，展示真实执行步骤、停止和最终原文引用，保留保存草稿。维护建议单独呈现；本切不自动写入/采纳 Wiki。建议关联本任务有效资料，不能将答案/记忆作为新原始证据。现有编译入口可用于后续人工执行；Agent 自写章节的结构化提案入口属于后续切片。

真实服务商质量、持久长任务恢复、多Agent协作、自动维护、联网搜索、纯视觉自主检索和生产部署不在本次本机验收内。
