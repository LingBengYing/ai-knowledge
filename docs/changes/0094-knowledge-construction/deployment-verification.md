# 0094 知识建设发布与修复验证

## 当前结论（2026-10-25）

首版 `20261025-knowledge-construction-0094` 已配套发布 Java/Web，生产schema42→43、真实数据库副本和运行UID验证通过。首轮真实5次HTTP虽均成功，但v2提案重复未变章节，已在浏览器暂不采纳并停止原run；该失败历史保留。

修复提交 `e94a6e17281fd572b1c1b6fb7501796e981c0dd0` 已推送，Java补丁 `20261025-knowledge-construction-0094-r2` 已 **SWITCHED / OPENED**，Web仍为0094的 `17fc7429add3b592252cfa1cf6a407f37a657276`。只追加一次真实DeepSeek `revise_wiki` 后，新提案已实际浏览器人工审阅采纳为v2；三个原章节编号/顺序准确保持，guide更新为3秒，care章节完整不变且无重复。统一6/6请求成功、传输失败0、重试0。

当前两份来源的Strong读回已通过：2份资料/2行/1024维，7次只读HTTP，publication/generation、正文、float32向量entry与manifest均相符。重启前完整旧数据保持检查通过：109旧表全部172,178行保留，21文件/11受管backup摘要及DDL不变，新增行仅本轮业务。**最终受控重启、零模型回读和公网45项复跑均通过**。重启前后当前schema43全部表/文件/DDL完整等值，模型计数仍为6，四服务active/NRestarts0。

## 版本与制品

| 用途 | 提交 / SHA-256 |
| --- | --- |
| 首版后端 main 已推送 | `b2baecc2ee1d5f720e7349d107469cc8a613b4dc` |
| 配套前端 main 已推送 | `17fc7429add3b592252cfa1cf6a407f37a657276` |
| r2 后端修复 main 已推送 | `e94a6e17281fd572b1c1b6fb7501796e981c0dd0` |
| 首版 application.jar（88,890,221 字节） | `3710667c8ac4d6b3db875058dd4d72a6c077a1fafd9bb6cd9cd1c3b7b45ba36e` |
| r2 application.jar（88,902,233 字节） | `bcd3dde6dc5923fd3186dd9c540e7e76fc5c1366967e73ca94c3ee2d24bcd57f` |
| 首版 schema43 兼容旧行为包 | `f4eae999076209c73281808e94a083735c8fe6dba386bf7b9b4affa40cf4ec1c` |
| 前端归档（2,167,604 字节） | `74a0d3ebb0ceb2551c16a1cab3768fae03e881f6de65f590ce5c83b57dbb9af5` |
| 相同 MigrationProbe.class | `4e3f170a12407dc035db10f080afc2ee9f7a512ebb107acf64bf26f0f2fe2199` |

首版由干净基线后端 `13ddbaa` / 前端 `bc8d9e9` 加本轮清单构建，图片/OCR/问答 WIP 排除。r2 只变化四个生产源码所属的 26 个 class（包括内部类及新增 DTO），87 个依赖 JAR、资源、其余 class 的集合和正文不变；所有打包 class 与实际 HTTP 回归输出逐 SHA 一致。r2 不改 schema、公开 API、前端、模型配置或索引。

首版运行目录为 `/srv/ai-knowledge/releases/20261025-knowledge-construction-0094`。r2 当前Java目录为同级 `20261025-knowledge-construction-0094-r2`；Web继续使用首版 `frontend`。Agent0076-r2、OCR0078-r3、Milvus0093容器/镜像/挂载/guard保持。

## 本地验证与保留的失败

首版后端最终单轮 **58/58**，格式（候选 1,294 文件、兼容源 1,278 文件）与 package 通过；87 个依赖与旧 0085 包相同。初次 sandbox 58 项中 5 项因 loopback 权限错误中断，在允许本地 HTTP 的环境完整重跑通过，原日志保留，没有修改断言求绿。

架构 11 项中 10 通过、1 个旧规则失败，包含四条既存 IndexingRepository→RetrievalProjection 依赖；对实际旧 0085 JAR 复现相同结果，新增 0。此例外不代表完整门禁通过。

