# AI Knowledge · Java Edition

当前增量：[0008文档生命周期](docs/changes/0008-document-lifecycle/intent.md)，实现独立开关控制的撤下请求、在途任务取消及旧引用失效。2026-09-08 13:09:46实际JDK21完整857项Java、73项Node、240文件格式及行/分支双80%门禁通过。`DELETE /v1/documents/{id}`返回`deleting/pending`，不表示物理清理完成；没有恢复接口，文件与历史证据仍保留并占配额。当前验证与剩余门禁见[0008验证](docs/changes/0008-document-lifecycle/verification.md)。本次仅同步源码与说明，不部署；下方0007及更早日期均为历史基线，不认证新增源码。

2026-09-08 12:08:55 本次代码同步快照：修复明确示例语境、多句操作步骤/必要前提遗漏及程序组重复全页扫描，policy 为 `java-text-grounding-v4-procedure-context`。最后修改后773项Java、73项Node、227文件格式与双80%覆盖率门禁通过，原675项测试及91个测试/语料文件完整保留；本批限定Standards/Spec审查均无未关闭问题。完整真实生成链路、网页接线、多模态和生产仍未验收。本次只同步Java代码与说明，不部署或改前端；当前范围与源码绑定见[0007验证](docs/changes/0007-text-answers/verification.md)和[REVIEW](docs/changes/0007-text-answers/REVIEW.md)，下方日期及“未推送”均为历史状态。

当前开发：[0007授权文本问答](docs/changes/0007-text-answers/intent.md)，仍为IMPLEMENTATION。2026-09-07 15:59:54 +08:00实际Temurin21.0.12.1+1干净构建通过635项Java、73项Node、212个Java文件格式检查和双80%覆盖率门禁。本批修复具名条件跨分块漏判及Model输出不变量，限定两轴审查通过。默认关闭的问答HTTP已接通，但不是完整语义、网页、真实provider/Milvus、多模态或生产验收。当前证据见[0007验证](docs/changes/0007-text-answers/verification.md)。

[真实Milvus集成](docs/changes/0007-text-answers/milvus-integration.md)于2026-09-08补测通过：固定2.6.22/ARM64、float32精确摘要、dense/BM25授权范围、4096+1完整性边界，以及卸载后只读不加载/显式重载恢复。[SiliconFlow联调](docs/changes/0007-text-answers/provider-integration.md)中真实嵌入与重排通过，证据摘录60秒超时，整体未通过；无自动重试。这不是完整容量、总体模型质量、多模态或生产验收。

[0005分层重构](docs/changes/0005-spring-layering/closure.md)和[0006摄取授权补强](docs/changes/0006-ingestion-authorization/verification.md)是已完成的历史基线，其297项结果不认证新增源码。前端详情页未改，未推送或部署。

一个面向单组织的 **Java AI 知识库 / RAG（Retrieval-Augmented Generation）** 项目。

当前可运行的是 **资料管理工作台与本机文本链路**：列表、授权分页、目录、标签、改名、批量整理和审计；独立开关控制文本上传/解析、索引发布及问答HTTP。0007连接授权混合检索、重排、受限事实验证、摘录答案与引用回读；网页提问尚未接线，图片、音频和视频知识处理仍待实现。

> **非生产版。** `parsed`与`indexed`不自动开放问答；需显式启用0007并满足当前授权与publication约束。列表`can_answer=false`和网页未接线的边界保留，`/health/ready`仍为503。Java21历史CI、本机替身和已有浏览器截图均不认证当前真实provider/Milvus或生产可用性。

**For AI agents:** A standalone Java knowledge-management application being extended into an evidence-grounded RAG system. Read [AI_CONTEXT](docs/AI_CONTEXT.md), [AGENTS.md](AGENTS.md), and the capability table before making claims or changes. Planned capabilities are not implemented APIs.

