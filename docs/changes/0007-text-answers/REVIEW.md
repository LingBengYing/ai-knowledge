# Review：文本问答

状态：IMPLEMENTATION。尚无整切0007实现完成或完整独立审查结论；以下仅为已定位缺陷的限定范围复核，旧重构审查不认证本切。

比较基准为0006已验证的173文件及297项JUnit冻结快照。审查必须分别核对Standards与Spec，尤其是完整selected set、source与generation区分、只读Milvus准入、逐事实/否定条件与冲突、最终授权/trace原子性和预算取消。不能用摘录子串、六golden或接口存在证明完整目标已实现。

## 408e622之后：示例标签和多句程序的限定审查

本批精确前像为408e622；审查当前WIP差异及新增文件全文，不使用三点空diff，也不沿用旧0006指纹。首轮322项相关回归及737项默认回归通过不作为审查通过的替代。

Standards由authority_layering独立检查：示例标签/真实句界修复及新测试符合L05/H03/G05，原测试不变；程序新逐step调用会重复从页首/字段起点扫描，形成平方级计算，属G07/P2。root用合法5万短步骤、仅首句候选/摘录在128MiB子JVM复现8.058秒超时失败，强杀并确认退出，不以普通线程超时留下后台计算。

Spec由adapter_layering只审非本人实现的程序分组，root另核示例修改。首轮指出两项：后续step未复用既有错误标注检查；必要授权前提被当作独立事实结束程序组。新增中英完整/partial及不同操作对照共10项，连同原29项真实运行39项8失败，0 errors/skips；正常禁令和完整条件仍须保留可答，不得通过全拒答关闭风险。具体复现/修复后完整回归见[verification](verification.md)。

最终限定复核：Standards由authority_layering确认0项未关闭硬性问题，G07已关闭。程序安全检查改为每组固定扫描与每步局部检查；5万步骤资源负例先8.058秒失败，再0.928秒通过，未增加任意步数上限、跳过冲突或将崩溃算通过。TruthContext仅复用原SELF_QUALIFIER，Tool无新增I/O或跨层依赖。

Spec由adapter_layering确认F1、F2及最后检查顺序回归全部关闭，0项未关闭限定finding。后续错误标注保持拒答，精确操作前提必须完整引用；未支持的`before resetting`前提由两项真实红测锁定，不能丢弃。新增guard一度误伤另一具名操作，追加原独立操作正例后58项中1红；root只前移具名操作边界判断，既不混入另一操作，也不忽略未知必要前提。审查者逆向扣除新增guard后源码SHA回到已审版本，确认原精确前提、引用和资源逻辑未漂移。

最终Procedure源码SHA256为`8ecacb229ca6039a861ceafc2d750cfa3259d8fede0d87a3dbb7cf74f9a72ed6`，Procedure测试为`aff1a8ec222fca41e1c7bac291e99c925cc41f32dbfb942e10f99b6bd98db21d`。最后修改后12:07:08完整376项相关回归通过，完整构建与保留校验见[verification](verification.md)。本批Standards/Spec各0未关闭限定finding，不认证整个Tool语义/容量、完整0007或生产；审查者没有编辑被审实现或运行Maven/模型。

## 2026-09-08 推送后整体Tool审查：发现与处理范围

审查起点 `5a30ea9`，六个 `tool/answer` 新文件完整diff相对bc82a7a；不包括parser迁移，不把它当完整0006基线。当前修复的精确前像为5a30ea9及本轮冻结归档，见plan。以下finding未全部关闭，不能把限定修复写成整个0007通过。

### Standards

authority_layering指出G07/L05资源问题：`SourceFields.splitAssignments` 对每个连接词复制并扫描不断增长的前缀，无赋值时cursor不前进，导致平方级工作；缺少分句循环协作中断。root在合法400011 code point合成页上通过公开verify复现8秒未退出，子JVM被强杀且确认终止；另预中断负例未抛取消。修复由adapter_layering负责，最终执行与复核另记。重复candidate/quote整页解析属于额外性能建议，不能据此宣称完整CPU/内存验收。

### Spec

spring_authority_explore指出三处错误支持风险：同页未检索chunk内的直接冲突被跳过；`Example:`/`例子：`可能被当作中性标签支持事实；多句操作步骤可能只引用首句就满足程序问题。第一项已由root用中英、前后两种顺序的Tool和真实parser/authority/AnswerService复现，共20项8个错误支持；只删除错误chunk范围后20项通过。完整页只能参与冲突拒答，不扩展模型支持或引用范围。后两项尚未在本轮执行反例或修复，继续作为明确待复现/处理项，不能以当前固定golden通过关闭。

