# Spec

## 持久设置 Interface

`GET /v1/retrieval-settings` 和 `PUT /v1/retrieval-settings`：JSON字段为 `version`（GET当前版本，PUT期望版本）、`search_method`（vector/full_text/hybrid）、`ranking_mode`（weighted/rerank）、`dense_weight`（0..1，关键词权重=1-该值，仅混合加权生效）、`top_k`（1..20）、`score_threshold_enabled`、`score_threshold`（有限数）。PUT完整替换，旧版本409。复用受验证的组织身份，无新增角色限制。配置与模型/索引身份分离，原子持久化；保存不调模型、不重建索引。

无配置时默认version=0、hybrid、rerank、dense_weight=0.5、top_k=5、threshold关闭、值0.5。0.5仅初始可编辑值，不宣称对现有模型经过质量标定。TopK是最终片段数量而非可访问资料范围，界面明示1..20。

## 检索行为

- vector只查稠密向量；full_text只查BM25，不调用查询嵌入；hybrid同时查两路。
- weighted：混合时使用COSINE归一化 `(1+score)/2` 与BM25归一化 `2*atan(score)/pi` 的权重和，缺失路为0；单路分别采用归一化COSINE或原BM25。rerank：候选池混合沿既有RRF召回，然后由当前重排模型排序。
- 阈值在最终排序分上包含边界地过滤（score >= threshold）；rerank用服务商原始重排分，不能当事实置信度或任意归一化，BM25/融合/向量分各自清楚标识，不能拿RRF冒充0..1相关性。
- 统一问答文档与视频候选合并后统一排序、阈值、相同原文去重、全局TopK，送模型的证据与实际选择一致。必须保留每个选中证据的真实来源，不编造跨资料来源；空结果不发送生成请求。
- 查询开始捕获一次设置快照；过程中保存不改变该请求参数。回答审计记录设置版本/指纹；不改变已有模型revision或索引anchor。
- 旧专门媒体/严格证明入口保留原行为。本切设置适用于普通综合问答和文字/OCR召回测试；后者尚不是视频候选预览，界面如实标明范围，不宣称两者候选完全相同。

## 召回与前端

- 召回测试默认省略临时参数并继承持久设置；可传完整 `retrieval_settings`（上述除version外6字段）临时覆盖。旧top_k/rerank兼容，省略不擅自补默认；不得同时传新旧两组覆盖。
- 召回响应添加 `effective_settings`（同GET完整快照形状），旧configuration_version继续指模型配置。score_kind描述检索分；rerank_score描述实际重排分。
- 设置页提供三种检索方式、混合权重/重排策略、TopK、阈值开关与数字输入、保存/重新读取/错误状态。展示当前模型，链接既有模型配置，不复制凭据表单或自动应用未经测试模型。
- “带入问答”只带问题，不将临时检索参数偷偷保存。保存成功后下次请求生效，无重新解析/索引或付费动作。

## 官方参考

- [Dify知识检索节点](https://github.com/langgenius/dify-docs/blob/main/en/cloud/use-dify/nodes/knowledge-retrieval.mdx)：候选筛选、重排后TopK和score threshold。
- [Dify重排实现](https://github.com/langgenius/dify/blob/main/api/core/rag/rerank/rerank_model.py)：先阈值过滤/排序再TopK。
- [Milvus加权排序](https://milvus.io/docs/reranking.md)：不同检索分数归一化后加权；本项目公式与实际返回score_kind在API文档明确，不声称完全复制Dify。
