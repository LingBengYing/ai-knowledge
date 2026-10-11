# 意图

修复已定位的 Agent 最终结果失败：模型在拒答标志下仍返回回答或维护建议，旧 Python 校验直接以 `refusal_has_content` 结束整次任务。生产安全日志已证明检索、阅读完成后停在 result 阶段。本轮按负责人要求修改代码并验证。

影响范围：Python `validate_result`/HTTP 失败输出、Java Agent 工具说明与安全失败码枚举、Web 任务失败说明；现有 Java finish 继续验证来源、资料版本并持久化。调用链为模型 terminate → Python 校验与规范化 → AgentHttpClient → KnowledgeAgentService → SSE/会话 → Web。无接口路径或数据库迁移。

仅本机修复与验证；本轮没有新模型请求、发布或 Git 推送。
