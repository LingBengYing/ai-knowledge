# 可观察合同

1. 同一Event保留sequence/type/message，新增可选details；开始/完成/失败在同一sequence更新，不把结果重复计为一次新检索。取消或超时封住运行中事件，晚到结果不覆盖终态。
2. details字段：status（running/completed/failed/cancelled）、tool_name（model/knowledge_search/knowledge_read）、query、elapsed_ms、result_count、document_count、sources、safe_code、reasoning、reasoning_truncated、tool_names。可空字段可缺省，列表默认[]，truncated默认false。
3. sources最多32条，为Java当前任务权威校验后的元数据：source_id/document_id/revision_id/title/kind/page/start_ms/end_ms。不返回原始工具JSON、完整原文或任意模型提供的链接。查询来自实际工具输入；source_ids即本次任务编号。
4. 模型阶段tool_names仅表示响应选中的工具，不冒充已执行。reasoning只取本次provider响应的reasoning_content，缺省/空不显示，不从content/system prompt拼造思考。展示副本最多16000Unicode码点，超出明确reasoning_truncated；此展示裁剪不修改协议原文。
5. AgentMessage新增可选reasoning_content，仅assistant工具消息可携带，纳入既有消息体/总上下文界限；同次任务工具循环原样回传。新的用户追问与上下文压缩仍只取问题及最终答案，不携带事件/思考正文。
6. 标准tool_choice改auto，移除对官方DeepSeek强制thinking.disabled；不强行切换模型、开启模式或指定推理强度。只有符合原有来源校验的terminate可交付答案，无工具响应仍显式失败，不自动重试。
7. 前端纯文本转义，独立折叠“模型思考”和工具详情。不执行HTML/Markdown链接或把模型文本当命令。当前任务GET和持久对话回读均能展示；旧记录缺details仍可读但不补造。
8. result_json保存公开展示事件，schema36不变。必须测试旧JSON→新读取器、会话重开及分页；未演练旧JAR读取新字段，不声明旧包可直接回滚。

协议依据：2026-10-11读取DeepSeek官方[思考模式](https://api-docs.deepseek.com/guides/thinking_mode/)与[Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)：思考模式使用auto工具选择，同一工具循环需保留reasoning_content。服务端来源权威不因tool_choice改变而放宽。

本机真实HTTP/合成模型验收与云模型质量分开，不修改生产、不自动调用云模型/推送/部署。
