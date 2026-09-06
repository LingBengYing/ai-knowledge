# Architecture：当前实现与未来边界

## 交付边界

这是独立的 Java / Spring Boot 资料管理应用，当前运行模式为 `management_slice`。可整理合成资料元数据，另有独立文本解析 Module；没有接通上传、语料发布、检索、问答或多模态处理。完整能力状态见 [ROADMAP](ROADMAP.md)，当前接口见 [API](API.md)。

[pom.xml](../pom.xml)声明 Java 21 编译目标、Spring Boot 4.1.1、SQLite JDBC 与 PDFBox。运行时版本和测试结果以 [VERIFICATION](VERIFICATION.md) 为准。启用虚拟线程不构成性能承诺，尚无可比较的吞吐/延迟基准。

## 当前运行路径

```text
同源浏览器：index.html + app.js
  ├─ api.mjs：同源 HTTP / 浏览器托管 Cookie
  └─ workbench-state.mjs：身份 epoch、并发读、选中项、写操作状态
        ↓
RequestContextFilter → AuthenticationFilter
        ↓
ManagementController → ManagementModule → 独立 SQLite java-library.db

CLI --seed-demo → DemoFixtures → ManagementModule

TextParser.parse(...) → Page / Segment
  独立库入口；当前没有通向 HTTP、worker、数据库或模型的运行时连接
```

## Module / Interface / Implementation / Adapter

复杂行为放在小 Interface 后面；不为未来可能性预先铺设抽象层。

| Module | Interface 与职责 | Implementation / Adapter 与验证 |
| --- | --- | --- |
| Management | `listDocuments`、`updateDocument`、目录/标签/批量操作；统一 ACL、事务、只允许元数据改动 | [ManagementModule](../src/main/java/com/evidence/rag/management/ManagementModule.java)封装 JDBC；[ManagementController](../src/main/java/com/evidence/rag/management/ManagementController.java)是 HTTP Adapter；[模块测试](../src/test/java/com/evidence/rag/management/ManagementModuleTest.java) |
| Authentication | `authenticate(request)` / `exchangeToken(token)` 返回 `Actor` | [AuthenticationModule](../src/main/java/com/evidence/rag/security/AuthenticationModule.java)、[过滤器](../src/main/java/com/evidence/rag/security/AuthenticationFilter.java)与[会话 Adapter](../src/main/java/com/evidence/rag/security/SessionController.java)；[JWT HTTP 测试](../src/test/java/com/evidence/rag/security/JwtHttpTest.java) |
| Text parsing | `parse(filename, mime, content)` 返回不可变 `Parsed` | [TextParser](../src/main/java/com/evidence/rag/corpus/TextParser.java)封装 PDFBox、UTF-8 解码与分块；[解析测试](../src/test/java/com/evidence/rag/corpus/TextParserTest.java)，未连接业务运行路径 |
| Browser state | 身份变化使旧票据失效；只接受当前有效读写结果 | [workbench-state.mjs](../src/main/resources/static/workbench-state.mjs)、[notices.mjs](../src/main/resources/static/notices.mjs)；[UI tests](../ui-tests/) |
| Runtime / HTTP boundary | 配置验证、能力声明、安全错误与响应头 | [RagProperties](../src/main/java/com/evidence/rag/config/RagProperties.java)、[RuntimeGuard](../src/main/java/com/evidence/rag/config/RuntimeGuard.java)、[RuntimeController](../src/main/java/com/evidence/rag/web/RuntimeController.java)、[ProblemHandler](../src/main/java/com/evidence/rag/web/ProblemHandler.java) |

只有确有两个 Adapter（如真实 provider 与测试替身）时才引入 Seam；目前不存在已完成的检索或模型 Seam。

## 启动、配置与迁移隔离

[RagApplication](../src/main/java/com/evidence/rag/RagApplication.java)是唯一 Java 启动入口。正常启动 Spring 应用；`--seed-demo NEW_DATA_DIRECTORY` 是显式 CLI，仅向全新数据库写入合成元数据，不上传或解析文件，也不会自动在服务启动时执行。

[application.properties](../src/main/resources/application.properties)和配置校验控制以下边界：

| 环境变量 | 默认 / 约束 |
| --- | --- |
| `RAG_ENVIRONMENT` | `development`；仅接受 `development` / `test`，拒绝 `production` |
| `RAG_BIND_ADDRESS` / `RAG_PORT` | `127.0.0.1` / `18084` |
| `RAG_AUTH_MODE` | `jwt`；也可显式设 `development_headers` |
| `RAG_WORKSPACE_ID` | `org-main`；每个进程固定单组织 |
| `RAG_JWT_SECRET` | 无可用默认值；JWT 模式要求至少 32 字符，拒绝占位前缀；不能提交真实值 |
| `RAG_JWT_ISSUER` / `RAG_JWT_AUDIENCE` | `evidence-rag` / `evidence-rag-web` |
| `RAG_DATA_DIRECTORY` | `./.data`；必须为独立 Java 数据目录 |

开发头模式要求绑定字面量 loopback 地址，并检查请求 Host 为本机；它不是可部署到公网的认证方案。JWT 模式也不解除生产 gate。反向代理转发头不被自动信任，不能假定当前配置已具备代理/TLS 部署认证。

[run-dev.sh](../run-dev.sh)启动已经构建的 JAR，并先复制到临时运行目录，避免后续构建覆盖正在运行的 JAR。它不负责构建、不自动换认证模式、不解除启动门禁。

