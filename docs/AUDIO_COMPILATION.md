# 音频知识库：编译、问答与时间来源

这是[0013音频知识库](changes/0013-audio-library/intent.md)的本机后端实现。显式音频开关复用既有上传/任务/索引HTTP，v8保存独立时间证据并完整发布；共用AnswerService接入音频问答，v9封存typed时间引用，来源接口回读同版本原音频并支持单byte Range。前端播放器、真实ASR效果和生产验收另行推进，不能由本机协议替身结果代证。

## 正常数据流与职责

`AudioCompilationService.compile(filename, mime, source, current)`返回完整`AudioCompilation`：原文件SHA、decoder/ASR/compiler版本、实际时长和有序转录分段。Service编排完整处理，不写数据库、不发布索引，也不代替调用方的最终授权事务。

| 层 | Interface / Implementation | 职责 |
| --- | --- | --- |
| Worker | `AudioDecoder` / `ProcessAudioDecoder` | 实际FFprobe探测单音轨、FFmpeg完整解码；固定可执行文件及哈希、参数、环境、输出上限与子进程退出确认 |
| Client | `AudioModels` / `OpenAiCompatibleAudioModels` | 有界multipart `audio/transcriptions`，发送canonical WAV和model，只消费text；复用包内HTTP传输，无自动重试/重定向 |
| Service | `AudioCompilationService` | 按真实采样分段、调用ASR、检查资格/预算/版本，全部完成后返回，任何阶段失败不返回部分结果 |
| Model | `DecodedAudio`、`AudioTranscriptSpan`、`AudioCompilation` | 不可变采样/文本与完整时间线不变量；脱敏字符串表示 |
| Tool | `AudioInput`、`AudioPcm` | 文件名/MIME/magic准入及确定性WAV封装，无HTTP/数据库/子进程 |
| Tool + Model | `TextGrounding.verifyText` + `GroundingText` | 复用既有完整问题证明，以真实全文及候选CP范围替代页码依赖；不构造假TextPage |
| Service | `IngestionService` / `IngestionTaskProcessor` | 冻结profile并复用现有任务；事务外完整编译，事务内复验claim/current/SHA后保存和封存 |
| Repository + Model | `IngestionRepository` / `AudioEvidence` | v8完整转录头与全部span；稳定证据ID来自revision和原ordinal，空文本不投影 |
| Repository | `IndexingRepository` / `EvidenceRepository` | 复用ProjectionItem、v3索引worker和完整manifest；发布及完整scope包含文字、图片、音频，不把模态候选当完整scope |
| Service | `AnswerService` / `EvidenceService` | 共用并发、预算、检索、摘录、证明与最终trace；仅候选读取和typed来源物化按模态分派 |
| Model + Repository | `AudioSourceEvidence` / `AudioTraceEvidence` / v9附表 | 内部CP绑定原span；保存原时间和source/text/transcript/quote摘要，联合文字/图片引用封存 |
| Controller + Web | `AudioAnswerController` / `AudioContentResponse` | 复用请求映射及鉴权；来源完整授权通过后，才解析单byte Range并输出200/206/416 |
| Config | `AudioConfiguration` | 默认关闭，显式开发/测试loopback装配codec、ASR与compiler；管理资源关闭，不在启动时转写 |

已有文字`verify`仍映射真实页全文，旧否定/条件/冲突/程序步骤算法保留。新候选可达4096 code points，已证明单摘录仍最多1200；音频完整上下文按原ordinal以单个换行连接全部span（包括空文本），不只检查已召回片段。每条摘录必须落在其索引span内，时间直接取原span起止；跨段事实输出多条引用，不能把CP偏移按比例换算成音频时间。

## 处理合同

- 上传输入最多20MiB；准入支持WAV、MP3、FLAC、OGG、M4A/MP4 audio、WebM audio，实际probe仍须通过且只有单音轨。带视频输入留给后续视频主线。
- 解码输出16kHz、mono、signed PCM16 little-endian，最多19,200,000 bytes（10分钟）。超限失败，不使用`-t`截取前段当成功。实际FFmpeg默认验收夹具为WAV；其他格式的真实编解码兼容性仍待补充验收。
- 编译分段长度由调用方提供，1–30秒，Config默认15秒。每段为原PCM精确切片，不补写/丢弃采样。起止为整数毫秒，最后不足1ms的采样向上取整。
- 转录为空的段保留时间线；全部转录为空则`audio_no_speech`失败。该错误名**不表示已实现VAD或已证明信号静音**，非空ASR输出也不自动证明识别正确。
- 只读取ASR `text`，不采信厂商提供的时间、置信度或说话人。引用精度是服务器分段边界，不是词级对齐；真实CER与时间误差质量gate保持。
- decoder的probe+decode共享10ms–60s硬deadline；HTTP请求为10ms–60s。编译总预算10ms–10min，在阶段/每次请求前后检查；在途调用按其自身deadline退出，不声称编译总预算能瞬间中止正在进行的HTTP。Job取消复用既有线程中断和current复验。
- `current`在处理前后及ASR前后复验；源SHA/profile不符、取消或预算耗尽不能返回部分转录。最终入库/发布仍须调用方在authority事务中再次复验。
- 固定格式/协议与受控子进程不等于OS沙箱。当前Module不提供生产安全或吞吐承诺；没有读取密钥文件、调用云模型或改动旧服务数据。

