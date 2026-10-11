# 验证

日期2026-10-11，LOCAL_VERIFIED。所有新增材料均为合成，云模型请求0。未部署、未提交/推送；生产0072未替换，历史失败任务不重发。

## 原因与反馈环

生产已定位的安全诊断是 `agent_invalid_result / result / refusal_has_content`，检索、阅读已完成。代码曾对拒答标志下的所有内容统一拒绝，连独立维护建议也不允许，且先于来源校验退出。

回归命令：`PYTHONPATH=agent-service <python3.10> -m pytest agent-service/tests/test_result_diagnostics.py -q -k 'refusal_with_valid_content or refusal_normalization'`。修复前4失败：有段落/仅建议均不能完成，未知来源/未知建议文档被错误归为refusal_has_content。修复后同一输入通过，非法来源仍失败且原因精确。

规范化只在全部引用验证后发生，不再次调用模型，不改变回答文字、不移除错误段落。缺少正文的非拒答仍失败。Java保持规范化互斥与独立来源/版本校验，持久化失败仍不能发布成功终态。

## 最终检查

| 验证 | 结果 |
| --- | --- |
| Python Agent全套 | 131/131，包含实际TCP/HTTP三种终态、来源负例、无重试和安全诊断；最后HTTP原因输出类型守卫调整后相关30/30再通过 |
| AgentProtocolClientTest / AgentModelStreamingTest / KnowledgeAgentServiceTest / KnowledgeAgentControllerTest / ConversationServiceTest | 64/64，0失败/错误/跳过；真实Java HTTP错误码、SQLite来源、拒答建议会话回读、SSE与上下文 |
| 前端全套串行 | 527/527；具体错误映射、未知码/服务端文本屏蔽、原SSE与代理 |
| syntax / diff检查 /密钥扫描 | 通过，两仓扫描为空 |
| Java package / spotless | 通过；打包跳过测试，测试来自上面独立命令 |

前端第一次516过/11失败均为Xcode Git启动器返回69，影响扫描器的合成Git仓库测试；切换已安装的CommandLineTools Git后保持全部断言不变，完整527/527。未接受Xcode许可或修改扫描器。

本机私有构建目录 `knowledge-result-0073.*` 保存报告。候选JAR SHA256：`447600e3c34e4b9b75b1e33666033a72d6cfe23cf80cb41a8fb7d36f46b68e68`。

## 边界

没有新数据库结构、检索重建或模型配置更改。旧全仓Java迁移/ACL/readiness与覆盖率门禁未重跑/未关闭。真实生产原题和供应商输出未复验；本机固定合成输入证明校验处理修复，不证明全库回答语义质量。

发布需同时包含Python运行模块、Java新失败码/工具说明与前端固定文案，避免新错误码被旧Java降为通用不可用。
