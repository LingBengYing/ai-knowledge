# 合同

- 参考 Codex 的原生工具调用编号、工具结果配对、执行生命周期与模型窗口/完整历史分离；独立实现知识库执行器，不冒称运行了 Codex 或具备其模型能力。
- 模型仍由 Java 持有凭据和选择现有 OpenAI-compatible Chat Completions Provider。内部模型回调返回原生 assistant tool_calls，工具结果以 tool_call_id 对应，不再转换 ReAct 文本。
- 只有 knowledge_search、knowledge_read、terminate。全部调用先校验再执行，终止必须独立；不加入 Shell、SQL、文件修改或任意网络工具。
- 来源发现、实际阅读、当前组织/资料版本/删除状态校验保持。历史答案和压缩摘要不能变成当前 evidence_ids，追问仍重新查阅。
- 对话支持列表、新建、继续、改名、删除与重启回读；保存原始问答与公开任务状态，不保存模型私有思考/工具原文/凭据。对话属于同组织内当前主体；免登录部署仍沿用现有共享主体。
- 长历史达到可配置的字符阈值时，使用现有生成模型压缩较旧轮次，优先保留最近轮次；超长历史按完整内容顺序分批，全部成功后提交检查点，不把分批当失败重试。字符阈值不冒称精确 token 数。压缩检查点记录覆盖位置，原始对话不删改。失败/取消不自动重试，不安装无效摘要。
- 新旧内部 Agent 协议不混用，发布须同批更新 Java/Python/前端；数据迁移先验证，不能旧库覆盖新写入。
- 本机合成回归和真实供应商语义效果分开。未执行新的云模型调用，不宣称质量优于原模型。

## 对话接口与存储

`GET /v1/knowledge-conversations?offset=0&limit=30` 返回items/offset/limit，按更新时间列出当前主体的对话。
`POST /v1/knowledge-conversations` 接受可选title；`PATCH /{id}` 接受title，`DELETE /{id}` 无body且活动任务409。
`GET /{id}?offset=0&limit=20` 返回conversation、turns、offset、limit、has_more。
turns按ordinal升序，默认20轮；按约2MiB UTF8 JSON预算提前封页（至少保留一个完整轮次），不截断正文。
前端逐页恢复原始历史，不将上下文摘要作为展示历史。
`POST /v1/knowledge-agent/runs` 增加可选conversation_id；request_id在对话中持久防重。

SQLite v35→v36增加knowledge_conversations与knowledge_conversation_turns，加入清理schema inventory；不改原资料/索引。
原始公开问答、终态/引用持久保存。压缩只改summary/covered_turn_count，generation与active_run_id双重核对后安装；取消或晚到结果不覆盖新状态。
服务重启将遗留任务标为failed/agent_interrupted，不自动恢复执行。

## 上下文参数

- `rag.knowledge-agent.context-character-threshold`：默认24000字符，允许100–200000。
- `rag.knowledge-agent.context-recent-turns`：默认4，允许1–12。是优先保留量，超长时可减少。
- 超长历史以完整24000字符片段组成不超过48000字符的批次，保留Unicode边界；每批沿同一生成模型与计数通道，不重试。
- 摘要最多12000字符；失败、取消或配置变更不安装检查点，原始消息保留。
- 不保存/展示模型私有推理，不将工具原文写入对话历史。结构与来源身份校验不等于模型语义正确性。
