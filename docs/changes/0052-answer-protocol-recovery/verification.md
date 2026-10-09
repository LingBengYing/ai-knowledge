# 验证与交接

## 当前最终方向（用户要求去除客户端token上限）

用户在第三次影子验证期间明确要求删除token上限。最终代码已移除OpenAiCompatibleModels所有max_tokens/max_completion_tokens字段及限额常量/helper参数；通用、事实、主题、主树知识摘录、综合和核验均使用服务商默认输出预算。服务商自身硬上限不能由客户端取消；finish_reason=length/JSON/原文与引用校验不变。

- 无限额回归先红：16项4个预期失败，三阶段真实loopback收到自设字段就length，未发送字段才返回完整合法结果；日志`/private/tmp/lighthouse-default-tokens-0052-red.log`。
- 最终隔离源513项完整相关回归通过，失败/错误/跳过0，package成功；日志`/private/tmp/lighthouse-default-tokens-0052-green.log`。JAR SHA `67592abb0c91ff5259686f5898bc2b49d77f14646ae258da2bcfdb641c6194fc`。该最终包尚未上传/部署或真实请求验收。
- 当前真实模型累计14/20，三次影子链均已停止；最后一次已经越过截断和主题锚点，完成摘录/综合/核验，但最终unsupported_synthesis、0引用。不得把该最新阻塞再归因token截断，详见provider-run。剩余6未发、不自动原样重发。
- 保留0050未发布主树逻辑，最终主仓库557项完整直接相关回归通过（Failures/Errors/Skipped=0），日志`/private/tmp/lighthouse-main-default-tokens-0052.log`。包括主题、旧字段证明、知识元数据、引用依赖和标准请求字段缺省断言；不是完整发布门禁。没有Git提交/推送，原线上仍0051、配置v6、旧资料及索引未变。后端发布脚本只做了本地草案与bash -n，没有生成风险批准文件或执行发布。
- 旧8192中间版本513项通过后启动的完整发布verify，因用户要求改变实现而主动终止，exit143，日志`/private/tmp/lighthouse-release-0052-verify.log`。该运行不算通过/失败门禁；既有分支覆盖率75.64%及PDF启动风险尚未关闭，旧一次性例外不自动沿用。
- 一次受限环境测试因loopback Socket Operation not permitted而失败；改用获准本机网络环境重新执行，未将工具环境错误伪称产品失败。旧记录保留。

## 以下为诊断阶段历史（不是当前状态）

最终更新：2026-10-08 22:27 +08。日志旁路异常隔离第9项先红，随后**46项完整相关回归通过**，Failures/Errors/Skipped为0；最终日志`/private/tmp/lighthouse-protocol-0052-final.log`，构建成功。最终本地JAR SHA为`8b2eb3bd56be352ace3ec2f1e320209acad286e14510c6f61359eb8716a32889`。下方16c9bb是已上传但未运行的前一观测包，不能混用；后续真实实验先上传最终包并核对SHA。外发范围确认尚未补齐，本轮仍0/20。

## 已完成的本地验证

- 实际0051 JAR零网络输入实验：同来源两条proof与独立context合法，重复proof ID负控正确拒绝；排除普遍引用ID冲突，不据此推断历史真实响应内容。
- 新安全观测回归先红：`TextModelProtocolLogTest`首6项在原实现均因没有细分终态日志失败，Errors0。日志`/private/tmp/lighthouse-protocol-0052-red.log`。
- 实现后8项新回归，连同隔离源实际存在的完整`OpenAiCompatibleModelsTest`、`OpenAiCompatibleTopicSynthesisModelsTest`、`ModelHttpAttemptLogTest`、`KnowledgeTopicAnswerServiceTest`共45项通过，Failures/Errors/Skipped均0。最终增加日志故障隔离后为46项。日志`/private/tmp/lighthouse-protocol-0052-green.log`与`/private/tmp/lighthouse-protocol-0052-final.log`。命令选择器另列的`OpenAiCompatibleSynthesisModelsTest`与`KnowledgeAnswerServiceTest`只在主树存在、隔离源未匹配，不能计为执行；以上数量按实际Surefire记录，不按选择器推定。
- 新测试覆盖extract/synthesize/verify、finish_length、quote_not_exact、JSON/ID/角色/工具字段错误、输入错误、1MiB发送前拒绝、固定枚举与隐私哨兵。只使用loopback替身，不触发云调用。
- 全隔离源Spotless格式化后package成功。构建输出`/private/tmp/lighthouse-protocol-0052.AnC6dq/`。没有降低POM门槛，没有重新声称完整覆盖率通过；旧75.64%历史缺口仍在。

## 改动范围

当前隔离源码只改`OpenAiCompatibleModels.java`并新增`TextModelProtocolLogTest.java`。在三个原方法边界增加私有固定枚举终态日志，复用旧响应包络校验；原Failure重抛。只保存固定operation/phase/reason/finish_reason与数值，不保存模型正文、引用ID、问题、密钥、URL或Throwable。

没有改PROMPT、max_tokens、模型选择、客户端revision、检索/grounding或来源契约。仅补观测，不把它当成已修复实际问答。

## 运行工件与计数

- 服务端私密目录：`/srv/ai-knowledge/diagnostics/lighthouse-recovery-0052/`，不属于当前release或应用启动配置。
- `diagnostic.jar` SHA256：`16c9bb6ad74abb30f4afcaa132a06e2450786e3ff9062b7662480ceefbf897b7`；本地已知摘要，执行前远端校验尚未运行。
- `ledger.json` 是独立`lighthouse-protocol-recovery-twenty-20261008`新账本，20次总上限，当前0。旧账本不写入、不复用。
- `shadow-question.py`仅复制私密资料及一致SQLite快照，最终副本无待处理任务才启动127.0.0.1:18088；显式关闭清理，单次POST灯塔，原模型/向量索引查询链，上界6次，独占日志计数，finally停止自身进程组。失败构建不能原样重发已消耗模型的同一实验。
- 独立限定审查指出的cleanup及快照TOCTOU两项已在运行前修正；不以原live预检代替最终副本检查。原argv的server/data/cleanup覆盖项先过滤，避免Spring重复参数合并。

## 当前阻断

真实运行在exec启动前被auto-review拒绝：认为20次修复授权缺少具体资料范围与外部目的地确认。已明确告知用户这是向外部模型发送线上召回片段的风险，并请求授权当前owner可访问资料片段/灯塔与Project测试 → api.siliconflow.cn嵌入重排、api.deepseek.com摘录综合核验；或由用户选择仅固定合成资料。

没有绕过拒绝，没有启动影子服务，没有新增云调用。真实根因仍待这一小步；前端、当前模型v6、原服务、资料及索引未变。不要用0/20或45项替身测试宣称线上已修好。