前端干净发布候选完整串行 **525/525**、语法、实际定制入口 **230** 项、打包回归 **36/36** 通过；归档 226 个 regular 文件全部路径/长度/SHA 与清单相同，重复/AppleDouble/额外文件 0。首次打包 fixture 相对路径错误保留，修正定位后 36 项完整通过。早期混合工作树的 529/529 是另一轮记录，不替代本次 525/525，也不关闭旧默认并发截止竞态。

r2 定向回归 **54/54**：

| 检查 | 通过 |
| --- | ---: |
| OpenAI 兼容 Wiki 模型协议（真实 loopback 替身） | 11 |
| WikiWorkspaceService / WikiMaintenanceService / WikiCompilationService | 12 + 10 + 18 |
| 实际 Spring HTTP 建设链 / Wiki HTTP | 1 + 2 |

r2 格式、package、class 一致性和限定扫描通过，密钥扫描命中 0。新增回归覆盖混合来源概览、目标章节精确集合与原位替换、未变章节完整保留、多分片来源分组、来源/页面并发变化，以及原文摘编的逐片段输出。首次开发中遗漏 HashMap import 的编译失败按原日志保留；最终回归不放宽产品断言。模型协议测试均为本机替身，不是额外真实供应商请求。

## 首版真实迁移与发布

首版已执行真实生产副本验证：旧42包控制重开两次、候选42→43及重复重开、旧42拒绝43且数据不变、43兼容包重复重开、实际 backend UID 迁移/重开。完整比较旧业务行/SQL对象/受管文件与配置，**108 张旧表、20 个旧受管文件保持，迁移后109表/21文件**；增加一张 placement 侧表和一份迁移备份。

停机备份、真实运行账号路径遍历/读取权限、正式迁移与配套发布完成。四服务 backend/entry/agent/ocr 均 active，NRestarts=0；配置、免登录和 Milvus 精确身份保持，未恢复旧数据库。内部检查记录共25项（含首页），公网 HTTPS 45项预期状态通过；包含应拒绝的旧路由/内部路由和无效参数，不能将检查数写成全为200。`/health/ready` 原503仍保留。

脱敏证据为本机 `.local/release-0094/production-opened.json`、`public-after.json`；真实详细副本/备份/配置证据保留在服务器私密发布目录，不进入 Git。

### 初次120秒超时与挂载顺序误报

- 首次私密副本 MigrationProbe 被120秒工具截止中断；之后确认副本已处于43，不把超时视作迁移回滚或重新创建库。保留原失败副本和记录，重新核对生产基线后续验。
- 只将探针及后端健康等待延长至600秒，应用 `-Xmx256m` 与完整校验未放宽。续验随后通过，报告记录 `initial_tool_timeout_seconds=120`、`extended_probe_timeout_seconds=600`、`initial_fixture_schema_after_timeout=43`。既有开库完整校验可能耗时，但没有运行期栈/GC证据，不据此宣称内存、磁盘或某一方法是已证根因。
- Docker Mounts 返回数组顺序导致首个预检误报。修复只将 `(Source, Destination)` 成对排序比较，容器ID/镜像/挂载内容/健康/OOM/重启检查保持。没有改动挂载或Milvus数据。
- 旧 helper 与 bundle 原样保留。当前首版核心 helper SHA `e1aecf5ca5300143ce05330a9278c8afdadec749136b508960b3284aa235bed3`；原 helper SHA `46f23b1524851d370bf7e401a165026e90541e9e8d3686e1de67de50ea0f82a8`；续验 helper SHA `fbab8142a5a0ddb54b7092f0349ba0b60e5de30bd6ffe8a38959424a5a513b2c`。

## 真实模型发现与r2验收

首轮真实5次请求全部HTTP成功且无重试：硅基流动嵌入3次、DeepSeek编译2次。两资料自动发布、v1审阅采纳、原件更新与重新索引成功；首轮v2提案重复稳定章节，已浏览器dismissed并停止原验收，当时不计为局部更新通过。详情见 [provider-run.md](provider-run.md) 与 [real-model-repair.md](real-model-repair.md)。

