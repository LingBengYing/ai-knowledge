# 0014 视频授权问答与来源验证

状态：公开后端主链已于2026-09-20 12:14:03通过本机最终门禁。完整0014和多模态/生产目标仍为IMPLEMENTATION，不能将本切结果写成完整交付。

## 本轮正常闭环

合成视频→原上传/持久任务→真实FFmpeg解码与v10封存→独立索引进程完整发布→显式visual/transcript/joint问答→同一真实group逐事实证明→完整scope复验与v11不可变trace→typed来源、原帧及原视频200/206/416。

Spring职责保持：Controller/mapper处理HTTP，AnswerService复用唯一准入/预算/生命周期，VideoAnswerProposalService隐藏视频检索与真实组选取，EvidenceService处理完整scope与最终提交/回读，Repository管理authority，Model保存证据不变量。没有新权限体系、通用任务框架或第二个问答线程池。见[完整接口](../../VIDEO_ANSWERS.md)。

候选只读取召回文本；选组后读取单个原帧BLOB与完整ordinal转录。caption不进入证明；内部span句柄映射为真实publication physical ID。联合答案须同组覆盖全部事实、两个模态各自贡献；源回读重新物化parent/manifest/frame/span/quote及完整范围。组时间为真实交集，ASR引用另给整段时间，不伪造scene或词级对齐。

## 执行记录

所有时间为2026-09-20 Asia/Shanghai，隔离构建目录 `/private/tmp/java-video-answers.lzzUFd`。实际JBR21.0.8+9-b1038.68，Maven3.9.9；本机协议替身，无云请求。

| 记录 | 实际结果与处理 |
| --- | --- |
| 11:37:24 `input-red.log` | 6项、2失败/3错误：缺少窄输入/证明适配的真实RED；后续补两个输入不变量回归 |
| 11:41:21 `answers-red.log` | 新Repository测试误用不存在的store.update，编译失败；改用已有execute，不算产品RED |
| 11:42:20 `answers-red-2.log` | 14项、4失败/10错误：缺Repository/编排/入口/mapper/Range行为的真实RED |
| 11:51:46 `answers-green.log` | 142项、2错误：修复生产group缺模态时不可变List.contains(null)；新测试不得依赖哈希排序代表帧ordinal，改按已知caption选帧并保留断言 |
| 11:53:22 `runtime-red-proof-green.log` | 新Entry测试误用Jackson2类型，改已有Jackson3 JsonMapper；未新增依赖，不算产品失败 |
| 11:54:59 `answers-green-2.log` | 152项仅新增runtime能力断言失败，其余151通过；随后加video+answers条件下的两个能力声明 |
| 11:55:37 `native.log` | 新HTTP夹具发送索引请求体 `{}`，不符既有“无请求体”合同；改夹具，不改API |
| 11:56:49 `native-2.log` | 新VideoAnswersNativeIT通过：真实上传→索引→三模式问答→typed引用/原帧/原视频Range及完整scope撤权 |
| 11:57:26 `format.log` | 435个Java文件格式化，未改格式配置 |
| 11:59:56 `verify-1.log` | 完整1261测试无失败/错误/跳过，435格式通过；行11049/11926，分支5855/7408=79.0362%，未过原80%门禁，保留失败并补现有视频接口合同测试 |
| 12:06:31 `native-final.log` | 最后生产源码的5项native验收全部通过：VideoAnswers1、VideoAssessment1、VideoPublication2、AudioAnswers1；单独归档4份XML，不混入默认数量 |
| 12:09:37 `contracts-green.log` | 补测53项、52通过；新夹具把“写诗”误当既有语法不支持，实际是证据不足。换用旧QuestionPlanningTest已明确拒绝的共享条件问题，保留unsupported_question及两模态零证明调用断言；未改生产逻辑 |
| 12:11:34 `contracts-green-2.log` | 完整5个新增合同测试文件53项全部通过，不跳过、不放宽断言 |
| 12:14:03 `verify-2.log` / `verify.log` | 最后源码完整clean verify：1299 Java、0失败/错误/跳过，437文件Spotless，行11088/11926=92.973336%、分支5976/7408=80.669546%，原双80%门禁通过 |

