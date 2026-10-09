# 灯塔请求：线上只读诊断

2026-10-08 20:56–21:00 +08。用户截图出现 `upstream_unavailable`，不同于此前 `Project` 的 `incomplete_evidence`。

核对线上SQLite问题SHA `c499ec83c91d203a288b63f3d95c5f830d244a1fe358e0ea1641ea5bc47ef300` 与“灯塔”完全相符：

- 20:50:58 综合问答 `5b3ce159-2599-4591-9285-32bf874611d0`：knowledge_answer_traces、model_failure、scope9、citation0、旧综合v2。
- 20:52:48 文字问答 `38b8e2c0-5293-4f8f-90d7-4c66e50d1120`：query_traces、upstream_unavailable、scope9、citation0、旧摘录v1。
- 实际Chrome当前页面 `https://<DEPLOYMENT_HOST>:18443/#/answers`（主机已脱敏），问题“灯塔”，选中“文字证据（含图片OCR）”，显示上述upstream_unavailable。没有重发、刷新或改选模式。
- 后端与entry均active，后端仍为20261004-unified-knowledge。0051尚未发布，本任务没有触发新模型请求。

旧AnswerService将TextModels.Failure、ProjectionException及CancellationException合并为upstream_unavailable；不能仅凭此代码区分嵌入、重排、生成或Milvus具体阶段。旧KnowledgeAnswerService的model_failure进入模型异常路径，也没有保留HTTP状态/安全细码，不能推断收费、欠费、限流或超时。

12:47–12:54 UTC 的backend journal为空，没有可恢复的阶段日志。scope9只表明授权范围，不证明这次实际召回成功。前端把上游错误统一显示“证据不足/拒答”是误导性分类，记录为后续前端修正，不在本次后端热修中夹带改动。

0051新增共用HTTP Transport的脱敏尝试计数与状态/耗时，为后续获批Project复验提供观测。它不能追溯恢复此次旧日志，也不证明上游故障已解决。本次只读诊断没有消费已批准的独立Project 6次额度；不会挪作灯塔重试。

## 调用链交叉核对

旧ProductHelpService将检索阶段异常独立映射为retrieval_embedding_failed、retrieval_rerank_failed或retrieval_search_failed。因此20:50的综合问答model_failure更具体指向后续摘录、综合、核验调用或响应校验；不能用20:52的宽泛upstream_unavailable反推这次召回成功。

旧、新OpenAiCompatibleModels的摘录和核验max_tokens均为2048，综合为16384，未显式发送thinking/reasoning设置。finish_reason不是stop、正文缺失、严格JSON结构不符同样会抛TextModels.Failure，即使HTTP为200。这个协议事实解释错误码的含义，但不证明此次出现了哪一种情况。

新增Transport日志只观察HTTP层，三种生成步骤均归generation，也不记录之后Adapter的安全异常细码；合法HTTP200外层JSON仍可能在后续解析失败。不得把transport_ok当作答案生成成功，不得宣称仅增加日志已修复上游问题。

21:09只读配置复核：当前active_version为5，不是早先预检记录的2。现存私密配置版本2和5都选择DeepSeek/deepseek-flash；版本5文件mtime为2026-10-08 20:55:52 +08（晚于两次失败），不能用当前版本倒推失败时配置。没有读取输出任何密钥，没有替换配置，发布必须保留最新选择。模型名称经[DeepSeek官方更新记录](https://api-docs.deepseek.com/updates/)确认有效，不以名称陌生判断配置错误。
