# Intent

真实合成音频验收发现：ASR 保存文字为 `Project Car launch code is 7,3,9,21`，针对 Car 的问答实际返回 `Project Car launch code is 7`，引用也被截断。Cedar 被识别为 Car 是另一个尚待新样本实证的 ASR 质量问题；本批不能改写转录、猜测代码或把旧失败报告为通过。

本批只修复共享文字证明中的数字逗号序列完整性，以封存转录的完整原值回答并提供完整引用。复用既有 SourceFields、TextGrounding、事实与冲突规则，保持组织、ACL、完整范围、原始引用、条件/否定/指令防护。UI 交接已经独立冻结，不改前端、0021、部署副本、配置、schema 或模型。