## 可重放验证

普通回归运行`mvn test`；显式native IT使用独立临时数据和实际本机codec，不连接现有知识库或真实模型：

```bash
RAG_AUDIO_DECODER_IT_ENABLED=true \
RAG_AUDIO_DECODER_IT_FFMPEG=/absolute/path/to/ffmpeg \
RAG_AUDIO_DECODER_IT_FFPROBE=/absolute/path/to/ffprobe \
mvn -Dtest=AudioDecoderNativeIT,AudioCompilationNativeIT,AudioPublicationNativeIT,AudioAnswersNativeIT test
```

未显式配置时IT硬失败，不自动下载codec、跳过或改用假解码。native IT的ASR/问答模型/Milvus服务端是测试自身回环HTTP替身，验证原字节、multipart、完整时间线与真实HTTP/SQLite/索引子进程接线，不证明真实语音识别质量。历史结果见[步骤1验证](changes/0013-audio-library/verification.md)与[步骤2验证](changes/0013-audio-library/publication-verification.md)，当前完整问答/来源/Range结果见[步骤3验证](changes/0013-audio-library/answers-verification.md)。

## 音频问答与回放接口

音频与问答开关同时开启时提供以下端点；问题和所选文档合同复用[文字问答](API.md#post-v1answers)。显式空选择或不可用选择不回退全库，完整selected scope包含所选文字、图片和音频，只有检索候选缩小为音频。

| 方法与路径 | 返回 |
| --- | --- |
| `POST /v1/audio-answers` | 原问答状态/拒答合同；成功的`citations`为音频时间引用 |
| `GET /v1/audio-sources/{answerId}/{ordinal}` | 同一回答者、完整当前scope/active下的typed引用，ordinal为1–32 |
| `GET /v1/audio-sources/{answerId}/{ordinal}/content` | 原文件SHA复验后的原音频；无Range为200，单byte Range为206，不可满足或不支持的多Range为416 |

音频引用字段：`number`、`kind=audio_span`、`document_id`、`revision_id`、`source_sha256`、`parser_revision`（音频compiler）、`filename`、`media_type`、`start_ms`、`end_ms`、`quote`、`quote_sha256`、`text_origin=machine_asr`、`time_precision=server_chunk`、`source_url`、`content_url`。不输出page/start/end等文字页定位字段；内部CP只用于原转录证明与封存。

`start_ms/end_ms`定位播放器时间；HTTP Range定位原文件字节，二者不是同一坐标，客户端不能直接将毫秒当作Range。内容响应设置`Cache-Control: no-store`、`X-Content-Type-Options: nosniff`、`Accept-Ranges: bytes`。来源回读每次重验完整授权，不能只因被引用的音频本身仍active就绕过原完整scope。当前支持闭合、开放结尾和suffix单Range，不宣告多Range或If-Range实现。

v9的`audio_trace_evidence`保存音频publication/span与物理条目、内部CP、原时间、source/text/transcript/quote SHA、召回/重排分数及逐事实哈希；真实FK和三类引用联合seal保持。它不保存原问题片段，也不修改v1–v8迁移体。

前端播放器、真实ASR/Milvus质量、音频事件/说话人、视频/音画联合、摘要与生产部署未完成，不因本Module验证而缩减。

## 本机上传与索引开关

仅`rag.environment=development/test`、`server.address=127.0.0.1/::1`且摄取开启时允许音频装配。`.env.example`提供全部`RAG_AUDIO_*`名称；ASR使用独立endpoint/model/key，不回退文字或图片凭据。默认关闭，无真实provider测试授权时不要配置云端进行验收。

- 必需：`RAG_AUDIO_ENABLED=true`、`RAG_INGESTION_ENABLED=true`，固定`RAG_AUDIO_FFMPEG_EXECUTABLE`和`RAG_AUDIO_FFPROBE_EXECUTABLE`，独立`RAG_AUDIO_BASE_URL/MODEL/API_KEY`。
- 可选：`RAG_AUDIO_CHUNK_SECONDS=15`、`RAG_AUDIO_DECODE_DEADLINE_MS=30000`、`RAG_AUDIO_COMPILATION_BUDGET_MS=600000`、`RAG_AUDIO_DEADLINE_MS=30000`、`RAG_AUDIO_MAX_RESPONSE_BYTES=65536`；本机ASR替身需显式`RAG_AUDIO_ALLOW_LOOPBACK_HTTP=true`。
- 原文件字节POST `/v1/documents?filename=recording.wav`，202后轮询`/v1/ingestions/{task_id}`直到parsed；再POST `/v1/documents/{document_id}/index`并轮询`/v1/indexings/{task_id}`直到indexed。索引仍需原`RAG_INDEXING_ENABLED`与embedding/Milvus配置，见[TEXT_INDEXING](TEXT_INDEXING.md)。
- 音频问答额外需要原`RAG_ANSWERS_ENABLED=true`及其模型配置，见[问答API](API.md)。ASR只用于摄取，不替代embedding、rerank或摘录模型配置。
- `/v1/config`按开关声明`audio_upload`、`audio_index`；仅audio与answers同时启用时声明`audio_answers`、`audio_sources`。音频parsed的文字页数/文字段数为0，不能拿它们当音频分段数；indexed表示完整召回投影已发布，不代表ASR质量或整个问题可回答。readiness仍503。
