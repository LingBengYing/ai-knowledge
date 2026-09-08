# Spec：授权范围、证据、事实与答案提交

状态：IMPLEMENTATION。完整目标在blocked后重新启用，按恢复请求继续以下原契约与验收，不缩小规格求绿，也不把恢复草稿视为通过。

## 1. HTTP与配置

- 新增显式默认关闭的`RAG_ANSWERS_ENABLED`；仅development/test与字面loopback，独立于摄取/索引开关。启用后才装配回答链路；共用的模型配置在索引或问答任一开启时加载一次，不因两个开关并开产生歧义。缺配置拒绝启动，不使用假模型补位。
- `POST /v1/answers`接受JSON question与可选document_ids，沿用可信Actor、Origin、安全错误、no-store与request ID。question为非空、合法Unicode、最多4096 UTF-8字节；不接受客户端角色、locator、模型配置、分数等未知字段。请求体有界；不开放未验证的流式答案。
- document_ids缺失表示当前授权已发布全库；显式空数组表示空范围，拒答且零外部调用；null、重复、非法ID或超过128个ID为422，不静默截断。任一显式所选资料不可用/越权/未发布时整次范围失败，不改成全库或只用剩余项。
- 回答/拒答输出固定安全字段：服务器answer_id、status(answered/abstained)、answer、reason、citations。来源路径按服务器生成的answer_id与citation序号回读，不能让客户端自由指定文件路径、页码或原始locator。来源访问仍验证当前Actor与当前active/ACL，旧绑定失效不可继续查看。
- 未开启问答时旧接口/能力保持。新后端能力与网页未接线分别说明；readiness继续503，不声明production就绪。

## 2. 权威范围与hydrate

- 开始短事务读取Actor当前文档ACL、真实active publication及其source revision、projection generation、source/parser/model/embedding/projection身份和完整manifest。全库也先授权再限制数量；超过128明确失败，不取前128伪称全库。
- 固定本次完整文档集合；最终未进入候选的所选资料同样保留并复验。事务期间校验可执行配置与已发布target一致、parsed/source身份一致；不使用legacy registered revision充当active。
- 投影scope为document→projection generation，不能填source revision。Milvus仅返回physical ID/score；hydrate经active publication entries还原source segment，确认完整身份、原文摘要及页内Unicode code point范围，再把权威正文交给重排/摘录模型。未知、重复、越界或不属于scope的候选不能被静默接受。
- 每个带证据的远程阶段前复验完整scope；远程等待不占Store事务。回答提交前同一事务重验完整scope与引用证据。撤权/active或配置变更导致安全失败，不返回旧证据内容。请求开始后新发布的其他资料不隐式加入冻结范围。
- 最终提交资格包括预算和本地模型/投影配置身份，在取得Store锁后与写入trace前复验；配置变更记录`configuration_changed`，不是`processing_timeout`。任一次资格不合格不得恢复成功。检查不发网络请求，不宣称能原子冻结外部Milvus配置；生产Adapter配置固定于实例，运行变更必须重新装配。

## 3. 检索与总预算

- 复用真实TextModels与RetrievalProjection Seam，测试使用明确替身。查询不得调用会创建/加载集合的写入initialize；增加有界只读查询配置验证，要求现有集合/schema/index与固定配置匹配，缺失时失败，不自动创建、加载或upsert。
- query embedding→dense/BM25范围前置检索→权威hydrate→完整有效重排结果→摘录。候选上限、模型上下文上限、事实数上限与总字节上限明确且不丢尾部问题；达到能力上限则拒答/校验失败，不部分作答。
- 本切能力边界：最多64个候选、32个摘录、8个问题事实，去重后的权威页总量最多8MiB UTF-8。超限整次拒答，不截断问题/候选正文或只回答部分事实；参数限制不代表已支持通用自然语言推理。
- 整次调用有总deadline及有界并发，预算中断后不继续新模型阶段、不提交成功答案；准入名额在实际执行退出后释放，不以Future取消假装底层已退出。共享client不能因一请求取消被关闭。无自动计费重试。
- 关闭先拒绝新调用并中断执行，最多等待5秒；超时或关闭线程被中断必须显式报告安全错误`answer_shutdown_timeout`/`answer_shutdown_interrupted`，后者保留中断标记。失败后仍拒绝新调用，后续close可再次等待实际退出，不能仅凭closed标记返回“已关闭”。这不等于能强杀忽略中断的线程，也不保证Spring会因Bean关闭失败而停止销毁其他依赖；完整生产退出策略仍须独立验收。
- 此总处理预算从完整请求体解码后开始，包含排入本次执行后的权威与远程阶段；既有普通JSON入口没有完整慢请求体接收deadline，不能将处理预算描述成入站slow-body防护。默认60000毫秒、可配置10–600000；并发默认2、上限8，无无界待执行队列。
- 只读prepareSearch验证集合存在及schema/两个索引，不创建或加载集合，也不宣称已经证明loaded；未加载导致search失败时按安全上游失败处理。构造/装配不发网络请求，非空且授权scope确认后才准备查询。
- rerank的任意有限分数不是概率，不照搬0..1置信阈值；无候选、无支持、模型拒绝、冲突、能力外/超预算分别有安全终态，不借模型常识补齐。

