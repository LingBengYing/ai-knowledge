# 视频上传与完整索引

状态：0014步骤2本机后端验收通过，见[验证记录](changes/0014-video-library/publication-verification.md)；不是联合问答、前端或生产完成声明。输入编译基线见 [VIDEO_COMPILATION](VIDEO_COMPILATION.md)，当前实现范围见 [0014 plan](changes/0014-video-library/plan.md)。

## 用户路径与类型合同

启用视频摄取后，沿用 `POST /v1/documents?filename=clip.mp4`，原始文件为请求体，显式设置 `Content-Type: video/mp4`。同样支持 `.mov` / `video/quicktime`、`.webm` / `video/webm`、`.mkv` / `video/x-matroska`。不支持 multipart、额外查询字段或重复内容类型头。

`application/octet-stream` 保留原文本/图片/音频合同，尤其不会把原 audio-only MP4/WebM 自动改成视频。显式视频 MIME 是“按视频处理”的请求，不是客户端提供的真实流型证明；后台必须通过原 VideoDecoder 的容器、真实流与完整解码检查。请求与实际文件不符则失败，不回退成音频处理或只保存半个产物。首切不是零提示的自动流型识别。

三个状态分别表示真实发生的步骤：

1. `202 / queued`：原文件、请求的编译版本、资料和持久任务已在同一事务保存，尚未完成解码。
2. `GET /v1/ingestions/{task_id}` 返回 `parsed`：全部原帧、描述、全部音轨分段及实际相交证据组已原子保存。描述只用于召回，不是视觉证明。
3. 显式 `POST /v1/documents/{document_id}/index` 后，轮询 `GET /v1/indexings/{task_id}` 到 `indexed`：所有帧描述和非空转录的 generation / physical ID / 内容摘要已完整验证并发布。

索引请求不会因上传自动触发。`parsed` / `indexed` 不等于自动开放视频问答；现有 [视频答案/来源接口](VIDEO_ANSWERS.md) 还需显式启用问答配置，列表 `can_answer` 和 readiness gate 不因本切开放。身份、Origin、错误和取消/重试语义复用 [API](API.md)。

## 配置

仅允许独立 Java 数据目录、字面 loopback、development/test；默认关闭，不改变旧音频/视觉开关。只通过环境/secret 注入凭据，程序不自动加载 `.env`；禁止用现有其他服务配置或数据做演示。

| 变量 | 默认 / 作用 |
| --- | --- |
| `RAG_VIDEO_ENABLED` | `false`；还须 `RAG_INGESTION_ENABLED=true` |
| `RAG_VIDEO_FFMPEG_EXECUTABLE` / `RAG_VIDEO_FFPROBE_EXECUTABLE` | 必填，已安装二进制的绝对路径；绑定摘要 |
| `RAG_VIDEO_DECODE_DEADLINE_MS` | `30000`；probe/decode 共用，10–60000 |
| `RAG_VIDEO_FRAME_INTERVAL_SECONDS` | `10`；1–30，变化选帧之外的间隔兜底，不假报硬性帧距 |
| `RAG_VIDEO_CHUNK_SECONDS` | `15`；1–30，ASR 采样分段边界，不是词级时间 |
| `RAG_VIDEO_COMPILATION_BUDGET_MS` | `600000`；10–600000，完整编译预算 |
| `RAG_VIDEO_ASR_BASE_URL` / `MODEL` / `API_KEY` | 三项必填；独立标准 multipart ASR 协议 |
| `RAG_VIDEO_VISION_BASE_URL` / `MODEL` / `API_KEY` | 三项必填；独立标准图片 chat 协议；摄取调用描述，显式问答启用后复用原帧逐事实证明 |
| `RAG_VIDEO_ASR_DEADLINE_MS` / `RAG_VIDEO_VISION_DEADLINE_MS` | 各 `30000`，单次请求预算 |
| `RAG_VIDEO_ASR_MAX_RESPONSE_BYTES` / `RAG_VIDEO_VISION_MAX_RESPONSE_BYTES` | `65536` / `262144` |
| `RAG_VIDEO_ASR_ALLOW_LOOPBACK_HTTP` / `RAG_VIDEO_VISION_ALLOW_LOOPBACK_HTTP` | 各 `false`，仅合成本机协议测试显式开启 |

索引另需 `RAG_INDEXING_ENABLED=true` 和已有 [TextAdapterSettings](TEXT_ADAPTERS.md) / 独立 Java Milvus 配置。视频编译不隐式开启问答、独立音频或视觉答案；配置装配不发任何模型请求。`VideoConfiguration` 的生命周期容器仅持有视频自身资源，不将其 ASR/Vision 注册为旧摄取路径的候选 Bean。

## 完整证据与发布

原视频继续存于 `corpus_documents.original_blob`。v10 增量格式新增 `video_compilations`、`video_frames`、`video_transcript_spans`、`video_evidence_groups` 和两类 publication entry；旧 v1–v9 迁移体不改。备份与迁移仍由原 Store 管理，不迁移其他服务。

帧与转录分别使用独立 `video-frame-…` / `video-transcript-…` 身份；caption/转录摘要不能替代原视频/原帧摘要。封存保存完整时间、版本、原帧字节、空转录与音尾，并绑定完整 manifest。每帧和每个非空转录各投影一次，帧在前、转录在后；完整计数同时进入 authority 发布约束和 all/selected 授权范围。

EvidenceGroup 仅关联同一 revision 的实际帧显示区间与 ASR 分段区间交集；无交集的成员保留单模态组。它不把选中帧延长到下一帧、不伪造 scene，也不声称词级同步。ASR 末端毫秒向上取整与原采样计数都保留，不裁去音尾。空转录不进入向量索引或音频证明。

开发上限仍为20 MiB原文件、10分钟、单视频/至多单音轨、128帧、32 MiB原帧总量；召回正文在封存前整体检查不超过现有索引协议的1500000 Unicode code points。超限整体失败，不截尾后标记成功。

## 验证边界

新增自动化覆盖正常显式上传、纯音频容器兼容、完整持久化/重启、两种视频投影和混合模态完整 scope；实际 FFmpeg 合成视频通过 Spring HTTP、SQLite 和独立索引进程进行 acceptance。真实 ASR/VLM/embedding/Milvus 质量不由协议替身代证。具体运行结果、源码冻结与未验项以0014当前验证记录为准，不能凭本文件的合同描述推定验收已通过。

完整事实身份、同组音画联合证明、typed 视频时间/关键帧/原视频 Range 已由 [VIDEO_ANSWERS](VIDEO_ANSWERS.md) 消费此 publication；字幕/OCR、摘要、网页与真实模型质量仍须继续。摘要不是视频知识库的替代目标。
