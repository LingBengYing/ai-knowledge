# 0014 步骤2：视频持久任务与完整索引验证

日期：2026-09-12。状态：步骤2本机后端验收通过；0014整体仍为IMPLEMENTATION。16:04:38实际Temurin21.0.12.1+1完整clean verify通过1171 Java（0失败/错误/跳过）、383文件格式、行9739/10505=92.708234%、分支4982/6215=80.160901%，原双80%门槛未变。上一份 [verification](verification.md) 仍是步骤1冻结证据，不覆盖本轮新增源码。

407个构建输入与工作树逐项SHA一致，旧1134测试精确class/name和参数化多重性全部保留，无丢失旧输入；机器记录为 [publication-source-manifest](publication-source-manifest.json) 与 [publication-test-cases](publication-test-cases.json)。native10项单列，不混入默认1171；报告记录原始日志、Jar、coverage和codec摘要。

独立只读制品复核实际通过：141份默认XML共1171 passed、7份native XML恰10 passed；407输入逐项一致，393旧输入中22项变化/14项新增与清单相符；两JSON为单LF、原始cases摘要/旧baseline摘要、14项日志与Jar/覆盖率/native XML摘要及实际codec身份均匹配。此检查不扩大为云质量或生产验收。

## 可观察业务结果

显式视频 Content-Type 经原 Spring HTTP 上传，原视频/请求的 compiler 身份与持久任务原子排队；后台实际解码并保存全部原帧、描述、完整转录和真实时间交集组后才为 parsed。原索引接口逐项验证所有帧描述/非空转录，完整 generation/manifest 发布后为 indexed。没有创建视频答案或来源接口，caption 不用于证明。

16:02:09 最后格式化源码的真实 native 组10项通过（失败/错误/跳过0）：新 VideoPublicationNativeIT 两例分别验证有音轨/无音轨；原 VideoDecoderNativeIT 三例、VideoCompilationNativeIT 一例，以及旧音频解码/编译/入库/HTTP问答各一例仍通过。视频 HTTP 验证真实 FFmpeg、Spring、SQLite、索引子进程与原帧字节/时间回读；ASR、VLM、embedding和Milvus服务端为本机合成协议替身，不是云质量评估。

16:00:20 集中视频行为与全部格式迁移110项通过。Node73项通过（0失败/跳过），前端文件未改。独立临时构建为 `/private/tmp/java-video-publication.6jnVua`，native XML 单列存于 `/private/tmp/video-publication-native.pMK1wR`；不复用旧服务目录、集合或运行配置。

## 先失败后通过

- 15:44:28 VideoConfigurationTest 实际2项/1失败：显式启用没有 compiler Bean。独立视频资源装配后通过，不隐式启旧答案或装配竞争模型 Bean。
- 15:45:46 摄取准入5项中四种视频失败：MP4/WebM返回旧audio MIME，MOV/MKV不支持；旧audio-only容器合同对照通过。新增显式video MIME并绑定compiler后通过。
- 15:47:32 Domain/存储8项实际RED：v9非v10、stub未保存/回读、缺完整seal。v10 authority/Repository实现后通过。该轮另3项索引测试因sandbox禁止loopback bind未运行到产品断言，不计作产品RED；两项scope在video准入处RED。明确本机loopback权限后所有5项索引正常路径/完整scope通过。
- 第一轮集中38项有3处新夹具断言失败：两项把现有拒答状态 `abstained` 错写为 `refused`；一项 Spring mock 的 Content-Type 添加操作覆盖旧值，未真实生成重复头。只修新夹具，保留 scope_changed/空引用/完整scope和重复头415/零排队实质断言，生产状态码与旧测试不改。
- 独立审查先发现新关闭测试使用非法PNG，输入校验会早于model_closed；改为已有合法合成PNG，保留model_closed断言。一次测试编译因 Config 新依赖未接旧夹具、一次 shell 未引用通配符，均按运行/夹具问题记录，不算产品失败或验收通过。

## 旧基线保留与必要适配

旧 v1–v9 迁移体保持：从 AuthoritySchema.migrateVersionNine 至EOF与步骤1冻结副本逐字节一致。v10只是新增六表/约束、真实发布gate和Store升级入口。旧原视频/原文仍由原 corpus_documents 保存；没有另一套通用存储框架。

八个旧 MigrationTest（AudioTrace、AudioLibrary、ImageRegion、Indexing、Ingestion、Evidence、DocumentLifecycle、VisualLibrary）仅把“当前格式版本”断言9改为10；其降级测试链先调用新 restoreVersionNine，且先验证仅临时v10库、视频数据为空，再剥新结构、恢复v9 gate。原旧历史升级/数据/备份/安全断言及用例名字保持。IngestionSettingsTest只增加一个空 VideoCompilationService provider以适配配置签名，其公共绑定拒绝断言未变。不生成仅为旧测试保留的生产兼容壳。

## 限定审查及未验项

独立Standards/Spec限定审查覆盖新Domain/索引scope、v10完整seal/FK/物理ID双向碰撞、Config资源生命周期、native夹具及摄取的显式类型/事务外执行/同事务复验。已处理的发现为上述新测试夹具问题，无未关闭的已复现本切主线阻断，不以先前步骤1审查认证本轮。

没有云模型调用（0）、Git分支/提交/推送、部署、前端或旧服务/数据操作。真实ASR/VLM质量、真实Milvus视频质量、视频逐事实证明/typed来源、字幕/OCR、网页、摘要和生产仍未完成；本轮后续直接推进同组音画证明，不回到已完成编译诊断。异常caption与ProjectionItem的旧NUL契约差异记录于 [backlog](backlog.md)，不抢占正常主线。
