# 0014 审查

## 视频选中原帧 OCR 限定审查（2026-09-20）

最终冻结通过：12:49:55完整1352 Java/458格式、双80%、73 Node；12:50:46单列native6。独立Ruby/REXML/Digest审计（未运行生成helper）确认485输入工作树/build一致、旧1299身份/多重性保留、173默认及5 native XML准确、两JSON格式及摘要一致、24证据/JAR摘要匹配，JAR内331个生产class与target/classes逐字相同。无未关闭的本轮主线阻断；不扩大为真实质量或生产验收。

- 实现者之外的代理分别检查了 Service/HTTP 和 v12 Repository/编译合同：混合候选先逐个 authority 分类再按模式筛选；完整 all/selected scope 不缩小；OCR 使用整帧文字上下文和独立 trace，不成为 v11 的音画贡献；来源使用同版本原帧 SHA、文字 manifest、精确 CP/摘录和相交原像素词框，时间不延长到下一选帧。未发现这些范围内未关闭的主线阻断；不是云质量或生产审查。
- v1–v11 历史迁移体及既有视频 preparation/publication helper 与 1299 基线逐字相同。旧测试仅适配当前 v12 版本及合成迁移回退夹具，不能删除或降低已有断言。具体文件和用例身份见本轮源码清单。
- 审查发现本批 21 处新增存储控制语句及主代理新增 Service 中存在无大括号写法，违反 G02；已补齐，不改变行为。Spotless 不会补大括号，不能拿格式化通过替代规则复核。
- 首轮完整 1351 项回归有 1 项真实兼容性回归：旧未装配视频的来源入口应在 authority 查询前返回 `answers_unavailable`，新 OCR 路由先查询导致 `not_found`。已恢复前置 `requireVideo()`，OCR 同样要求视频装配；旧测试原样保留。新 OCR 夹具改为真实视频 Service 装配并补充禁用入口测试，15 项直接相关用例于 12:47:38 全通过。最终完整冻结以 ocr-verification 为准。
- 一次模块 GREEN 编译采到了共享树正在编辑的 Repository 方法签名中间态，未进入测试；后续按整批就绪同步。Node 首次测试调用了未许可的 Xcode Git launcher，12 个合成 Git 夹具失败；显式使用 CommandLineTools Git 的 PATH 后 73/73 通过，没有修改测试或接受系统许可。这些运行环境问题不算产品故障。

## 授权视频问答与来源限定审查（2026-09-20）

当前公开链已接入完整scope、publication映射、v11逐事实trace和typed时间/原帧/原视频Range；最终冻结以[answers-verification](answers-verification.md)为准，以下内部Module审查是历史记录。

- 独立编排/来源审查核对全scope不随候选缩小、同组完整事实、span→physical映射及提交前权威重新物化；未发现未关闭的主线阻断。
- 独立入口/配置/HTTP审查核对共用AnswerService并发/预算、视频配置不影响旧文本入口、当前source授权先于Range、组/帧与ASR整段时间区分；未发现主线阻断。
- 独立native夹具审查发现文字上传MIME不符合既有合同，执行前改为application/octet-stream；后来索引夹具删除不允许的请求体，不放宽HTTP合同。
- 主代理发现真实单模态组在List.of.contains(null)处失败，直接测试后修复；又核对前置空转录的CP规则，修复Repository及v11定位触发器，并增加leading-empty/exact quote封存回读测试。旧migration方法体不改。
- 首次全量1261行为通过但分支79.0362%未达80%，保留失败日志；仅补已实现的公开组选择/来源、共享生命周期及Model身份合同，不扩展权限体系或放宽门槛。新增夹具误把任意写诗请求当不支持语法，改用旧Planner明确拒绝的共享条件问题，两模态零调用与拒答断言保留；定向53项于12:11:34全通过。
- 最终12:14:03完整1299 Java/437格式、行92.973336%/分支80.669546%、73 Node及单列native5通过，原1216精确身份与多重性保持。独立只读文档复核确认API与五份当前合同的时间/模态/范围/开关/状态码一致，未将本机替身扩大成云质量或生产完成。
- 最终制品独立只读PASS：使用独立Ruby/REXML/Digest直接重算，未运行生成器；461输入集合及仓库/构建SHA一致，423旧输入无缺失，20改/38新声明一致。163份默认XML的1299用例与case artifact精确身份/多重性一致，旧1216完整保留；4份native XML恰5通过，167份报告JBR版本一致，Node73、覆盖率、437格式/clean verify及23项证据/JAR摘要全部匹配。两JSON单LF无CR、raw case SHA匹配。9个旧migration测试差异恰20处当前版本10→11和1处restoreVersionTen调用；initialize/v2–v10方法体逐字未变。最后native后仅增改测试/文档，生产文件未再修改；无具体制品差异。

