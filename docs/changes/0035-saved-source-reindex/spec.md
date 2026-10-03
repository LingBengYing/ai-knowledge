# Spec：保存材料、新任务与原子发布

状态：LOCAL_VERIFIED；本机实施和实际回归通过，未部署。当前证据见[verification](verification.md)。下方设计及早期记录保留历史语境。

## 请求与资格

新增精确`POST /v1/documents/{documentId}/reindex`，无query，仅application/json对象`{"base_publication_id":"用户刚读到的publication"}`；字段必须唯一、无额外字段、字符串1..100及既有identifier字符规则。缺失、null、重复、尾随JSON、无效UTF-8等沿422 invalid_request；超过128KiB沿413 request_too_large。普通请求期限及原认证/operation lease保持，无自动重试。

当rag.indexing.enabled开启，实际当前索引processor可用时cap增加text_reindex；managed未应用模型时不可声明。管理DTO新增真实can_reindex：当前可编辑、保存parsed revision完整1..4096项、实际active corpus publication匹配、无queued/processing文本任务、无基于当前base的独立image/audio vector receipt。can_index原初次索引语义保持。cap和can_reindex都不替代创建事务的完整复验。旧构造兼容默认为false；sound/video-av原始独立publication不属于此corpus入口。

创建事务先复验当前ACL、用户观察base、parsed revision/source SHA/parser、完整projection items及当前target与base一致；重复pending、过期base、不可重建均409 indexing_state_conflict且零新模型/投影写入。目标真实变化409 index_configuration_changed；真正配置迁移原model_rebuild_required仍保留。接受202原TaskResponse，任务新ID、初次attempt1，旧indexed任务绝不回队。

## 保存与发布

v23→v24独立有备份迁移：indexing_jobs新增immutable rebuild_sequence（初次default0）及nullable base_publication_id，UNIQUE(document_id,rebuild_sequence)保留重复初次拒绝；独立partial unique保留每资料至多一个queued/processing任务。sequence连续选择由当前authority事务完成，latest按sequence确定。旧数据和原表约束/trigger不放宽，旧DDL历史方法不改；完整schema inventory、FK、purged行及cleanup guards必须继续验证。

新的base字段必须同资料、同完整source/revision/target及创建时current active。新任务复用旧claim/token/attempt三次上限与独立generation/physical ID、完整manifest和qualified remote-write ledger。初次任务仍要求无active；重建claim、retry及发布要求base仍current且没有新增依赖receipt。retry不能与另一任务pending冲突。迟到、撤权、source或target变化、取消、失败、worker_interrupted均不能替换active。

实际并发窗口已补充：独立向量receipt在重建claim后产生时，完整worker身份仍须能结束本次失败任务，不能因来源资格失效而永远processing。fail保持全部workspace/document/revision/source/parser/target/attempt/generation/token/attempt及完整items身份校验；provider/current和注册仍要求sourceCurrent。complete在同一authority事务复验creator ACL与来源，来源失效时终止为failed/output_invalid并返回false，旧active不变。伪造、取消、迟到和另一attempt不得据此终止真实任务。

完整新publication和entries写入、受guard约束的旧base→新publication active CAS、任务终态及审计处于同一短事务；远程执行在事务外。任何完整性失败整体回滚。成功同事务终止仅旧base queued/processing synopsis任务为unavailable/source_changed并清除claim，释放新摘要slot；保留完成摘要和标签。失败/取消不改旧摘要任务。所有旧source/parsed页、jobs/attempts/publications/entries/traces不可变，清理仍覆盖全部历史资源。

## 来源与页面

沿原完整saved-scope current-publication策略：处理中、失败或取消时旧回答来源仍可读；成功后旧trace与新active不符，原来源拒绝，页面提示重新查询。不新增历史来源读取政策。

前端独立“重建文本索引”行/详情入口，同时保留“索引任务”。要求text_index/indexings/text_reindex及server can_reindex。确认说明新嵌入/向量处理和旧索引继续可用，发送确认时捕获的base。202只开始观察新任务，不自行设置active；终态回读授权列表并比较publication_id（parsed revision可能不变）。成功使旧来源预览失效、提示重查；保留问题、scope和未保存整理草稿。失败/取消显示“本次未发布，旧版本继续使用”。

## 验收及保留

旧实际3075 Java、435前端用例身份/多重性和原失败行为保留，POM双80%、架构及格式保持。旧迁移测试“当前schema=23”结果oracle随合法新版本更新为24，须列明完整差异。原逆迁移夹具从新当前v24开始时先经新test-only helper恢复真实v23 DDL/guards和原历史数据，再进入原v23→v22等场景；这是必要的历史输入恢复，不能以改变PRAGMA数字伪造旧格式。其原assertEquals(23, version)在真实恢复后保持，全部旧行为/拒绝断言不放宽。分别记录current oracle与这项必要fixture适配，不能谎称所有旧测试字节不变。

受控备份随独立v23→v24迁移新增一份：原v22→v23快照必须仍以精确旧前缀读取并验证SHA、字节及旧版本；原cleanup两份受控备份结果变三份，新增精确v23-before-v24前缀断言。未登记快照继续原字节保留，不能泛化或跳过备份验证。

图片/音频Native验收的原整管理行比较需体现唯一新动态字段：建立独立向量前can_reindex=true，建立后false。先断言旧true，再由旧完整行deepCopy仅设置该字段false，仍完整assertEquals新行；所有旧publication、source、task及其余字段继续完全相同。这两项不在默认测试suite，若仅作此test-only oracle适配，原完整默认验证的生产和默认用例输入可按精确字节复用，最终六Native、完整testCompile/格式及classes/JAR必须实跑并记录两份执行输入的仅两文件差异，不能宣称跨run全输入相同。

先在原产品执行新实际HTTP404对202、schema23对24和DOM缺入口的业务RED；编译、夹具或工具错误单列且不算RED。同一测试源码GREEN后执行直接相关回归、原全量/格式/双80门禁、前端check/test和必要Native。完整输入前后、旧测试身份及最终classes/JAR绑定。真实provider质量、部署与页面NOT_RUN，目标ACTIVE。
