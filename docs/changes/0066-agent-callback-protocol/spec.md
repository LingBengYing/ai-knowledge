# Spec

1. Agent 调度使用标准 tools + tool_choice=required；仍接受已合法验证的多个只读工具调用，terminate 必须独立。禁止自动重试、文本 Action 猜测解析或将自由文本包装成有据答案。
2. 仅官方 api.deepseek.com 的 Agent 请求显式 thinking.type=disabled，以兼容 required。以端点能力适配，不按模型名猜测；其他兼容端点不增加私有参数。普通生成、模型选择、索引指纹和 token 参数不变。代理端点不能推定支持 DeepSeek 私有参数，需另验兼容性。
3. 正常文本 stop 且无工具返回 model_tool_required；其他非法 envelope/arguments 仍 model_invalid_response。来源身份、已搜索/已阅读、版本及终态校验保持。
4. Java Agent 回调把模型失败转成安全固定原因码：agent_model_tool_required、agent_model_invalid、agent_model_unavailable、agent_model_timeout；资料工具中的模型失败为 agent_tool_failed。未知故障保留通用回调失败，不暴露原异常。
5. Python 仅从有界、合法 problem JSON 读取允许的 error_code；超时沿用 HTTP408，模型/资料阶段不可用沿用 HTTP503。未知/畸形/超大错误、重定向与传输错误不透传正文。原因码经 Agent HTTP→Java 任务状态→前端固定文案保留。请求失败后不执行下个工具或重试。
6. 无 schema、权限、运行配置或业务资料改动。模型运行模式变化必须明确记录；本地协议替身通过不等于真实模型质量或线上已修复。

官方依据（2026-10-10 查阅）：[DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/) 明确 auto 可选择文本，required 要求工具，并明确思考模式不支持 required；[Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode/) 说明默认思考模式及 thinking 参数。
