# 语音问题合同

- VQ-01：新增显式默认关闭的`rag.voice-questions.enabled`；依赖现有audio/ingestion/answers及development/test、字面loopback装配，不依赖query_attachments、visual、video或图像ranking配置。启用时GET /v1/config声明`voice_questions`，不启动模型请求。无新ASR endpoint/key设置。
- VQ-02：精确POST `/v1/voice-questions`，认证及现有Origin保护，无query，仅JSON对象`{filename,media_type,content_base64}`。仅现有AUDIO MIME WAV/MP3/FLAC/OGG/MP4/WebM（M4A为audio/mp4），一个非空原文件≤20MiB；文件名无路径/控制符，canonical base64，拒绝未知/重复/额外JSON及坏UTF8。实际音频类型由既有decoder核实，视频不能伪装音频。整个JSON≤28MiB。
- VQ-03：复用有界异步媒体JSON接收，接收默认30秒（10–60000ms）、处理默认120秒（10–120000ms），独立最多2在途（1–8配置）。接收/处理超时、中断、profile变化均失败，无自动重试；取消只停止等待，不保证撤回已发送上游请求。不能放宽普通JSON或原附件/问答预算。
- VQ-04：完整解码、按既有采样分段ASR，逐ordinal用单个LF连接每段原始text，包含空段和尾段；不trim、不改数字/词语、不取摘要或截尾。识别无语音沿稳定`voice_no_speech`错误；完整拼接超过UTF8 65536字节显式`voice_transcript_too_long`失败，不返回部分文字。语音可能产生多次分段ASR调用，不宣称一次请求或VAD能力。
- VQ-05：成功200只返回8字段：`transcript`、`transcript_sha256`、`source_sha256`、`decoder_revision`、`model_revision`、`compiler_revision`、`duration_ms`、`policy_revision`。policy固定`java-voice-question-v1`，duration沿现有1–600000ms；原文件与完整转录SHA绑定，版本非空≤200CP。结果与command的toString脱敏，原字节/识别文字不写SQLite、任务、trace、日志或投影。原ASR失败及真实质量问题不改写。
- VQ-06：服务异常只返回稳定安全错误：无语音422 voice_no_speech；转录过长422 voice_transcript_too_long；无效音频422 voice_audio_invalid；profile变化409 voice_profile_changed；处理超时408 voice_question_timeout（沿既有TIMEOUT映射）；中断400 voice_question_interrupted；其他解析/上游失败503 voice_question_unavailable。HTTP接收错误沿共享媒体transport既有安全错误，不输出原始异常。
- VQ-07：前端0014新增独立语音Session和单文件控件，显式转录→可编辑完整文字→明确“用作问题”。确认前不覆盖手工问题、不提交答案、不把语音自动加入附件；确认后保留当前scope/mode并沿旧问答。确认文字仍满足非空、合法字符与UTF8 4096字节，超长识别可手工编辑，不能自动截短。
- VQ-08：文件/身份/范围/模式/问题变化、离开问答和pagehide使未确认结果失效；取消读取不得发请求，迟到转录不得改问题；转录与问答互斥、防重复，写请求不重试。原附件和来源生命周期保持。
- VQ-09：两个前端代理仅增加精确静态Module和POST，独立28MiB/180秒/2在途；普通限额和Host/Origin/Cookie/身份边界不变。前端核对完整原字节SHA和完整transcript SHA后展示纯文本。

必要验收为真实合成WAV完整解码及loopback生产ASR协议→编辑确认→指定范围旧问答→库内来源，核对没有入库/trace转录副作用；另验无声/过长、非法请求、取消/迟到、能力关闭和媒体transport原回归。真实中文ASR、provider质量、浏览器和生产另验。
