# 0028 sound wire contract

冻结日期 2026-10-03。此处固定本地 Adapter 接受的窄协议，不宣称实际账户/model 已可用；本切只使用合成 loopback。

## SoundModels

POST 固定 root URL 下 `/v1beta/interactions`；`x-goog-api-key`、JSON、单次有界同步请求。每次 describe/draft/verify 独立携带完整 AudioWaveform.wav() 的裸 base64，`input` 为一个 text 数据项和一个 `{type:audio,mime_type:audio/wav,data:…}`；`system_instruction` 固定阶段规则。显式 model；`store:false`、`stream:false`、`background:false`；generation_config 为 `max_output_tokens:8192,thinking_summaries:none,tool_choice:none`。不发 tools、URI、previous_interaction_id、session 或缓存引用。

`response_format` 固定 `{type:text,mime_type:application/json,schema:…}`；schema 均 object/required/additionalProperties:false。describe 输出仅 `recall_text` 字符串，允许空、最多8192 UTF8 bytes。draft 输出仅 `complete` boolean 和 `claims` 字符串数组：complete=true 要1..16条；false必须空；各非空≤1024CP，总≤8192 UTF8 bytes且无重复。verify 请求带完整原问题和全部 indexed claims；输出仅 `complete` boolean 与 `support:[{index:integer,supported:boolean}]`，数量与原claims相等，每个0起index恰出现一次，不接受省略、重复或分数。所有事实字段拒控制字符/无效Unicode；事实中的指令由独立证明策略再次拒绝，不从描述作证。模型不输出或决定时间。

外层只允许 object/id/model/status/created/updated/steps/usage；object 必须 interaction，model 必须与显式请求相同，status 必须 completed，id 必须非空。created/updated如出现须为字符串，usage如出现须为object且只作统计旁项。steps为1..64项：

- 最后恰一个 model_output，字段恰 type/content；content恰一个 `{type:text,text:完整JSON}`，可有空 annotations 数组；不得有错误、工具或其它模态内容。
- 此前可有 thought：只允许 type/signature/summary；signature如有为非空有界字符串；summary只能缺省或空数组（已请求none），不把思考作为事实。
- 此前可有 processing_call（type/id/可选signature）与对应 processing_result（type/call_id/可选signature）；唯一call ID、先call后result、全部配对。它们仅为服务端媒体处理元数据，无text/工具执行内容。
- 其它step，包括 user_input echo、function/google_search/retrieval等工具步骤，均拒绝。不能拼接多个model_output或丢弃未知语义字段。JSON外层及内部均检测重复key和trailing tokens；缺失、截断、所有非completed状态均失败，无自动续轮。

两种Configuration构造都做纯本地校验且公开 revision()。SoundModels revision绑定API/阶段提示/schema/解析策略、root URL、model和显式modelRevision，不含key；deadline10..120000ms、response1024..4MiB、请求2MiB。model必须合法单path ID，root URL不得含额外path/query/fragment，模型版本不能latest/default/unknown；构造0请求。

## SoundEmbeddingModels

独立 `google-v1beta-models-embedContent-sound-text-audio-v1` profile，不改0027旧客户端。POST固定 `/v1beta/models/{model}:embedContent` 和Google认证头。body仅content+embedContentConfig；后者固定显式 outputDimensionality 和 autoTruncate:false，无taskType/前缀/title。

embedAudio是唯一inlineData part，audio/wav、完整canonical44-byte header/16kHz mono s16le WAV、1..480000 samples。embedText是唯一text part，原非空完整问题≤4096UTF8 bytes，不截断/改写。两路分开请求，同一显式model/版本/维度；profile还绑定decoderRevision及两种输入策略。真实配置128..3072维；2..127仅显式允许的字面loopback HTTP合成端点。response仅embedding与可选usageMetadata，embedding恰values，有限非零float32且恰D维；shape拒绝。deadline/response/request预算沿SoundModels。

## 官方依据及范围

2026-10-03查阅：[音频理解](https://ai.google.dev/gemini-api/docs/audio)、[结构化输出](https://ai.google.dev/gemini-api/docs/structured-output)、[Interactions参考](https://ai.google.dev/api/interactions-api)、[协议迁移](https://ai.google.dev/gemini-api/docs/interactions-breaking-changes-may-2026)、[embedding REST](https://ai.google.dev/api/embeddings)、[Embedding 2专页](https://ai.google.dev/gemini-api/docs/models/gemini-embedding-2)。当前steps替代旧outputs；Api-Revision已不作为可用锁定方式。完整严格JSON不保证事实支持或时间精度；本切时间仅由服务器samples/windows给出。上述窄接受白名单有意比供应商总schema小；真实模型响应、质量与成本仍未验证。
