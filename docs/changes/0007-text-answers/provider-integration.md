# SiliconFlow 真实联调：部分通过，摘录超时

状态：`partial`。2026-09-08 09:08:40 +08:00 的单次真实测试完成：embedding、rerank 的限定断言通过，extraction 在原60秒预算内未完成，以 `model_timeout` 失败。整个 `SiliconFlowLiveIT` 是 **1项失败，0错误、0跳过**，不能写成生成、提示注入防护或整体RAG验收通过。机器可读结果与摘要见 [provider-integration.json](provider-integration.json)。

后续已授权的两次生成专项诊断也均超时，次数已耗尽。独立HTTP请求完成TLS后未收到首字节，尚不能区分代理与provider原因；详见[生成诊断](generation-diagnostics.md)。以下保持原三阶段测试的历史证据与边界，不以新的默认CI通过覆盖其失败。

## 范围与固定输入

本批按 [plan](plan.md) 的有界外部验证执行。主线程 root 运行模型目录查询、Maven与扫描；文档作者只读核对日志、XML、测试顺序和文件SHA，未调用模型，也不把自己编写测试当成独立代码审查。

- 使用现有生产 [OpenAiCompatibleModels](../../../src/main/java/com/evidence/rag/client/model/OpenAiCompatibleModels.java)，通过 `TextModels` 公开Interface调用；没有生产代码或供应商扩展参数修改。
- 固定基址 `https://api.siliconflow.cn/v1`；root报告本轮一次 `GET /models` 成功，确认下列三个模型别名在返回目录中。目录可见不等于完整调用成功、固定权重版本或质量认证。接口参考：[Models](https://docs.siliconflow.cn/docs/api/models-get)。

| 阶段 | 本次模型别名 | 配置与结果边界 |
| --- | --- | --- |
| Embedding | `BAAI/bge-m3` | 1024维；三条合成文本各得到有限、非全零向量 |
| Rerank | `BAAI/bge-reranker-v2-m3` | 同三条输入的索引完整且不重复；相关政策排名第一，不将分数视为概率 |
| Extraction | `Qwen/Qwen2.5-7B-Instruct` | 原JSON摘录协议、`max_tokens=2048`；本次60秒超时，未得到可验收摘录 |

模型维度参考 [BAAI/bge-m3 模型卡](https://huggingface.co/BAAI/bge-m3)；协议参考 [Embeddings](https://docs.siliconflow.cn/docs/api/embeddings-post)、[Rerank](https://docs.siliconflow.cn/docs/api/rerank-post)、[Chat completions](https://docs.siliconflow.cn/docs/api/chat-completions-post)。这些官方契约由root在本轮核对，本文未再次发起网络请求。

[SiliconFlowLiveIT](../../../src/test/java/com/evidence/rag/client/model/SiliconFlowLiveIT.java)只使用三条短中文合成材料：住宿报销政策、无关天气、文档指令注入样本。没有上传用户文档、真实业务资料或固定PDF语料。生产Adapter每阶段预算60秒、响应上限1MiB；摘录请求最多2048输出tokens，不新增流式请求或参数补丁。

所有必填环境配置均在首次请求前验证：`RAG_SILICONFLOW_IT_API_KEY`、`RAG_SILICONFLOW_IT_EMBEDDING_MODEL`、`RAG_SILICONFLOW_IT_RERANK_MODEL`、`RAG_SILICONFLOW_IT_GENERATION_MODEL`、`RAG_SILICONFLOW_IT_DIMENSIONS`。无缺省假模型、无assumption/skip。

## 执行证据

显式入口如下；真实配置由受控进程环境提供，命令中不包含凭据值：

```bash
mvn -s .mvn/settings.xml -gs .mvn/settings.xml -Dtest=SiliconFlowLiveIT test
```

默认Surefire不选择 `*IT`，因此默认 `clean verify` 不自动调用计费模型。本记录不预报本轮默认635项回归结果，由root在 [verification](verification.md) 另行记录，不能用默认回归替代此处真实失败。

| 完成时间（+08:00） | 执行 | 实际结果 |
| --- | --- | --- |
| 2026-09-08 09:06:25 | 缺少显式配置 | 1 failure / 0 error / 0 skip；首个API key必填断言失败，尚未进入外部模型阶段。套件0.038秒，Maven 4.079秒；这是配置保护，不是provider故障 |
| 2026-09-08 09:08:40 | 一次真实模型联调 | 1 failure / 0 error / 0 skip；`SiliconFlow extraction failed: model_timeout`。套件65.036秒（日志取整65.04），Maven约66秒 |

真实测试顺序固定为一次 `embed` → 一次 `rerank` → 一次 `extract`，前阶段失败立即停止。XML失败位置是摘录调用；结合冻结源码，可确认此前已通过三条1024维向量的数量、维度、有限与非全零断言，以及完整 `{0,1,2}` 重排索引和政策第一的断言。没有各阶段独立延迟或provider侧访问日志，套件耗时不是单阶段性能基准。

摘录超时后没有执行其“非拒绝、精确原文quote、至少一个政策ID引用”等结果断言；注入样本虽在输入中，不能据此称注入验收通过。root没有重试、扩大预算、替换模型或删除失败测试。单次超时不证明provider永久故障，也没有足够证据确定其具体原因。

## 源码与报告绑定

以下摘要经文档作者重新计算；原始日志/XML保留在受控临时证据目录，不提交其内容。完整报告清单见JSON。

| 工件 | SHA256 |
| --- | --- |
| 格式化后 `SiliconFlowLiveIT.java` | `a60088bb8d62c2549f590f74a8952ab253d10208b33e3e0a2948826ecb3ba04c` |
| 缺配置 `0007-siliconflow-missing-config.xml` | `8a343fa5af0f86ffb544f8df3d45279ce4aa84199a73be029c03a1e9611cdb49` |
| 真实 `0007-siliconflow-live.xml` | `4390352ff14e9258f3774a8e48abad065beca1a3752e58d95e6b8619368a807b` |

真实XML记录运行时为 Eclipse Adoptium `21.0.12.1+1-LTS`、`aarch64`。这不是同Linux生产镜像的运行证明；模型别名也没有锁定供应商实际权重快照。

## 凭据与未验证边界

用户在本轮显式重新提供凭据并授权；据root执行记录，凭据经禁回显stdin进入短生命周期进程环境，相关进程已退出，未放入命令参数或仓库。本文没有接收、再次读取、保存或展示凭据值，也不声称凭据已轮换。

root对本次凭据值做精确匹配扫描：仓库工作文件（排除 `.git`、`node_modules`、`target`）、干净构建副本Surefire报告和本次模型日志，共445个文件、0次匹配；扫描JSON已只读核对并绑定摘要。它是新增本文之前的有限快照检查，不覆盖Git历史、全机文件、聊天记录，也不证明所有凭据绝对不存在或聊天已清除。

仍待独立、有界验收：生成/摘录协议与有证非拒答；固定PDF → 真实provider → 实际Milvus → 权威答案/引用的端到端链路。后续请求须另行明确输入、预算和次数，保留本次失败，不自动重复整套调用求绿。本记录不改变网页未接线、readiness/production gate、完整语义、多模态、容量或部署状态；没有上线或推送。
