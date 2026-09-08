# 两次生成诊断：均失败，原因仍未定位

状态：FAILED / diagnosis incomplete。2026-09-08追加授权的两次生成调用已经执行且均失败，剩余授权次数为0；没有自动重试，也没有调整生产代码或60秒单次预算。原三阶段联调的嵌入、重排通过与摘录失败仍单独保留在[provider-integration](provider-integration.md)，不合并、覆盖或重复计数。

## 本次实际结果

| 执行 | 可核对结果 | 不能据此声称 |
| --- | --- | --- |
| `SiliconFlowGenerationLiveIT`，公开Java Adapter单次摘录 | 1项测试、1 failure、0 error、0 skipped；`model_timeout`。JUnit suite 60.409秒，case 60.396秒；Maven日志结束于2026-09-08 09:38:32 +08:00 | 未取得可验证的摘录结果，不能称生成通过或固定根因已复现 |
| 独立curl单次POST | curl exit 28；连接完成计时0.000284秒、TLS完成计时1.250228秒、总计60.003526秒；首字节计时0、HTTP `000`、下载0字节 | 不能定位provider内部排队，也不能证明Java性能问题、代理无故障或模型返回了非法JSON |

curl时间字段是从请求开始累计的完成时间，不把`time_appconnect`当作单独的TLS握手耗时。其保留日志没有墙钟开始/结束时刻，记为unknown，不用文件mtime补造。HTTP `000`表示没有取得有效HTTP状态，不是服务端返回的业务状态码。

两组结果支持的有限判断是：生产Adapter在预算内未完成摘录；独立curl完成连接/TLS阶段后，直到60秒预算耗尽仍未记录HTTP响应首字节，下载量为0。等待点究竟在代理链路还是provider，现有证据无法区分。Java XML确认配置了loopback HTTPS代理；curl保留指标不记录实际代理选择，不能从时间指标反推完整路径。

## 输入与调用边界

两次诊断沿用同一模型别名`Qwen/Qwen2.5-7B-Instruct`、原三条合成材料和同一问题，仅请求摘录，不再调用embedding或rerank。模型别名不是固定权重版本；Java侧模型环境值依据执行者记录，JUnit XML本身没有保存该环境值。

只读比对确认：临时`payload.json`的system prompt、question和三条evidence与Java测试及生产摘录代码一致；两者均为JSON mode、`stream=false`、`n=1`、`max_tokens=2048`。这是**语义等同的JSON输入**，不是逐字节相同的受控对照；键序、客户端、代理机制和执行时点不同，Java实际出站字节未归档。

Java测试调用原`OpenAiCompatibleModels.extract`；curl用一次性脚本、`--retry 0`、禁止重定向、60秒与1MiB响应预算，将响应流直接交给限长校验器，不保存原始响应。校验器本次看到0字节，因无JSON可解析而exit 65；其`jsonParseValid=false`及其余未成立的结果字段不代表收到并判定了模型输出。两次均没有通过原文摘录、650数值或提示注入相关结果断言。

## 本地guard问题与真实请求分开

curl首次本地guard使用正则重复范围`{1,4096}`，执行者记录其在本机产生exit 2；保留guard输出明确为`requestStarted=false`，没有网络请求。随后仅将诊断脚本的guard改为独立长度检查加图形字符检查。该问题属于诊断工具，不属于Java产品或provider错误，不占一次真实模型请求。

修补后的脚本以原子创建`attempt-1`目录防止重复执行；只发生一次实际curl请求，结果为exit 28。JSON清单中的脚本SHA对应修补后版本；原脚本摘要由spring_authority_explore此前工具记录提供，但原文件已被修补，当前无法重算原摘要，二者证据来源分开。一次性标记不能自行删除来重试；后续调用需新的明确授权。

## 证据与安全

执行者为root；本文作者只读核对日志、JUnit XML、curl指标、测试/Adapter源码及临时脚本，并重算SHA256，没有运行Maven、网络或模型。详细指标、字段依据和文件指纹见[generation-diagnostics.json](generation-diagnostics.json)。外部日志/脚本仅以basename和摘要登记，未复制到仓库。

凭据由执行者经禁回显stdin注入短生命周期环境；curl授权头只经管道传递，不放命令参数或文件。记录只包含安全结果和哈希，不保存凭据、原始响应或个人绝对路径。执行者提供的本批精确值扫描结果为450文件、0匹配；这是最终实际curl请求和本文新增之前的快照，不认证后续新增文件。该摘要没有列出全部扫描路径，不能扩大成全机、历史、聊天记录或密钥轮换证明。

生产Adapter当前SHA与原provider集成清单一致，生产Java工作树无修改。新PDF完整链路入口仍无真实执行通过证据；这两次诊断没有运行解析、索引、Milvus、publication、AnswerService或source闭环，不认证完整RAG、网页、多模态或生产readiness。

## 后续非计费连通性检查

root另做一次直连官方域名的TLS建立检查，未使用代理、凭据，未发送HTTP或模型请求。10,034毫秒时尚未收到secureConnect事件，主动销毁socket，结果为`TLS_DEADLINE`，其进程随后已确认退出。该时间是超时回调的观测耗时，不是进程总运行时长；检查没有分别记录DNS/TCP阶段，不能据此确定具体握手失败原因或永久不可达。它不消耗或新增模型授权，也不支持立即改走直连就能解决生成超时的推断。安全结果文件`0007-generation-direct-tls-only.json`及其SHA见JSON，新增此项由root记录，未包含在先前文档作者的独立核对中。