r2 改为独立 `revise_wiki`：明确受影响 section_id 与每份来源证据组，单次模型返回每个目标恰好一次，服务按原ID/顺序替换，保留未变章节完整值。模型和服务均验证合法来源、每来源至少一项及目标全集。未知/漏/重复章节不会自动修补成可采纳结果。零模型原文摘编使用独立策略，按精确 publication+reference 避免重复已保留片段，不把整文档拼成每个章节。来源版本、页面版本、配置快照、人工审阅保持。

| r2检查 | 当前状态 |
| --- | --- |
| 干净源码、定向54项、格式、包与class/依赖差异、扫描 | PASSED |
| 修复提交推送、精确制品stage | PASSED |
| 当前schema43真实副本按实际UID开包、全部表/文件/配置等值 | PASSED：REHEARSED_43_UNCHANGED_ACTUAL_UID_ONCE；不迁移 |
| 生产备份、仅Java切换并开放入口 | PASSED：SWITCHED / OPENED；Web仍0094 |
| 同两份更新后合成资料的一次revise_wiki真实请求 | PASSED：新增DeepSeek1次；统一6/6，0传输失败/0重试 |
| 三原章节ID/顺序、guide3秒、care完整不变且无重复 | PASSED |
| 浏览器审阅采纳v2 | PASSED：提案3af0a6cd-2e74-4180-baeb-cca5b853012a；旧问题提案仍dismissed |
| v1内容不变、v2来源SHA/当前两原件SHA、目标维护待办消除 | PASSED；浏览器实际查看v1仍1秒，v2/current来源原文3秒 |
| 本轮两份当前generation Strong | PASSED：2资料/2行/1024维；7次只读HTTP，模型0 |
| 重启前全部旧业务数据保持 | PASSED：109表172,178旧行、21文件/11受管backup摘要、DDL保持；新增行仅本轮业务 |
| 最后受控重启、当前快照等值与零模型历史/来源回读 | PASSED；同库v1/v2、当前来源及原件回读，模型新增0 |
| 公网45项最终复跑 | PASSED；重启后独立执行45项，TLS验证开启，模型0 |

v1知识页历史内容保持已验证；但现有下载API不支持在原件被替换后下载v1旧原件。替换前旧原件SHA已核对，当前guide/care原件SHA已核对，**不得据此声称替换后仍可通过API下载v1原件**。此限制不由本次章节修订补丁解决。

## 回退与证据边界

首版从43回旧行为时必须使用43兼容包并保留当前数据：停止新写入、seal并排空带placement的批次；文件须终态indexed/failed/cancelled/deleted/duplicate，awaiting_upload/queued/parsing/parsed/indexing均阻止回退。仅seal或halt不等于排空。禁止旧42 JAR直接打开43，禁止旧数据库覆盖新业务。

r2代码回退只回首版0094 Java，保留执行时最新schema43数据和已存在业务；前端仍0094，Milvus不切换、不重启或删数据。新旧包须对最新快照完整等值验证后才开放。

自动审批曾拒绝将服务器私密证据整目录复制到本机；没有通过其他方式搬运该目录。只保存不含正文/密钥的状态、数量、SHA摘要；Git不包含配置、私密数据库、模型正文或证据原件。

本轮不关闭历史全仓门禁、旧四条架构依赖/ACL断言、readiness503、前端默认并发截止竞态，也不认证大规模吞吐、全库语义质量、其他问题或图片/音视频能力。本轮修复后的单次语义、当前来源Strong及重启前旧数据保持已通过；最后重启与公网复查完成前，不标记发布收尾全部完成。

## 最终发布收尾

2026-10-25，受控后端重启前后schema43当前全表/受管文件/DDL完整等值；同库v1/v2、当前来源及原件SHA零模型回读通过，6个请求集合完全保持。四服务active/NRestarts0；Milvus0093精确身份/配置及Web、Agent、OCR均未改。重启后公网45项独立检查全部符合预期状态，TLS验证开启；实际浏览器刷新仍显示已采纳v2。脱敏摘要见后端0094 `production-summary.json`。

源码发布版本为Java e94a6e1/Web17fc742；随后的Git提交只同步验收文档，不需要再次替换生产包。原始私密证据保留服务器，本轮截图及操作标记留本机私密目录；Git仅保留脱敏结果。
