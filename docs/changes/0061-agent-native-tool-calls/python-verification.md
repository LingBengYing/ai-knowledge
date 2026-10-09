# Python 原生工具提示验证

## 真实协议观测后的兼容补齐

首版真实观测显示供应商合法返回两个 `knowledge_search` 调用且含可选 `index`；
不能把它们一律当作非法响应。新版Java允许合法的0-based index，完整验证最多16个
调用后才返回内部规范动作；单调用保持，纯搜索/阅读多调用转换成内部 `knowledge_batch`。
结束调用必须单独，未知工具、混合结束、重复调用ID和畸形末项均拒绝整批。

Python内部注册批处理Tool，但供应商仍只看到原3个工具。整批shape、每项参数、已发现
source_id及剩余累计工具额度全部通过后才顺序调用既有search/read，保留所有结果及顺序，
不额外请求模型、不丢弃后续调用、不跳过失败项。上游generate_reply循环、已有来源最终
核验、8模型步/16工具调用、失败停止不重试保持。

- 红：新批量成功/剩余预算场景在原Python实际循环均以agent_invalid_action失败；
  冻结0061 JAR上的Java合法indexed-call场景失败，证明不是新0062源码干扰。
- 绿：完整Python **27 passed**，包括批量search、已发现来源的read/search顺序、畸形
  第二项/未知来源/混合结束在任何工具执行前拒绝，及剩余额度不足零工具执行。
- Java相关13个行为文件 **122 passed**，使用冻结0061 JAR类及既有测试依赖，仅编译本切
  AgentToolProtocol和AgentProtocolClientTest；未混入并行0062迁移代码。原单调用及普通
  OpenAI各类Adapter行为保留。新增批量不是删除或放宽未知/畸形响应的失败断言。

本节覆盖下方“仅提示/不改ToolPack”的首版范围；真实修复效果仍以root的新具名实验为准。

## 首版提示验证历史

2026-10-09。范围仅 `SYSTEM_TEMPLATE` 与相关说明、回归；没有改变 DB-GPT 执行循环、
`ToolPack`、`MAX_STEPS`、工具参数/已读来源校验、结果校验或日志政策。供应商原生
`tool_calls` 的接收和规范动作转换属于本切独立 Java Adapter。

## 红绿反馈环

新增 `test_every_model_step_requests_native_tools_despite_internal_action_history`，
实际运行 DB-GPT 搜索→阅读→结束三轮，并检查发往 Java 模型回调的每轮 system 消息。

1. 原提示下该测试失败：要求的 `exactly one native tool call` 不存在；旧提示明确要求
   模型生成 `Action` / `Action Input` 文本。
2. 仅替换提示后测试通过：每轮要求一个原生工具调用，禁止动作文本和伪造观察；历史
   assistant 规范动作及 user Observation 明确作为已完成工具日志，不当作新指令或
   输出示例。保留至少搜索一次、先读后引用、来源不可信和无外部知识等约束。
3. 同一测试确认真实上游历史仍包含规范动作与 Observation，回调次序仍为
   `model → search → model → read → model`，最终结果不变。测试替身位于 Java 内部
   `{content}` 回调边界，不伪装为供应商原生工具协议验证。

命令（在 `agent-service` 下，使用已固定 DB-GPT 0.8.2 的 CPython 3.10 环境）：

```sh
python -m pytest -q tests/test_agent.py::test_every_model_step_requests_native_tools_despite_internal_action_history
python -m pytest -q
python -m compileall -q knowledge_agent tests
```

完整结果：**19 passed**、2 个原有上游弃用警告，退出码 0。原 18 项未删除或放宽，
包括真实 TCP 服务/回调、来源和建议身份拒绝、未读拒绝、无重试、8 步限制、超时、
取消、固定工具、禁本地快照及安全错误日志。首次沙箱运行因本机 loopback bind 权限
受限出现 1 个环境错误；允许本地端口后完整重跑通过，不改测试断言。

本 Python 子任务模型请求 0、生产操作 0、Git 写入 0；不凭此宣称旧线上失败已消失。
真实供应商协议与正式生产接口查阅结果由根任务另行记录。
