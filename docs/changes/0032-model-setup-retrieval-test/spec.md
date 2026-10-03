# Spec：设置、测试、应用、索引、召回闭环

状态：CONTRACT_FROZEN_IMPLEMENTATION。用户授权开发及合理操作设计；本机实现无需重复请求开发权限。

## 1. 管理与启动

新模型配置能力默认关闭，开启后采用managed文字运行模式；关闭时原legacy装配、默认值、路由及构造测试保持。仍遵守当前development/test、loopback和生产/readiness边界，不借模型设置解除它们。管理员由服务器显式配置固定workspace下的principal名单，必须匹配可信Actor；文档owner/editor不自动获得组织模型管理权。GET可向同workspace已认证用户返回安全状态和can_edit，变更/测试/应用只有管理员，越组织/无权控制请求安全拒绝。

managed模式不因缺文字模型而使整个服务启动失败：资料管理、原件和允许的解析正常，文字索引Job不claim、文字索引/问答/召回返回503 text_configuration_required。安全GET /v1/config保留原六字段，只按真实装配和active bundle宣告text_index/indexings/answers/sources/retrieval_test；新model_configuration说明实际设置入口可用，不是假模型。缺投影参数也显示缺配置，不补伪连接。

首切固定此前硅基流动三个文字角色。provider endpoint由受信服务器配置，默认https://api.siliconflow.cn/v1；HTTP loopback替身只允许明确本机测试配置，请求体不接受provider URL。Milvus仍是服务器预置连接，不新增任意URL的管理转发，设置单独显示已配置状态并提供只读连接测试。角色、嵌入维度/版本和Milvus维度沿原严格校验。

## 2. 精确HTTP与安全形状

GET /v1/model-configuration 无query/body，返回 {version,active_version,state,can_edit,provider,embedding,rerank,generation,projection}。version/active_version为非负整数/null，state仅unconfigured/draft/active，provider固定siliconflow；embedding固定{model,dimensions,revision,has_key}，rerank/generation固定{model,has_key}，未配值为null。projection固定{configured,dimension,can_test}，不返回token、私有路径或目标URL。

PUT同一路由严格{base_version,embedding,rerank,generation}，三个角色都必须提交；embedding固定model/dimensions/revision，rerank/generation固定model，可选api_key仅写入。缺api_key保留该角色旧凭据，首次配置必须提供；null/空密钥拒绝，不用回显占位字符串代替真实值。字段/shape/长度/Unicode/维度失败422并标安全field，不调用模型；陈旧base_version409 configuration_conflict，保存成功200返回完整安全状态，新draft version递增且active不变。保存不测试/应用/索引。

POST /v1/model-configuration/test 严格{version,role}，role仅embedding/rerank/generation/projection。只测试完整保存的精确draft version，不接正文、任意地址或额外请求参数。返回{version,role,status,error_code}，status passed/failed，成功error_code null；失败仅安全字段/鉴权/限流/超时/不可达/协议/维度/投影配置错误，不返回供应商正文。只有用户明确点击才发有界合成请求，每角色最多一个请求、无自动重试；生成只发送固定短合成证据且严格校验实际协议。投影连接测试只读，不create/load/upsert/delete。失败必须指出所测角色，不把生成失败写成召回失败。旧客户端安全错误码保持，需要HTTP状态元数据时仅附加安全数字，不暴露响应正文。

POST /v1/model-configuration/activate 严格{version}，成功200返回安全状态。管理员检查先于维护gate；路由精确豁免普通HTTP lease仍保留认证/Origin/no-store，请求自身不得造成恒busy。取得零实际body的维护窗口，否则409 configuration_busy、零变更。离线构造完整immutable bundle、原子持久保存active version并交换。应用不测试、不建collection、不重索引；失败旧配置继续有效。Store短事务检查所有未墓碑资料的既有indexing_jobs（含queued/failed/cancelled）、当前active出版以及已登记legacy投影attempt的完整文字目标与新target；不兼容409 model_rebuild_required且active不变，不能静默破坏已有问答或让失败任务无法重试。独立sound/image-vector/audio-vector/video-av投影不是文字目标，不与文字IndexTarget硬比较。现modelRevision绑定三个角色的模型参数，任何角色model变化都可能冲突，不能仅称更换embedding才需重建。密钥轮换不改变target。离线激活不证明远端集合/schema已兼容，连接/索引阶段仍核真实服务器配置并单列失败。

