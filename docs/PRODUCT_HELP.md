# 产品使用帮助：说明文档与视频片段

> 方向已调整：负责人要求本能力并入知识问答，不作为独立功能。最新合同见[0049统一知识问答](changes/0049-unified-knowledge-answers/spec.md)。下方为0048首版历史设计；其内部检索复用，不代表当前仍应展示专门页面。

对应后端[0048](changes/0048-product-help/spec.md)与独立前端0036。首版是跨资料类型的原文检索入口，不是新的通用答案生成器，也不是原生音视频向量能力的验收。

## 使用方式

1. 将说明书与教程视频按现有上传、解析、索引流程发布。只有原文件或“已解析”还不能检索。视频需已经保存转录、字幕或帧OCR文字；只有视觉描述而没有上述材料的视频不会在本入口伪造文字命中。
2. 在资料库使用目录/标签整理同一产品、型号和版本，例如合成标签“产品:青榆X1”“版本:2026”。筛选后明确选择需要检索的说明书和视频，再进入“产品使用帮助”。选择只代表勾选的资料，不代表自动包含整个目录或其他分页。
3. 输入具体问题，例如“青榆X1怎么更换滤芯？”。全库模式可以使用，但未明确产品范围时不保证结果都属于同一型号；必要时返回资料库缩小选择。
4. 点击查找后，分别查看说明文档和使用视频的原文片段。每类返回数独立，不要求两类都必须有结果。
5. 点击文档阅读入口按实际页码核对说明书；点击视频入口读取同版本原件并定位到服务器给出的时间。保留安全前提与完整原文上下文，不把命中片段当作完整安装/维修建议。

## 模型与数据

2026-10-04负责人选定的ASR候选为 `XingChenAGI/XingChenASR-V3.2-Ultra`，配置项为独立音频的 `RAG_AUDIO_MODEL` 和视频音轨的 `RAG_VIDEO_ASR_MODEL`；base URL/key沿对应独立私密配置，不进入前端。硅基流动[官方价格页](https://siliconflow.cn/pricing)当前标免费，价格可能变化；免费不代表无限配额、无排队或已通过本项目实测。当前通用ASR文档仅确认multipart端点及text返回，尚无该模型专属字段认证，仍按服务器采样分段标注时间，不假报句级/词级对齐。

对于有讲解或内嵌字幕的教程，可在后端显式启用 `RAG_VIDEO_TEXT_EVIDENCE_ONLY=true`，并用 `RAG_VIDEO_SUBTITLES_ENABLED=true` 读取完整字幕轨。该模式使用已有ASR、BGE嵌入/重排与Milvus，不需要配置视频VLM；原帧、PTS、原文件仍保存，画面描述不再阻塞文字入库。开关默认关闭，原有视觉联合模式不变；切换不自动重处理旧资料。新v4编译使用v28存储格式，应先在独立数据环境验证，不能直接替换旧包后回退到不认识v28的版本。

此模式不提供视觉视频问答和旧查询附件入口；本页只检索真实语音/字幕/OCR文字，并按来源的真实时间播放。没有上述文字的视频不会生成占位caption凑成命中。

复用设置页已应用的文字嵌入与重排，以及现有Milvus文字索引。查询只读，不重建资料，不重新调用ASR、视觉描述或生成模型；点击查询仍可能产生嵌入/重排服务调用，实际是否收费以已配置服务为准。不开启自动重试。

文档与视频分别使用混合召回并排序。视频文字索引中的纯画面描述只用于既有视觉流程，本入口不将其伪装成字幕或转录。有限top-K召回并不保证遍历整库或找到所有步骤。

结果的“文字来源”区分原文、机器OCR、机器ASR、内嵌字幕。ASR时间为真实服务器段，不是逐词或实际动作边界；字幕时间为cue；画面OCR时间为真实解码帧显示区间，可能很短。排序分不是事实置信度。

## HTTP合同

```http
POST /v1/product-help/search
Content-Type: application/json
```

```json
{
  "question": "青榆X1怎么更换滤芯？",
  "document_ids": ["manual-id", "tutorial-id"],
  "top_k": 5,
  "rerank": true
}
```

ID为示意，应替换成实际已发布资料ID。省略`document_ids`表示完整授权全库，空数组保持空范围，不支持用null代替省略。`top_k`为每类1–10，默认5；`rerank`默认true。

响应包含`search_id`、`configuration_version`、`scope_count`、`status`、`reason`、`score_kind`和`matches`。每个match包含：

- `category`：`document`或`video`；`rank`为该类中的排序。
- `evidence_kind`：`document_text`、`video_transcript`、`video_subtitle`或`video_frame_ocr`。
- 原身份：`document_id`、`revision_id`、`filename`、`source_sha256`、`parser_revision`、`media_type`。
- 原文：`text`、`text_sha256`、`origin`；不返回模型改写的步骤。
- 定位：文档`page/start/end`，视频`start_ms/end_ms/time_precision`；不适用字段为null。
- `content_url`：既有按资料及revision回读的原件路径，前端继续核对metadata与完整原件SHA；版本改变时重新查找，不静默读新文件。
- `retrieval_score/rerank_score`：召回与可选重排排序数据，不代表已通过事实证明。

服务端始终保留完整选中范围和当前ACL/publication复验。无匹配正常返回空结果；未应用模型、失效范围或处理失败按既有安全错误规则提示。没有answer_id或答案trace，不允许把这些match拼成旧答案引用URL。

## 本版不包含

- 显式产品主数据、自动型号消歧或跨版本关系管理。
- 跨说明书与视频的统一生成答案和新的混合事实证明。
- 无字幕无讲解演示的Qwen3-VL视觉补充、原生声音故障检索。
- 本轮真实provider效果验收、自动化回归或生产发布；结果以[0048记录](changes/0048-product-help/REVIEW.md)为准。
