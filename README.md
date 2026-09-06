# AI Knowledge · Java Edition

一个面向单组织的 **Java AI 知识库 / RAG（Retrieval-Augmented Generation）** 项目。

当前可运行的是 **资料管理工作台**：列表、授权分页、目录、标签、改名、批量整理和审计。后续目标是 Milvus 混合检索 + OpenAI-compatible 模型接入 + 可追溯引用 + 图片、音频、视频知识处理。

> **状态：开发中，非生产版。** 不要把路线图当作已实现功能。本仓库尚未接通上传、检索问答、Milvus、嵌入/重排/生成模型或多模态流水线；`/health/ready` 有意返回 503。

**For AI agents:** A standalone Java knowledge-management application being extended into an evidence-grounded RAG system. Read [AI_CONTEXT](docs/AI_CONTEXT.md), [AGENTS.md](AGENTS.md), and the capability table before making claims or changes. Planned capabilities are not implemented APIs.

## 当前能力

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| 传统列表、分页、搜索和类型筛选 | 可运行 | 授权过滤先于统计和分页 |
| 目录、改名、手工标签、批量移动/加标签 | 可运行 | 整理不会修改原文件身份或触发模型 |
| JWT / HttpOnly 会话 / 文档角色 | 可运行 | owner、editor、reader；开发身份仅限显式 loopback |
| SQLite 持久化与哈希审计 | 可运行 | 单写入者；重启保留；独立数据目录 |
| PDF / TXT / Markdown 文本解析 | 独立 Module 已测试 | Unicode code point 定位；**尚未接 HTTP 或隔离 worker，不可对公网文件使用** |
| 上传、任务、Milvus dense + BM25 | 待接通 | Java 专用 collection，不改写旧投影 |
| 嵌入、重排、生成与有证问答 | 待实现/接通 | 三种模型独立配置；rerank 为 provider 扩展协议 |
| 图片、音频、视频、联合事实与文件摘要 | 规划中 | 不等同于仅生成文件摘要 |
| 生产部署、迁移与真实性能对比 | 未验收 | 不声称 Java 版本已比 Python 更快 |

## 技术栈

Java 21 编译目标、Spring Boot 4.1.1、Maven、SQLite JDBC、PDFBox 3.0.8；前端为原生 HTML/CSS/JavaScript。JUnit、真实 SQLite/HTTP 测试、Node 原生测试、JaCoCo 行与分支双 80% 门禁。

不依赖 Python，不通过 Python 代理业务。无模型 API key 也能运行当前管理工作台。

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

## 配置与密钥

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

JWT 模式缺少 secret 会拒绝启动。浏览器通过 `POST /v1/session` 换取 HttpOnly、SameSite=Strict 会话，不把 token 放入 localStorage。当前版本不读取任何模型 provider key，添加变量不会自动启用模型。

**不要提交 API key、JWT secret、SSH 私钥、`.env`、数据库或运行日志。** [.gitignore](.gitignore) 与 [敏感信息检查](scripts/check-secrets.mjs) 是双层防护；完整处理流程见 [SECURITY.md](SECURITY.md)。若密钥曾被贴入聊天或日志，应在对应平台轮换，而不是只删除代码里的字符串。

## 测试与工程方式

```bash
mvn -s .mvn/settings.xml -gs .mvn/settings.xml spotless:apply
mvn -s .mvn/settings.xml -gs .mvn/settings.xml clean verify
node --test ui-tests/*.test.mjs scripts/check-secrets.test.mjs
node scripts/check-secrets.mjs --history
```

按 AI-Native 的版本化意图、规格、计划和验证证据推进小的完整功能链路，采用先失败后通过的测试；不以生成了代码或界面有按钮作为完成标准。见 [CONTRIBUTING](CONTRIBUTING.md)。

固定合成 PDF 位于 `src/test/resources/corpus/`；`docs/evals/golden.json` 保存原六个问答预期。**当前只验证 PDF 解析和定位，未通过 Java 检索/问答 golden eval。** 本地 JDK 22 的 `--release 21` 结果不能替代实际 JDK 21 运行、真实 provider/Milvus 集成或生产验收。详见 [验证记录](docs/VERIFICATION.md)。

## 文档导航

- [AI_CONTEXT](docs/AI_CONTEXT.md)：项目是什么、代码在哪里、哪些不能假设
- [ARCHITECTURE](docs/ARCHITECTURE.md)：Module、数据与安全约束
- [API](docs/API.md)：当前真实 HTTP 契约
- [ROADMAP](docs/ROADMAP.md)：文本、多模态、生产迁移路线
- [AGENTS](AGENTS.md)：AI 开发约定
- [SECURITY](SECURITY.md)：密钥与安全报告
- [llms.txt](llms.txt)：机器可读文档导航，不保证被任何搜索引擎或模型收录

本仓库尚未指定开源许可证；公开可读不等于已授予 MIT/Apache 等许可。
