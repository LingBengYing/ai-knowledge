# 图片视觉模型：Interface 与验收边界

当前工件：[0011 intent/spec/plan](changes/0011-visual-models/intent.md)。本 Module 已通过本地协议/评估验收及限定独立审查：923 Java/73 Node，真实云请求0；证据见[verification](changes/0011-visual-models/verification.md)。它不是新的公开问答接口或运行能力。已有图片 OCR 与词框见[IMAGE_EVIDENCE](IMAGE_EVIDENCE.md)。

## 职责

- `model.domain.VisualImage`：不可变原图 bytes/MIME、派生 SHA；不含用户权限、模型描述或模型生成坐标。
- `client.model.VisionModels`：describe、draft、verify 与 revision。生产 `OpenAiCompatibleVisionModels` 和测试 Adapter 位于真实 Seam；Client 负责 HTTP，不实施库内 ACL。
- `service.VisualAssessmentService`：完整问题、原图生成、全部事实独立核验、版本复验和整体拒绝。它不调用 describe，不建立或发布来源引用。
- `model.domain.VisualAssessment`：通过核验的事实或安全拒绝原因、原图 SHA、model/policy revision。不是已封存的知识库答案，也不能直接序列化为现有文字 CitationResult。

调用者必须先以完整 selected set、ACL 和 active revision 取得权威原图，并在最终提交时再验证。0011 尚未接入这一 authority/数据库/检索/HTTP 链，不能单独对外暴露 assess 为已授权回答。

## 标准协议

仅发送受限 PNG/JPEG 原始 bytes 的 `data:image/...;base64,...`，使用 `/chat/completions`、`image_url`、`detail=high`、`stream=false`、`response_format={type:json_object}`。没有远程图片 URL 下载、自动重试、模型探测或自动切换；构造不发网络。

模型配置显式传入 endpoint/model/key。仅 HTTPS，测试可显式允许字面 loopback HTTP；不跟随重定向，key 只进入 Authorization header。每次最多60秒，图片最多10 MiB/1200万像素，序列化请求最多16 MiB。原文本 Adapter 的1 MiB请求限制和错误语义保持。

描述输出 `recall_text` 仅作为未来召回材料，不能证明事实。draft 输出 `refused` 与至多8条完整 `claims`；verify 重新看同一原图、完整问题和每条有序事实，输出 `complete` 及全部索引对应的 `supported`。只有全部支持且问题覆盖完整才通过评估，不能截尾或返回部分事实。

独立请求不等于独立模型或确定性真值验证。同一模型可能重复犯错；真正的图像准确率、复杂关系、图表数值、提示注入质量仍需独立真实 eval。当前不虚构置信度或宣称达到总体质量阈值。

## 可复现验证

默认 `mvn ... verify` 的 `VisualModelFlowHttpTest` 使用真实 loopback HTTP 和自生成无文字图片，不使用云模型；检查原字节、完整事实和负核验。此结果只认证协议与编排。

`VisionModelsLiveIT` 是显式运行的真实 SiliconFlow 测试，不属于默认测试发现范围。需要负责人额外授权后通过进程环境提供：

- `RAG_VISION_IT_APPROVED_CALLS=4`，明确本次最多4请求，不使用旧文本额度。
- `RAG_VISION_IT_MODEL`：确认支持图片的模型名，不内置猜测值。
- `RAG_VISION_IT_API_KEY`：secret 注入，不写源码、命令行参数或文档。

执行 `mvn -s .mvn/settings.xml -gs .mvn/settings.xml -Dtest=VisionModelsLiveIT test`。只调用 describe、draft、verify、错误事实 verify；每次最多60秒、失败即停、不重试。未配置时在第一请求前硬失败，不记为跳过或成功。仅此小型合成样例通过，也不能替代真实知识库召回、Milvus、浏览器或生产验收。

后续按同一 authority 接入 immutable image evidence、caption-derived retrieval 和 tagged image locator；视觉 embedding、多图、音频、视频、联合证据仍保留。不要把 caption 写入 `corpus_pages` 或生成假的 page/start/end 来复用旧文字引用。