以下为步骤3内部证明及更早历史审查；其中“尚未接线”仅描述当时版本。

## 步骤3内部证明Module限定审查（2026-09-20）

状态：内部同组逐事实证明已本机冻结；0014整体仍为IMPLEMENTATION，授权检索/trace/typed来源及HTTP未接通。当前证据见[assessment-verification](assessment-verification.md)，不以此认证真实云质量或生产。

- 独立审查发现P1：摘录模型拒答可以隐藏转录反证；具名测试实际RED后，增加完整转录的独立反证否决，保留原上下文/条件过滤，反证不能反向充当支持。
- Domain跨span引用具名测试实际RED后补同组span约束；共同事实身份绑定整问题/ordinal/requirement，旧claim/requirement身份不混用。原帧证明不传caption，转录只证明当前span，所有事实完整覆盖且联合模式两模态均贡献。
- 最终架构测试发现Tool直接依赖业务Exception，已在窄Domain构造范围处理，不新增白名单。旧整问题Interface、prompt/revision与1171用例身份/多重性保持。
- 首次完整功能通过但branch未到80%，补显式模型依赖/总预算Interface测试后达标；未降阈值、删除或跳过旧测试。最终1216 Java/399格式、双80%、73 Node与单列native1通过。
- 最终制品独立只读复核通过：423输入仓库/构建/manifest SHA、148份默认XML的1216用例、单列native1、Node73、旧1171身份/多重性、两JSON单LF/raw摘要、coverage和18项证据摘要全部匹配。实际JBR21.0.8；旧native10未在本轮重跑，不并入本轮数量。
- 当前结果是内部候选证明，不是授权发布引用；后续必须通过完整scope和真实publication映射重新物化，不把span候选句柄当作Milvus physical ID写入trace。

以下为步骤2及更早历史审查。

状态：步骤2视频持久任务/完整索引的限定Standards/Spec审查及本地验收通过；0014整体仍为IMPLEMENTATION。当前结果见[publication-verification](publication-verification.md)，下方步骤1记录保留，不以历史审查认证新增源码；联合问答与typed来源尚未验收。

## 步骤2限定审查（2026-09-12）

- 摄取独立审查确认旧octet/audio容器不被抢占，显式video MIME仅选择处理合同；后台按冻结MIME/compiler实际编译，关闭/错型不降级；提交在原Store事务复验claim/source/profile/ACL，完整保存后才parsed。
- Domain与索引独立审查确认帧/转录独立身份、空段/孤立音尾完整保留、真实半开交集组、caption仅召回；全scope同时计入所有视频source/entry，候选模态缩小不替换完整all/selected范围。
- 存储独立审查确认v10真实复合FK、完整seal、双向physical ID碰撞和三发布gate；v1–v9迁移体与步骤1冻结副本逐字节一致。原文件仍在原authority，没有新通用存储框架。
- Config/native独立审查确认视频资源不注册竞争的旧模型Bean、不隐式启旧问答；发现新关闭测试的非法PNG夹具已改为合法合成图，model_closed断言保留。另新mock重复头与abstained状态夹具修复原因见验证记录；不修改生产状态码或放宽旧断言。
- 16:04:38完整1171 Java/383格式/双80%门禁、73 Node和单列native10通过；407输入绑定，旧1134用例身份/多重性保持。未关闭的异常caption NUL差异按主线优先记录[backlog](backlog.md)，不是当前正常链阻断。
- 最终制品独立只读复核通过：407输入工作树/构建SHA、141份默认XML1171和7份native XML10、1134旧身份及多重性、两JSON单LF/raw摘要、覆盖率和14项证据摘要全部匹配，不把本机协议验收扩大为模型质量。

