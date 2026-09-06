# Spec: 有界、失败关闭的文本 Adapter

## 当前子切的可观察行为

1. `TextModels` 小 Interface 提供批量 embedding、完整候选 rerank 和证据原文摘取。真实 OpenAI-compatible Adapter 与测试替身是两个 Adapter；embedding、rerank、generation 各自配置 base URL、model、key。rerank 是 provider 扩展，不冒充标准 OpenAI API。
2. embedding 返回与输入数量、索引、固定维度一致的有限数值；拒绝缺项、重复索引、零向量、非数值。rerank 返回全部候选的唯一索引和有限分数，不静默丢弃候选。输入、输出、HTTP 总时限和响应字节有上限；JSON 数字不能靠字符串强转通过。
3. generation 仅返回给定 evidence ID 和逐字原文 quote，或者空摘取的拒答。未知 ID、非原文 quote、超限或不合法结构全部失败。精确摘取只是防伪造的一层，不证明问题事实已被支持；本切不生成最终答案，不签发引用，不提供问答 HTTP。
4. 请求禁止重定向；provider 端点只允许 HTTPS，测试可显式允许字面 loopback HTTP。总 deadline 覆盖完整响应体，不能只等响应头。失败不返回 provider 原文、请求正文、URL 中凭据或底层异常；配置对象与错误的字符串表示不含密钥。中断传播并保留线程中断状态。
5. `RetrievalProjection` 提供显式初始化、批量投影和范围内混合搜索。Milvus REST v2 只操作明确带 Java 前缀的新集合；既有集合必须校验 schema、embedding revision/维度、BM25 function、dense/sparse index，失败不得自动 drop/alter。远端调用不是数据库权威事务。
6. 每条投影绑定 workspace、revision、segment ID、正文和 embedding；搜索只返回候选身份/分数。dense 与 BM25 两路使用完全相同的完整授权 workspace/revision 过滤，过滤先于各路 top-K。空范围零网络，超限范围拒绝而非截断；字符串 ID 不可形成表达式注入。合并使用确定性 RRF、去重与稳定 tie break。返回的字段和有限分数再次校验，越范围或结构异常失败。
7. 环境配置显式加载、无 secret 默认值、不自动启用 Spring bean、模型或检索路由；未知/不完整/不安全值失败关闭。加载配置本身不发网络请求。现有管理界面、鉴权和 readiness 保持不变。

## 验证与非目标

保留全部原 Java/Node/PDF 测试；新测试先红后绿，使用真实本地 HTTP server 检查协议、认证头、请求作用域、超时、重定向、坏 JSON、泄漏和配置失败。完整 Maven verify、80% 行/分支门禁和 secret scan 不放宽；新上下文审查后记录结论。

不属于本切：公开上传、持久化任务/语料 authority、隔离解析 worker、active revision 发布、最终答案/引用、用户界面新按钮、多模态、性能或生产发布。原六个 golden 继续冻结，不能将本子切协议测试称为完整问答 acceptance。真实 provider 与 Milvus staging 尚需独立测试。