## 当前能力

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| 传统列表、分页、搜索和类型筛选 | 可运行 | 授权过滤先于统计和分页 |
| 目录、改名、手工标签、批量移动/加标签 | 可运行 | 整理不会修改原文件身份或触发模型 |
| JWT / HttpOnly 会话 / 文档角色 | 可运行 | owner、editor、reader；开发身份仅限显式 loopback |
| SQLite 持久化与哈希审计 | 可运行 | 单写入者；重启保留；独立数据目录 |
| PDF / TXT / Markdown 文本解析 | 本地验收通过 | 受限独立Java进程、Unicode code point定位；不是OS沙箱，不可对公网文件使用 |
| Milvus写入与完整revision验证 | 隔离真实集成通过 | Java专用collection、完整ID与正文/float32摘要回读；4096短正文/4维边界通过，非一般容量验收 |
| Milvus dense + BM25查询 | 已接0007后端，本机替身已测 | 范围前置、RRF、权威正文回读；查询只验证现有集合，不创建/加载/写入 |
| 嵌入、重排、原文摘取 | 后端已接；实际provider部分通过 | SiliconFlow嵌入/重排合成smoke通过，摘录超时未通过；rerank为provider扩展，最终答案仍须服务端验证 |
| 上传、持久任务、取消/重试、版本化解析证据 | 本地验收通过 | 默认关闭；显式启用且loopback；解析完成标为parsed，保留原文件 |
| 摄取后台当前授权与撤权取消 | 0006 本地验收通过 | 领取/执行前/提交复验原创建者当前写权限；取消审计原子提交；重试需恢复创建者权限 |
| 显式索引任务与active发布 | 0004实现中 | 默认关闭；每attempt独立generation、完整物理manifest与映射台账；父存活/跨JVM lease和晚写隔离仍按当前验证记录验收 |
| 有证问答与来源 | 0007开发实现，默认关闭 | POST /v1/answers、GET /v1/sources/{answerId}/{ordinal}；范围/配置复验和trace同事务，网页未接线，完整语义与真实provider待验收 |
| 文档撤下、取消在途任务与旧引用失效 | 0008开发实现，默认关闭 | DELETE /v1/documents/{id}；当前权限与事务审计，v5墓碑；返回deleting/pending，物理清理与批量硬删仍未实现 |
| 图片、音频、视频、联合事实与文件摘要 | 规划中 | 不等同于仅生成文件摘要 |
| 生产部署、迁移与真实性能对比 | 未验收 | 不声称 Java 版本已比 Python 更快 |

## 技术栈

Java 21 编译目标、Spring Boot 4.1.1、Maven、SQLite JDBC、PDFBox 3.0.8；前端为原生 HTML/CSS/JavaScript。JUnit、真实 SQLite/HTTP 测试、Node 原生测试、JaCoCo 行与分支双 80% 门禁。

不依赖 Python，不通过 Python 代理业务。无模型 API key 也能运行当前管理工作台。

