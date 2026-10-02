# 视频处理：真实帧和统一时间轴

最新增量见[0016内嵌字幕输入](VIDEO_SUBTITLES.md)：显式decoder-v2/compiler-v3保留完整文本轨、原包与精确同epoch时间；旧构造/hash保持，Runtime尚未激活，字幕authority/索引/问答待接。既有视频问答与OCR已分别在[VIDEO_ANSWERS](VIDEO_ANSWERS.md)、[VIDEO_OCR](VIDEO_OCR.md)冻结；下文为原v1输入Module的历史合同，不再作为当前后续任务清单。

当前变更为[0014视频知识库](changes/0014-video-library/intent.md)。本页记录已冻结的输入编译Module；步骤2已另接[视频上传/持久证据/完整索引](VIDEO_PUBLICATION.md)，见[本轮验证](changes/0014-video-library/publication-verification.md)。下一步为音画联合问答和typed来源，不把输入或索引称为完整视频RAG。前端、旧服务和数据未改；[步骤1验证](changes/0014-video-library/verification.md)作为历史基线保留。

## 已实现的接口边界

| 层 | 入口 | 职责 |
| --- | --- | --- |
| Worker | [VideoDecoder](../src/main/java/com/evidence/rag/worker/parser/VideoDecoder.java) | 完整视频字节→真实时间戳的选中PNG帧和可选对齐PCM |
| Service | [VideoCompilationService](../src/main/java/com/evidence/rag/service/VideoCompilationService.java) | 完整校验、PCM转写、原帧描述；每次调用前后校验当前资格/配置/总预算 |
| Model | [VideoCompilation](../src/main/java/com/evidence/rag/model/domain/VideoCompilation.java) | 源SHA、处理版本、原时间起点、相对时间、完整帧/描述和可选转录 |

复用[AudioTranscriptionService](../src/main/java/com/evidence/rag/service/AudioTranscriptionService.java)的PCM分段逻辑，不让旧AudioDecoder接受视频。独立音频全静音仍报`audio_no_speech`；视频无音轨或全空转录允许保留视觉产物，绝不凭空补声音。

## 时间与完整性

- 接收MP4/MOV/WebM/MKV，最多20MiB、10分钟、一个视频流及至多一个音频流；格式名、magic、实际容器和流共同验证。更复杂轨道选择后续显式定义。
- 以第一个实际视频帧PTS为原时间起点；每个选中帧保存实际相对PTS、实际时长、尺寸及PNG字节SHA。不能通过帧号/FPS猜时间。
- 首帧、场景变化和间隔到期后的下一实际帧共同参与选择。间隔默认由调用者指定1–30秒，变化阈值0.3；不把它说成所有视频都保证的最大帧距。
- 音轨使用同一个视频起点，保留相对延迟并前补静音；起点前已有真实声音的输入先明确拒绝，不能偷偷裁掉。保留完整音尾，总时长取音画两条实际时间线末尾较大值。
- 最多128个选中帧、总PNG32MiB、每帧1200万像素。native probe/输出/运行时间有界；超过上限或输出不完整必须整体失败，不取前一部分伪称完整。
- 音频分段位置来自16kHz PCM样本，保存精确样本数；毫秒末尾向上取整不是词级对齐，忽略provider自行输出的起止时间。

描述仅供召回，不是画面事实的证明。当前Compiler不调用问答生成/事实验证，不生成页码、字符引用或EvidenceGroup。后续视频来源要绑定父视频版本及原帧SHA，不能套用整图`imageSHA == documentSHA`规则。

## 验证方式与未完成项

普通单测使用受控Decoder/ASR/VLM；显式native验收用新临时目录合成视频、真实FFmpeg/FFprobe与仅本机HTTP模型协议替身：

```bash
RAG_VIDEO_DECODER_IT_ENABLED=true \
RAG_VIDEO_DECODER_IT_FFMPEG=/absolute/path/to/ffmpeg \
RAG_VIDEO_DECODER_IT_FFPROBE=/absolute/path/to/ffprobe \
mvn -s .mvn/settings.xml -gs .mvn/settings.xml \
  -Dtest=VideoDecoderNativeIT,VideoCompilationNativeIT test
```

该命令必须显式提供本机二进制，不读取云key。它验证帧/PCM时间和标准协议字节，不验证真实语音识别或视觉理解质量。当前运行结果、源码绑定及其适用范围以[0014审查](changes/0014-video-library/REVIEW.md)为准。

待完成：视频持久任务和独立authority、同时间窗口EvidenceGroup、完整索引发布、视觉/音轨/联合逐事实问答、typed时间/关键帧来源及授权原视频Range；字幕/OCR、真实模型质量、浏览器播放器与生产验收也继续保留。
