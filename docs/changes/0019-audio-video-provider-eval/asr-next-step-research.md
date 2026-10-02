# ASR 下一步：公开合同与最小实验边界

核对日期：2026-09-22。状态：RESEARCH_ONLY。仅读取官方文档、公告、公开源仓库与项目记录；没有推理、模型发现、账号或凭据请求，没有改 Adapter、样本、金标或安全规则。

结论：**未找到 TeleSpeechASR 在硅基流动当前转写 API 上受支持的语言、标点、提示或热词参数。** 不能靠猜字段修复本样本。官网价格页已直接列出 Qwen3-ASR 等新候选，但转写接口枚举尚未同步；目前只能选定下一项待授权实验，不能称其已确认可直接替换或质量更好。

## 问题证据，不推测转录正文

[诊断02台账](provider-run.md)记录：诊断01/02转录均为50码点且SHA相同；当前规范化匹配 `AU=false`、`731=true`、`AU731=false`。原摘录与完整转录证明均命中本目标字段内的 `SourceInstructions.INSTRUCTION`。这支持分别检查字母编号与断句/字段边界，但不能确认具体错字、标点，不能由首次LiveIT总错误码倒推其转录身份。完整转录对照不是正常摘录验收，删除口头注入句或把金标补进ASR都不可取。

## 当前模型可调什么

| 项目 | 官方公开依据 | 本项目结论 |
| --- | --- | --- |
| 请求与响应 | 2026-09-22直接打开的转写 API 只列必填 file/model；示例为 multipart，成功响应为 JSON text。官方源仓库 AudioRequest 也只有这两个属性。[API](https://docs.siliconflow.cn/docs/api/audio-transcriptions-post)、[OpenAPI 源文件](https://github.com/siliconflow/siliconcloud/blob/main/openapi.yaml) | 与[现有 Audio Adapter](../../../src/main/java/com/evidence/rag/client/model/OpenAiCompatibleAudioModels.java)的字段一致。 |
| language、prompt、hotwords、punctuation、ITN | 上述 API/AudioRequest 没有列出这些请求选项；也没有逐模型的标点或字母编号保证。[API](https://docs.siliconflow.cn/docs/api/audio-transcriptions-post)、[AudioRequest](https://github.com/siliconflow/siliconcloud/blob/main/openapi.yaml) | **没有可据此实施的已文档支持参数。** 未文档化不等于已实测拒绝，但本轮不靠试探字段，也不能把“OpenAI兼容端点”理解为供应商支持所有同名参数。 |
| 转写模型枚举 | 当前 API 列 TeleSpeechASR 与 SenseVoiceSmall；公开 OpenAPI 文件却只列 SenseVoiceSmall。[API](https://docs.siliconflow.cn/docs/api/audio-transcriptions-post)、[源文件](https://github.com/siliconflow/siliconcloud/blob/main/openapi.yaml) | 不把源文件当成完整实时模型目录，不把遗漏解释为服务器必然拒绝。 |

注意：本地生产客户端只有 model/file，未发送 response_format。不存在“把 response_format 改成 JSON 即可改善转录”的现成开关；不得将聊天模型后处理的改写文本替换为原始ASR authority。

## 替代模型：官网在列不等于端点与质量已验

本次直接打开硅基流动[价格页](https://siliconflow.cn/pricing)的“语音模型”部分，确实列出 `Qwen/Qwen3-ASR-1.7B`、`XingChenAGI/XingChenASR-V3.2`、其 Ultra/带说话人版本及 SenseVoiceSmall；不是仅引用搜索摘要。但价格列表未给这些新型号的 multipart 转写请求示例，不能据此确认账号权限、端点合同或本样本效果。点击 Qwen3-ASR 与 XingChenASR-V3.2 详情均转向登录，本次未继续登录。

**最小候选选一个：`Qwen/Qwen3-ASR-1.7B`。** 选择依据是硅基流动官网在列，且 Qwen 官方仓库明确列出中英文识别支持；这使其值得验证本次中文夹英文字母样本，不构成字母编号准确率保证。Qwen 仓库记录该模型于2026-01-29发布；其中 language 设置是本地推理库功能，服务示例走 audio_url 的 chat/completions，不能据此把这些参数搬进硅基流动 multipart 接口。[硅基流动价格页](https://siliconflow.cn/pricing)、[Qwen 官方仓库](https://github.com/QwenLM/Qwen3-ASR)

SenseVoiceSmall 的日期冲突仍未消失：2025-02-27公告称将于2025-03-06下线，但本次 API 枚举和价格页又列出。不能凭旧公告断言今日已下线，也不能凭当前列表断言已恢复且本账号可调用；本轮不把它自动用作 fallback。[下线公告](https://docs.siliconflow.cn/docs/release-notes/overview)、[当前 API](https://docs.siliconflow.cn/docs/api/audio-transcriptions-post)、[当前价格页](https://siliconflow.cn/pricing)

因此，公开资料能确认“有新ASR候选在官网列出”，**尚不能确认任何替代候选满足现有 multipart 合同、60秒边界和本样本质量**。不再扩成多模型比较，不自动更换供应商或部署本地推理服务。

## 建议负责人选择的下一步

推荐具名范围为：**最多1次 Qwen3-ASR 转写实验，仅更改 model ID，保持当前供应商、标准 multipart model/file、完整原PCM与60秒超时不变；没有 prompt、language、hotwords、标点修正或答案注入。** 这是新的受限协议/质量实验建议，不是调用批准；不自动使用台账剩余额度。

1. 先由负责人确认是否接受“官网列出、接口文档尚未确认”的不确定性；若不接受，先让负责人取得供应商对该型号 `/audio/transcriptions` 合同的确认。本研究没有替负责人联系供应商。
2. 获准后只发1次ASR请求，不顺带文字摘录、视频或模型发现。任何失败均计次并停止，不换模型重试；成功也只做本地只读统计，保存安全码、长度、SHA和原规范化金标布尔值，不保存转录正文。
3. 原样保留不可信口头指令和整段音频，不从已知答案推导热词；不得将“731匹配”单独当成完整编号正确。若后续需要新的原摘录→证明验收，另按具名范围确认，不把本次单ASR成功扩成音频知识库通过。

如果负责人要求先解决标点控制，当前最准确的回答是“尚无已确认的硅基流动请求参数”；需先获得明确供应商合同或另选有该合同的ASR服务，而不是放宽RAG安全校验。生产代码、本机冻结613输入及已有真实失败证据均保持。