前端已独立发布至 [ai-knowledge-web](https://github.com/LingBengYing/ai-knowledge-web)，提供原生界面、本机同源开发代理及独立运行说明。本仓库仍保留同源内置页面；分仓不代表自动同步或跨域认证已启用。

## 快速开始

需要 **JDK 21+、Maven 3.6.3+**；Node 22+ 用于前端测试与敏感文件检查。命令在本仓库根目录执行。

```bash
mvn -s .mvn/settings.xml -gs .mvn/settings.xml verify
RAG_AUTH_MODE=development_headers bash run-dev.sh
```

打开 [本地工作台](http://127.0.0.1:18084/)。空库没有资料；开发模式可以输入 `owner` 作为本地演示身份。不要把开发 header 模式接到公网或反向代理。

要演示四类资料的整理界面，可显式创建一份 **合成元数据**，不是上传/解析/检索结果：

```bash
java -jar target/rag-java-0.1.0-SNAPSHOT.jar --seed-demo ./demo-data
RAG_AUTH_MODE=development_headers RAG_DATA_DIRECTORY=./demo-data bash run-dev.sh
```

`owner` 可整理四条合成资料，`reader` 只能读取两条被授权资料，`editor` 可编辑其被授权资料。重复 seed 同一目录会被拒绝，避免覆盖。

`run-dev.sh` 会复制一个不变 JAR 后启动，避免后续 Maven 打包覆盖运行中的程序。默认绑定 `127.0.0.1:18084`。

### 文本摄取开发入口（0003）

新建专用目录和端口，不重启或替换其他演示进程：

```bash
RAG_AUTH_MODE=development_headers RAG_INGESTION_ENABLED=true \
RAG_DATA_DIRECTORY=./text-demo-data RAG_PORT=18086 bash run-dev.sh
```

页面接受PDF/TXT/MD，最大20MiB。任务持久化为queued/processing/parsed/failed/cancelled；解析成功后仍未索引，问答继续禁用。默认30秒解析/上传接收时限、最多2个在途上传、单解析并发。上传仅授予创建者owner，其他身份不自动获得权限。详见 [TEXT_INGESTION](docs/TEXT_INGESTION.md)。仅允许字面loopback绑定，不得放到公网或代理后当生产服务。

### 文本索引开发入口（0004）

先通过进程环境配置[TEXT_ADAPTERS](docs/TEXT_ADAPTERS.md)中的全部三种模型Endpoint与独立Java Milvus集合，再显式设置`RAG_INDEXING_ENABLED=true`。这会启用`POST /v1/documents/{documentId}/index`与持久任务状态/取消/重试；只处理当前有写权限且已解析的真实资料，上传不会自动索引。摄取开关独立，需要新上传时再开启`RAG_INGESTION_ENABLED=true`。

索引仅允许development/test和字面`127.0.0.1`或`::1`绑定；全任务默认60秒，`RAG_INDEXING_TIMEOUT_MS`范围10–600000。完整模型配置是启动要求，当前worker仅调用embedding与Milvus，缺配置不退回假模型或内存索引。使用新专用数据目录/端口与合成资料；不要更改现有服务、数据或collection。v3迁移备份、计费重试、完整验证与运行限制见[TEXT_INDEXING](docs/TEXT_INDEXING.md)。

每次索引claim使用新的物理generation，重试保留source revision；即使旧上游HTTP迟到完成，也写旧namespace。protocol v2检测父PID/startInstant，worker使用同OS用户的跨JVM collection lease；不把kill当上游撤回或OS沙箱。lease小文件保留，父崩溃的私有job临时目录还需后续回收。早期缺generation/台账的未发布WIP v3拒绝复用；这些机制仍需当前源码的真实进程/远程验收，未解除生产gate。

## 配置与密钥

文档撤下须显式设`RAG_DOCUMENT_REMOVAL_ENABLED=true`，只允许development/test及字面loopback，不依赖模型配置，也不会开启其他任务。能力名为`document_removal`；物理删除`document_delete`仍不可用，前端未加删除按钮。关闭开关只关闭新请求，不会使已撤下资料重新可见。已有Java库会一致性备份后迁移v5；旧备份不包含后续删除请求，切勿当作保留删除状态的生产恢复方案。

文本问答需另设`RAG_ANSWERS_ENABLED=true`，并提供[模型与Milvus配置](docs/TEXT_ADAPTERS.md)。仅development/test及字面loopback；默认总处理预算60000毫秒、并发2，分别通过`RAG_ANSWERS_TIMEOUT_MS`（10–600000）和`RAG_ANSWERS_MAX_CONCURRENT`（1–8）调整。接口、显式空选择、错误和来源语义见[API](docs/API.md)。索引/摄取/问答三个开关独立，不自动创建索引或调用真实模型重试。

真实配置只放环境变量或部署平台的 secret 中。[.env.example](.env.example) 仅列出空值/非敏感默认值；**程序不自动读取 `.env`**，不要仅复制文件就以为配置生效。

| 变量 | 默认值 / 用途 |
| --- | --- |
| `RAG_AUTH_MODE` | `jwt`；本地演示需显式改为 `development_headers` |
| `RAG_JWT_SECRET` | 无默认值，JWT 模式至少 32 字符；不要使用文档或测试中的值 |
| `RAG_JWT_ISSUER` / `RAG_JWT_AUDIENCE` | `evidence-rag` / `evidence-rag-web` |
| `RAG_WORKSPACE_ID` | `org-main` |
| `RAG_BIND_ADDRESS` / `RAG_PORT` | `127.0.0.1` / `18084` |
| `RAG_DATA_DIRECTORY` | 独立的 `.data` 目录，不能指向旧数据库 |
| `RAG_ENVIRONMENT` | `development`；当前拒绝 `production` |

JWT 模式缺少 secret 会拒绝启动。浏览器通过 `POST /v1/session` 换取 HttpOnly、SameSite=Strict 会话，不把 token 放入 localStorage。索引和问答均关闭时不加载模型配置；任一开启时共用一份`TextAdapterSettings`，启动校验三种模型及Milvus配置但不发网络请求。远程写入仅由已授权索引任务触发，问答采用只读查询。完整变量见[TEXT_ADAPTERS](docs/TEXT_ADAPTERS.md)，0007装配与接口以[API](docs/API.md)为准。

**不要提交 API key、JWT secret、SSH 私钥、`.env`、数据库或运行日志。** [.gitignore](.gitignore) 与 [敏感信息检查](scripts/check-secrets.mjs) 是双层防护；完整处理流程见 [SECURITY.md](SECURITY.md)。若密钥曾被贴入聊天或日志，应在对应平台轮换，而不是只删除代码里的字符串。

## 测试与工程方式

```bash
mvn -s .mvn/settings.xml -gs .mvn/settings.xml spotless:apply
mvn -s .mvn/settings.xml -gs .mvn/settings.xml clean verify
node --test ui-tests/*.test.mjs scripts/check-secrets.test.mjs
node scripts/check-secrets.mjs --history
```

按 AI-Native 的版本化意图、规格、计划和验证证据推进小的完整功能链路，采用先失败后通过的测试；不以生成了代码或界面有按钮作为完成标准。见 [CONTRIBUTING](CONTRIBUTING.md)。

0006 最终 `clean verify` 于 **2026-09-07 11:33:24 +08:00** 通过：297项Java测试，失败/错误/跳过均为0，其中包含11项架构测试；Spotless检查150个Java文件。原283项测试逐项保留，新增14项撤权回归先红后绿，Node回归73项通过。源码指纹、命令与未验证项见[0006验证记录](docs/changes/0006-ingestion-authorization/verification.md)。本地门禁不是完整阿里规范合规认证、实际JDK21运行或生产发布证明。

固定合成PDF位于`src/test/resources/corpus/`，`docs/evals/golden.json`保存未修改的六个问答预期。0007的`AnswerGoldenTest`已使用真实Java解析/publication和明确的确定性模型/投影替身全部通过；这是固定样例回归，不是实际模型召回率或完整语义验收。最新全量已在实际JDK21运行，仍不能替代同生产镜像、真实provider/Milvus或生产验收。详见[0007验证](docs/changes/0007-text-answers/verification.md)。

## 文档导航

- [Java / Spring 人机协同规范](docs/JAVA_DEVELOPMENT_STANDARDS.md)：阿里规范裁剪结合 deep Module / 奥卡姆剃刀；0005 本地重构验收通过，后续执行入口在 AGENTS
- [AI_CONTEXT](docs/AI_CONTEXT.md)：项目是什么、代码在哪里、哪些不能假设
- [ARCHITECTURE](docs/ARCHITECTURE.md)：Module、数据与安全约束
- [API](docs/API.md)：当前真实 HTTP 契约
- [TEXT_ADAPTERS](docs/TEXT_ADAPTERS.md)：模型与Milvus Interface、环境配置及实际验收边界
- [TEXT_INGESTION](docs/TEXT_INGESTION.md) / [TEXT_INDEXING](docs/TEXT_INDEXING.md)：解析与索引任务、进程、迁移和发布契约
- [ROADMAP](docs/ROADMAP.md)：文本、多模态、生产迁移路线
- [AGENTS](AGENTS.md)：AI 开发约定
- [SECURITY](SECURITY.md)：密钥与安全报告
- [llms.txt](llms.txt)：机器可读文档导航，不保证被任何搜索引擎或模型收录

本仓库尚未指定开源许可证；公开可读不等于已授予 MIT/Apache 等许可。