adapter_layering另只读审查finish/source，未发现当前可达ACL/source泄露：源读取限制原Actor/组织，完整scope当前ACL/active与locator/hash仍在同一authority事务复验。建议新增等Store锁期间撤销未引用文档的针对性竞态覆盖，属于未验证项而非已证实漏洞。本轮不借此作整体安全认证。

### 本批修复后的限定复核

Standards：authority_layering独立复核四份最终生产差异和四份新增测试，0新增finding。页级去重/纯Fact空过滤前移及policy显式升级符合职责；取消只在propose内转安全拒答，finish/trace存储失败仍外抛，真实执行finally才释放准入。SourceFields原patterns未变，以单调匹配器、region和零拷贝前缀两端检查消除增长前后缀复制/重扫；协作检查不清中断、不返部分字段。结合真实红绿，可关闭具体连接词平方级扫描与分句取消缺口，不关闭整页重复解析建议或全部G07容量验收。

Spec：spring_authority_explore独立复核0新增finding，同页冲突finding关闭；完整页只否决，支持仍须落在原候选quote范围。保留wholeSentence、主体、条件/历史过滤及数字精确含义；SourceFields替换保持原边界，6项旧行为对照先绿。22项Tool/8项实际parser组合及取消路径的范围符合本批spec。示例标签与多句程序两项继续开放，未扩大成整切PASS。

审查者独立重算SourceFields及ResourceTest最终SHA，root在最后修改后完成278项定向及675项完整验证；源码/运行绑定见[verification](verification.md)。两个审查者均未运行Maven/网络。总结：本批限定Standards 0未关闭硬finding（一般资源建议仍待验收）；Spec关闭1项、仍有2项待实证/修复，主要风险仍为错误支持答案。

## 2026-09-08 零模型Milvus鉴权入口：限定复核

相对d43504f新增 `MilvusAuthenticationLiveIT`，adapter_layering实现，root统一格式与执行；生产代码不变。Standards由authority_layering复核：真实生产配置加载、无模型client、15秒总等待、接收期间64KiB上限、安全错误与owned精确清理符合本项规范，0未关闭finding。Spec由spring_authority_explore复核：初审P2指出只有不存在用户名不能排除忽略密码的实现；补充保留有效用户名和密码长度、仅改变末字符的负例，仍要求HTTP401/code1800及生产安全异常后关闭，0未关闭finding。

最终文件SHA256 `7859606217a48061c78585bae01d91a3c32247314be1aa4dde2e5579acbbb24a`；缺凭据、未知用户、同用户错误密码三个负例全部保留。root真实运行1项通过及空库回读见[鉴权记录](milvus-authentication.md)。这是本项限定两轴复核，不认证整个0007、生产RBAC/TLS或真实模型链路。审查者未运行Maven、网络或模型。

## 2026-09-08 真实PDF入口：新增WIP限定复核

本项前像是已推送 `d43504fc2341d9bae83352b773e79a1897dfccc7`（非上述0006旧基线），审查两个新文件的完整WIP diff；HEAD与前像相同、commit list为空，不能只看三点空diff。authority_layering实现入口，root统一格式并修复接收上限；无生产或原测试变更。

### Standards

adapter_layering最初报告1项G07/P2：管理HTTP使用ofByteArray先收全再验64KiB。root保留实际红测后改成向delegate交付前累计检查、超限取消并异常完成；真实post使用同一subscriber，保留15秒deadline、中断、泛化错误和清理。独立复核核对红绿XML、最终SHA，并逆向扣除修复后恢复最初审查SHA，确认其余原逻辑未改。该finding已关闭，最终本项0未关闭finding，不扩大为整切规范认证。

### Spec

spring_authority_explore限定PASS，0代码finding：真实生产配置加载、固定PDF、实际parser/IndexWorker.main、publication/AnswerService/source/trace、N≤batch及4次应用请求上限、空库与owned清理均符合本项plan。修复没有更改付费计数、worker或清理资格。诊断文档的原guard历史摘要遗漏作为P3指出后补齐，注明历史工具记录而非当前重算。

最终IT/worker摘要及实际执行结果见[verification](verification.md)。审查者未运行Maven、网络或模型；root的离线回归和配置保护通过也不证明完整真实链路。Spec其他未验收范围、生产gate及外部授权限制保持。

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
