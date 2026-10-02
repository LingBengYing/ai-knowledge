# 视频内嵌文本字幕

变更工件：[0016](changes/0016-subtitle-tracks/intent.md)。本机后端已接真实字幕输入、v15 持久化、完整索引、字幕问答、文件摘要和 typed 时间来源；最终验收见 [library-verification](changes/0016-subtitle-tracks/library-verification.md)。默认关闭，模型和 Milvus 服务端为本机协议替身；前端、真实模型质量及生产没有完成。

## 它与其他视频文字有什么不同

- 内嵌字幕：读取视频容器里的独立文本轨，保存实际轨号、原包、文字与显示时间。
- 音轨转写：ASR 根据声音得到文字，时间精度是服务器 PCM 分段。
- 画面 OCR：识别选中原帧里的文字，有对应像素词框与帧时间。
- 帧描述和文件摘要：模型派生内容，不代替前三者的原始证据。

字幕不会写入 ASR 数据，也不会被旧 `joint` 模式当作声音证明。

## Interface 与分层

`ProcessVideoDecoder(ffmpeg, ffprobe, deadline, intervalSeconds, true)` 显式启用字幕输入。原四参构造仍是原 decoder-v1 合同；新构造使用 decoder-v2。`VideoCompilationService(decoder, transcriber, vision, ocr, budget, true)` 使用 compiler-v3 并要求完整字幕产物，`ocr=null` 表示此内部构造不启用 OCR。旧四/五参 Service 构造和 v1/v2 哈希保留。

Runtime 新增 `rag.video.subtitles.enabled=true`（环境变量 `RAG_VIDEO_SUBTITLES_ENABLED=true`），默认 `false`。需先满足现有视频、摄取、索引和问答配置；文件摘要还需独立启用 synopsis。旧关闭路径与旧处理版本保持；仅 development/test、字面 loopback，不能作为公网配置使用。`rag.video.ocr.enabled` 独立控制选帧 OCR，v3 header 显式封存 OCR 是否必需，缺产物不能冒充关闭。

字幕解析复用同一 native Job 的原文件私有副本、deadline、输出限制与进程清理；Controller/Repository 不执行 FFmpeg。Domain 为 `VideoSubtitleCompilation` → 真实 `VideoSubtitleTrack` → 全部 `VideoSubtitleCue` 包，包括不作证据的清屏包。

## 当前支持的输入

支持 `mov_text`、`subrip`、`webvtt`，分别使用合成 MP4、MKV、WebM 验证。每个视频仍最多一个视频流和一个音轨，新增最多四条文本字幕轨。所有轨完整读取，不擅自挑一条语言轨。

总输入最多 20 MiB / 600 秒；字幕总计最多 2048 包、单包最多 4096 code points、总文本最多 500000 code points、原包最多 2 MiB。超限整体不可处理，不截尾。ASS/SSA、位图字幕、烧录字幕逐帧识别不在这个切片内。

`text` 的格式明确为 `subtitle-payload-utf8-v1`：mov_text 文本不包含后置样式 atom；SubRip/WebVTT 的内联 markup 作为原始数据保留。它不是渲染后的纯文字，不证明屏幕位置、样式或说话人。

## 时间与身份

保留每轨的整数 PTS、duration、原有理 time_base、语言元数据、原包 SHA 和顺序。相同起点、重叠字幕、不同轨道不会合并。原包 SHA 和文字/引用 SHA 是不同的身份，不能互用。

时间先在有理数域减去实际首视频帧 epoch，再将起点向下、终点向上取整到微秒。例如视频起点 `60001/30000` 秒、字幕区间 `[2.5, 3.25)` 秒，得到相对区间 `[499966,1249967)` 微秒。字幕显示区间不是逐词对齐。

空清屏包可早于视频起点，但不能产生事实定位；非空字幕早于起点则显式不可处理。真实末尾字幕可扩展完整媒体时长，不生成虚假的视频帧。完整产物 manifest 包含时基、轨、所有包（含空包）及原文本身份；源文件和处理版本由父视频编译身份绑定。

## 持久化、检索与来源

v15 独立保存字幕 compilation、track、cue、publication entry 和 trace。全部包（含清屏包）进入 authority，非空 cue 才进入索引；所有模态合计仍受 4096 projection 完整发布上限约束。轨身份绑定 revision 和实际 stream index，cue 再绑定原包 ordinal；authority manifest 同时绑定原视频、编译版本、native 字幕和旧 base/OCR 合同。

`POST /v1/video-answers` 复用当前鉴权与完整文档范围，例如：

```json
{"mode":"subtitle","question":"项目预算是多少？","document_ids":["已索引文档ID"]}
```

候选是当前 cue，文字证明上下文是完整同轨文本，因此未召回的尾部更正/条件仍能否决旧事实。“更正”与“示例”按共用文字证明规则区别处理；模型不能自由编造时间、轨号或引用。旧 `visual`、`transcript`、`joint`、`ocr` 含义不变。

回答及来源标记为 `kind=video_subtitle`、`proof_origin=embedded_subtitle`、`time_precision=subtitle_cue`。`subtitle` 对象含真实轨号、codec、语言、原包 ordinal/PTS/duration/time_base、同轨 CP 范围与原包/文字/manifest 哈希；公共时间为相对视频 epoch 的微秒区间。通过响应中的来源 URL 回读同版本原视频，支持单 byte Range；没有伪造 frame、page、ASR 或 EvidenceGroup。

文件摘要新增第八类原始材料 `VIDEO_SUBTITLE`，完整枚举所有非空 cue；短文件及分层长文件共用已封存 publication 和完整材料 fingerprint。字幕引用直接回读原视频时间区间，摘要本身仍不进入问答事实证据。重启后的已完成回答/摘要来源读取不调用模型。

## 验证及下一步

显式本机 native 验证（仅新临时合成媒体；不会读取云密钥）：

```sh
RAG_VIDEO_DECODER_IT_ENABLED=true \
RAG_VIDEO_DECODER_IT_FFMPEG=/absolute/path/to/ffmpeg \
RAG_VIDEO_DECODER_IT_FFPROBE=/absolute/path/to/ffprobe \
mvn -s .mvn/settings.xml -gs .mvn/settings.xml \
  -Dtest=VideoSubtitleDecoderNativeIT,VideoSubtitleLibraryNativeIT test
```

真实 native HTTP 验收使用临时合成双轨 MP4：上传→完整发布→字幕独有事实→时间/原视频 Range→含尾部字幕的摘要→重启零模型回读。它不证明真实 ASR/VLM/摘要质量。后续为查询附件、真实质量、前端和生产；字幕选择/替换 UI、ASS/位图/烧录字幕不在当前合同内。不重做已完成协议诊断，不改变旧音画联合事实合同。
