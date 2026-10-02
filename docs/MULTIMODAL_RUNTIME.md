# 完整多模态后端运行与验收

适用独立Java后端的development/test、字面loopback环境。完整装配验收工件位于[0018](changes/0018-multimodal-composition/intent.md)；执行结果以该目录verification为准。全开关启动不是生产发布，也不是模型效果评测。

## 配置一次说明白

以[.env.example](../.env.example)为字段清单，通过进程环境或私有secret注入；应用及`run-dev.sh`均不自动读取`.env`。不要在命令历史、公共文件或Git中填写真实key。各模型的base URL、model和key显式独立配置，程序不自动继承其他模型凭据。

| 处理链 | 必需开关和配置 | 实际边界 |
| --- | --- | --- |
| 上传/索引/问答 | `RAG_INGESTION_ENABLED`、`RAG_INDEXING_ENABLED`、`RAG_ANSWERS_ENABLED`；三套文本模型及Milvus配置 | 上传解析后需显式请求index；parsed不是indexed |
| 图片视觉 | `RAG_VISUAL_ENABLED`及`RAG_VISION_*` | 同时开图片OCR时，新PNG/JPEG上传仍按原合同走视觉证据；不宣称同时有OCR词框 |
| 图片OCR | `RAG_IMAGE_OCR_ENABLED/EXECUTABLE/LANGUAGE/REVISION` | 指定实际Tesseract绝对路径与语言数据版本；附件图片OCR与视觉入库分开 |
| 音频 | `RAG_AUDIO_ENABLED`、FFmpeg/FFprobe路径、ASR `RAG_AUDIO_*` | 真实解码/分段；时间不是词级对齐 |
| 视频 | `RAG_VIDEO_ENABLED`、解码路径、`RAG_VIDEO_ASR_*`、`RAG_VIDEO_VISION_*` | 使用自身ASR/VLM配置，不继承单独图片/音频客户端 |
| 视频OCR/字幕 | `RAG_VIDEO_OCR_ENABLED`及其独立OCR配置；`RAG_VIDEO_SUBTITLES_ENABLED` | 选中原帧OCR不等于全视频文字；内嵌字幕不是ASR；字幕开启使用compiler-v3 |
| 文件摘要 | `RAG_SYNOPSIS_ENABLED`及`RAG_SYNOPSIS_*` | 短与分层摘要共同装配，无独立hierarchy开关；摘要不是问答证据 |
| 查询附件 | `RAG_QUERY_ATTACHMENTS_ENABLED`及`RAG_QUERY_RANKING_*` | 依赖answers/visual/audio/video/image-ocr；附件临时存在、不入库、不作引用 |

所有上述开关默认关闭。`*_ALLOW_LOOPBACK_HTTP=true`只用于本机协议替身；文本embedding/rerank/generation共用`RAG_TEXT_ALLOW_LOOPBACK_HTTP`，不支持三套独立的同名后缀。Milvus endpoint是根URL（不附`/v1`），collection必须使用独立`java_`前缀，不能复用其他系统集合。

使用JDK 21与Maven，提供全套配置后，在仓库根目录运行：

```bash
mvn -s .mvn/settings.xml -gs .mvn/settings.xml verify
bash run-dev.sh
```

运行前用`RAG_DATA_DIRECTORY`指向新建Java专用目录，并选择独立loopback端口。脚本复制本仓库`target`中的不变JAR；不要在运行JAR路径上重新打包。`GET /v1/config`可读取实际能力；`/health/live=200`只说明进程存活，`/health/ready=503`仍表示未完成生产条件。不能将开发header模式放到公网。

## 可重放的本机验收

`MultimodalCompositionNativeIT`以真实RagApplication及生产Bean启动，外部模型/Milvus仅为loopback协议替身。它必须使用明确存在的FFmpeg、FFprobe及Tesseract，不静默skip：

```bash
export RAG_VIDEO_DECODER_IT_ENABLED=true
export RAG_VIDEO_DECODER_IT_FFMPEG=/absolute/path/to/ffmpeg
export RAG_VIDEO_DECODER_IT_FFPROBE=/absolute/path/to/ffprobe
export RAG_IMAGE_OCR_IT_ENABLED=true
export RAG_IMAGE_OCR_IT_EXECUTABLE=/absolute/path/to/tesseract
export RAG_IMAGE_OCR_IT_REVISION=installed-engine-and-language-data-revision
mvn -s .mvn/settings.xml -gs .mvn/settings.xml \
  -Dtest=MultimodalCompositionNativeIT test
```

这条命令只认证自身实际断言，不能替代完整默认回归、既有native集合或下面的发布验收。测试生成合成媒体，不用业务资料或真实凭据。

## 进入真实质量和生产前还缺什么

1. 新增模型调用授权：明确provider、模型/维度/版本、合成样本、调用上限、超时及无自动重试；不复用旧文本剩余额度。对真实业务资料另需数据owner确认供应商保留/训练使用/区域与允许类别。
2. 固定语料真实质量：分别记录文本、图像、ASR、视频各证明模式、摘要与附件检索效果及拒答；本机合成英语OCR不能认证中文或非WAV效果，Bean存在不能证明长文件分层已执行。
3. 真实Milvus与同镜像staging：独立Java集合、完整publication及重启/恢复；协议替身成功不能替代真实服务或容器运行证据。
4. 已授权后接前端真实流程：上传、任务、列表、提问、typed引用/图片/播放器、摘要；后端HTTP通过不等于用户界面可用。
5. Java发布条件：原生处理的OS隔离、合法身份/TLS与受控入口、备份恢复/迁移/回滚、真实负载与观测、独立审查、目标环境与发布授权。旧Python发布工件/镜像/覆盖率不能认证Java；不得仅修改RuntimeGuard或ready响应制造上线。

本机装配验收只关闭“现有Configuration未共同运行验证”这一缺口，以上条件及完整多模态生产目标均保留。
