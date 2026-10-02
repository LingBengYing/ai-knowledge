# 0015 生成 Module 本机验证

2026-09-20 13:16:15 +08:00，内部生成 Module 完成完整本机回归。**不是用户已可调用的文件摘要功能**：完整 authority 枚举、持久任务、来源 HTTP、长文件分层、真实模型质量、网页与生产未完成。下一步直接接完整文件材料与持久摘要/来源，不重复已完成的模型协议诊断。

## 已验证的正常链

完整合成原证据 → OpenAI-compatible 摘要候选 → 逐条仅用实际引用证据核验 → FileSynopsis 来源/指纹/服务器时间，四类文件及视频画面/转录/OCR均有测试。真实 loopback HTTP 使用固定模型协议替身，云调用 0；不是模型质量评估。

- 概览、主题、术语及媒体时间线保留来源。每个支持 ID 必须实际贡献；遗漏一项贡献、最后一个条目失败、版本/current/预算失效均不返回部分摘要。
- 服务器保存引用内容 SHA、真实微秒时间及完整输入/模型/提示/策略身份；原始图片字节传入模型，caption 不充当证明。
- 首切只接受不超过 64 证据、64000 code points、8 图/8 MiB 的完整输入。超限不可用，不截取前几段；大型文件分层仍未实现。

## RED → GREEN → 完整门禁

| 阶段 | 时间（+08:00） | 实际结果 |
| --- | --- | --- |
| 首次具名 RED | 13:09:24 | 48 项，33 failure、3 error、0 skipped；生成/协议桩及未实现输入指纹失败 |
| 定向 GREEN | 13:13:13 | 同 48 项全通过（Domain15、Service20、Client13） |
| 最终 clean verify | 13:16:15 | 1400 Java，176 XML，0 failure/error/skipped；旧 1352 身份与多重性完整保留 |
| 格式与架构 | 随最终 verify | 468 Java 文件格式通过，原架构规则通过，未新增白名单 |
| JaCoCo | 随最终 verify | LINE 12387/13256 = 93.444478%；BRANCH 6670/8276 = 80.594490%；原双80%不变 |
| Node | 本批 | 73/73，通过；使用显式 CommandLineTools Git 的 PATH |

实际运行时为 JetBrains JBR 21.0.8+9-b1038.68、Maven 3.9.9；离线复用本机依赖。隔离构建使用新的 `java-file-synopsis.*` 临时目录，不替换运行中 JAR，也不接旧服务数据库。

命令均带 `-o -B -ntp -s .mvn/settings.xml -gs .mvn/settings.xml` 及已存在的隔离 Maven repository：

```sh
mvn -Dtest=SynopsisDomainTest,SynopsisServiceTest,OpenAiCompatibleSynopsisModelsTest test
mvn spotless:apply
mvn clean verify
node --test ui-tests/*.test.mjs scripts/check-secrets.test.mjs
node scripts/check-secrets.mjs --history
```

本批没有修改旧 Java/测试/配置/构建输入；新增 7 个生产文件、3 个测试文件。旧 485 输入逐 SHA 未变，新增 10 个输入，当前总 495 全部与构建副本一致。原 RED48 也逐身份/多重性保留于最终 GREEN。

原 0014 native6 未在本批重跑或冒称新增验收：其 FFmpeg/Tesseract/视频生产链未变，新摘要模块未接 runtime。完整默认回归仍运行既有媒体协议/进程测试。新真实模型 HTTP acceptance 在默认 13 项 Client 文件内，不另重复计数。

## 证据与偏离

- [source-manifest.json](source-manifest.json)：495 输入、176 报告、日志/覆盖率/JAR 摘要及原基线绑定。
- [test-cases.json](test-cases.json)：1400 精确 case identity 和多重性。
- [REVIEW.md](REVIEW.md)：实现者之外的限定 Standards/Spec 审查；没有未关闭的本步骤主线阻断。
- 首批固定模型的蓝色描述与测试 PNG 黑色不一致，已把合成图实际绘蓝，所有断言保留，最终完整回归重跑对应文件；此为夹具修正，不是产品故障。
- 一次状态读取误用系统 Git launcher，退出69；立即换已规定的 CommandLineTools Git，未接受 Xcode 许可、未改测试。此为工具执行问题，不计产品 RED。
- 历史/跟踪文件敏感信息扫描无 finding；初次306个未跟踪文件按相同扫描规则无 finding，新增文档/冻结JSON后最终309个未跟踪文件亦无 finding，`git diff --check`通过。不输出任何凭据。

独立制品复核PASS：代理未运行生成helper/Maven，直接重算495输入及176 XML、旧1352/RED48身份多重性、两JSON字节摘要、覆盖率/日志/JAR；350个生产class在JAR与target/classes逐字一致。全部与本记录一致，无差异。

此步按 plan 只交付必要内部 Module。完整文件摘要、独立来源、真实质量与多模态生产总目标继续 IMPLEMENTATION；没有推送、部署、云请求、前端或旧服务/数据修改。
