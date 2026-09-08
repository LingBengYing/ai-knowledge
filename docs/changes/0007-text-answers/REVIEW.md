# Review：文本问答

状态：IMPLEMENTATION。尚无整切0007实现完成或完整独立审查结论；以下仅为已定位缺陷的限定范围复核，旧重构审查不认证本切。

比较基准为0006已验证的173文件及297项JUnit冻结快照。审查必须分别核对Standards与Spec，尤其是完整selected set、source与generation区分、只读Milvus准入、逐事实/否定条件与冲突、最终授权/trace原子性和预算取消。不能用摘录子串、六golden或接口存在证明完整目标已实现。

## 2026-09-07 已定位缺陷的限定复核

2026-09-08补充：authority_layering以首轮冻结归档（SHA256 `f256a5641622e12d0beced4d75b2827da70f5b9977eceb012f24ecc5a0e7378b`）作精确diff，独立审查新增4096+1及卸载/显式重载IT。Standards/Spec各0 finding，原测试及断言未改，owned清理和有界轮询保持。独立核对当前/构建副本SHA、三个方法XML与38项日志一致；未重跑Maven或连接实例。结果只覆盖[新增集成边界](milvus-integration.md)，不是一般容量、flush、重启或生产验收。

| 范围 | 非生产实现者复核结论 |
| --- | --- |
| R01 最终配置资格（adapter_layering审查，root/authority_layering实现） | Standards / Spec各0 finding。闭集资格仅读本地固定身份与预算；Store锁后和写trace前检查，首次拒绝不能恢复，最终拒绝清hash/引用，null整体回滚。两项真实Store竞态保持原拒答断言，另有3项资格转换/null测试。 |
| R02 关闭等待（spring_authority_explore审查，root实现） | 生产实现scoped PASS；5秒超时/关闭线程中断明确错误，失败后拒绝准入且可再次等待实际退出。审查发现P3测试先释放worker后再close，已补成阻塞期间第二次close不得返回，并用临时旧实现mutation取得2项预期失败；复核关闭。 |
| Milvus HTTP自身超时分类（authority_layering审查，adapter_layering实现） | Standards / Spec各0 finding。仅新增HttpTimeoutException分类，保留原ProjectionException及其余transport错误；共享post的总预算、中断恢复、取消/脱敏不变，无重试或错误成功化。新断连测试覆盖过宽分类风险。 |
| HTTP错误字段测试（spring_authority_explore定位，root核对与验证） | 生产契约一直是error_code；只修正新增AnswersHttpTest误读字段。不是生产错误修复，未改旧HTTP测试/生产序列化或放宽状态/错误/零外部调用断言。 |

R01没有单独冻结15:04红测前源码快照；该限定审查依据0006固定基准、当前0007规格/实现与已记录红测，不声称精确R01前后diff审查。上述助手未运行Maven，执行结果由root记录：最终564项Java/73项Node及格式/双覆盖率门禁通过，见[verification](verification.md)。

整切Tool语义/资源、所有新增权威与HTTP边界、真实provider/Milvus、实际运行环境、网页/多模态和生产仍需独立验收；不得把三个scoped PASS合并为完整0007安全认证。

## 本批限定条件与Domain：独立限定复核

固定前像为本批`baseline.tgz`，SHA256 `062b2f6a83b03e2c7cb628d487fc5e30fc02fa2ad062c759912260b320fdeff7`，不是旧Git HEAD。Tool实现由adapter_layering负责，Standards由authority_layering、Spec由spring_authority_explore分别审查。主线程负责组合测试和执行验证；Domain由authority_layering实现、主线程非本人复核。

### Standards

限定范围PASS，0 finding。修改留在纯TruthContext和policy版本；具名关系判定、前置/后置条件与有限标签规则集中，没有新I/O、SQL、Bean、空包装或跨层Map。9份原Tool测试SHA与前像相同，新增fixture复用同一组限定双向执行；真实parser组合保留原方法和正反例，未通过删改旧断言求绿。对应L05/E01/G01/H03。Domain两个输出record遵循M04/G05：不可变快照、安全原因码、无正文自动字符串，不重做权威来源证明，也未收紧尚不可信的输入record。

### Spec

限定范围PASS，原两项finding已关闭。首次审查发现前置远隔限定漏判、带中性标签的真实跨主体前提被错当独立主体；最小反例均取得真实红测。复核又指出首个修复尚缺既有审批限定的逆序，未提前给PASS；复用原六条件双向重放并补前置审批guard后关闭。仅具名同主体/同关系限定可反向检查，匿名指代不反向绑定，不把历史CHANGE整体纳入前置入口；独立不同主体的合法答案仍由正例保护。policy升级为v2，组合断言同时核对安全拒答原因和trace版本。

上述为本批可定位diff的独立审查，不是整切0007、通用语义、资源容量或生产认证。审查者没有运行Maven，最终执行/源码绑定以[verification](verification.md)为准。

## 真实Milvus首轮：测试与执行证据限定复核

仅新增MilvusLiveIT，生产输入235项保持原SHA。adapter_layering实现测试；authority_layering与spring_authority_explore做限定Standards/Spec复核。初版topK=1可能隐藏同词同向量跨组织并列行，已改为limit=2并继续严格断言唯一合法ID及双路RRF贡献。root仅在临时构建副本删除workspace过滤取得真实失败，恢复逐SHA一致源码后真实IT与相关35项回归全绿，没有把故障注入留在工作树。

authority_layering独立读取三次日志/XML、最终源码/基线manifest及实际运行捕获，核对首次1项绿、mutation 1 error、最终7 prepare+28 REST+1 live共36项绿；它们不与635默认回归重复计数。捕获确认Milvus2.6.22/830fdd6806、固定arm64摘要、仅命名卷/loopback及测试库无残留集合。当前限定finding已关闭；审查者未重跑Maven、连接实例或写被审源码。

本结论只认证[集成记录](milvus-integration.md)及[摘要绑定](milvus-integration.json)中的小数据量读写/检索与测试有效性，不认证真实模型质量、4096+1容量、冷加载/重启、整个0007或生产发布。

## 2026-09-08 Provider IT 限定复核

SiliconFlowLiveIT由spring_authority_explore新增，adapter_layering只读Standards/Spec两轴各0阻断finding：固定官方HTTPS、全部配置前置、key只在环境/Endpoint、无原始响应或异常cause日志，三个顺序公开Adapter调用且无应用重试，默认构建不选*IT。摘录断言只要求相关policy的精确子串，不能证明完整事实或全面抗注入。root实际执行在第三阶段超时，按失败保留；审查通过不等于模型执行通过，见[provider-integration](provider-integration.md)。
