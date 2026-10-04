# Review：实施前审查

状态：CONTRACT_FROZEN，实际实施审查及验收待执行。

依据实际 entryDigest/manifest：独立媒体向量绑定自身 vector generation、完整向量及原 source/PCM SHA，不绑定文本 publication/generation。因此可以保留独立远端数据；当前 typed base 与 text physical ID 仍必须通过新不可变关联完整映射。仅移除旧 guard、改旧 receipt、复制旧行或在文本发布后补建媒体均不满足合同。

选择实际完整远端复验及同事务关联发布。全部 effective receipt 都须可准确复验，不采用静默丢历史 profile 的实现。前端增加明确兼容 capability 并保持服务器资格，保留旧无新cap的 negative tests，新增正常操作正例。确认依据实际文字调用成本和媒体继续使用行为，不宣称免费。

baseline source/current 1561 files 已实际一致；1043 后端与64前端执行输入已保存。当前仅设计草稿，不能据此写成实现、测试、部署或真实质量通过。独立审查会检查新增行为，旧约束与 oracle 变化另行具名记录。