控制代理精确方法/route，无query（含空问号）、普通128KiB/10s；test精确POST独立70s，其他普通期限保持。禁止转发自由Authorization/Host/Origin或自动重试。所有新接口沿原请求ID/安全错误/no-store；模型请求内容不进应用日志。

## 3. 真正运行与凭据

服务端独立私有配置文件位于本库受控数据目录，NOFOLLOW、终端路径/权限检查、0600、原子写与fsync；不进正文SQLite、managed数据库快照、源码/交接或浏览器持久存储。密钥对象toString脱敏。GET/成功/失败/日志均不回真实密钥，不以密钥hash充当状态。环境配置只作首次bootstrap，存在有效保存配置后以保存状态为准；明确区分draft与active，重启恢复相同active version。应用保存失败不得内存生效，损坏配置保守拒绝恢复且显示安全原因，不伪造成功。

每个实际文字索引、问答、来源/原件或召回操作在普通gate进入后只capture一次运行bundle；配置、唯一IndexTarget、AnswerService及IndexingTaskProcessor来自同一快照，全链不可混新旧配置。激活仅零actual bodies，旧bundle安全close；超时/cancel不提前释放实际body。旧固定构造与Module行为测试保留。没有全局每阶段读取最新版的模型代理，也不重新启动Spring或旧服务。

managed只配置文字索引/问答/召回，不伪称已热更新视觉、声音、原视频或摘要模型。旧依赖文字target的已装配媒体入口若不匹配当前managed target，在decode/provider前安全拒绝；无真实legacy配置时不可装配假Bean或宣告该能力。独立声音、原视频及摘要仍保持各自真实旧配置。缺模型状态和已装配/可执行能力必须分开。

## 4. 独立文字召回测试

POST /v1/retrieval-tests 严格{question,document_ids?,top_k?,rerank?}，question沿原4096 UTF8字节限制，完整document_ids语义沿AnswerCommand（缺失全授权范围、显式空绝不全库）；top_k 1..20默认5，rerank布尔默认true。文字入口只支持真实text/OCR publication，不用图片caption伪装文字事实；完整所选资料逐一校验，任一无权/未发布/配置不符整次拒绝，不丢失败项。模型嵌入→已授权dense/BM25前置scope→全部authority hydrate→可选完整rerank→返回前全scope复验；无generation/extraction/事实证明，不创建答案引用或伪造answer_id/source_url。

成功返回{test_id,configuration_version,status,reason,scope_count,score_kind,matches}；status completed/empty，reason null/empty_scope/no_matches，score_kind固定rrf。matches为按结果排序的最多top_k完整片段，固定{rank,document_id,revision_id,filename,source_sha256,parser_revision,page,start,end,text,text_sha256,retrieval_score,rerank_score}，Unicode locator来自服务器权威材料，rerank关闭时分数null。RRF及rerank分不是概率、原始COSINE/BM25或事实置信度。所有候选先校验再截断，失败不返回部分命中/旧结果。生成模型实际调用数必须为零。

检索与重排失败分别带安全stage代码；总deadline/有界并发/实际线程退出沿原准入设计。读取等待不持Store事务，预算结束不继续模型步骤，late response不变成功；完整scope/配置与metadata最终复验在同一权威短事务。代理精确POST独立180s、128KiB/4MiB，旧问答与上传预算不扩大。

## 5. 页面操作与验收

设置中集中三个角色，各自模型名/凭据已配置状态、只写更新密钥；版本/维度只在确实需要时解释。保存草稿、逐角色测试、应用分别显示明确结果，未保存改动不能测试/应用，编辑使旧测试显示失效。密钥提交后清空输入，身份变化/离页/401清全部敏感草稿，迟到响应不能覆盖新身份/version。保存不触发后台调用，取消/断网结果未知先读取状态，不自动再次PUT/POST。

索引入口提示未配置/未应用/缺投影，已有上传/解析/任务流程复用；不把parsed写成indexed。问答页提供“测试召回”，复用当前完整范围和问题，独立Session状态；结果显示文件、页码、原始片段和清楚的排序分数。可打开对应详情/同版本原文件，并可“继续问答”保留原问题和完整范围；命中片段不显示为已支持答案。编辑问题/范围/身份或新测试立即清旧结果，停止只停止本地等待，late results隔离。

验收主线为未配置启动→管理员保存→各角色真实本机替身测试→应用→真实合成文本上传/解析/索引→召回权威片段→生成调用0→继续问答/来源；另保留同目标密钥轮换、陈旧version、reader拒绝、维护busy无写、凭据负例与兼容性拒绝。用户自行网页验收，本机DOM/HTTP不冒称部署浏览器已通过。
