# 摘要建议标签

[0024](changes/0024-tag-suggestions/spec.md)和前端0013提供：从已保存的当前摘要推荐短标签，用户勾选后合并保存，再按标签筛选。标签只用于整理资料，不作为问答事实来源。没有新模型调用、生成队列或表；不会自动创建摘要或覆盖手工标签。

启用既有文件摘要配置后，服务增加tag_suggestions能力，无新provider或密钥配置。资料必须有当前有效available摘要；短/长摘要和四类文件均复用同一个已保存FileSynopsis合同。缺少摘要时先显式完成现有摘要流程，读取建议本身不调用模型。

候选按术语、主题的顺序取完整短条目，每条最多40 Unicode code point，排除控制符和中英文逗号/分号；精确去重后最多8个。不截断长句或猜测关键词，无合适条目时返回空集合。已有标签由页面排除选择，服务器候选序号和建议指纹不随已有标签变化。

- GET /v1/documents/{documentId}/tag-suggestions：无query/body，读取当前摘要派生候选及当前标签。reader可看，can_apply只在当前可编辑时为true。
- POST /v1/documents/{documentId}/tag-suggestions/apply：仅JSON，发送suggestion_fingerprint和ordinals，不发送标签正文。当前编辑ACL、摘要与完整publication/input/model/policy、重算候选指纹和合并保存处于同一事务。

示例确认正文：

```json
{"suggestion_fingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","ordinals":[1,3]}
```

示例指纹仅为格式展示，不能用于真实请求。建议过期返回409 tag_suggestions_changed；无当前资料/摘要或失权沿既有404。非法选择为422，合并超过既有20标签上限为409 tag_limit_reached，整体不写。成功200返回既有资料行；原文件、源SHA、revision和索引publication不变，审计复用hash-only元数据审计。

页面在当前摘要可读时提供“从摘要推荐标签”，读取和勾选不保存。“合并保存已选标签”只追加所选项并去重，保留此刻其他编辑者新增的标签。有未保存整理草稿时先处理草稿，避免刷新丢失修改。失败需显式刷新，不自动重试可能已经提交的写入。

实际验证见[verification](changes/0024-tag-suggestions/verification.md)。本轮是摘要短条目派生的保守建议，不是自由分类模型；真实类别质量、无摘要自动分类和页面像素不由本机测试推定。尚未部署，页面由用户验收。
