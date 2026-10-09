# 0052：真实定位与修复复验

独立批次：`lighthouse-protocol-recovery-twenty-20261008`；上限20次，所有服务商共用。用户已明确允许现有owner可访问线上召回片段及灯塔/Project测试发送给api.siliconflow.cn与api.deepseek.com；允许本地连接验证。旧5/7、4/6和20/27批次不续用。

## 阶段1：有观测的隔离定位——4/20，失败链已停止

输入：线上私密资料副本、当前活动模型v6、同Milvus仅读查询；唯一问题“灯塔”。副本启动前已关闭document-cleanup且检查最终SQLite无待处理后台任务。诊断JAR `8b2eb3bd56be352ace3ec2f1e320209acad286e14510c6f61359eb8716a32889`，只新增安全观测，不改变请求或证据规则。

| 调用 | 服务商/操作 | HTTP | 毫秒 |
|---|---|---|---|
| 1 | 硅基流动 embedding | 200 | 422 |
| 2 | 硅基流动 rerank | 200 | 126 |
| 3 | 硅基流动 rerank | 200 | 117 |
| 4 | DeepSeek extraction | 200 | 8799 |

答案编号`fc26e97e-9b1b-48b7-a0e8-02cf619fdce1`，终态`abstained/model_failure`、0引用；综合和核验未发送。安全诊断记录：

```text
operation=extract reason=finish_length phase=response finish_reason=length
content_characters=183 reasoning_characters=7451
prompt_tokens=713 completion_tokens=2048 total_tokens=2761 reasoning_tokens=1955
```

**已确认根因**：此次真实摘录的2048 completion预算被耗尽，其中1955为reasoning；剩余预算不足以完整输出结构化结果。上游HTTP200但finish_reason=length，旧客户端正确拒绝截断结果，Service随后归类为model_failure。不是本次密钥鉴权或召回为空，也不是推测的重复引用ID问题。没有保存模型正文，不能恢复历史两个请求的每个token；此结论直接对应本次同链复现。

失败后影子Java已停止（shadow_stopped=true）；原线上服务、资料、配置和索引未改。当前实际4/20、剩余16，不原样重发旧构建。下一步以确定性回归修复结构化生成预算，再在本轮额度内复验。

协议说明已核对[DeepSeek官方Chat Completions文档](https://api-docs.deepseek.com/api/create-chat-completion/)：JSON mode仍可能在finish_reason=length时输出被截断的内容；max_tokens约束总生成长度，usage可细分reasoning_tokens。官方现行thinking默认开启。修复仍用标准max_tokens，不增加专属thinking开关或改变模型角色；本文的实际根因由上面的本轮日志证明，不靠文档推断历史响应。

## 阶段2：预算修复复验——新增4，累计8/20，失败链已停止

8192预算修复的确定性回归先红后绿，隔离源码实际5个完整相关文件47项通过。JAR SHA `8a96728e6c39c5de5c134a30a01eb4b6d09b5e0cc6761cf44cbff04751f130fd`。仍只在私密新副本唯一提问灯塔，原服务/资料/配置不动。

硅基流动嵌入/两类重排HTTP200（432/111/135ms），DeepSeek摘录HTTP200（5777ms）。摘录安全日志为validated/stop，正文长度798、reasoning长度3713、prompt713、completion1326、reasoning965。说明这次完整协议成功，不再是截断。

最终答案`90b5c555-385b-499d-83b7-86368ce7a6bc`为abstained/incomplete_evidence、0引用，综合/核验未发，影子进程已停止。代码限定复查：灯塔必走TopicEvidence；该路径唯一可达incomplete_evidence是至少一条摘录不含完整灯塔锚点。未记录模型正文，不猜是哪条事实；旧通用摘录提示没有表达这一要求。下一步对齐专用主题摘录提示，保持原锚点/原文准入与全部上下文核验，不用剩余12次原样重发此构建。

## 阶段3：主题摘录合同修复——新增6，累计14/20，核验拒答后停止

修复包SHA `dc0bba58795a2e1c0caf04a5c382e4ad9da5a3c54703a49f17ae32a62da81093`，513项完整直接相关回归通过。唯一灯塔问答确实进入完整检索→主题摘录→综合→核验；SF三次HTTP200（414/117/117ms），DS三次HTTP200（5006/16321/13335ms）。

摘录、综合及核验全部validated/stop，completion分别1110/3657/2847。最终`6a7ea0ca-e656-40ca-98a1-99897d253bfd`为abstained/unsupported_synthesis，0引用；没有截断，也不再是主题锚点拒绝。核验协议合法不代表核验认可答案，目前未保存细分核验字段或模型正文，不能推断具体哪句话/哪条引用不获支持。

影子已停止，真实累计14/20，剩余6尚未发。原服务/配置/资料不动。此时用户明确要求删除客户端token上限，后续最终实现省略max_tokens/max_completion_tokens；旧8192包和本段验证是历史中间结果，不认证最新缺省参数的真实模型行为。不因删除参数自动原样重跑未解决的核验拒答。