以下为步骤1历史审查。

Standards范围：传统Spring职责、深Module小Interface、复用真实native生命周期和PCM转写、不复制问答Service、不放宽旧音频准入；旧行为/测试保持。

Spec范围：实际视频帧/音轨时间与相对偏移、变化+兜底选帧、原帧SHA及parent lineage、完整EvidenceGroup/manifest/scope、完整问题逐事实联合证明、typed来源与原字节Range。原图事实不能由caption证明，ASR替身不能代证真实识别质量。

## 限定审查与处理

- 独立Domain/编排审查发现空caption的Domain异常映射不匹配：ImageRecall抛ApplicationException，不能只捕获IllegalArgumentException。已精确修复在Domain构造处，不扩大到remote/current/timeout；既有本轮空caption测试通过。
- 独立审查发现ASR已结束、caption阶段更换ASR版本仍可错误返回。16:30:32新增具名用例实际1项RED；AudioTranscriptionService.revision()复验current模型后仍返回冻结fingerprint，16:32:28完整62项直接行为通过。旧Compiler哈希、check与旧测试不变。
- 独立native协议检查未发现当前步骤1主线阻断：完整帧表整数PTS/有理time base、首帧/变化/间隔、PNG/metadata逐项配对、同epoch和完整音尾符合已冻结合同。非零epoch当前实现仍仅受控native子进程正例，早前真实平移实验是协议参考，不能计作本次实现的native验收。
- 本批初次新Compiler配置测试误把ModelValues.invalid()断言为IllegalArgumentException，已改为项目真实ApplicationException且断言invalid_request；不是放宽校验，测试名与所有非法输入保持。旧测试未改。
- 新native测试夹具的ImageIO缓存曾试写/var/tmp，在沙箱被拒；两个最小JVM重放确定headless开关无影响，改为夹具自己的MemoryCacheImageOutputStream。另showinfo夹具的时间冒号后空格不同于实际native输出，修正夹具，不放宽生产协议或断言。129帧负例也改为完整合法递增帧，确保测真实上限而不是重复PTS提前失败。这些夹具问题不算产品能力失败。
- native HTTP第一次因运行沙箱禁止本机bind失败；真实视频解码3项和旧音频解码1项已通过。明确授权本机loopback后同组8项在16:36:13、最终格式化生产源码16:39:12通过，模型服务均本机合成协议，不读取云key。
- 首次完整门禁16:45:12为1133 Java全通过、369格式通过，但JaCoCo分支4730/5915=79.9662%，未达原80%。保留失败记录，不放宽门槛；补充已有真实PTS合同的非整数有理时间基正常用例后再执行完整门禁，不以功能测试通过掩盖覆盖率失败。
- 最终16:50:36完整clean verify：1134 Java/369格式、行92.415250%/分支80.084531%、73 Node通过，原1073测试身份与多重性保留，旧测试零改动；393输入与构建副本一致。步骤1无未关闭的已复现主线阻断，后续视频入库/联合/HTTP和实际模型质量仍开放。
- 最终制品独立只读复核通过：393输入的工作树/构建SHA一致，JSON单LF及cases原始字节摘要匹配，134份默认XML共1134通过并保留旧1073多重性，6份native XML恰8通过；Jar、覆盖率、列明日志及codec摘要均一致。该制品检查不扩大为视频知识库或生产验收。

总目标尚未完成：视频联合问答/typed来源、字幕/OCR、网页与真实质量仍需逐项交付。步骤2已交付authority/上传/索引，不重复其诊断；没有运行证据的检查不得标记通过。