Node原73项全部通过、0跳过，`node-green.log`已归档。最后生产修改后native5全部通过；之后仅新增/更正测试和文档，`format-final.log`/`format-contract.log`未改生产文件。默认163份XML与native4份XML分开归档，native不计入1299；早前native10/内部native1历史次数不叠加到本轮5项。

## 冻结与可复核产物

- [answers-source-manifest.json](answers-source-manifest.json)：461个源码/资源/构建输入与隔离构建逐文件SHA相同；绑定JDK、覆盖率、JAR及23项日志/XML等证据摘要。
- [answers-test-cases.json](answers-test-cases.json)：1299条默认Surefire精确className/name及多重性，原[assessment基线](assessment-test-cases.json)1216条逐条保留；没有遗失旧输入，9个必要migration测试变更明确列出。
- 本机native合成视频覆盖黑/蓝真实帧、原PNG字节及像素、ASR分段、公开上传与持久任务、完整索引发布、visual/transcript/joint三模式、typed来源/原字节/单Range、显式空范围零模型调用，以及撤销非候选所选资料后所有旧来源失效。旧音频HTTP回放同时回归。
- `git diff --check`、现有tracked/history敏感信息扫描与补充untracked扫描均通过；不输出或保存真实凭据。完整独立制品核对结果记于REVIEW。

新native夹具还在执行前经独立审查修正文字上传MIME为现有application/octet-stream，并使用单一ImageReader生命周期读取PNG；未放宽生产合同。前置空转录的CP拼接与旧证明入口有差异，经具名回归修正Repository及v11触发器，以 `""`、非空段、`""` 验证精确摘录。

## 旧行为和迁移

v11随真实消费方新增video_trace_proofs/facts/evidence和全局引用seal。旧v2–v10迁移方法体与上一冻结构建逐字一致，原始schema迁移顺序保持；新 trace 是增量，不回写旧数据。

旧测试仅9个migration夹具必要适配：Ingestion、Indexing、Evidence、ImageRegion、VisualLibrary、DocumentLifecycle、AudioLibrary、AudioTrace、VideoLibrary；20个“当前版本”断言10→11，VideoLibrary的旧库恢复先撤v11。测试身份/历史版本断言与业务预期保留，新增VideoTraceMigrationTest覆盖v10→v11。没有删除、跳过、降阈值或放宽失败测试。

## 独立审查与边界

限定独立审查覆盖新proposal/final source完整scope与group身份，以及AnswerService/config/HTTP/共用Range；native夹具MIME问题已修正。主代理复核v11真实FK/全局seal，并修正上述空段CP差异。另一轮只读文档审查确认API、VIDEO_ANSWERS、VIDEO_ASSESSMENT、VIDEO_PUBLICATION与ARCHITECTURE的当前描述和代码一致，未扩大云质量或生产结论；最终制品审查另记REVIEW，不能以审查描述替代运行证据。

计划落实/细化：先交付窄VideoProofInput的真实消费者，再接公开链，不提前创造通用框架；完整编译输入仍保留原Interface。只读单个选中帧代替全帧BLOB加载，v11增量随消费方落地。下一主线为视频原帧OCR文字和真实词框/帧时间；独立字幕轨不与OCR混同。覆盖率失败导致补测而非扩功能，其余后置项不抢占本轮闭环。

ASR/VLM/文字/embedding/rerank/Milvus服务端全部为本机替身；FFmpeg、Spring HTTP、SQLite、索引子进程及原字节真实运行。未验证真实中文ASR/VLM质量、真实Milvus部署、网页播放器、生产吞吐或发布。字幕/OCR、scene、查询附件、视觉/声音向量及文件摘要仍未完成；readiness和production gate不解除。未改前端、旧服务/数据，未创建Git分支/提交/推送/部署，未读取真实模型密钥或挪用旧额度。
