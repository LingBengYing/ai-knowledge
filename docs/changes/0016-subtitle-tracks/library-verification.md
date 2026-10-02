# 0016 步骤 2–3：字幕知识库本机后端验证

2026-09-20 16:10:40 +08:00，完整字幕上传/持久任务/索引→独立文字问答→typed 时间与原视频 Range→完整文件摘要→重启来源读取已本机后端冻结。步骤 1 的 [verification](verification.md)、source-manifest 和 test-cases 保留原字节，不覆盖历史。不是云质量、网页或生产验收。

## 用户可观察的正常闭环

- 真实 FFmpeg 合成双轨 MP4，红色画面与音轨替身没有字幕事实。第一轨有 `Project A's budget is 650 USD.` 和审批条件，第二轨末尾有 `TAIL-917`；全部三条非空 cue 均持久化并发布，不把字幕合并到 ASR。
- Spring HTTP 真实上传后经持久任务、独立索引子进程和 loopback Milvus 发布。`mode=subtitle` 可以回答字幕独有预算事实，引用区间为 `[500000,1250000)` 微秒；类型明确为 `video_subtitle/embedded_subtitle/subtitle_cue`。
- 来源回读同版本原视频的准确 SHA 和单 byte Range，没有伪造 frame/group/transcript/OCR。双轨末尾字幕 `[3500000,4500000)` 超过原 4 秒画面尾部仍完整保留；不制造虚假帧。
- 摘要模型收到全部三条字幕，结果可以引用末尾 `TAIL-917`，来源回读真实末尾区间与原视频 Range。重启后回答和摘要来源仍相同，模型调用计数不增加。
- 独立 SQLite/Service 验证还覆盖短文件、65 条 cue 的完整分层长文件、两轨空清屏与 emoji CP 身份。完整同轨尾部更正可以否决只召回首 cue 的旧答案，不能用检索 top-k 截掉反证。

以上 FFmpeg、HTTP、SQLite、索引进程和原文件字节是真实运行；ASR/VLM/问答/摘要与 Milvus 服务端是本机协议替身。云调用 0，不认证真实模型语义质量、自然录音识别质量或生产检索效果。

## RED → GREEN 记录

| 阶段 | 时间（+08:00） | 实际结果与解释 |
| --- | --- | --- |
| 新配置/摘要Domain/v15迁移 RED | 15:26:18 | 7项，4 failure / 2 error / 0 skip；可编译旧合同缺功能 |
| 字幕 authority/publish RED | 15:28:38 | 8项，3 failure / 5 error / 0 skip；完整回读/旧 schema gate 尚未实现 |
| 查询/摘要接线 RED | 15:32:33 | 14项，2 failure / 12 error / 0 skip；缺独立候选/来源/材料接线 |
| 摄取产物合同 RED | 15:34:22 | 6项全部 failure；缺产品被误归 storage unavailable，需在提交前拒绝 |
| 新旧定向整合 | 15:41:47 | 208项，3 failure：真实尾部更正1项、无值等同性对象比较的夹具2项；未删除用例 |
| 更正最小化 RED | 15:44:45 | 8项，3 failure；中性标签错误参与同主体匹配 |
| 示例回归 RED | 15:49:00 | 11项，3 failure；示例/例子/Example 不得撤销真实事实 |
| 完整相关语义 GREEN | 15:54:28 | 408/0/0/0；之后短路顺序等价调整由最终全量重新冻结 |
| 首轮完整运行 | 16:00:09 | 1595项，0 failure / 2 error / 0 skip；两处旧全枚举夹具遗漏新增字幕的必需时间 |
| 全枚举夹具修正定向 | 16:05:58 | 36/0/0/0；仅新增 timed 条件，全部原断言保留 |
| 最终 native | 16:06:53 | 19/0/0/0，7份XML；真实新字幕HTTP1、字幕输入10、旧解码3/编译1/发布2/问答1/OCR1 |

夹具编译错误与对象地址比较不作为产品行为 RED。两处全枚举测试只把 `VIDEO_SUBTITLE` 加入 temporal/timed 判别，原七类行为、错误内容和缺时间拒绝断言均保持；没有删除、跳过或放宽测试。36项定向命令随后误用无 execution 配置的 `jacoco:check`，报告 rules 缺失是命令错误，不是覆盖率失败；最终使用原 `clean verify` 的 report execution 和双80%规则，不改 pom 或门禁。

真实更正问题按 diagnosing-bugs 先复现、最小化、逐假设验证后修复。生产仅在已有同主体比较使用 `SourceFields.statement`，并复用 `SourceInstructions` 排除示例/指令；原文/CP路径不改，policy 为 `java-text-grounding-v5-neutral-label-context`。没有新增通用解析框架或扩张词表。

## 分层、兼容与明确边界

