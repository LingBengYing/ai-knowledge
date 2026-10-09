# Spec：一个知识问答入口

1. `#/answers`默认综合问答，不按文件扩展名自动把普通提问切到视觉模式。旧视觉、声音等专门能力仍可显式选择。移除产品使用帮助顶层导航/专门列表动作；旧hash导向知识问答并保留已有问题与完整所选范围。
2. 综合问答使用`POST /v1/knowledge-answers`，请求沿AnswerCommand的question和可选document_ids合同。该技术端点属于知识问答，不是新产品功能。显式空范围不回退全库，所有上下文先做当前ACL和active publication复验。
3. 复用0048文档/视频分别召回，选中的总文字证据不超过64。视频只用ASR/字幕/帧OCR；caption和文件摘要不得成为事实证据。每条候选保留其完整原文context（整页、完整转录、字幕轨、OCR帧）及真实定位，不拼成假页面。
4. 共用TextModels.extract和TextGrounding.verifyText先证明原问题/原文。再由同一OpenAI兼容chat/completions客户端生成结构化综合回答，逐段绑定已有证据ID；独立整体验证所有生成段落是否受其实际引用支持、是否保留条件/否定、是否完整回答原问题。有一段无据、矛盾、验证失败即拒答，不退化成模型常识回答。
5. 最终只释放完成当前完整scope复验并持久化trace的回答。trace记录actor、问题SHA、模型/提示版本、完整publication范围和各引用的原物理ID、原文偏移/SHA及typed定位，不存原问题正文。旧音画joint trace/proof不改；v4的纯文字转录不伪造frame投影或joint证明。
6. 响应为answer_id/status/answer/reason/citations。混合引用按evidence_kind区分document_text/video_transcript/video_subtitle/video_frame_ocr，包含citation_id、document_id、revision_id、filename、source_sha256、media_type、quote、text_sha256、page/start/end或start_ms/end_ms/time_precision、origin、content_url与source_url。所有locator和URL由服务器产生，模型仅返回受限证据ID。
7. `GET /v1/knowledge-sources/{answerId}/{ordinal}`返回answer_id和同一citation，按原actor的当前完整授权范围读取原证据；重启读取不调用模型。前端逐引用类型选择PDF或视频，复用原件metadata/完整SHA核对与Blob生命周期。
8. 模型接入按协议复用，禁止基于模型名分支或新增星辰专用客户端；generation沿当前已应用配置。无自动重试/模型切换。未获新授权前不发综合生成请求，不把页面布局/打包成功当真实综合回答通过。

## 具名真实续作接线（2026-10-04）

服务器可显式配置 `RAG_MODEL_CONFIGURATION_DEEPSEEK_BASE_URL`，连接测试与实际生成共用该地址；未设置时保持既有官方地址选择。仅允许官方HTTPS，或在原有loopback开关明确开启时使用字面loopback HTTP根路径或 `/v1`。不改变持久角色、索引anchor或官方服务商识别；真实生成端点变化如实进入模型revision。

负责人已批准同两份新合成资料的一次页面综合问答，最多6次模型HTTP：硅基流动嵌入1次、两类重排2次；DeepSeek摘录、综合、核验各1次。历史8次不清零，累计上限14/20；不重解析、不调用画面模型、无自动重试、失败即停。本机两服务商入口共用持久计数，不在启动时自动重开失败批次，台账不保存密钥或请求正文。

## 正常中文操作问题的原文证明增量（2026-10-05）

对于单一“产品名＋如何/怎么＋操作”问题，保留完整产品名和操作名。只有同一候选原文中有明确的“适用产品”声明、匹配的编号操作标题及具体动作，并且模型摘录实际覆盖动作原文，服务器才可形成正向证明。编号操作之前的连续步骤、紧接的确认步骤和产品声明放在同一条服务器定位的原文引用中；必要材料超出该候选原文、条件不完整或含不安全指令时拒答。其他语法与旧证明合同保持，不把文件名或另一资料的声明当成视频转录自身的产品证据。

综合生成和独立核验额外接收本次全部已授权检索原上下文作为只读限制/反证材料；只有经服务器证明的 support_quote 可以提供正向事实并被引用。未引用的检索材料不能生成新事实、引用或时间定位。客户端未能接收完整上下文时拒绝请求，不以自动裁切或丢弃上下文换取成功。提示版本和证明策略版本递增，旧答案来源不改写。

本增量先限本地实现与跳过测试的生产源码构建。自动化测试/检查/审计、真实综合质量及视频时间引用仍按当前授权边界分别验收；已知现有合成视频 ASR/字幕不含产品名，不能强制要求其出现在本轮产品操作答案引用中。
