# 0018 完整多模态配置本机验证

状态：MC-01～06本机验收与独立制品核对已通过。完整生产Bean装配正常链、最终默认回归与固定native集合已冻结；总体多模态生产目标仍未完成。本切不使用云请求、不改生产环境、不推送代码。

## 实际证明的用户流程

`MultimodalCompositionNativeIT.productionEnvironmentCompositionUploadsAnswersSummarizesAndRestartsWithoutModelReads`于2026-09-20 18:07:48 +08通过。只用`RagApplication`，通过隔离的真实SystemEnvironmentPropertySource配置全部功能；无测试Configuration、Bean override、手工Service、手工parsed/publication提交。所有媒体为现场合成，FFmpeg/FFprobe/Tesseract、SQLite、Spring HTTP、解析/索引Job与独立Java索引worker真实运行；外部模型和Milvus服务端为本机协议替身。

| 合同 | 可观察证据 |
| --- | --- |
| MC-01 全部配置 | production配置共同创建短/长摘要、媒体编译、附件/答案等Bean；static临时目录非null，实际配置路径与其精确相等 |
| MC-02 启动/鉴权 | 空库启动模型/向量请求均0；JWT缺失401，能力包含全部当前后端链；ready仍503 |
| MC-03 真实任务 | TXT/PNG/WAV/双字幕MP4经HTTP上传、持久任务parsed、显式索引任务indexed；PNG为视觉authority；视频全部帧、音频段、OCR段、非空字幕cue数量与实际完整投影一致 |
| MC-04 答案/摘要 | 三个附件编译及真实PNG OCR/全部ASR/第二字幕轨尾部进入检索，650答案及引用来自库内TXT、不是附件999；音频、视频OCR与字幕typed来源，摘要第二轨最后cue及3.5–4.5秒来源可读 |
| MC-05 重启回读 | 同库重启，答案/摘要来源完全相同、原帧字节相同，音视频200与单byte Range206精确匹配；模型与向量请求计数均不增加 |
| MC-06 回归保留 | 1680默认/22 native/73 Node，旧1680默认与21 native身份及多重性保留；606输入绑定见source-manifest，原0017工件不覆盖 |

这是“完整生产Configuration在受限development/test下共同运行”的证明，不是`RAG_ENVIRONMENT=production`验收。图片视觉优先规则保持；本切没有重跑全开配置下的image答案或video visual/transcript/joint提问，没有重跑长文件分层生成（仅确认hierarchy真实装配），其原专项测试保留；不借此宣称新模型质量或生产安全。

## 首跑和修正记录

- 首次临时JAR启动探针因生成了不允许的文本配置字段、非`java_`集合及非根Milvus URL失败，是probe配置错误；修正后原JAR全开启动、`GET /v1/config=200`、live200/ready503、remote0，进程关闭。错误的`/v1/runtime`401探针不计作能力验证。
- 17:56:16：新增native可编译，1项1 failure/0 error/0 skip。固定画面经合法SHA去重后不应触发采样，夹具预期错误。改为保留文字的真实变化帧，新增至少4个distinct SHA断言，保留原`visual_sampled=true`。
- 17:59:07：1项1 failure/0 error/0 skip。新测试误用上传路由读取列表；按现有API改为`/v1/management/documents`，保留401/200和库内总数4断言。
- 18:07:48：1项0 failure/0 error/0 skip，完整正常链通过。以上没有生产代码RED或生产修复，不把夹具修正包装为产品缺陷修复；旧测试均未改动。

隔离构建目录：`/private/tmp/java-composition.CwYF7u`。Maven仅root串行执行；实际JetBrains JBR 21.0.8+9-b1038.68/Maven3.9.9，离线依赖。无新云请求、业务资料、现役数据或服务变动；前端、Git提交/推送和部署未执行。

## 最终门禁

18:12:47，隔离副本`clean verify`通过1680项Java测试（221个XML、0失败/错误/跳过）、578个Java文件格式检查及原LINE/BRANCH双80%门禁；73项Node回归通过。执行前后检查0017全部604构建输入原字节不变，没有生产源码或旧测试的修改。FFmpeg/FFprobe实际8.1.1，Tesseract实际5.5.3，当前安装语言仅eng/osd/snum，未验证中文。

18:13:49，既有21项与新增1项native回归共22项/10个XML通过，0失败/错误/跳过。默认JaCoCo原XML记录LINE16389/17521（93.539182%）、BRANCH8840/11021（80.210507%）；native运行后没有重新生成默认覆盖率报告。默认精确测试身份文件SHA为`82c2f9a7fde5c4c96474837f32589d49e07a92eb5b2ea345e8112e71ffec3e9a`，与0017相同，因为新增验收为单列native而非默认测试。

[source-manifest](source-manifest.json)绑定606个输入：604原基线输入原字节不变，加新增native测试及新纳入清单的既有`.env.example`（本轮补字幕/摘要字段与更正注释，非新增文件）；删除0。包含默认/原生/两组fixture失败XML的统计与SHA、日志/覆盖率/JAR指纹；[test-cases](test-cases.json)保留默认身份和多重性。没有用测试编译失败、删测、skip、放宽失败断言或降低覆盖率完成验收。

新测试两次fixture失败XML分别保留于`evidence-red-composition`和`evidence-red-composition-route`，最终正常链同一测试身份保留；构建日志中的既有编译警告不隐瞒，也不当作新增产品错误。敏感信息既有工作树/历史扫描无命中，最终额外455个未跟踪文件限定模式扫描无命中（不是完整泄密证明）；一次第二条Node未独立设置PATH导致Git检查工具失败，按原规范单独设置PATH重跑通过，没有修改或跳过检查器。

## 独立制品核对

非实现代理只读复核，实质不符0：独立枚举606输入，在仓库与构建副本两处均与清单SHA一致，旧604输入原字节全部保留；默认221份XML的1680身份、多重性与原基线相同；native10份XML共22项，旧21项全部保留，仅新增具名装配验收。两组fixture失败与单项green是同一测试身份，报告统计、SHA及完成时间均一致。

12项日志/启动探针/覆盖率/JAR证据SHA全部吻合；默认LINE与BRANCH计数及578格式、73 Node结果独立核对通过。JAR与`target/classes`均为443个生产class，集合与逐文件原字节一致。审计未运行Maven、证据生成helper、网络请求或写入文件；不是实现者自审的替代描述。`source-manifest.json`最终SHA256为`21175ceb88b979b106195c6312be4019173dfa6b1f6d8e0dec03b630770317b7`。

## 尚未验收

真实provider/Milvus多模态质量、中文OCR/其他codec质量、真实网页流程、OS/容器隔离、同镜像staging、备份迁移/回滚、负载与生产仍保持原gate。操作说明和下一前提见[MULTIMODAL_RUNTIME](../../MULTIMODAL_RUNTIME.md)。此前文本额度不用于本切，新增云调用须另获授权并使用轮换后私有配置的密钥。
