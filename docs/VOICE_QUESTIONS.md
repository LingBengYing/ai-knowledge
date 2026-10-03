# 语音提问

用户可以选择一个音频文件，点击“转成文字”，核对或编辑完整识别文字，再点击“用作问题（替换当前文字）”。这一步只填入问题框；用户随后照常提问，保留已选资料范围和证据模式，答案及引用来自知识库。转录前不要求已有文字问题，也不会把音频自动加入检索附件。

该功能复用现有音频解码及ASR配置，默认关闭。启用需要已有 `audio`、`ingestion`、`answers`，运行环境为 development/test 且 server.address 为字面 `127.0.0.1` 或 `::1`；不要求 visual、video 或 query_attachments。配置如下，ASR端点、模型和私有凭据仍由已有音频配置提供：

| 设置 | 默认值 | 范围 |
| --- | ---: | --- |
| RAG_VOICE_QUESTIONS_ENABLED | false | 显式启用 |
| RAG_VOICE_QUESTIONS_RECEIVE_TIMEOUT_MS | 30000 | 10–60000 ms |
| RAG_VOICE_QUESTIONS_PROCESSING_TIMEOUT_MS | 120000 | 10–120000 ms |
| RAG_VOICE_QUESTIONS_MAX_CONCURRENT | 2 | 1–8 |

`GET /v1/config` 在启用后声明 `voice_questions`。认证的精确 `POST /v1/voice-questions` 不接受query，JSON只含 `filename`、`media_type`、`content_base64` 三字段。支持现有 WAV、MP3、FLAC、OGG、MP4/M4A、WebM 音频MIME；单文件1–20MiB、JSON最多28MiB。实际音频流由decoder核实；重复/未知字段、坏UTF8、非canonical base64及路径文件名均拒绝。

成功返回200及八字段：`transcript`、`transcript_sha256`、`source_sha256`、`decoder_revision`、`model_revision`、`compiler_revision`、`duration_ms`、`policy_revision`。policy固定 `java-voice-question-v1`。每段ASR原文字按ordinal以一个LF连接，保留空段、首尾空白、数字及尾段，不生成摘要或截尾。整个预览最多65536 UTF8字节；超过明确失败。用作问题时仍受既有4096 UTF8字节及合法字符约束，可以手工编辑超长预览。

转录准备不读知识库、不入库、不创建持久任务或答案trace；原字节和识别文字不写数据库、审计、投影或日志。确认后的问题才进入已有完整scope、ACL、原始证据证明和服务器来源校验。转录与问答互斥；换文件、问题、身份、范围、模式或离页清除未确认结果。取消停止等待，不能保证撤回已经发送的ASR请求；请求失败不自动重试。长音频可能产生多个分段ASR请求。

无语音/过长/非法音频返回422，profile变化409，处理超时沿既有映射返回408，中断400，其他处理失败503；错误只含稳定安全码。接收错误沿共享媒体transport原合同。两个Node代理为语音独立保留28MiB、180秒和2在途，普通JSON、原问答及附件限制保持。

本机验收及未验范围见 [0025验证](changes/0025-voice-questions/verification.md)，网页行为见前端0014工件。合成音频及loopback生产协议不能认证真实中文ASR、云模型质量或生产效果；当前部署版本为20261003-scanned-pdf，语音与摘要标签尚待发布，页面由用户验收。