- codebase-design/fullstack-dev 的小 Interface 与明确可观察合同用于本切；沿用 Controller→Service→Repository、Domain/DTO、Config 与现有 security，不引入 ORM、脚手架、第二套权限或重试系统。
- v15 追加独立字幕 authority/publication/trace，并扩展原摘要四表；旧 v1–v14 迁移方法体不变，v14→v15 真实迁移保留旧摘要结果。v3 显式封存 OCR 是否必需，不以缺旁表推定关闭。
- 所有包入 authority、非空 cue 入索引；帧/ASR/OCR/字幕共享完整发布上限。旧 base manifest、旧 compiler 构造/hash 和七类摘要 fingerprint 保持。
- 完整scope/ACL/active、当前模型/策略、trace与来源校验复用；caption和摘要不作事实证据。字幕不是声音证明、画面OCR或渲染后的markup，cue时间不是逐词对齐。
- `rag.video.subtitles.enabled` 默认 false。仅本机 development/test，前端未改；没有启用线上服务、调用云模型、创建分支/提交/推送/部署或操作旧服务/数据。
- 下一业务主线为多模态查询附件，随后真实 provider 质量、前端与生产。ASS/位图/烧录字幕、字幕管理 UI 和新联合字幕证明按 backlog 推进，不扩展当前正常闭环。

## 最终门禁与制品

| 门禁 | 最终实际结果 |
| --- | --- |
| 完整 clean verify | 16:10:40，1595 Java / 206 XML，0 failure / error / skip |
| 真实 native | 16:06:53，19 Java / 7 XML，0 failure / error / skip，单列不混入默认数 |
| 格式与架构 | 540 Java 格式文件，0需修改；原架构检查通过，无新豁免 |
| LINE | 15260 / 16311 = 93.556496% |
| BRANCH | 8098 / 10099 = 80.186157% |
| Node | 73/73，0 failure / skip；前端未改 |
| 旧用例 | 步骤1全部1541精确class/name及多重性保留；所有本批RED身份亦保留 |

实际 JBR 21.0.8+9-b1038.68、Maven 3.9.9，已有离线依赖。隔离构建位于 `/private/tmp/java-subtitle-library.MWkkDi`；root 独占串行 Maven，最终默认报告来自 clean verify，native 仅复制本次7份XML到独立目录。最终源码之后未再修改，门禁没有删除、跳过、放宽。

[library-source-manifest.json](library-source-manifest.json) 绑定567个输入及默认/native/RED报告、日志、JaCoCo与最终JAR；其中上一步547输入501未变、46必要修改、20新增，删除0。[library-test-cases.json](library-test-cases.json) 原字节 SHA 为 `a3af0cff8053c3c857348f3afb02646b33c36a9a4222386d616224ac5eebbf17`。六组行为RED报告共按各自原身份独立核对，不将重叠用例累加为新增测试数；`red-correction-reports` 额外保留原失败Service6项，与最小化8项合计14项。

46个既有输入修改中生产28个、测试18个；测试为16个current schema/迁移夹具和2个全枚举时间夹具的必要适配，旧行为断言保留。20个新增文件为7个生产字幕Domain/实体/DTO和13个测试/共享fixture。共享语义最后修改由完整1595重新覆盖，不只用新增11例认证。

限定独立Standards/Spec/文档审查无未关闭阻断，详见[REVIEW](REVIEW.md)。最终制品独立核对另列于下方，不以源码审查替代报告/字节证据。

工件落盘后再次逐项重算567输入的repo/build SHA及case原字节SHA全部一致；跟踪内容/可达历史扫描和398个未跟踪文件扫描均无finding，`git diff --check`通过。只记录扫描计数，不记录或输出实际凭据。没有Git写入或上传。

## 独立最终制品复核 PASS

非实现代理使用独立 Ruby JSON/REXML/Digest 和 unzip 复核，没有运行生成 helper、Maven、Git 或网络，也没有修改文件：

- 567输入在repo/build/manifest的原字节SHA完全一致，501未变/46修改/20新增/0删除；旧v1–v14迁移与helper字节不变。
- 默认206 XML为1595/0/0/0，case SHA一致，旧1541身份与多重性完整保留。六组RED、36项夹具、历史RED/native身份保留；所核227个XML内部testcase子状态与suite总数一致，没有只读日志推定PASS。
- 最终native7 XML为19/0/0/0；完整相关语义408、Node73、540格式、实际JDK与两次最终时间一致。
- LINE15260/16311和BRANCH8098/10099均满足原80%；14项日志/覆盖率/JAR原字节SHA匹配。JAR全部419个生产class的条目集合和逐字节内容等于target/classes。

剩余审计项0。该复核仅认证本机后端冻结，不扩大为真实云质量、网页或生产验收。
