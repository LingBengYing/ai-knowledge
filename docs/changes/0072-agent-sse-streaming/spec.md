# 可观察合同

1. Agent 的 OpenAI 兼容请求使用 stream:true；增量解析 reasoning_content 和 tool_calls。完整工具消息校验完才交给 Python 执行；普通非 Agent 模型调用保持原合同。
2. 思考只取实际 reasoning_content。回答预览只取 terminate 的 result.statements[].text，支持跨帧字符串、转义和 Unicode；不显示原始工具 JSON。预览未核验，不提前生成来源、保存草稿或纳入对话上下文。
3. GET /v1/knowledge-agent/runs/{id}/events 返回 text/event-stream。event:snapshot 或 terminal，id 为独立递增 revision，data 为 {revision,run}。run 沿用现有字段，新增 preview 字符串。首帧和后续均累计快照；阶段 sequence 不用作流游标。
4. 模型每个片段更新运行快照；慢消费者可合并到最新快照，但模型不等待客户端网络发送。Run 锁内只更新状态，锁外传输。SSE 断连只撤销订阅，不重发模型、不取消任务；显式取消才终止任务。重连同任务从完整快照恢复。
5. 来源、版本和结果校验与会话持久化成功后才发送 completed 终态。失败、取消清除未确认 preview，迟到片段不得覆盖终态。服务重启沿用 interrupted 恢复，不重发问题。
6. Controller 处理 SSE/HTTP；Service 只处理纯 DTO/订阅。复用既有身份与任务访问校验，不用 URL token、不扩大代理路径或信任头。模型正文/密钥不进日志。
7. 前端两个代理逐块转发精确 SSE 路由，设置 no-cache 和 X-Accel-Buffering:no，保持背压和断连释放；普通响应规则不变。浏览器 fetch 流解码，旧生产轮询实现移除，GET 状态保留只读恢复用途。
8. 当前 schema36 不迁移，不持久化 token 日志；最终事件仍按0071保存。思考不进入后续对话或压缩上下文。

验收：合成上游尚未结束时，真实 HTTP 与页面已显示思考和回答前缀；结束后来源、对话、刷新一致。此类本机协议证明不冒充真实模型质量或生产验收。

协议依据：2026-10-11核对DeepSeek官方[Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)和[思考模式](https://api-docs.deepseek.com/guides/thinking_mode/)。流使用choices[].delta，工具按index拼接arguments，同轮reasoning_content原样回传；不引入模型专用私有请求协议。
