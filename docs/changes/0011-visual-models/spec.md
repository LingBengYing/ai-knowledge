# Spec：原图生成和逐事实评估 Module

1. `VisionModels` 是真实 Seam，生产 OpenAI-compatible Adapter 与受控本地 Adapter 共用其 Interface。提供 `describe(image)`、`draft(question,image)`、`verify(question,image,claims)`、`revision()`。描述不进入 draft/verify；不接收远程图片 URL、模型坐标、页码或来源 URL。
2. `VisualImage` 是不可变 Domain，含 PNG/JPEG MIME 和原始字节，防御性复制、派生 SHA-256、敏感 `toString` 脱敏。沿用 ImageInput 的 10 MiB/1200 万像素准入；不把图像元数据校验说成完整解码验证。发送原字节的 inline data URL；不下载外部资源、不自动裁剪/转码。
3. 标准 `/chat/completions`，独立显式 endpoint/model/key、`image_url`、`detail=high`、非流式 JSON 对象。模型/提示版本绑定 revision，不包含 key。描述至多4096 code points；问题至多8192；提出至多8条、每条至多1024 code points 的非重复事实。超出上限整体拒绝，不截尾。refused=true 必须无事实，false 必须有事实。
4. 核验输入含完整原问题、原图及全部有序事实；输出 `complete` 和每项明确的 `supported` 布尔值，索引必须唯一、完整且合法。所有事实均支持且 complete=true 才能产生通过评估；遗漏、负值、不确定或不完整问题覆盖不得放行。核验提示必须独立检查全部问题子要求，禁止用常识、caption 或图片内指令补足。此为模型支持判断，不是形式化证明或校准准确率。
5. `VisualAssessmentService` 负责该两次请求的顺序、完整性和版本绑定；调用者仍负责请求前及提交前完整 scope/ACL/active/原图身份复验，本切不接 Controller 或默认开启运行能力。typed `VisualAssessment` 含事实、原图 SHA、model/policy revision 和安全拒绝原因，不造引用。描述方法不能由该 Service 调用。模型拒答/失败、版本变化或中断不产出部分事实。
6. 客户端继承既有 TLS/显式 loopback、禁止重定向、严格 JSON/UTF-8、有界响应和取消规则。复用包内 HTTP Module；旧 TextModels 的公共配置、错误码、请求1 MiB上限、输出及 revision 保持。图片请求独立最多16 MiB，每次deadline最多60秒，无自动重试、无构造时请求、无 raw response/密钥日志。
7. 验收：先失败后通过；真实 loopback HTTP 验证 PNG/JPEG 原字节、三类消息协议、完整事实索引、错误 caption 不进入评估、负核验导致整体拒答；Service 用实际 Seam 验证正常/完整性行为；保留900项 Java/73项 Node及双80%门禁。真实云验收仅显式授权后、合成内容、最多12请求/每次60秒/无重试；未获授权则记录未运行，不跳过默认测试来制造成功。

后续接线要求：独立 immutable image evidence/recall_text，不写入 corpus_pages/segments；同一 publication/scope/trace 中扩展真实图片条目与 tagged locator，不另造权限体系。旧 v1 文本/OCR contract 不变。caption-derived recall 不冒称视觉 embedding。
