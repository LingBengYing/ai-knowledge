# Wiki 工作区（0055 / 0056）

将已发布原资料编译为**待审阅的知识页提案**，人工采纳后生成不可变版本。页面、章节和来源绑定真实持久化；18090 工作台已接真实 Java API，不再使用静态演示数据。详细边界见 [0055 REVIEW](changes/0055-wiki-workspace/REVIEW.md) 与 [0056 联调记录](changes/0056-wiki-workspace-integration/integration-verification.md)。本机模型与向量服务仍为协议替身，不代表真实服务商质量。

## 与已有 RAG 的关系

Wiki 是可维护的派生知识层，原文仍在现有 authority 中。它不会重新上传资料、修改原件、重建 Milvus 或把派生文章当作原始回答证据。普通问答仍使用 `/v1/knowledge-answers` 和已保存的 TopK/相关性配置。

当前使用单组织共享身份，不另造角色。Wiki API 复用既有认证入口；上线时保留既有免登录入口的身份适配。读取与原文摘编不依赖模型配置；只有明确指定 `generation_method: model` 才使用当前已应用文字模型，产生模型请求。

## 正常使用链

资料必须已完成解析、索引和发布；仅上传或 parsed 不算可编译来源。

1. `GET /v1/wiki/pages` 查看知识页。
2. `POST /v1/wiki/proposals` 生成提案，显式选已发布的 document id：

```json
{
  "base_version": 0,
  "title": "灯塔产品使用方法",
  "kind": "procedure",
  "document_ids": ["实际已发布的文档ID"],
  "generation_method": "extractive"
}
```

`extractive` 逐段保留完整原文，零模型调用；不是 AI 综合。`model` 调用同一 OpenAI 兼容客户端的 `/chat/completions`，让模型返回章节及服务器分配的证据ID；未知或缺失引用会失败，模型不会指定页码。模型草稿仍需人工审阅，不代表独立语义核验通过。

3. 从响应 `id` 读取 `GET /v1/wiki/proposals/{id}`，比较 `before/after`。每个章节 `sources` 提供文件、publication、revision、SHA 和 evidence 身份。
4. `GET /v1/wiki/proposals/{id}/sources/{sourceId}` 打开真实 typed 原文。其 `content_url` 提供原件，帧型来源提供 `frame_url`；服务器重新验证版本和SHA，媒体字节支持现有Range合同。
5. `POST /v1/wiki/proposals/{id}/accept`，正文 `{"base_version":0}`。成功返回知识页版本1；生成提案不会自动发布。
6. `GET /v1/wiki/pages/{pageId}` 读取当前页；`GET /v1/wiki/pages/{pageId}/versions/1` 读取历史。章节来源为 `/v1/wiki/pages/{pageId}/versions/1/sources/{sourceId}`。
7. 更新时提交同一 `page_id` 和当前 `base_version`，重新选择完整来源。采纳生成下一版本，旧版不覆盖。竞争提案使用旧版本采纳返回409。

暂不采纳：`POST /v1/wiki/proposals/{id}/dismiss`，正文 `{}`；保留历史，不删除提案。

## 状态、列表和错误

- 页面/提案的 `source_state` 为 `current` 或 `stale`。原资料更新/撤回后显示stale，无法把过期来源当当前原文回读或采纳；已经保存的派生页历史保留。
- 提案状态 `pending → accepted/dismissed`，已结束提案不能再次采纳或反向切回pending。
- 列表返回 `items,total,offset,limit`；默认20，最大100。页列表 `q` 为标题字面子串筛选，不是语义检索。提案列表 `status` 默认pending。
- 格式错误400，非法业务参数422，不存在404，版本/来源变化409，模型缺配置或编译失败503。开发头认证缺身份沿用旧422行为，JWT模式沿用既有认证合同。
- 来源正文只按服务器持久locator输出；页面正文按不可信纯文本/安全Markdown渲染，禁止直接innerHTML。原文中的指令不是系统指令。

