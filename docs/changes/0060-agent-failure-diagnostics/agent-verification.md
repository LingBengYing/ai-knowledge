# Agent 安全失败链验证

2026-10-09 +08，范围为 Java Agent 协议 Adapter / 任务终态、Python Agent 安全诊断和前端失败说明。未执行云请求、旧问题重发、生产修改或 Git 写入。

## 实际修复

- 原共享 HTTP transport 在非 200 响应头阶段取消响应，Agent 无法读取 Python 的枚举失败码。新增包内、只有 Agent 使用的 `postWithSafeErrorDecoder`；有界读取、严格 JSON、deadline、禁止重定向与不重试保持。其他模型调用仍在非 200 响应头阶段拒绝，不改变其协议。
- Agent Adapter 只接受精确 `{ "error": "known_code" }`。未知值、额外字段或非字符串错误泛化；畸形 JSON/UTF-8/响应格式返回 `agent_invalid_response`。不携带原始异常或服务端正文。
- `AgentFailureCode` 为 Java Client/Service 共用领域枚举。Service 不再把已知回调、模型输出、动作、参数、最终结果、步数原因合并为 `agent_failed`。
- Python action 异常曾被上游循环吞掉后变成 `agent_execution_failed`；在现有失败路径保留安全枚举，再由原循环停止。没有修改上游执行循环、模型提示、工具集、来源证明、预算或重试策略。
- Python 仅输出 `run_id/code/stage/model_calls/tool_calls`；Java 失败/取消终态仅输出对应 UUID、状态、枚举原因、最后阶段与调用计数。原问题、思维、工具输入/原文、模型正文和密钥不进入诊断日志。
- 前端只按 code 映射固定说明，保留原因码。API 解析及 Session 发布两个边界均不显示服务端自由 message；不会统一建议盲目重发。

## 原因码合同

| 原因码 | 含义 |
| --- | --- |
| `agent_unavailable` | 服务不可达或非可识别错误响应 |
| `agent_timeout` | 任务/HTTP 等待超时 |
| `agent_callback_failed`, `agent_callback_invalid` | Java 模型/资料回调失败或返回无效；日志阶段区分 model/search/read |
| `agent_model_invalid` | 生成模型没有可用内容 |
| `agent_invalid_action`, `agent_invalid_tool_input`, `agent_tool_failed` | 动作协议、工具参数或工具执行失败 |
| `agent_invalid_result`, `agent_invalid_response` | 最终结构/来源或 Adapter 响应校验未通过 |
| `agent_step_limit`, `agent_limit_exceeded` | Python 查阅步数或 Java 处理范围限制 |
| `agent_execution_failed`, `agent_failed` | 其余已泛化的执行失败 |
| `agent_cancelled`, `agent_busy` | 连接中断或 Agent 容量占满 |
| `scope_changed`, `configuration_changed`, `evidence_changed` | 资料范围、模型配置或证据版本发生变化 |

Python阶段为 starting/model/search/read/action/result；Java阶段沿现有安全事件 running/planning/searching/reading。阶段不是模型思维链。新字段未加入公开响应，页面可从现有安全事件展示最后步骤。

## RED → GREEN

1. 新真实 loopback `AgentProtocolClientTest` 收到 HTTP 502 `agent_callback_failed`，旧实现返回 `agent_unavailable`，**1 failed / 2 passed**。首次 sandbox 禁止监听属于环境错误，重获本机 loopback 执行权限后的上述结果才是产品 RED。
2. 前端新回归期望固定区分说明，旧实现输出“智能体任务未完成，请重新发起”，**1 failed / 5 passed**。
3. 修复后 Java 完整相关文件 `AgentProtocolClientTest,KnowledgeAgentServiceTest,KnowledgeAgentControllerTest,OpenAiCompatibleModelsTest`：**39 passed，0 failed/error/skipped**，17:11:41 +08。包含真实 502 枚举、未知/畸形响应不泄露、超时与不可达区分、Service 终态/安全日志、原问答引用拒绝与共享 OpenAI HTTP 23 项回归。
4. Python `python -m pytest -q`：**18 passed**，2 个既有上游废弃警告。真实 TCP 运行实际 DB-GPT 循环，用合成未知 action 验证 HTTP502 + `agent_invalid_action` + stage=action + model_calls=1/tool_calls=0，证明未额外重试且不泄露模型/问题/工具正文。原成功搜索→阅读→结束、来源负例、超时/取消仍通过。
5. 前端 `node --test ui-tests/knowledge-agent.test.mjs`：**6 passed**；`node --check public/knowledge-agent.mjs` 通过，19 类中文映射不显示服务端原文。
6. 六个本切 Java 文件 Spotless apply/check 通过；Python compileall 和前后端限定 diff whitespace 检查通过。

Python 新 ASGITransport 日志用例最初因其不模拟 TCP disconnect、与监视器交互卡住而停止；测试显式提供“仍连接”替身后完成。真实 TCP 成功与失败链另行运行，并未以该替身认证网络断连行为。旧 unknown-tool 断言由泛码 `agent_execution_failed` 收紧到 `agent_invalid_action`，保留“只调用模型一次、不执行工具”的断言，未删除或放宽失败保护。

## 未验证边界

这证明新失败可以被准确区分与关联，不恢复旧线上已丢失的 502 正文，也不证明旧线上错误已消失。没有真实模型质量复验；没有部署、云调用或原资料改动。原全仓发布例外与真实 RAG 验收仍开放。完整新版浏览器联调和全部前端回归由 Web0046 统一记录。