## SQLite authority：单进程 writer

[ManagementModule](../src/main/java/com/evidence/rag/management/ManagementModule.java)持有一个数据库连接；公共操作 `synchronized`，事务使用 `BEGIN IMMEDIATE`，锁等待上限配置为 5 秒。生命周期文件锁 `.java-library.lock` 阻止同一规范化目录被第二个 Java writer 打开。当前不是多副本或分布式数据库架构。

- 数据库为 `java-library.db`，具有独立格式标记 `evidence-rag-java-management-v1`。
- 拒绝带旧 `rag.db` / `authority.db` 的目录、危险符号链接和不匹配的数据库格式；不就地复用其他实现数据库。
- `documents` 保存合成源身份及可编辑展示元数据，`document_acl` 保存 `reader/editor/owner`，`folders`、`document_tags` 和 `management_audit` 保存整理状态。
- 所有列表、总数和分页 SQL 先约束组织与 ACL；目录可见性来自目录所有者或其中有权访问的资料，目录计数只计算当前用户可见资料。
- 只有资料 `owner/editor` 能更新展示元数据；只有目录所有者可改名/删除目录。非空目录删除失败，不隐式删除资料。
- SQLite 触发器保护源身份与审计记录；元数据变更和审计在同一事务内。批量操作逐项事务，允许部分成功并返回逐项回执。
- 审计 Interface 只返回当前 actor 最近至多 100 条；没有 HTTP 审计端点。审计保存字段名及前后值的摘要，不保存这些前后值的明文；这是本地追踪，不是外部不可篡改审计系统。

合成记录的 `active_revision_id` 仅是演示元数据。当前没有真实 active revision 发布机制、持久化证据分块或可用于生成答案的 corpus authority。

## 身份与浏览器信任边界

- JWT 仅接受 HS256，校验签名、`iss`、`aud`、固定 `workspace_id`、`sub`、整数 `exp` 及可选 `nbf`；没有令牌签发、刷新、SSO 或用户管理。
- HTTP 接受 Bearer 或 `rag_session` Cookie；显式 Authorization 优先，错误 Bearer 不回退 Cookie。重复身份头或重复会话 Cookie 被拒绝。
- 会话交换仅在 JWT 模式可用。Cookie 为 HttpOnly、SameSite=Strict、Path=/；TLS 或非 loopback 链路加 Secure。删除会话清除 Cookie，不撤销已签发 JWT。
- `/v1/` 写请求若携带 Origin，必须单一且与请求 scheme/host/port 精确同源。无 Origin 的非浏览器调用仍需正常身份；不要描述成独立 CSRF token 机制。
- [RequestContextFilter](../src/main/java/com/evidence/rag/web/RequestContextFilter.java)设置请求编号、no-store、nosniff、no-referrer 与同源 CSP；错误响应不输出内部异常、令牌、源码正文或堆栈。
- 浏览器使用 `credentials: same-origin`，不将 JWT 放入 localStorage / sessionStorage；输入提交后清空。身份切换与异步读取用 epoch/ticket 限制旧结果回写。列表切换后的选中项和详情以当前状态为界，批量失败不会被成功回执覆盖。
- 不可信展示文字使用文本节点渲染；未迁移的上传、提问、摘要、来源及生命周期按钮明确不可用。

## 独立 TextParser 的实际能力

`TextParser.REVISION = java-text-parser-v1-codepoints`。输入为内存 `byte[]` 与文件名/MIME，输出：

```text
Parsed(pages, segments)
Page(number, text)
Segment(ordinal, page, start, end, text)
```

PDF 按页抽取文本；TXT / MD 严格按 UTF-8 解码。统一换行，移除文本文件 UTF-8 BOM，拒绝不合法控制字符。页号从 1 开始；`start/end` 是规范化页文本的 Unicode code point 半开区间，不是 UTF-16 字符索引或原文件字节偏移。分块目标 1200 code points、约 120 重叠，优先在中文句末/换行处分界。

边界：文件 1 字节至 20 MiB；文件名不含路径分隔符，后缀与 MIME/文件头匹配；PDF 最多 500 页，加密 PDF 拒绝；总抽取文本不超过 1,000,000 code points；无可用文本则失败。它不做 OCR、表格语义还原、图像理解、音频转写或视频抽帧。

这些限制**不等于解析沙箱**：当前同进程 PDFBox 没有独立 worker、强制 CPU/内存隔离或总执行时限。未完成隔离与生命周期设计前不能对外开放不可信上传。

四份[合成 PDF](../src/test/resources/corpus/)和 [golden](evals/golden.json)保留解析回归及未来 RAG acceptance 的输入。解析器把恶意指令与其他组织文字作为数据抽取，是预期行为；这本身不证明未来模型会拒绝提示注入或越权检索。

## 未来连接，尚未实现

目标链路是“受限上传 → 隔离解析任务 → 权威 revision/segment → 标准 OpenAI-compatible 模型 Adapter → Milvus 投影 → ACL/选中范围内混合检索与重排 → 服务端证据校验 → 有据回答/拒答”。每个箭头均需独立的失败测试、真实集成与相应验收；不能以这条目标链路宣称当前可用。

下一切、安全 invariant 和生产 gate 见 [ROADMAP](ROADMAP.md)；实际验证结论仅由 [VERIFICATION](VERIFICATION.md)记录。
