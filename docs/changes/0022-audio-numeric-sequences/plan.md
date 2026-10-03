# Plan

输入为现有已发布音频的原始转录；用户选择本音频并问代码；可见输出应是完整原值与准确 typed 来源，资料原文件、转录和源身份保持。先以共享 Tool 和真实 SQLite/publication/AnswerService 证明路径建立失败用例，再修复数字字段边界和必要千位分组规范化。

1. 新增数字序列完整摘录、前缀拒答、错误主体、完整尾部冲突及原语义对照 RED；保留现有测试，不更换转录金标来求绿。
2. 修改 SourceFields 的数字逗号边界、EvidenceConflicts 的完整合法千位分组 token 规范化，以及 TextGrounding policy revision；由新增 RED 明确触发的 QuestionFacts 只处理完整数字序列排除断言，不改变通用否定或证明循环。
3. 运行完整共享字段/文字证明、中英文正反例和相关真实 parser/AnswerService/音频/视频文字行为测试，随后格式/架构检查。无需完整默认评测、真实模型调用或改部署配置。
4. 子切完成同配置离线 `-DskipTests package` 并核对构建前后源码输入一致，记录红绿、命令、制品及未验项，再由主线创建独立音频交接；不能覆盖 `.tools/document-originals-handoff`。

Ownership：上述四个生产文件、新增相应 Tool/Service 测试与 0022 工件。EvidenceConflicts 的必要修改及 QuestionFacts 的完整数字序列排除边界已由主线负责人确认。没有新抽象或审批例外。

计划中的条件/否定对照增加了必要排除边界：`is/are not` 后为完整数字逗号序列，单独不能证明实际值，但与正断言共存仍参与冲突否决；`is not enabled`、`is not approved`、`are not enabled`、未安排及名称保持合法。首次后缀指令 RED 的拒答预期过宽，主线依据现有 SourceInstructions 与安全事实隔离合同修正为独立原事实正例和执行指令负例，同字段/前置指令仍拒绝；没有扩改 SourceInstructions 或删掉用例。实际命令与过程见 verification。
