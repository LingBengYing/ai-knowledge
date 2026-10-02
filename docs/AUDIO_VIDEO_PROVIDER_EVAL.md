# 音视频模型评测入口

最新状态（2026-09-22 17:39:50）：累计6/20、未使用14，第二组具名2次诊断已结束。原/full真实证明均命中instruction_in_field，ASR编号规范化731=true/AU=false，转录SHA与前次诊断相同。下一步核对ASR配置/候选，不直接放宽规则或猜测字符；视频未开始。下方首次失败和12次配置属于历史，最新范围/计数/证据以[台账](changes/0019-audio-video-provider-eval/provider-run.md)为准，勿照旧命令重置预算。

0019提供固定合成样本的真实模型评测入口。2026-09-22获批最多12次及使用原密钥后，单次LiveIT已执行：2次请求后因`eval_audio_not_grounded`失败停止，视频未开始，未使用10次。完整结果见[执行台账](changes/0019-audio-video-provider-eval/provider-run.md)；不得照下面的操作说明重新执行或把剩余额度用于诊断，新的云诊断/复验需另行明确授权。本机工具验收仍见[0019 verification](changes/0019-audio-video-provider-eval/verification.md)，不能替代此次真实失败。此前文本调用余额不能用于这里。

## 验证什么

输入是仓库内固定SHA的[合成中文语音与视频](../src/test/resources/multimodal-provider/README.md)。先用真实FFmpeg/FFprobe完整解码，再调用现有生产ASR、文字摘录与VLM客户端，复用原TextGrounding和VideoAssessmentService，不复制模型协议或放宽证明。

- 音频：实际ASR转录→一次原文摘录→本地原文字证明，核对备用泵事实。
- 视频：完整音轨ASR＋全部选中原帧描述→固定首个真实相交组→设备码与巡检窗口双事实联合证明。描述只供召回，不作为事实依据；画面和音轨各须贡献，失败不换组重试。

每份音轨须不超过30秒并完整送出。实际选帧数为F，最坏调用量为`F+9`（两次ASR、一次音频摘录、F次帧描述、双事实最多6次证明）。首个请求前核算不超过新授权额度且至多30；不足就停止，不裁掉帧、音尾或尾部事实。所有客户端共用逐次计数，失败也消耗一次；每次最多60秒，无自动重试或模型发现请求。

固定参数为`intervalSeconds=2`、`subtitlesEnabled=true`、`chunkSeconds=30`，联合问题是“蓝图计划的设备识别码是什么？蓝图计划的巡检窗口是什么？”。本机预检F=3、最坏12次；运行时仍重新计算。字幕不剥离，也不作为声音事实来源；“是什么时间”问法的当前语法限制在0019 plan中保留，不把等义问法验收扩大为所有中文问法支持。

## 本机入口验收

实际JDK21，先将`RAG_VIDEO_DECODER_IT_FFMPEG`、`RAG_VIDEO_DECODER_IT_FFPROBE`设为现有可执行文件的绝对路径，显式开启本机native测试，再执行：

```bash
export RAG_VIDEO_DECODER_IT_ENABLED=true
mvn -s .mvn/settings.xml -gs .mvn/settings.xml \
  -Dtest=AudioVideoProviderEvaluationTest,AudioVideoProviderEvaluationNativeIT test
```

NativeIT使用本机HTTP模型替身；它证明实际PCM/原图、生产协议、预算和证明链可运行，不代表ASR/VLM准确率。旧测试和完整Java/Node/覆盖率门禁仍须执行，不由该单项替代。

## 已获授权批次的配置

目标固定为`https://api.siliconflow.cn/v1`。以下只说明配置，不是新授权，也不触发调用。通过进程私有环境注入：

| 环境变量 | 内容 |
| --- | --- |
| RAG_AUDIO_VIDEO_IT_APPROVED_CALLS | 本批固定为12；工具硬上限30不是本批授权，且必须足够覆盖解码后的F+9 |
| RAG_AUDIO_VIDEO_IT_API_KEY | 本批负责人明确授权的密钥，仅私有进程环境注入，不放命令参数、报告或Git |
| RAG_AUDIO_VIDEO_IT_ASR_MODEL | 已确认支持现有标准音频转写协议的模型名 |
| RAG_AUDIO_VIDEO_IT_VISION_MODEL | 已确认支持现有图片chat协议的模型名 |
| RAG_AUDIO_VIDEO_IT_GENERATION_MODEL | 已确认支持现有文字chat协议的模型名 |
| RAG_VIDEO_DECODER_IT_FFMPEG / FFPROBE | 同本机验收的实际解码器路径 |

具名批次批准且配置就绪后才显式执行`mvn -s .mvn/settings.xml -gs .mvn/settings.xml -Dtest=AudioVideoModelsLiveIT test`。本次`20260922-av01`已失败结束，这条操作说明不是重跑许可。默认测试不运行LiveIT；缺少授权字段或配置应硬失败，不静默skip。不自动读取`.env`，不记录凭据或原始模型响应。

## 运行前检查

先读[官方协议与候选模型核对](changes/0019-audio-video-provider-eval/provider-configuration.md)。文档列出的模型只是候选，不代表当前账号已开通，也不把文档兼容性当成真实效果。遇到官方文档冲突应保留待验，不能预先删除`response_format`、放宽JSON/事实校验来制造通过。

本批调用额度及使用原密钥的明确授权已获得并用于这次单独执行，失败后已停止。运行前曾私有注入本批密钥并配置三种模型名、两项本机解码器路径。设置授权环境变量只是传递已获批准的额度，不是授权本身，也不能覆盖执行台账里的累计计数或失败即停要求。后续新具名运行的完整预检仍须按实际选帧数F+9计算，额度不够则零请求结束。

2026-09-21只检查当前执行进程的配置是否存在，未读取或输出任何密钥值：上述7个变量全部未设置。该观察仅代表当时进程，不代表全机没有其他配置；不搜索旧凭据文件或复用聊天密钥。此前文本链路的代理/网关成功也不能证明本入口固定HTTPS目标当前可达；本批未进行新网络探针或连通性诊断。

## 结果边界

保留可到达阶段的样本SHA、模型revision、调用数/耗时、原时间和事实贡献；错误只保留安全码。当前真实结果已记录为音频原文证明失败，尚未到金标匹配或视频链，不补造未到达阶段的结果，也不从安全码推断具体模型内容。失败不覆盖为通过。本入口不是索引/检索/Milvus、HTTP授权/引用、摘要、附件、前端或生产验收；上述能力的本机证据保留，实际provider整链质量、同镜像staging及发布门禁仍待执行。
