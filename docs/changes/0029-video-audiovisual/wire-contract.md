# 0029 原视频音画模型 wire contract

状态：按已冻结 interfaces/spec 实现的本地严格子集。官方文档查阅日期：2026-10-03。仅合成 loopback 测试；未调用真实模型，不声明某个未配置模型已通过这些组合输入。

## 理解：独立 draft / verify

固定 `POST /v1beta/interactions`，根 endpoint 不含路径；`x-goog-api-key`，JSON UTF-8，不使用 Bearer。模型名由显式配置给出，不猜默认值。每次请求完整重发对应原材料与完整原问题，不复用前次 interaction、文件引用或缓存。

顶层恰为 `model`、`store:false`、`stream:false`、`background:false`、`system_instruction`、`input`、`generation_config`、`response_format`。`generation_config` 固定 `max_output_tokens:8192`、`thinking_summaries:"none"`、`tool_choice:"none"`；无工具。

- 视频材料：`{type:"video",mime_type:"video/mp4",data:<完整 canonical base64>,processing:{type:"static",fps:1}}`。服务端连续实际 MP4 已移除音轨；不发送 provider offset，不用图片列表替代视频。
- 声音材料：`{type:"audio",mime_type:"audio/wav",data:<完整 canonical WAV base64>}`。包含窗口所有实际样本、静音和尾部。
- 最后一个输入：`{type:"text",text:<JSON 字符串>}`。其中含原问题、模式、epoch 原 PTS/timebase/L 的 canonical decimal strings、窗口起止 ticks，以及各真实材料的偏移/hash。视频 first/end local tick 映射至父窗口；音频全局 start/end sample、sample rate 和 `first_local_tick=startSample*(L/16000)-window.startTick` 明确保留。verify 另带服务端 ID、原 claim 和 requirement。

VISUAL 只发视频；AUDIO 只发 WAV；JOINT 两者齐备。任何选定模式缺材料均在 dispatch 前拒绝。源视频可没有音轨，不能据此排除 VISUAL 资格。

`response_format` 为 `{type:"text",mime_type:"application/json",schema:<strict object schema>}`。所有对象 `additionalProperties:false`；本地再次验证完整 shape、类型、范围和 exact ID set，不只依赖 provider 的结构化输出承诺。

Draft JSON 恰为 `complete:boolean, claims:[{text:string,requirement:"VISUAL"|"AUDIO"|"JOINT"}]`。完整成功 1..16 条；每条 ≤1024 code points，总 ≤8192 UTF-8 bytes；失败必须空列表。模型不产生事实 ID。Service 按完整 question SHA、ordinal、text、requirement 生成稳定 ID。

Verify JSON 恰为 `complete:boolean,support:[{id:string,supported:boolean,visual_contribution:boolean,audio_contribution:boolean}]`，每个输入 ID 恰出现一次，不能多、少、重复或替换。Service 依模式/requirement 校验贡献：VISUAL 只视觉，AUDIO 只声音，JOINT fact 两者均须贡献；JOINT 整答两模态各至少一次，可以用独立属性的两条事实共同完成。关系问题不能用独立观察冒充关系事实；独立 verify prompt 要求这种情况 complete=false。布尔结果不是确定性事实真值认证。

## Interactions response 严格子集

顶层必需 `id/model/status/steps`；仅另允许 `object/created/updated/usage`。`object` 可缺，存在时必须 `interaction`；status 必须 completed，model 必须当前配置精确值。id/可选时间字段为有界非空字符串，usage 若存在为对象，仅作旁项，不能形成事实或日志证据。

steps 为 1..64 项。唯一最后项必须 `{type:"model_output",content:[{type:"text",text:<上述 JSON>,annotations?:[]}]}`。前置项仅接受：

- `thought`，可选 signature 和空 summary；任何非空语义 summary 拒绝。
- `processing_call`（唯一 id，可选 signature）与后续 `processing_result`（call_id，可选 signature）完整配对；未知字段、悬空或重复拒绝。

工具、其他语义步骤、非完成状态、多个输出、未知 envelope/JSON 字段均拒绝。该 allowlist 是应用所选严格子集；不声称穷尽 provider 所有合法响应。

## Embedding：一个空间，两条独立 collection

固定 `POST /v1beta/models/{显式模型}:embedContent`，同样 Google header。每次 body 仅 `content` 与 `embedContentConfig`。`content.parts` 恰一个：完整 `text`，或 `{inlineData:{mimeType:"video/mp4"|"audio/wav",data:<完整裸 base64>}}`。不加 question 前缀，不发送 taskType；不发送 audioTrackExtraction 或视频 offset。输入视频已无音轨，音频另独立 embed。

`embedContentConfig` 恰 `outputDimensionality:<pinned dimensions>,autoTruncate:false`。三种输入固定同 model/endpoint/维度/profile；视觉与原声写入两个不同且前缀为 `java_video_av_` 的 collection，与旧集合分离。返回顶层只允许 `embedding` 和可选对象 `usageMetadata`；embedding 必须恰 `values`，指定维数、数值有限、float32 可表达且整体非零。拒绝 shape/soft tensor、错误维度、字符串坐标、未知字段。

## 预算、身份与现实限制

完整 question ≤4096 UTF-8 bytes；实际 MP4 单窗口 ≤8 MiB、连续窗口 ≤30 秒；canonical WAV 最多 480000 样本。实际序列化 JSON 在 dispatch 前受 14 MiB 硬限约束，不能通过裁剪媒体或问题绕过。HTTP deadline 10..120000ms，response 1024..4194304 bytes；失败不隐式重试。理解与 embedding 配置 revision 均公开纯计算，绑定协议/材料策略/endpoint/model/显式版本，embedding 再绑定 decoder/维度，排除 key 和运行预算。未知/latest/default revision 不接受。低维 2..127 仅合成 literal loopback HTTP；真实 provider 可用维度/模型兼容性仍须按供应商能力验收。

Interactions static 1 fps 是实际 provider 采样限制，不代表每帧被模型充分理解；运动短事件/精确同步不确定时应拒答。服务端完整帧/像素和 PCM 产物证据验证保证发送的材料身份与时间轴，并不替代模型对事实的判断。结构化输出约束 JSON 形状，不保证事实准确或精确到单个采样点；来源只引用服务端窗口，不伪造 provider 细粒度时间戳。

## 官方依据

- [Interactions API](https://ai.google.dev/gemini-api/docs/interactions)：stateless/store、inline media、processing 与响应 steps。
- [Video understanding](https://ai.google.dev/gemini-api/docs/video-understanding)：实际视频输入与采样限制。
- [Audio understanding](https://ai.google.dev/gemini-api/docs/audio)：原始音频输入。
- [Structured outputs](https://ai.google.dev/gemini-api/docs/structured-output)：JSON schema 约束及局限。
- [Embeddings](https://ai.google.dev/gemini-api/docs/embeddings) 与 [REST embedContent](https://ai.google.dev/api/embeddings)：多模态相同 embedding 空间、inlineData/text、embedContentConfig 与返回 values。

本记录不包含凭据，不推断真实供应商模型已支持所选完整请求组合。loopback 只证明应用协议、字节完整性、身份、拒绝行为和预算。
