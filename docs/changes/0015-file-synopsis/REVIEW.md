# 0015 限定审查

当前 IMPLEMENTATION。生成 Module 的独立限定 Standards/Spec 审查完成，未发现未关闭的本步骤阻断；最终运行冻结以 verification 为准，不以代码审查替代运行证据。

## 2026-09-20 完整长文件分层限定审查

非对应实现代理分别只读核对材料/Domain/分层Service、独立模型Adapter、v14存储及最终消费者装配，Standards/Spec未发现本步骤未关闭的具体阻断。比较点为上步冻结 `/private/tmp/java-synopsis-library.QWHp4T`，不是用新测试替代旧基线。

- 完整FileInput必须与真实publication数量一致；原v1长度前缀/字段顺序/原字节指纹保持。连续greedy batch完整覆盖全序列，三合一层级保留尾单节点，派生节点不是原证据。
- 最终引用只能继承原来源ID，并回到完整原材料一次证明；时间由服务器原引用生成。全部原批次复查所有最终条目，Domain唯一索引加服务size/range检查要求恰好0..n-1；任一否决清空结果。
- 新Adapter只把派生节点放在derived_nodes；严格JSON、全index review、全引用贡献verify、10MiB原图与16MiB传输边界保持。原图验证后直接Base64，无缩放。新revision绑定endpoint/model/四prompt且不含key；旧SynopsisModels/Adapter/ModelHttpTransport逐字节不变。
- v14在原Store事务中复制四表，按count和整行EXCEPT核对旧行，依赖逆序重建并恢复原trigger/index、复查FK，仅扩大两个输入CHECK；旧v1–v13迁移体不变。
- Library按完整input选择短/长精确profile；旧短结果不因长模型版本变化而失效。claim与complete继续复验完整身份、权限、指纹、引用；Processor在事务外执行，Config只装配，关闭时无模型恢复仍可工作。Controller及公开来源URL/Range合同未改。

这些审查不认证真实模型摘要语义、云质量、前端或生产；运行冻结与独立制品复核见[hierarchy-verification](hierarchy-verification.md)。

独立制品复核已PASS：非实现代理Ruby/REXML/Digest/unzip复算539输入、193XML/1485用例、旧1429身份多重性、三组RED、133定向GREEN、两个JSON单LF与SHA、8项制品摘要及403个生产class逐字节一致；25旧文件变化与必要适配符合声明。无生成helper/Maven/网络/Git写，未发现差异。

## 2026-09-20 持久摘要后端限定审查

独立代理只读审查完整authority材料、Source Domain、LibraryService、v13/Repository以及Config/Job/Controller/Mapper。确认完整六类枚举、真实片段文字/原像素、实际时间、最终同事务输入/引用复验、当前授权来源回读；旧v1–v12迁移体未改。

已关闭两项具体发现：公开来源URL从1编号而Service从0索引，Controller现在明确减1，真实HTTP逐条回读ID/SHA验证；初版Config直接开启恢复事务，现改为Service无模型恢复usecase，Config仅装配。没有新增架构白名单或修改原安全协议。

非阻塞分支：同文件旧模型/publication任务仍pending时，新配置新建可能撞单pending约束；按主线优先约定列入backlog，待旧任务安全结束后再显式提交。不是已索引文件正常摘要链的阻断，不顺带新增任务框架。

代码审查不能替代运行证据；最终制品复核另记于本批library-verification，不认证真实模型质量、长文件、网页或生产。

本批独立制品复核已PASS：Ruby/REXML/Digest+unzip直接验证521输入、185XML/1429用例、旧1400身份多重性、三组RED、18必要旧输入变化、两JSON单LF与case原字节SHA、9项日志/覆盖率/JAR摘要及390个生产class逐字节一致；与[library-verification](library-verification.md)无差异，未运行生成helper/Maven或native。

授权、完整 authority 枚举、持久化、公开来源与真实模型质量不由本步骤 Module 代证。审核结果与具名测试证据在完成后追加。

## 2026-09-20 生成 Module 审查

- 独立代理只读审查四个 Domain、Service、Client Interface/Adapter 和全部三个新测试文件。生产层没有新增反向依赖，未修改旧模型接口、答案、存储或配置。
- 完整有序输入及 publication 全部字段参与长度前缀 SHA；原图和列表防御复制。先检查全部候选引用，再逐条只用其原证据核验；结果 Reference 和微秒时间由服务器构造，失败不保留前面已通过条目。
- 初审要求明确贡献校验：引用 A+B 时不能只凭 A 获得成功。Adapter 对 supported=true 要求 contributing_evidence_ids 恰好包含全部已引用 ID，无未知/重复/遗漏；两项真实 loopback Service acceptance 分别验证成功和遗漏贡献整份不可用。
- 文本和原图均是 user data，不进 system 指令；caption 没有证据输入类型。完整 prompt/model/endpoint 进入独立版本指纹，key 排除；严格 JSON 和旧 transport 的资源/错误约束复用，无自动重试。
- 测试夹具将原先黑色 PNG 修正为实际蓝色以匹配固定模型的蓝色声明，断言未变。此为合成夹具一致性修正，不是产品故障；最终完整回归重新运行对应协议文件。
- 不认证真实模型语义、授权/持久化/公开 HTTP、长媒体分层、网页或生产。时间线包络只是导航范围，不伪装连续证明。

## 独立制品复核

只读代理未运行生成 helper、Maven 或 native，直接使用 Ruby/REXML/Digest 重算：495 输入仓库/build/manifest SHA 一致，旧485输入不变；176 XML为1400/0/0/0，旧1352和RED48精确身份/多重性保留。两JSON单LF、cases原字节SHA、覆盖率、468格式、Node73、13:16:15 SUCCESS与全部6项日志/JAR证据摘要一致。额外比对350个生产class，JAR与target/classes逐字节相同。结论PASS，无差异；不扩大为模型质量或未接公开链验收。
