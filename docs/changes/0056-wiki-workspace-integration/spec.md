# 0056 合同

- `GET /v1/wiki/catalog?offset=0&limit=20&q=&kind=`：原始资料目录与文件级关键词发现，先组织范围/匹配/类型过滤再count与分页。关键词匹配文件名、显示名以及完整已发布文字/OCR/转录/字幕，不用向量TopK，不调用模型，不推断别名。结果每份文件一行，保留实际文件身份，提供片段excerpt/匹配数和真实处理/可问状态；纯视觉无文字只匹配名称。未发布文件仍可按名称列出，不假报可编译。
- Catalog result `{items,total,offset,limit}`；item最少`document_id,filename,display_name,media_type,kind,state,answerable,source_revision_id,source_sha256,excerpt,match_count`，状态派生现有authority/任务，不修改旧数据。
- `kind` 为 `document|image|audio|video`，空值表示全部；`q` 为大小写不敏感的字面关键词，不将 `%`、`_` 解释成 SQL 通配符。`answerable` 表示当前版本存在完整已发布文字证据，不表示模型服务健康或纯视觉问答已验证。`match_count` 为原文非重叠匹配数；仅名称命中为 1，空查询为 0。
- `/v1/wiki/drafts` GET分页/POST新增；`/{id}` GET/PUT/DELETE。POST`{title,body}`，PUT`{version,title,body}`；DELETE 固定为 `/{id}?version=N`，无请求体，成功返回 204。result `{id,title,body,version,created_at,updated_at}`，时间为 epoch 毫秒，首次版本为 1，更新每次递增 1。明确未核验草稿，不作为检索原文，不自动变知识页；正文不执行、不接受自由来源定位作为证明。
- 同组织共享，跨组织拒绝；CAS冲突409，非法输入/缺失沿既有错误合同。持久schema32附加draft表，不修改原资料或索引；保存/删除为显式操作，删除仅目标草稿。
- 知识关系由前端根据真实共同来源派生，不新增语义图或图数据库。普通问答沿原knowledge-answers；没有新模型协议。
- 首轮主线联调用显式test-source启动器、真实Spring/SQLite/parser/index worker和loopback标准模型/Milvus协议替身。生产源码不包含fixture默认配置/凭据/自动seed；其验收不冒充真实服务商结果。
- 最新真实流程授权：本机运行生产 JAR，使用新隔离 data、线上相同 DeepSeek / 硅基流动角色与真实 Milvus 新 collection；模型全部实转发并记入统一新 ledger，不用旧余额、不自动重试失败。只验证单资料导入索引 → 模型 Wiki 编译审阅采纳 → 综合问答及同版本引用，不另行触发重建，不改生产或旧资料。执行方为首条闭环设置最多 6 次模型 HTTP 自限，不记为用户数字授权；实际请求与结果由 root 记录。
- 最新分工覆盖后续执行范围：知识问答由其他智能体负责，本任务不改问答代码、不追加提问，主线限定资料入库、模型编译、审阅、版本与来源。范围切换前已发问答仅只读收尾，不作为接管问答的授权。第 6 次实际用于知识页更新编译，其后历史版本、原文及同库重启回读均为零模型请求。

本轮限定验收 `REAL_SINGLE_TEXT_WIKI_WORKFLOW_VERIFIED`：新单 TXT 导入、解析、索引、两次真实模型编译与采纳 v1/v2、历史及同版来源回读通过。实际 6 次全 HTTP 200（SF 嵌入 2 / 重排 1，DS Wiki 编译 2 / 已发问答 1），统一新台账闭合 halted，不扩大问答验收。当前本机 18090 → 18092，不是线上发布；多模态、全量质量及生产未验，70 项扩展 Java 回归中 7 项失败保留。证据见[provider-run.md](provider-run.md)。

服务端继续Controller/Service/Repository/Model/Config分层；小接口复用SynopsisMaterialRepository/EvidenceRepository，完整来源读取不伪造摘要。真实模型质量、生产发布与长文自动增量编译不由替身验收代替。
