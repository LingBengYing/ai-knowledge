# 视频授权问答与 typed 来源

0014当前后端将既有视频上传/索引接入显式画面、转录及音画联合问答，再读取同版本关键帧与原视频字节；新增独立`ocr`模式消费[选中原帧文字证据](VIDEO_OCR.md)，不改变原三模式证明语义。OCR主线已本机冻结，证据见[OCR验证记录](changes/0014-video-library/ocr-verification.md)。本文记录当前代码合同；不代表完整多模态目标、网页或生产已经完成。已有摄取与发布流程见[VIDEO_PUBLICATION](VIDEO_PUBLICATION.md)，共同事实证明机制见[VIDEO_ASSESSMENT](VIDEO_ASSESSMENT.md)，完整目标和当前验证记录以[0014工件](changes/0014-video-library/plan.md)为准。

## 启用与调用顺序

同时启用`RAG_VIDEO_ENABLED=true`和`RAG_ANSWERS_ENABLED=true`才注册视频问答与来源路由。视频装配保留原限制：`RAG_INGESTION_ENABLED=true`，`rag.environment`为`development`或`test`，服务绑定字面`127.0.0.1`或`::1`；`localhost`和公网绑定不能替代。仍须提供[视频codec/ASR/vision配置](VIDEO_PUBLICATION.md#配置)和问答所需完整[TextAdapterSettings](TEXT_ADAPTERS.md)。凭据只从受信环境/secret配置注入，不进入请求或源码。

视频问答复用视频自己的vision客户端执行原帧逐事实提案/验证，并要求文字模型实现`FactTextModels`。不自动开启或依赖旧`RAG_AUDIO_ENABLED`、`RAG_VISUAL_ENABLED`，不向旧摄取模块注册竞争的ASR/Vision Bean。配置装配本身不调用模型或初始化远程索引。

视频OCR摄取另由默认关闭的`RAG_VIDEO_OCR_ENABLED`控制，需显式本机Tesseract路径、语言、实际revision和处理预算；配置见[VIDEO_OCR](VIDEO_OCR.md#配置)。启用后使用绑定OCR版本的v2 compiler，逐个处理已有选帧并记录合法无字结果；旧v1资料不会自动重新识别。它不启用独立图片OCR，不表示读取了独立字幕轨或每一帧文字；问答只读取已经完整索引的OCR证据。

正常顺序：

1. 以显式`video/*` MIME向原`POST /v1/documents?filename=...`上传；202仅表示已保存并排队。
2. 轮询摄取任务到`parsed`，再显式创建原索引任务并等待`indexed`；索引还须独立开启`RAG_INDEXING_ENABLED`。
3. `POST /v1/video-answers`发送完整问题、显式证明模式和可选资料选择。
4. 只在`status=answered`时使用服务器返回的来源URL读取typed引用、原帧或原视频。`indexed`不保证任意问题有证据。

Runtime在video与answers同时开启时声明`video_answers/video_sources`；`video_upload/video_index`仅分别表示摄取/索引能力。原stage、管理列表`can_answer`和503 readiness限制没有因视频路由开放而变成网页/生产承诺。

## 问答请求与范围

`POST /v1/video-answers`使用`Content-Type: application/json`，不接受query，包括空query分隔符。仅允许以下三个字段：

```json
{
  "question": "指示灯的颜色是什么？重启等待时间是多少秒？",
  "document_ids": ["doc-video"],
  "mode": "joint"
}
```

`doc-video`为示意ID，须替换为原上传/发布返回的真实资料ID。身份与Origin规则沿用[API认证约定](API.md#认证与公共约定)，客户端不能设置Actor、角色、publication、group、帧时间、模型或locator。

| 字段 | 当前合同 |
| --- | --- |
| `question` | 必填、非空白字符串，最多4096 UTF-8字节；合法Unicode，不允许非法surrogate和控制字符，换行/制表符除外 |
| `mode` | 必填字符串，仅接受小写`visual`、`transcript`、`joint`、`ocr`；无默认模式，不推断、不回退 |
| `document_ids`省略 | 快照当前用户可见且已发布的完整全库；超过128份明确失败，不截断 |
| `document_ids: []` | 显式空范围：200 `abstained`、`reason=empty_scope`、`citations=[]`，不调用模型或投影 |
| 非空`document_ids` | 最多128个不重复ID，逐个匹配`[A-Za-z0-9][A-Za-z0-9._:-]{0,99}`；顺序不被客户端模式重写 |

`null`选择、缺失/大写/未知模式、未知字段、错误类型或重复/非法ID为422 `invalid_request`。任一显式所选资料不存在、无权、未发布或发布目标不匹配，整次404 `not_found`，不会只留下其余资料或回退全库。

视频publication子集仅用于检索。原all/selected快照中的普通文字、图片和音频资料仍保留在完整范围内；即使未成为候选，其ACL/active publication变化也可使问答或后续来源整体失效。每次外部模型上下文前后检查当前范围、冻结模型/投影配置与预算，最终只有authority持久化的trace回执可以放行答案。

## 模式、预算与拒答

| `mode` | 成功所需证据 |
| --- | --- |
| `visual` | 同组真实原帧证明完整问题的全部事实 |
| `transcript` | 同组真实ASR段的精确摘录证明完整问题的全部事实 |
| `joint` | 同一个真实EvidenceGroup中的原帧/转录覆盖全部事实，且两种模态各自至少贡献一个通过证明的事实；不能跨组拼接 |
| `ocr` | 已封存原帧OCR的精确摘录覆盖完整问题；保留整帧文字的条件/反证上下文，不使用caption或ASR作为文字证明 |

原三模式的事实身份绑定完整问题摘要、ordinal和requirement。完整转录中的明确反证可否决答案，模型拒绝摘录不能隐藏反证；组外转录不能反向充当当前组的支持。caption只用于召回/重排，不进入原帧证明；不会拼接两个整问题失败结果冒充成功。

`ocr`复用既有文字摘录和完整问题证明，不向`joint`贡献视觉或转录分数。同一generation的描述、ASR、OCR命中先全部经当前authority分类验证，再按模式筛选；未知或越权ID不能被当作“不需要的模态”静默丢弃。原三模式在含OCR的新视频上仍只使用原帧/转录证据。

当前沿用有界确定性字段、颜色、布尔及操作步骤语法，最多8个事实。不是通用自然语言拆题或代词消解；无法保持共享条件、时间、逗号上下文或所属关系时明确拒答，不截取可处理前缀。离散支持判定不是校准概率；召回/重排分数也不是事实置信度。

文字、音频与视频使用同一个`AnswerService`准入/执行/提交生命周期，复用`RAG_ANSWERS_TIMEOUT_MS`（默认60000，10–600000毫秒）和`RAG_ANSWERS_MAX_CONCURRENT`（默认2，1–8）。预算从JSON解码后的用例入口开始，不覆盖慢请求体接收；每次视频请求没有独立额外并发池或无限等待队列。总引用数最多32，失败或预算中断不返回部分答案，不自动重试计费调用。取消本地处理不等于上游请求或费用已撤回。

回答与持久拒答均为200 JSON；须检查`status`，不能将200等同于成功回答：

| 字段 | 当前JSON含义 |
| --- | --- |
| `answer_id` | 服务器生成并持久化的答案/拒答trace ID |
| `status` | `answered`或`abstained` |
| `answer` | 有据回答文本，或安全拒答说明 |
| `reason` | 成功为null；拒答为安全原因码，例如`empty_scope`、`no_evidence`、`unsupported_question`、`incomplete_evidence`、`conflicting_evidence`、`scope_changed`、`configuration_changed` |
| `citations` | 经过服务器校验的typed引用数组；拒答为`[]` |

408 `answer_timeout`、429 `answer_capacity_exceeded`/`scope_capacity_exceeded`和503 `answers_unavailable`使用既有`application/problem+json`及`error_code`，不是上述200响应的`reason`。未开启时路由不注册；身份验证后按404处理。

## typed引用字段

原三模式的每个引用对应一个事实、一种模态；联合答案的引用共享真实`group_id`和父视频身份，两个模态分别贡献引用。OCR引用是原帧文字的精确摘录，可以支持多个事实，但不归入音画证据组。以下为每条`citations[]`及来源响应`citation`的全部公共字段：

| 字段 | 含义 |
| --- | --- |
| `number` | 1起、连续的引用序号，整次答案最多32条 |
| `kind` | `video_frame`、`video_transcript`或`video_frame_ocr` |
| `proof_origin` | 依次对应`machine_vlm`、`machine_asr`、`machine_ocr`；机器证明，不是人工确认 |
| `document_id`、`revision_id` | 父文档和source revision；不是投影generation |
| `source_sha256`、`parser_revision` | 原视频SHA-256及冻结video compiler版本，不是caption或帧摘要 |
| `filename`、`media_type` | 原视频文件名和真实发布的video MIME，不是可改展示名或帧MIME |
| `group_id` | 原三模式为服务器确定的同revision证据组，不是scene；OCR为null |
| `start_us`、`end_us` | 原三模式为真实组半开区间，OCR为原帧真实显示区间；均为`[start_us,end_us)`整数微秒 |
| `start_ms`、`end_ms` | 上述微秒精确除以1000的JSON十进制数字，服务端用BigDecimal，不作整数毫秒截断 |
| `time_precision` | 原三模式为`group_interval`；OCR为`frame_interval` |
| `frame` | `video_frame`或`video_frame_ocr`时为下表对象；`video_transcript`时为null |
| `transcript` | `video_transcript`时为下表对象；其他kind为null |
| `ocr` | `video_frame_ocr`时为下表文字/词框对象；其他kind为null |
| `source_url` | `/v1/video-sources/{answerId}/{number}` |
| `content_url` | `/v1/video-sources/{answerId}/{number}/content`，原视频而非片段 |

`frame`对象：

| 字段 | 含义 |
| --- | --- |
| `frame_us`、`frame_ms` | 封存原帧的真实归一化PTS；微秒整数及精确十进制毫秒 |
| `duration_us` | 原帧真实显示时长，微秒；不延长到下一选中帧 |
| `frame_sha256` | 当前封存PNG/JPEG字节SHA-256 |
| `width`、`height`、`media_type` | 原帧真实像素尺寸与`image/png`或`image/jpeg` |
| `origin` | `decoded_original`，说明是实际解码材料；视觉结论的机器性质由公共`proof_origin`说明 |
| `content_url` | `/v1/video-sources/{answerId}/{number}/frame` |

`transcript`对象：

| 字段 | 含义 |
| --- | --- |
| `start_ms`、`end_ms` | ASR原始服务器分段的完整时窗，整数毫秒；不是摘录字符的插值时间 |
| `quote`、`quote_sha256` | 在封存完整转录中精确回读的摘录及UTF-8 SHA-256 |
| `text_origin` | `machine_asr` |
| `time_precision` | `server_chunk`，不声称词级对齐 |

`ocr`对象（只有`mode=ocr`成功引用使用）：

| 字段 | 含义 |
| --- | --- |
| `start_code_point`、`end_code_point` | 封存整帧OCR文字的Unicode code point半开区间，不是字节或UTF-16索引 |
| `quote`、`quote_sha256` | 精确摘录及其UTF-8 SHA-256 |
| `ocr_revision` | 产生这份文字及词框的冻结OCR revision |
| `regions` | 与摘录相交的完整词框；每项`start/end`为同一整帧文字CP区间，`left/top/right/bottom`为原始像素边界，不是归一化值 |

词框可超出摘录的字符区间，不冒称字符级框。OCR公共时间是该封存原帧的实际PTS和显示时长，不延长至下一选中帧，不能用来推定整句字幕持续时间。原始像素词框与独立图片接口的归一化`bbox`合同不同，客户端不能混用。

组区间、帧PTS和ASR段采用同一个归一化视频时间轴，以第一实际视频帧PTS为原点，保留音画偏移。有两种模态时组是帧显示区间与ASR段的真实交集；单模态成员使用自身真实区间。组不等同于整段ASR时间，也不虚构场景。例如`start_us=1001`对应`start_ms=1.001`，不能在客户端先取整再定位。转录摘录即使很短，仍只公开整段ASR时间。

JSON不公开内部physical segment ID、事实原文/分数，也没有`page`、假scene或词级时间字段；原三模式不公开内部CP定位，OCR仅公开上述真实frame-local CP。审计仅保存允许的问题/答案/事实摘要、定位、离散贡献与模型/提示/策略版本；来源重新从当前权威发布中物化，不把模型生成的链接当来源。OCR产物与引用使用[v12独立附表](VIDEO_OCR.md#分层与版本)，不改写v11音画证明或旧v1材料。

## 来源、原帧与原视频Range

三个GET端点均要求原回答者的当前身份；`ordinal`仅接受1–32，无query、非空body或`Transfer-Encoding`，请求形状错误为422。访问其他人的答案，即使有相同文档权限，也不能获得来源。

- `GET /v1/video-sources/{answerId}/{ordinal}`：200 JSON，只有`answer_id`和上述`citation`。
- `GET /v1/video-sources/{answerId}/{ordinal}/frame`：200封存的原始解码PNG/JPEG，按真实Content-Type/Length返回；不是重新截帧或生成图片。OCR指向其文字所在原帧；旧三模式对应组无帧则404。该端点不承诺byte Range。
- `GET /v1/video-sources/{answerId}/{ordinal}/content`：读取封存父视频完整字节后按下表响应；不是截取组区间、抽取音轨或转码播放API。

未知/拒答引用、非原回答者、完整冻结范围中的任一ACL/active publication失效，或原视频SHA不符，统一404 `not_found`。回读还校验parent/manifest/封存帧/转录引用身份和摘要；校验失败不返回旧证据。先完成当前授权与原视频SHA校验，才解析Range或公开长度。

| 原视频请求 | HTTP响应 |
| --- | --- |
| 无`Range` | 200，完整原字节、真实Content-Length |
| 一个合法byte区间，例如`bytes=0-99`、`bytes=100-`、`bytes=-100` | 206，实际选中字节；`Content-Range: bytes start-end/length`，末端超长按原单Range规则截到文件末尾 |
| 非法、不可满足、重复Range头或逗号多区间 | 416，`Content-Range: bytes */length`，Content-Length为0，无body |

原视频200/206/416复用已有音频单Range算法，返回实际video MIME、`Accept-Ranges: bytes`、`Cache-Control: no-store`与`X-Content-Type-Options: nosniff`。帧同样为no-store/nosniff。未授权请求先404，不以416暴露源长度。

## 验证与未完成范围

`VideoAnswersNativeIT`通过真实FFmpeg生成视频，经Spring HTTP上传、真实解码、SQLite封存、独立索引进程、三模式问答与typed来源/Range进行本机验收。ASR、VLM、文字/embedding/rerank和Milvus服务端为本机协议替身；这证明协议和授权后端链，不证明真实中文识别、视觉推理或检索质量。逐次执行/最终源码绑定见0014验证工件，不能以合同文档代替完整门禁。

视频选中原帧OCR链已完成本机冻结：1352 Java、73 Node、458文件格式及双80%门禁通过，6项真实音视频流程单列通过，其中新增OCR验收使用真实FFmpeg/Tesseract合成英文视频；模型/Milvus服务端仍为本机协议替身。源码、报告和旧1299用例保留绑定见[OCR验证记录](changes/0014-video-library/ocr-verification.md)，不能沿用此前三模式报告认证新增源码。独立字幕轨、未选帧文字覆盖、视觉/声音向量、查询附件、文件摘要、真实中文OCR与模型质量、网页播放器和生产发布仍未验收。本轮不调用云模型、不修改旧服务/数据、不推送或部署。视频知识库不是文件摘要接口，当前后端闭环也不等于完整多模态目标完成。

代码依据：[VideoAnswerController](../src/main/java/com/evidence/rag/controller/VideoAnswerController.java)、[VideoAnswerRequestMapper](../src/main/java/com/evidence/rag/web/converter/VideoAnswerRequestMapper.java)、[AnswerService](../src/main/java/com/evidence/rag/service/AnswerService.java)、[VideoAnswerProposalService](../src/main/java/com/evidence/rag/service/VideoAnswerProposalService.java)、[EvidenceService](../src/main/java/com/evidence/rag/service/EvidenceService.java)、[VideoCitationResult](../src/main/java/com/evidence/rag/model/dto/VideoCitationResult.java)、[MediaContentResponse](../src/main/java/com/evidence/rag/web/MediaContentResponse.java)。
