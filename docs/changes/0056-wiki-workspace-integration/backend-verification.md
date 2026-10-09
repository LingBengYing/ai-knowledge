# 0056 Catalog / Drafts 本机验证

日期：2026-10-09。范围仅新增文件目录发现、持久草稿、schema32 与直接相关 Wiki / 迁移 / 分层回归；不代表真实模型质量或生产发布验收。

## 可观察行为

- Catalog 一份原文件一项，先完整组织范围、类型与名称/正文匹配，再计算 total 和分页。完整文字页、图片 OCR、音频转录、视频转录、视频帧 OCR 与字幕尾部均可找到；不查询派生 Wiki 正文、草稿、摘要或画面描述。
- 保留当前版本与 SHA；未发布文件可按名称找到但不伪造 `answerable`。查询为字面大小写不敏感匹配，分页不是向量 TopK。
- 草稿创建、同组织共享读取、显式更新、CAS 冲突、显式删除与重启回读通过。服务端不接受客户端来源证明字段，草稿只写独立表。
- schema32 只新增草稿表、索引与版本触发器。真实 v31 快照迁移保留所有旧表内容及旧 schema 定义，备份版本、数量和 SHA 仍逐项校验；新版本触发器被篡改后启动拒绝。

## RED → GREEN

`WikiWorkspaceHttpTest#catalogFindsWholePublishedTextAndDraftsArePersistentUnverifiedEdits`：

- 11:09:28 +08：新增 HTTP 行为测试预期 Catalog 200，旧实现实际 404；1 项失败，0 错误、0 跳过。
- 11:13:00 +08：实现后同一测试通过；覆盖查询、草稿创建/更新/CAS/删除与真实应用重启。

## 相关回归

采用离线 Maven、独立 `/private/tmp/wiki-0056-backend` 构建目录、新建临时 SQLite 与 loopback 替身；未连接旧业务数据或云模型。

| 时间（+08） | 选择器 | 结果 |
| --- | --- | --- |
| 11:17:43 | `Wiki*Test,*MigrationTest,ArchitectureTest` | 178 项通过，0 失败/错误/跳过；`ArchitectureTest` 名称未匹配实际类，不据此认证分层 |
| 11:18:52 | `Wiki*Test,ArchitectureRulesTest,OpenAiCompatibleWikiModelsTest` | 60 项通过，0 失败/错误/跳过，含 11 项真实分层检查 |

两批存在重叠，不累加为独立用例数量。两批均包含主任务新增的 `WikiWorkflowHttpTest`：真实 Spring 上传、解析 worker、索引、Wiki 编译、问答与重启链，模型与向量服务为本机协议替身。

第二批随后执行完整 Spotless 检查，1175 个文件中仅主任务负责的两个新增联调文件（`WikiWorkflowHttpTest`、`WikiLocalIntegrationServer`）需要格式化，已通知负责人处理，因此该组合命令最终退出 1。Catalog/Drafts 所属 27 个新增及必要适配文件已单独实际格式化；完整格式通过以主任务最终记录为准。

## 浏览器发现的 JSON 合同缺陷修复

首次页面联调发现 Catalog DTO 使用 Java 默认驼峰字段，前端依约读取 `document_id` 后为空，导致原资料链接缺少 ID；草稿时间字段有同类问题。这说明前述仅检查 HTTP 状态、总数与正文的测试不足以验收线缆合同。

- 11:30:32 +08 RED：加强真实 HTTP 断言后，服务返回 `documentId`，`document_id` 实际为空，1 项失败、0 错误/跳过。
- 仅为 `WikiCatalogResult.Item` 的 6 个复合字段及 `WikiDraftResult` 的 2 个时间字段添加显式 `@JsonProperty`；未修改全局 Jackson 命名策略或其他接口。
- 11:31:21 +08 GREEN：完整 `WikiWorkspaceHttpTest`、`WikiWorkflowHttpTest`、`WikiCatalogServiceTest`、`WikiDraftServiceTest`、`WikiDraftRequestMapperTest` 共 10 项通过，0 失败/错误/跳过；3 个相关修改文件实际格式通过。
- HTTP 测试现在断言 Catalog 的真实 `document_id` 值、其余 snake_case 字段及驼峰字段不存在；草稿创建、更新、列表、重启读取均验证 `created_at` / `updated_at`。

此修复不改表、不改资料、不重新配置模型；新的页面构建与重验由主任务继续完成。

## 未验证与边界

- 本记录不替代浏览器实操、真实服务商语义质量、Milvus 实例质量或生产发布回归。
- 新增云请求 0；未修改旧资料、模型配置或运行中服务，未推送或部署。
- `answerable` 是当前完整已发布文字存在性，不是运行时模型健康承诺。