## 持久化与分层

- `controller/WikiWorkspaceController`：HTTP形状与响应。
- `service/WikiWorkspaceService`：来源快照、编译、审阅事务、当前状态。
- `service/WikiCompilationService`：完整原材料→草稿、模型证据ID映射。
- `repository/WikiWorkspaceRepository`：组织限定、分页、版本CAS、提案状态。
- `model/domain/Wiki*`：不可变内部模型；`model/dto/Wiki*` 与 `model/vo/WikiSourceResponse` 为公开白名单。
- `config/WikiConfiguration`：沿用现有数据库与可选ManagedTextRuntime。
- `TextModels.compileWiki` / `OpenAiCompatibleModels`：沿用协议与请求计数，不按厂商复制Client。Wiki提示版本单独保存在model提案策略版本中，不改变既有嵌入/索引指纹。

SQLite v31增加 `wiki_page_revisions` 和 `wiki_proposals`，v32增加独立 `wiki_drafts`。启动新版时会先备份再迁移；本轮仅在临时库验证，没有升级线上库。切换旧版程序必须考虑其不认识新 schema，不能直接对新库运行旧包，更不能覆盖迁移后新增资料。

## 文件发现与草稿

- `GET /v1/wiki/catalog?offset=0&limit=20&q=&kind=`：文件级名称/完整已发布文字查找，类型为 `document|image|audio|video`。按文件归并、匹配后分页，独立于问答 TopK；不调用模型、不把 Wiki 或草稿正文混入原文。`answerable` 表示当前完整已发布文字存在，不代表运行时模型健康。
- `GET/POST /v1/wiki/drafts`、`GET/PUT /v1/wiki/drafts/{id}`：创建 `{title,body}`，更新 `{version,title,body}`，版本 CAS；`DELETE /v1/wiki/drafts/{id}?version=N` 无请求体。返回 `id,title,body,version,created_at,updated_at`，时间为毫秒。草稿不自动发布、不进入问答证据。
- 页面关系由完整知识页集合中同 document/publication/revision 来源派生，称“共同来源关系”，不冒充模型语义图。
- 检索配置复用 `/v1/retrieval-settings`，保存影响既有综合问答；模型设置在新版 `#/models` 完成，复用既有 `/v1/model-configuration` 保存、测试、应用及 `/rebuild` 合同。`/classic/`仅保留旧完整资料维护的兼容入口。

Catalog/Drafts分别为 `WikiLibraryController → WikiCatalogService/WikiDraftService → WikiCatalogRepository/WikiDraftRepository`，公开 snake_case DTO；没有把 SQL、模型逻辑塞入 Controller。

## 本机运行

后端新建隔离运行：`bash scripts/run-wiki-integration.sh`；前端仓库执行 `npm run dev:workspace`。默认18090→18091，仅loopback。前端 `/wiki/` 为真实入口，`/classic/` 保留原管理入口；独立0041静态预览仍可回归但不作为工作台数据来源。

启动器在独立目录编译、跑真实HTTP工作流后启动，不读取私密配置；支持同进程 `restart` 重开Spring/SQLite且保留本机向量替身。`stop` 会结束整个替身进程，其内存向量不具备生产持久性；再次启动必须用新目录，不用旧测试索引冒充持久Milvus。详见 [local-runtime.md](changes/0056-wiki-workspace-integration/local-runtime.md)。

## 后续主线

当前18090已接知识页/版本、来源阅读、提案审阅、文件发现、问答、持久草稿、共同来源关系、检索设置。问答仍以当前原始证据生成；知识页主题可预填问题，不把派生正文作为证据。后续为真实服务商质量、自动增量编译、真正语义关联与受控发布；当前没有关系图数据库、自动全库编译或自动发布。

首切编译文字、图片OCR、音频转录、视频转录/OCR/字幕；纯图片需要后续视觉编译能力，明确失败而不伪造文字。完整视频画面语义、多模态页面播放、真实模型质量、生产发布尚未由本切证明。