## 4. 逐事实支持与来源

- 分解并覆盖完整文本问题，不仅首个问句；引用必须是模型接收的权威证据的精确片段，服务器计算页内code point半开范围、hash和来源URL。模型不能自由决定文档、页号或链接。
- 参照已批准文本行为实现主体/谓词/数值/否定/条件与同主体冲突校验；摘录确实出现在文档中仍不充分，必须支持被问事实。证据冲突、只有提示注入或只证明部分问题必须拒答。未支持的语义明确拒答，不能硬编码六个golden答案或把同义词表当完整推理。
- 答案只能由已验证摘录构成，不附加模型常识。问题/文档中的操作指令均为数据，不执行工具或系统指令；危险正文不能改变角色或绕过证据验证。
- 最终答案、引用与判定所用模型/提示/验证器版本、完整scope绑定由服务器冻结。既有source和parsed证据不就地改写。
- 同页不同分块中的明确同主体/同属性限定（例如预算仅适用于试运行）不能因模型只摘取数值分块而丢失；未证明限定条件时，无条件问题必须拒答。不同明确主体的限定不能串用，中文前缀名与英文扩展名不能按子串视为同一主体。使用真实Java分块/publication和中英文正反例验收；这不代替通用指代或跨页推理验证。
- 已验证的Domain输出须保持内部一致：引用身份、Unicode code point范围/正文长度、1–8项事实摘要合法；支持结果有引用且reason为supported，拒答结果无引用且只含既有安全原因码。未验证的模型选句仍可表示非法值，由Tool整次验证，不在输入record中隐式转换/丢弃。语义规则变化须更新policy revision以区分历史trace。

## 5. 可还原审计与迁移

- 现有management_audit只有摘要，不足以还原引用链；新增版本化append-only trace sidecar，存question hash、Actor/组织、完整publication/scope绑定、结果/原因、模型/提示/验证器revision、证据ID/locator/hash与相关分数，不存原问题、全文、原始模型输出、凭据。
- trace与最终授权决定同Store事务；故障整体回滚，不能返回未留审计的成功答案。来源通过trace引用与当前ACL/active交集回读。
- 保留原v1/v2/v3迁移与不可变约束；新schema必须一致性备份、原子迁移、失败关闭、重复打开可用，不能改旧迁移删表或绕过CHECK。仅独立Java目录。

## 6. 验收

1. 先失败后实现；原297项Java与73项Node保留，既有断言和80%双覆盖率门禁不放宽。
2. 真实SQLite验证scope/完整selected set/非候选撤权/伪造physical ID/版本target不匹配/locator/trace原子性及schema升级重开；真实HTTP验证输入、身份/Origin、能力开关、成功/拒答/来源失效。
3. 真实本机HTTP协议替身贯通embedding/Milvus search/rerank/extract；记录所有出站阶段，验证零越权正文、只读查询、超时/取消/并发以及错误脱敏；替身不冒充实际provider/Milvus质量。
4. 四份原PDF通过Java解析/发布/问答链路，重放未修改的六golden；同时覆盖超出这六项的中英文正反例、多事实、否定/条件/冲突/注入。单测/确定性eval与真实provider质量分别报告。
5. 完整clean verify、Node、静态/格式、manifest/原测试逐项保留、敏感信息扫描和独立Standards/Spec审查。网页和实际provider/Milvus、Java21、生产尚缺的证据不得推定通过。
