# 文本主线：新增30次调用批次

状态：本轮固定PDF文本后端主线验收完成，Java整体仍IMPLEMENTATION。真实生成、Service及完整HTTP正常路径通过，完整回归已完成。2026-09-08负责人明确授权“可以运行30次”。仅单组织合成文本主线，真实SiliconFlow与隔离Milvus；不推送、部署、改前端或旧数据。

- 本批上限30次，此前已耗尽的3+2次不计入本批。实际执行逐项记录，不能把预留额度当成已调用或成功。
- 每次最多60秒，无自动重试。先用现有生成诊断入口取得可用证据，再执行已有固定PDF的4次链路；成功后只推进当前主线必需步骤，不为用满次数调用。
- 固定原三条合成材料与PDF；不发送用户真实业务资料。密钥只进短生命周期进程环境，不写仓库、命令参数或日志。
- 生成模型候选来自本日已取得的模型清单。首先只将原免费Qwen2.5-7B改为同系列Pro入口，其他Java Adapter/问题/证据/JSON参数保持；模型池、网络路径、请求兼容性按单变量探针逐项区分，不提前断言根因。
- root执行外部请求；另一智能体仅只读确认现有LiveIT及编译副本一致。单次LiveIT仍设置4次上限，而不是将其开关改为30。

## 执行记录

当前已执行13/30次模型请求，剩余17次；已停止本批模型调用，没有自动重试。另有一次带鉴权模型列表读取和三次不带凭据的连通性检查，均非模型推理。

| 次序 | 验证 | 结果 |
| --- | --- | --- |
| 1 | 相同Java生成IT，仅改Pro/Qwen2.5-7B | 60秒model_timeout，未改生产超时 |
| 2 | 同模型与代理，curl极简JSON消息 | 60秒，HTTP000、响应0字节 |
| 3–4 | 极简消息分别去掉response_format、强制HTTP/1.1 | 两项各60秒，HTTP000、响应0字节；并行的独立探针，不是重试 |
| 5 | 相同Java生成IT，仅改deepseek-ai/DeepSeek-V3 | 14:31:05 +08:00通过，1项0失败/错误/跳过，suite58.456秒，650元原文摘录断言通过 |
| 6–9 | 原固定PDF TextAnswersLiveIT：索引embedding、问题embedding、rerank、extract | 14:32:59 +08:00通过，1项0失败/错误/跳过，suite22.193秒；实际解析/索引子JVM、Milvus、publication、AnswerService、source与trace均通过 |
| 10–13 | 不变JAR完整HTTP：原PDF上传、真实任务、嵌入/Milvus/重排/生成、来源回读 | 14:42:27 +08:00通过，四个实际上游请求均HTTP200，原文引用回读一致 |

