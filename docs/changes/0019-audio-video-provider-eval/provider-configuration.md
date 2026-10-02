# 0019：硅基流动模型配置核对

核对日期：2026-09-21。状态：DOCUMENT_RESEARCH_ONLY / PROVIDER_NOT_RUN。本文只读取当前源码与供应商公开文档，没有调用推理、`/models` 或账户接口，没有读取/配置凭据；不是新的云调用授权。使用 research 技能以官方一手资料筛选候选，未修改生产协议或本机已冻结测试。

2026-09-22实测补充：下述组合已用于一次获批LiveIT，ASR和DeepSeek摘录各1次返回，随后的原文证据校验失败，安全码`eval_audio_not_grounded`。这仅证明两个客户端在该次请求中的协议解析可达，不证明ASR内容、严格摘录或端到端质量通过；视觉模型0请求，仍未验证。实际2/12后停止、剩余10次不自动使用，见[执行台账](provider-run.md)。下文NOT_RUN及轮换前置是9/21研究时的历史状态，负责人对本批使用原密钥的明确授权及本次真实结果以台账为准。

## 单一待验组合

下面是下一次具名评测的候选配置，不是已验证可用的默认配置。只固定一组，不追加模型试跑或失败自动切换。

| LiveIT 环境变量 | 候选值 | 选择依据与边界 |
| --- | --- | --- |
| `RAG_AUDIO_VIDEO_IT_ASR_MODEL` | `TeleAI/TeleSpeechASR` | 官方转写 API 的 model 枚举明确包含此 ID；请求是 multipart 的 model/file，成功响应含字符串 text。文档层协议吻合，16 kHz 单声道 PCM WAV 的实际接受与中文识别质量待验。[转写 API](https://docs.siliconflow.cn/docs/api/audio-transcriptions-post) |
| `RAG_AUDIO_VIDEO_IT_VISION_MODEL` | `Qwen/Qwen3-VL-30B-A3B-Instruct` | 官方能力列表给出此精确 ID，官方 Qwen3-VL 发布说明确认其 Instruct 视觉版本。选这一候选而不是另加 Thinking 或更大模型，是控制首次评测变量的工程选择，不是速度/成本最优结论；JSON 请求兼容性有下述冲突，尚未确认。[模型 ID 列表](https://docs.siliconflow.cn/docs/userguide/guides/fim)、[视觉模型发布说明](https://siliconflow.cn/news/wmtgf1jruk58yky7h1q7j66q) |
| `RAG_AUDIO_VIDEO_IT_GENERATION_MODEL` | `deepseek-ai/DeepSeek-V3` | 官方能力列表仍列此精确 ID；项目已有同客户端 JSON 摘录成功的历史证据，优先复用该候选，不重新扩展文本选型。但 JSON 指南英文版本排除 V3，且与其支持列表自相矛盾，兼容性仍为 NOT_RUN。历史成功不认证今日账号可用性、延迟或当前样本。[官方 ID 列表](https://docs.siliconflow.cn/docs/userguide/guides/fim)、[JSON 指南](https://docs.siliconflow.cn/docs/userguide/guides/json-mode)、[0007 实测记录](../0007-text-answers/mainline-live.md) |

配置字段和运行方式以 [AUDIO_VIDEO_PROVIDER_EVAL](../../AUDIO_VIDEO_PROVIDER_EVAL.md) 为准。仅设置模型名不能启动评测；必须另有负责人明确的新调用授权、轮换后私有环境密钥、两个解码器路径及完整预算预检。本文不写入密钥，不提供含密钥的命令。LiveIT 固定端点仍为 `https://api.siliconflow.cn/v1`。

## 与现有客户端逐字段对照

源码事实以当前三个 Adapter 和显式 LiveIT 为准：

- [Audio Adapter](../../../src/main/java/com/evidence/rag/client/model/OpenAiCompatibleAudioModels.java) 发送 `POST audio/transcriptions`，multipart **只有 model 和 file**；file 为 `chunk.wav`、`audio/wav`，验证为 16 kHz/单声道/16-bit PCM。没有发送 `response_format`、language 或预期转录；直接读取 JSON 字符串字段 text。官方示例同样只传 model/file，并展示 JSON text 响应，因此不是依赖一个未设置的 `response_format=json`。[官方转写合同](https://docs.siliconflow.cn/docs/api/audio-transcriptions-post)
- [Vision Adapter](../../../src/main/java/com/evidence/rag/client/model/OpenAiCompatibleVisionModels.java) 发送 `chat/completions`，system + user(text JSON、原图 `data:image/png;base64,...`)、`image_url.detail=high`，以及 `response_format={type:json_object}`、`stream=false`、`n=1`、`max_tokens=4096`。官方多模态指南支持 image_url 的 URL/base64 与 high detail，通用入口一致；指南不能单独证明该具体模型接受 JSON 模式。[图片输入指南](https://docs.siliconflow.cn/docs/userguide/capabilities/multimodal-vision)
- [Text Adapter](../../../src/main/java/com/evidence/rag/client/model/OpenAiCompatibleModels.java) 发送相同 chat 入口，system + user JSON、`response_format={type:json_object}`、`stream=false`、`n=1`、`max_tokens=2048`。system 明确要求 JSON。官方 JSON 指南给出这一参数形式，但对 DeepSeek-V3 的支持声明有下述冲突，不能称本候选已完全文档兼容；即使返回合法 JSON，也不代表摘录满足原文/事实合同。[JSON 指南](https://docs.siliconflow.cn/docs/userguide/guides/json-mode)
- 两个 chat Adapter 都不发送 `enable_thinking`、`thinking_budget` 或采样参数；只接受一个 index=0、`finish_reason=stop` 的 assistant JSON content，并继续做字段与证据校验。不能静默去掉 JSON 模式、容忍截断、改写事实或放宽服务端证明来制造成功。
- [AudioVideoModelsLiveIT](../../../src/test/java/com/evidence/rag/service/AudioVideoModelsLiveIT.java) 只调用转写/视觉/摘录，不调用 embedding/rerank；它构造文本客户端时复用 generation Endpoint，并不证明该模型支持其他用途。每次请求 60 秒，完整预算 F+9，失败停止；当前固定样本最坏 12 次，实际执行仍重新完整解码核算。

## 已发现的文档冲突与取舍

1. **VLM 与 DeepSeek-V3 的 JSON 兼容性都不能凭文档确认。** 2026-09-21 复查同一 JSON 指南 URL：中文检索返回的索引全文排除 VL；直接打开返回的英文正文第 1 节却排除 DeepSeek R1/V3；两者第 3 节又泛称语言模型都支持。中文索引内容不是已确认的当前中文页面，可能存在语言/版本/索引差异，未取得能稳定直接打开同一中文内容的独立 URL，不猜测哪版代表服务器规则。chat API 的视觉分支也列 response_format/json_object，但并未消除具体模型的不确定性。保留现有严格请求，授权后各自第一次实际调用验证；若拒绝则终止并记录，不额外诊断或改成提示词-only。[同一 JSON 指南 URL](https://docs.siliconflow.cn/docs/userguide/guides/json-mode)、[chat API](https://docs.siliconflow.cn/docs/api/chat-completions-post)
2. **旧视觉示例不能直接用。** 官方多模态示例仍使用 Qwen2.5-VL-72B，但发布公告已记录该 72B/32B 型号于 2026-04-29 下线，Pro 7B 型号于 2026-03-17 下线。因此本次不选这些旧示例 ID。[多模态示例](https://docs.siliconflow.cn/docs/userguide/capabilities/multimodal-vision)、[下线公告](https://docs.siliconflow.cn/docs/release-notes/overview)
3. **ASR 示例也有历史冲突。** 转写 API 仍列 SenseVoiceSmall，公告却将其列入 2025-03-06 下线清单。本次选择同一 API 枚举中的 TeleSpeechASR，不能由“未发现它的下线公告”推定账号当前可调用。[转写 API](https://docs.siliconflow.cn/docs/api/audio-transcriptions-post)、[历史公告](https://docs.siliconflow.cn/docs/release-notes/overview)
4. **不能把 max_tokens 当作思考与费用总上限。** 当前 chat API 将 max_tokens 解释为不含思维链的最终输出上限；推理指南另有 thinking_budget，并说明达到输出上限会返回 length。当前客户端没有思考模式控制，所以不擅自替换为 Thinking/混合模型，也不假设未传字段等于强制关闭推理；候选的实际响应/耗时仍待验，原 60 秒硬边界保持。[chat API](https://docs.siliconflow.cn/docs/api/chat-completions-post)、[推理参数指南](https://docs.siliconflow.cn/docs/userguide/capabilities/reasoning)

## 尚未解决的执行条件与验收边界

- 2026-09-22已获得本批最多12次合成语料云请求授权，负责人也明确确认本批使用其原密钥，实际次数/结果见[执行台账](provider-run.md)。旧文本余额不复用，密钥仅私有注入临时进程。以上官方文档核对保留为2026-09-21研究事实，不是新执行结果。
- 三个模型的账号权限、当前服务可用性、最新费用与额度没有核验。公开模型广场本次读取转到登录，未继续登录或读取账户数据；也没有以未登录页面/能力示例作为账号可用证明。
- ASR WAV 接受/中文数字、设备码及时间识别、VLM 与 DeepSeek-V3 JSON 兼容、严格摘录和同组双事实证明，全部仍为 NOT_RUN。Qwen/DeepSeek 历史结果不替代这些结果。
- 这次研究只移除模型名与协议检查的部分不确定性，不新增云证据，不认证图片全量质量、Milvus/检索/HTTP 整链、摘要、网页、staging 或生产发布。授权运行有失败就记录实际阶段并停下，不为用完额度继续调用。
