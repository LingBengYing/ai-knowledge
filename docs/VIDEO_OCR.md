# 视频原帧文字证据

这是 0014 的视频 OCR 后端主线，不是文件摘要、字幕轨导入或完整多模态发布。2026-09-20 已本机冻结：1352 Java、73 Node、单列 native 6 项通过，详见[验证记录](changes/0014-video-library/ocr-verification.md)与源码绑定。

## 正常路径

显式 video MIME 上传 → 持久摄取任务 → 实际解码选帧 → 每帧本机 OCR → 完整封存 → 原有索引发布 → `mode=ocr` 问答 → 同版本原帧文字、词框及原视频回读。

OCR 使用与独立图片相同的 TSV 解析和受限原生进程生命周期。合法无文字帧也必须记录已处理；格式损坏、超时和未处理帧不能冒充空白成功。画面描述只供召回，OCR 与音轨转录独立保存。

## 配置

在现有视频、摄取和问答配置之外，显式设置：

```dotenv
RAG_VIDEO_OCR_ENABLED=true
RAG_VIDEO_OCR_EXECUTABLE=/absolute/path/to/tesseract
RAG_VIDEO_OCR_LANGUAGE=eng
RAG_VIDEO_OCR_REVISION=tesseract-5.5.3-eng
RAG_VIDEO_OCR_DEADLINE_MS=30000
```

以上是示例，不代表目标机器已经安装。语言数据与可执行文件由运维准备，revision 要准确绑定实际版本；不会自动下载语言包或外部调用 OCR。默认关闭时仍使用原 v1 编译合同；启用后 v2 将 OCR revision 纳入身份，旧资料不自动重新处理。视频装配现有的本机 development/test 限制保持，不因此开放生产。

## 问答与来源

向原 `POST /v1/video-answers` 发送：

```json
{"question":"Project A's status and budget?","document_ids":["actual-document-id"],"mode":"ocr"}
```

使用实际资料 ID；省略选择表示完整授权范围，显式空数组仍拒答。`ocr` 是独立的文字证明模式，不是原 `visual/transcript/joint` 中的额外视觉贡献。复用既有 embedding、rerank、精确摘录和完整问题证明；无法证明全部事实时拒答。

同一视频索引可同时返回描述、转录和 OCR。服务端先验证每个物理命中均属于当前授权发布，再按模式筛选；未知命中不能通过筛选被悄悄忽略。检索子集不缩减原 all/selected 范围，未命中文档撤权同样可使来源失效。

成功引用在原视频来源端点回读：

- `kind=video_frame_ocr`、`proof_origin=machine_ocr`、`group_id=null`。
- `time_precision=frame_interval`；时间是原帧真实 PTS 和显示时长，不延长至下一选中帧，不冒称字幕/逐词时间。
- `frame` 保留原帧 SHA、尺寸、时间及原帧 URL，`transcript=null`。
- `ocr` 包含 `start_code_point`、`end_code_point`、`quote`、`quote_sha256`、`ocr_revision` 与相交 `regions`。字符索引按整帧文字的 Unicode code point；词框 `left/top/right/bottom` 为原始像素边界，不是归一化值。
- 原 `source_url`、`/frame`、`/content` 路由复用当前身份和完整范围复核，原视频保持 200/单 byte Range 合同。没有假页码或自由生成链接。

## 分层与版本

`ImageOcr` 是小 Interface，`ProcessImageParser` 是共享原生 Adapter；视频编译 Service 负责组合。v12 在既有视频数据旁新增 OCR 附表，保留 v10 基础投影计数和旧 v1 材料；总索引发布计数包含非空 OCR 分块。无字帧封存但不产生空向量。引用使用独立 OCR trace，而不混入 v11 音画联合逐事实证明。

本机合成英文视频与协议替身验证只证明处理链、协议和引用身份，不证明真实中文 OCR、云模型质量、网页播放器或生产就绪。其他字幕、摘要、网页和真实质量工作仍按后续主线推进。
