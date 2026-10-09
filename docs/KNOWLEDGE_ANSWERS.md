# 知识问答：文档与视频文字证据综合回答

这是普通知识问答的一条回答路径，不是独立“产品使用帮助”功能。产品名称、型号、操作问题直接在问答页输入。登录后检索组织内全部已发布、可检索资料；目录和标签只用于日常整理，不再限制问答范围，也不能当作原文事实证据。

## 回答流程

完整组织资料快照 → 文档/视频文字检索与重排 → 一次大模型综合原文 → 版本/删除状态复验与持久trace → 答案与引用。

取消本入口的手写问题分类、强制摘录、字段证明和二次模型核验。模型可回答已知部分、指出资料没有说明的内容；没有相关资料时明确说明不足。不声称回答经过独立语义验证。模型只选择服务器提供的证据ID，不生成页码、时间或来源URL。ASR是服务器音频分段时间，字幕是cue时间，帧OCR是原帧显示时间，不冒充词级或操作动作对齐。纯视觉caption、文件摘要不是本路径事实来源；原有视觉/音画联合能力保持单独明确合同。

## HTTP

`POST /v1/knowledge-answers`接受`question`；旧`document_ids`仅作兼容参数，合法数组（包括空数组）均不限制范围。前端不再发送该字段。未登录仍拒绝；登录后的同组织成员可以读取相同答案来源。无单独问题字数、回答段数/字数或32条引用上限，仍受实际HTTP资源及服务商上下文容量影响。

响应为`answer_id/status/answer/reason/citations`。每条引用包含`citation_id/evidence_kind`、原document/revision/filename/SHA与quote，文档给页码/码点，视频给start_ms/end_ms/time_precision。source_url和content_url由服务器派生。

`GET /v1/knowledge-sources/{answerId}/{ordinal}`按当前组织和原版本状态检查，返回`{answer_id,citation}`。来源读回不调用模型；前端再通过同版本原件元数据和完整字节SHA核对后阅读/播放。不能用客户端缓存替代服务器版本核对。

## 模型与运行

复用当前已应用的文字嵌入、重排、生成配置与Milvus索引。生成沿OpenAI兼容`chat/completions`协议实现，不为DeepSeek、Qwen或星辰增加业务分支，不发送客户端`max_tokens/max_completion_tokens`。ASR仍用`audio/transcriptions`；重排是明确扩展协议，不标为OpenAI标准。

一次完整普通问答最多1次嵌入、文档/视频各1次重排、1次综合（有相关候选时）；不再增加摘录和二次核验请求，不自动重试。Milvus全库按128份过滤批次搜索后全局排序，不截掉第129份资料。密钥仅存在后端私密配置，不进网页/源码/日志。

当前验收范围以[0053 REVIEW](changes/0053-shared-workspace-rag/REVIEW.md)为准。新模型请求0，旧0052累计14/20已停，不能自动使用剩余6次；文档中的流程合同不代表真实质量或生产已验收。
