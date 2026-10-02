# 多模态查询附件

0017已接通请求期编译、授权答案生命周期、hash-only trace和独立有界HTTP入口。默认关闭，仅开发/测试loopback；真实合成PNG/WAV/MP4的HTTP链已通过，本轮完整冻结记录见[附件答案验证](changes/0017-query-attachments/answers-verification.md)。**前端尚未接线，真实provider质量、完整生产配置和生产发布未验收**。完整合同见[0017 spec](changes/0017-query-attachments/spec.md)，此前输入/匹配Module的独立历史证据仍见[步骤1–2验证](changes/0017-query-attachments/verification.md)。

## HTTP正常路径

`POST /v1/attachment-answers`使用现有Bearer/Cookie身份与mutation Origin校验，Content-Type为`application/json`，不允许query参数。独立接收器最多28 MiB JSON；base64解码后的全部附件最多20 MiB，不开启全局multipart、不调用资料上传。

```json
{
  "question": "上海住宿上限是多少？",
  "mode": "text",
  "document_ids": ["<published-document-id>"],
  "attachments": [
    {"filename": "query.png", "media_type": "image/png", "content_base64": "<base64-original-bytes>"}
  ]
}
```

`mode`选择**库内证据类型**，与附件类型无关：`text`、`image`、`audio`、`video_visual`、`video_transcript`、`video_joint`、`video_ocr`、`video_subtitle`。例如视频附件可以帮助查找文字资料，不代表视频附件自身成为证据。`attachments`必需且可为空，最多3项；`document_ids`省略为完整有权范围、`[]`为明确空范围，不回退全库。原问题仍必需，不支持只有附件的语音提问。

响应为`{"mode":"text","result":{...原typed答案...},"query_attachments":[...]}`。`result`保留原答案ID、answered/abstained和对应模式的库内citations/source_url；`query_attachments`逐项提供零起ordinal、media_kind、prepared/failed、visual_sampled和稳定reason，不回传文件名、hash或编译正文。无附件时说明数组为空，旧四种JSON入口不改变字段。

库内没有完整证据时`result.status=abstained`，即使附件含答案也不能补证。来源继续使用原`/v1/sources`、`visual-sources`、`audio-sources`、`video-sources`路径，无查询附件下载或额外引用类型。

启用前完成既有answers/visual/audio/video/image-ocr配置，再显式设置`RAG_QUERY_ATTACHMENTS_ENABLED=true`以及独立`RAG_QUERY_RANKING_BASE_URL/MODEL/API_KEY`。秘密只从环境读取，仓库示例留空。依赖原视觉/ASR/编译器配置，视频字幕/OCR仍由原开关决定。完整启动要求development/test、字面loopback；不能用此开关冒充生产放行。`GET /v1/config`只增加`query_attachments`能力，不改变stage或readiness。

接收超时默认30秒，并发默认2；答案复用`RAG_ANSWERS_TIMEOUT_MS`总预算，不自动重试。超时取消等待线程并向原有执行器传播中断，不能继续释放回答。模型单次deadline不取代总预算。

## 输入与输出

`QueryPreparationService.prepare(question, attachments, current)`接收原文字问题和最多3个附件，总原字节最多20 MiB。支持PNG/JPEG、既有音频格式及MP4/MOV/WebM/MKV视频；文件名/MIME只选择处理合同，内容仍由现有图像检查或真实FFmpeg解码核实。

产物`PreparedQuery`将四种职责分开：

| 字段 | 用途 | 不可用于 |
| --- | --- | --- |
| originalQuestion | 原字节问题；extract/grounding/fact plan继续使用 | 被附件摘要、caption或转录改写 |
| retrievalText | 原问题+完整有界OCR/描述/ASR/字幕线索 | 库内Evidence、引用或事实证明 |
| queryImages | 附件实际原图/原帧；辅助匹配 | 替换库内待证明原图 |
| attachments | 源SHA、编译版本、完整产物SHA、计数、选中图SHA和采样状态 | 存储原文件名、原问题、转录或媒体bytes |

无附件时保持原问题与原检索输入，不读取媒体依赖。图片保留OCR和描述；音频保留完整分段转录；视频保留全部编译原帧的描述、完整音轨及配置内OCR/字幕。不以整文件摘要替代这些材料。

检索正文上限8192个Unicode code point，超过返回`query_text_limit`而不是截尾。原图完整编译后按SHA去重，最多选全局首、中、尾3张；选帧影响匹配预算，不删除完整正文或未选帧哈希。`visualSampled`表示该附件有独有图像未被选中，不代表完整视觉分析。

## 双角色模型接口

`QueryRankingModels.rank(PreparedQuery, List<QueryRankCandidate>)`只返回完整候选排序。标准OpenAI兼容Adapter通过`chat/completions`传递服务器持有的图片bytes，分别标注query image与authorized candidate角色，不接受外部URL。

每批1–20个候选，必须返回全部且唯一的整数index和finite `[0,1]` score；请求最多16 MiB，不自动重试。图像/文字内容只在低信任user消息中，system消息不拼附件内容。结果不含答案、页码、引用或authority ID。既有单图VisionModels/FactVisionModels证明接口没有改动。

现有投影单次查询限制4096 UTF-8字节：完整retrievalText按Unicode边界分批查询，全部批次候选先由authority验证，再去重取稳定top64；不截掉尾部线索。纯空白分隔批没有检索信号，可略过。所有候选均按20一批匹配，不只取前20；没有查询图的音频材料仍走原文字rerank，其分数合同不改成图像的`[0,1]`。

## 授权与可追溯边界

编译器不访问数据库，也不能自行证明用户权限。答案Service先取得完整EvidenceScope，在同一有界执行生命周期内编译，并在匹配、证明及最终事务前复验。候选模态过滤不会缩小完整scope；未被引用的所选资料撤权仍会阻止回答。

附件只用于寻找库内材料，最终证据必须是当前授权的已发布资料。音画联合仍须对原完整问题逐事实、同组证明，不能用附件图或附件转录补齐缺失事实。v16追加独立hash-only附表，绑定原问题SHA、附件源SHA、编译/排序版本、完整manifest和采样说明；准备失败也保留每个源SHA，但不伪造成功内容。终态与原trace在同一事务封存，最终拒答不丢准备记录。回读答案不依赖临时附件或再次调用模型。

本机真实媒体、Spring/SQLite与loopback协议验证认证后端链路，不认证真实模型质量、Milvus服务端检索效果、前端体验或生产。17个旧schema测试夹具仅适配当前v16版本，旧迁移与历史来源合同保持。