免费Qwen历史失败保留，本批仅证明所选DeepSeek在此时可完成两项验收，不证明原Qwen永久故障或网络完全无问题。生成JSON协议参照[供应商官方文档](https://docs.siliconflow.cn/docs/api/chat-completions-post)，模型存在性另由实际带鉴权GET/models（HTTP200）确认。

额外连通性证据：经代理GET/models无凭据返回401（1.449秒），无凭据POST/chat/completions返回401（1.680秒）；直连GET/models在10秒内未建立连接。因此未再发送原先计划的直连模型请求。另一个待输入凭据的embedding探针在发出前被取消，进程已退出，没有创建attempt目录，不计入调用数。

PDF测试前后都实际回读专用Milvus数据库为空；本次owned集合已清理，没有删除数据库或其他数据。该IT走Service，不冒称完整HTTP入口或网页已验收。

| 本地执行报告 | SHA-256 |
| --- | --- |
| DeepSeek生成IT JUnit XML | `2052646f6f79173559c9db544b8cdeb69d9dd0931e3c550c3bc4bd98dc4290f3` |
| 固定PDF LiveIT JUnit XML | `3bcdd2c1eb776a8cb62eae5294459ea956883673b8dfb2d40a3be45af3cbb62e` |

两个成功入口使用实际Temurin21.0.12.1+1、既有源码及已验证隔离编译副本，未修改生产Java。原报告保存在本次受控临时运行目录，不提交含环境信息的原始日志。

## 完整HTTP正常路径

使用实际Temurin21和已验证不变JAR（SHA-256 `83c9860ffedce0f384e4dcaa15b1ef3ac9574222c61b922f5f1de2c82564f7be`），未重新打包或更改生产Spring Bean、Job、worker。新建数据目录和随机collection，不复用Service IT的publication。实际流程：

1. 新服务启动，列表为空，三个能力开关启用；readiness仍503，不解除生产gate。
2. `POST /v1/documents?filename=…`上传原PDF返回202，实际摄取任务解析出1个分块，active revision仍为空。
3. `POST /v1/documents/{id}/index`返回202；实际索引worker完成嵌入、Milvus校验和publication，列表active revision对应上传版本。
4. `POST /v1/answers`只选择该文档、提问“上海住宿标准是多少？”，返回`answered`及“住宿：上海住宿标准为每晚650元”。
5. GET返回的source URL，answer ID及完整citation与原答复相同；PDF摘要、文件名、document/revision一致，引用为第1页code point范围`[41,57)`，quote摘要`1b8c0b0868b87dc82164d67731c61aa1ced94b48dc3d2c75861a4d02bfb8b536`。

生产worker不继承父JVM代理。本次标准模型base_url指向仅loopback的临时转发网关；网关将请求/响应body原字节转发至实际SiliconFlow，传回真实HTTP状态，没有模拟JSON或答案。网关使用单独临时本地凭据，供应商密钥仅留在网关进程；`Content-Type`固定JSON，不宣称所有响应头完全透明。该路径是开发环境代理接线，不是生产直连、TLS拓扑或独立网关产品的交付。

网关逐次在发出请求前检查总上限4及分项2/1/1，实际为索引embedding、问题embedding、rerank、extract各一次；curl无重试/跳转，最多59秒，应用请求上限仍60秒。此前零模型loopback自检证明测试传输的body未改变；首轮自检因sandbox缺少绑定权限失败，获准后原脚本通过，不计产品故障。

本次从启动到停止/清理约19.8秒，仅一个合成样本的观测，不是性能基准。结束后确认本次Spring进程已退出，精确删除本次owned collection并回读两个专用数据库均为空；没有删除数据库、其他集合、原资料或旧服务数据。应用日志未匹配实际供应商、Milvus或临时网关凭据。

另一智能体只读核对运行脚本的次数限制、原样body转发、正常HTTP断言与精确清理，未发现本项主线阻断；没有扩展异常/权限审查。

| HTTP证据 | SHA-256 |
| --- | --- |
| 本次安全结果JSON | `fa4de9a406a0eb706e87f5b104b67369ccaf66fa0ce54584d8e1ba2899859b98` |
| 本次临时HTTP执行脚本 | `58bdaf7dcd039718aa654f971332496770107f2c0ec5acb8b4e0cd87735f5901` |

重放需使用全新数据目录与collection，以及可达的OpenAI兼容模型端点；完整变量与调用顺序分别见[真实入口配置](text-answers-live.md)、[API](../../API.md)和[HTTP行为测试](../../../src/test/java/com/evidence/rag/web/TextMainlineHttpTest.java)。临时脚本/原始日志留在受控本地，不将个人路径、实际凭据或运行数据库提交仓库。

## 交付检查与边界

最终完整Java门禁于2026-09-08 **14:47:51 +08:00**通过：83个suite、858项测试，0失败/错误/跳过；241个Java文件格式通过，行覆盖6131/6465（94.834%）、分支3071/3585（85.662%），双80%门禁保持。Node回归73项通过。默认回归不调用真实模型；上述付费IT另列，不重复计入858项。

原6个golden全部通过，新正常HTTP测试1项通过。254个构建输入与当前工作树逐字节一致；对照0008原253输入，仅共享IndexingTestServer摘要变化，另新增TextMainlineHttpTest，152个生产/配置输入不变。固定golden文件原样保留，SHA-256 `f9482c00a0592f54350cfeb67370753d0a29955febdf4eb428fbe8aaaef1477a`。

首次完整Java回归的隔离副本漏拷`docs/evals/golden.json`，报`NoSuchFileException`，853项中0断言失败/1夹具错误；未修改测试或语料，原样补齐后重跑完整门禁。首轮失败日志保留，不能将执行夹具问题记为产品能力回归。

敏感信息检查器扫描当前index/对应工作树无发现；实际供应商密钥精确扫描仓库工作文件、本次报告和临时运行工件共543文件，0匹配。此检查不表示全机、聊天或历史已消除密钥；没有推送。临时runner与凭据文件不提交仓库。

| 最终门禁证据 | SHA-256 |
| --- | --- |
| 完整Java构建日志 | `7f4079e8f2be2b2c224281337b7494bc2413f38ac450ce57e77a06630c72b1ec` |
| Node回归日志 | `9b2a2aae517b20b6e1efc56976b963aab611258195eca54fcfd563132fccfbfd` |
| 最终6项golden JUnit XML | `261e7f171fcf03054a27c12f42d7d42727fe4c9dc948c872da19106525ff5300` |
| 最终正常HTTP测试JUnit XML | `aa7f909247bb87a2a18219e0d9fec92b31ffb23759c0aabb988a4a6b0847dd97` |

先前[生成超时](generation-diagnostics.md)、[本机HTTP替身验收](mainline-verification.md)各自保留。本项证明开发环境固定PDF文本主线，不代表全部真实语料评测、网页、多模态、容量、Linux同生产镜像或生产发布完成。
