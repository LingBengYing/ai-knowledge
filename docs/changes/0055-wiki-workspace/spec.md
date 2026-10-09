# 0055 行为合同

## 主线

输入：显式选择已发布资料、知识页标题与类型。创建完整页面更新提案；用户读取 before/after 与逐章节来源，再采纳为不可变知识页新版本。刷新及重启后仍可读取。更新/撤回的来源使页面显示 stale，不能继续作为当前原文读取或采纳。

首切两种明确生成方式：`extractive` 为完整文字原文摘编（零模型，不声称 AI 综合）；`model` 使用既有 TextModels 协议能力生成章节草稿，必须返回服务器提供的 evidence id，并经人工审阅才发布。模型请求不自动重试。已发布的图片 OCR、音频转录、视频转录/OCR/字幕可参加文字编译；纯图像材料明确不支持首切编译，不静默当成文字。

## 公共 API

- `GET /v1/wiki/pages?offset=0&limit=20&q=`：列表与总数，组织范围先于分页。
- `GET /v1/wiki/pages/{id}`：当前版本、章节、逐章节来源、current/stale。
- `GET /v1/wiki/pages/{id}/versions/{version}`：历史版本，不覆盖原内容。
- `POST /v1/wiki/proposals`：`page_id` 可空，新页 `base_version=0`；已有页需当前版本。`title`, `kind`（topic/entity/procedure/overview）, `document_ids`，`generation_method`（extractive/model）。生成提案不自动采纳。
- `GET /v1/wiki/proposals?offset=0&limit=20&status=pending` 与 `GET /v1/wiki/proposals/{id}`：真实状态与 before/after。
- `POST /v1/wiki/proposals/{id}/accept`：正文 `base_version`，同一 authority 事务复验所有来源、基础版本，新增页版本并结束提案。
- `POST /v1/wiki/proposals/{id}/dismiss`：正文空对象，保留提案历史。
- `GET /v1/wiki/pages/{id}/versions/{version}/sources/{sourceId}`：根据持久服务器绑定回读 typed 原文来源。提案也提供 `GET /v1/wiki/proposals/{id}/sources/{sourceId}` 供审阅。

错误沿用现有 ApplicationException/ProblemHandler。不存在404、版本或状态冲突409、非法输入422、模型不可用503。HTTP只输出公开DTO，不序列化内部publication target或私有路径。列表数量为真实数据库统计，不把分页当全量。

## 内部合同与所有权

Domain：`WikiContent(title, kind, List<Section>)`；Section(id, heading, body, List<Source>)；Source(id, PublicationVersion publication, FileSynopsis.Reference reference)。`WikiPageRevision(pageId, version, content, modelRevision, policyRevision, createdAt)`；`WikiProposal(id, pageId, baseVersion, before, after, generationMethod, modelRevision, policyRevision, status, createdAt, reviewedAt)`。时间为epoch毫秒；before在新页时null。

Repository所有方法由Service持有既有Store事务；页版本不可变，提案仅pending→accepted/dismissed。接受使用数据库CAS及同一事务，失败不留下半个页面。按actor.workspaceId限定所有读写，来源保存完整PublicationVersion与Reference，不合并不同文件的身份。

Schema v31只新增Wiki表/触发器/索引，沿用v30→31备份与完整schema inventory。旧源文件和索引不重写。迁移仅在新临时测试库验证，本轮不启动旧数据目录。

## 验收与边界

真实SQLite+合成已发布资料：创建提案、来源回读、采纳、历史版本、冲突、更新后stale、重启持久化；真实HTTP与协议替身验证。编译模型Adapter为loopback协议测试，不代表真实模型质量。

首切不引入图数据库、自动定时编译、对话长期记忆、撤销已采纳版本、自动增量影响传播或问答全文替换。普通问答继续使用原始RAG链，检索设置/Milvus不变。知识图谱与真正Wiki引导问答另走下一主线，不以首切冒充完整预览后端。
