# 检索设置

当前本地变更：[0054](changes/0054-retrieval-settings/REVIEW.md)。页面入口为“设置 → 检索设置”。发布状态以该记录为准，源码存在不代表线上已经升级。

## 使用方法

1. 选择“向量检索”“全文检索”或“混合检索”。全文检索使用现有Milvus BM25，不请求查询嵌入；另两种需要已生效的嵌入模型。
2. 选择重排模型，或使用检索分数排序。混合检索不用重排模型时可以设置语义/关键词权重，两者相加为1。
3. 设置TopK：最终最多保留多少个片段（1–20，默认5），不是限制可以查多少份文件。普通问答的文档和视频文字合并后使用一个TopK。
4. 可开启Score阈值；分数等于阈值也保留。阈值过高可能没有结果，过低可能保留噪声。初始值0.5默认关闭，未声称已针对任何真实模型校准。
5. 保存后下一次查询使用新版本；不会重新解析、重建索引、测试模型或自动提问。当前请求继续使用开始时捕获的设置。

召回测试默认继承此配置，可展开“仅本次测试”覆盖；不会自动保存，不随“带入问答”进入普通问答。召回测试目前只展示文字/OCR，普通综合问答还可检索视频ASR/字幕/帧OCR；两者使用相同筛选规则，但不是同一个候选集合。

## 分数含义

| 情况 | 最终排序/阈值分数 | 含义 |
| --- | --- | --- |
| 开启重排 | `rerank_score` | 当前重排服务商的原始相关性分数；不统一缩放，不保证所有模型都是0–1 |
| 向量、不重排 | `vector_similarity` | COSINE按(1+score)/2映射至0–1，浮点边界裁剪 |
| 全文、不重排 | `bm25` | 原始非负BM25分数，无固定0–1范围 |
| 混合、权重 | `weighted_score` | 归一化COSINE与2×atan(BM25)/π按权重融合，缺失路为0 |

混合候选在采用重排模型时仍以RRF合并召回两路；`rrf`只描述检索阶段的排序分，阈值比较的是随后得到的`rerank_score`。任何分数都不是回答事实正确率。切换检索方式或重排模型后需要重新观察分布、调整阈值，不能机械复用一个所谓最佳值。

最终选择顺序：排序 → 阈值过滤 → 完全相同原文去重 → TopK。仅选中原文片段进入普通生成模型；不将未选同页全文通过context旁路重新填入。相似但不完全相同的段落不会冒险进行语义去重；来源版本和服务器定位规则不变。

## API 与持久化

`GET /v1/retrieval-settings`读取；`PUT`完整替换：

```json
{
  "version": 0,
  "search_method": "hybrid",
  "ranking_mode": "rerank",
  "dense_weight": 0.5,
  "top_k": 5,
  "score_threshold_enabled": false,
  "score_threshold": 0.5
}
```

`version`是期望当前版本，首次保存0→1；版本冲突409，客户端应重新读取，不自动覆盖。请求使用已有组织身份，组织成员共用，不新增角色。页面免登录入口保留现有服务器侧共享身份方式，不把凭据发给浏览器。

`POST /v1/retrieval-tests`仅发送question时继承。`retrieval_settings`可携带除version外完整六字段临时覆盖；与旧top_k/rerank不能同时传。响应`effective_settings`返回实际参数及全局基准版本；原`configuration_version`继续表示模型版本，不混用。

文件为SQLite数据文件同目录`retrieval-settings.json`，绑定workspace，原子写入，须纳入数据目录备份。无配置时只读默认值，不写盘；损坏文件不回退覆盖。不改变模型配置、索引身份、数据库schema或旧资料。新普通问答trace的policy_revision包含设置版本及SHA指纹，日志不保存模型正文或密钥。

专门的原图、原声、音画、参考附件和旧product-help搜索接口保持已有合同；本页设置不冒称已控制这些独立路径。

参考：[Dify知识检索](https://github.com/langgenius/dify-docs/blob/main/en/cloud/use-dify/nodes/knowledge-retrieval.mdx)、[Milvus加权排序](https://milvus.io/docs/reranking.md)。
