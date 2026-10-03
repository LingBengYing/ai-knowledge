# 验证记录：Java 开发纵切

当前本机[0027原声向量检索验证](changes/0027-audio-vector-retrieval/verification.md)：2026-10-03 06:01:04+08最终clean verify通过2042 Java/716格式，LINE93.068059896441%、BRANCH80.306962259954%，原80%门槛未改。299前端/73后端Node及4项单列native通过；原1913默认用例精确身份多重性保留、732/46输入无变、529 native生产类等于最终JAR。首轮coverage失败、补测和全部实际红绿记录保留；模型均合成loopback，本轮真实调用0、未部署、网页用户验收。下方为历史快照。

## 当前：0019 音视频模型评测工具 · 本机冻结

[评测入口验证](changes/0019-audio-video-provider-eval/verification.md)：2026-09-21 09:43:17默认clean verify通过1685 Java/582格式、LINE93.5334741%/BRANCH80.2014336%；73 Node通过，15:46:13单列native23通过。613输入双副本SHA绑定、旧606原字节和旧1680默认/22 native精确身份多重性保持；235报告/10日志制品及443生产class经独立审计，实质不符0。固定合成WAV/MP4通过完整真实解码、F+9首请求预算、生产客户端协议及原文/同组完整双事实证明；本轮只有测试/资源/说明新增，无生产修改。LiveIT未运行、云0，真实provider/Milvus质量、网页和生产未验收。

## 历史：0018 完整多模态配置装配 · 本机冻结

[完整装配验证](changes/0018-multimodal-composition/verification.md)：2026-09-20 18:12:47最终clean verify通过1680 Java/578格式，LINE93.539182%/BRANCH80.210507%，73 Node通过；18:13:49单列native22通过。606输入在仓库/隔离构建副本均绑定SHA，旧604原字节和旧1680默认/21 native身份多重性保持；独立制品审计实质不符0，443个生产class在JAR与target/classes逐字节一致。新增测试只用RagApplication与全部真实Bean，完整HTTP上传/任务/索引、附件/音频/视频OCR/字幕问答、短摘要及重启零模型/向量来源回读通过。无生产源码或旧测试修改。模型/Milvus仍为loopback替身；全开配置不认证云质量、长摘要或visual/joint再次验收，原专项测试保留；前端和生产未验收。

## 历史：0017 查询附件授权答案与HTTP · 本机后端冻结

[附件答案验证](changes/0017-query-attachments/answers-verification.md)：2026-09-20 17:33:40最终干净构建1680 Java/577格式、LINE93.527767%/BRANCH80.192360%和73 Node通过，17:35:00单列native21通过。604构建输入，旧1619精确身份与多重性保留；542旧输入原字节不变、37必要修改、25新增、删除0。真实合成PNG/WAV/双字幕轨MP4经HTTP辅助检索→库内证明与原typed引用→重启零模型来源回读已验证。模型/Milvus为loopback替身，默认关闭；前端、真实质量和生产未验收。上一阶段输入/排序工件保持历史原件。

## 历史：0017 查询附件输入/匹配 · 本机 Module 冻结

[查询附件验证](changes/0017-query-attachments/verification.md)：2026-09-20 16:41:06完整1619 Java/552格式/双80%、73 Node通过，单列native20于16:41:52通过。579输入中旧567原字节不变，旧1595用例身份/多重性保留；实际PNG OCR、WAV/MP4完整转录/字幕尾部和原图采样、标准双角色多图排序已验证。仅内部Module，授权答案/trace/HTTP未接线，无前端、云模型或生产变更。下一步直接做授权带附件问答闭环。

## 历史：0016 完整字幕知识库 · 本机后端冻结

[字幕后端验证](changes/0016-subtitle-tracks/library-verification.md)：2026-09-20 16:10:40完整1595 Java/540格式/双80%、73 Node通过，单列native19于16:06:53通过。567输入、旧1541用例身份多重性保留；全轨持久化/完整索引、同轨全文问答、typed时间/原视频Range、完整短长摘要与重启零模型来源读取已接通。模型/Milvus为loopback替身，默认关闭；云质量、查询附件、前端与生产未完成。步骤1工件保持历史原字节。

## 历史：0016 内嵌字幕输入/编译 · 本机 Module 冻结

[字幕输入验证](changes/0016-subtitle-tracks/verification.md)：2026-09-20 15:10:56完整1541 Java/520格式、双80%、73 Node通过；单列native14于15:07:19通过。547输入、534旧输入不变/5生产修改/8新增Java、旧1485测试身份多重性全部保留。真实MP4/MKV/WebM全轨/原包/有理epoch/尾部已验证；尚未接字幕持久化、索引、问答、摘要或来源HTTP，Runtime不激活。非云质量、网页或生产完成。

## 历史：0015 完整长文件摘要 · 本机后端冻结

[分层验证](changes/0015-file-synopsis/hierarchy-verification.md)：2026-09-20 14:33:25完整1485 Java/512格式、双80%、73 Node通过；539输入与旧1429身份多重性保持。真实Spring/SQLite/loopback模型覆盖长文字、长音频、9帧视频、尾部反证否决、v14迁移与重启原来源读取；旧短模式及上步[持久摘要](changes/0015-file-synopsis/library-verification.md)保留。非云语义质量、native上传重验、前端或生产完成。

## 历史：0014 视频选中原帧 OCR · 本机冻结

[OCR验证记录](changes/0014-video-library/ocr-verification.md)：2026-09-20 12:49:55完整1352 Java/458格式、双80%、73 Node通过；12:50:46单列native6通过。485输入与旧1299身份多重性经独立制品审计；真实合成英文FFmpeg/Tesseract与HTTP链不代表中文/云模型质量、网页或生产完成。后者及摘要等主线继续保留。

## 历史：0014 视频授权问答与来源 · IMPLEMENTATION

当前真实闭环及逐次结果见[视频答案验证](changes/0014-video-library/answers-verification.md)。它衔接[输入](changes/0014-video-library/verification.md)、[持久发布](changes/0014-video-library/publication-verification.md)、[内部证明](changes/0014-video-library/assessment-verification.md)三次历史冻结；只以最新源码绑定认证当前链。ASR/VLM/文字/向量服务为本机协议替身，不能认证真实模型质量、网页或生产。下方均为历史基线。

## 历史：0008 文档生命周期 · IMPLEMENTATION

基准10dff37的773项Java、73项Node及GitHub CI通过属于历史。新增v5、撤下/取消/来源失效和相关真实进程、SQL、HTTP证据独立记录在[0008验证](changes/0008-document-lifecycle/verification.md)。物理清理B步、完整生命周期、多模态及生产仍未完成；不得用历史773项认证工作区新代码。

## 历史：0007 授权文本问答 · IMPLEMENTATION

2026-09-07 15:59:54 +08:00实际Temurin21.0.12.1+1干净副本完整clean verify通过：635项JUnit、212个Java文件格式检查、行94.83%/分支85.46%且双80%门禁不变；Node73通过。具名条件跨分块/前后顺序、独立主体和标签前提漏判及Grounding输出不变量已修复；详细红绿、旧测试保留、源码/JAR绑定、限定两轴审查和未验证项见[0007验证](changes/0007-text-answers/verification.md)。本机JDK21通过仍不是完整语义、同生产镜像、网页、实际provider/Milvus、多模态或生产验收；下面结果均为历史基线。

## 历史交付复核：仅Java重构（2026-09-07 13:54）

按负责人最新限定收尾0005分层及已验收0006安全基线，不扩展功能或改前端详情页。0007未完成草稿已可恢复地移出编译路径。13:54:52 +08:00重新完成clean verify：297项Java、150文件Spotless、73项Node通过，原297项逐项保留；本次行/分支覆盖率94.71% / 86.90%。173个构建输入与0006完全一致，新JAR指纹及全部限制见[交付复核](changes/0005-spring-layering/closure.md)。未推送或部署。

## 历史：0006 摄取后台授权复验 · 本地验收通过（2026-09-07）

独立[0006工件](changes/0006-ingestion-authorization/intent.md)关闭0005登记的摄取后台创建者当前ACL复验差距。14项新增负例先红后绿，最后源码修改后于11:33:24 +08:00完成clean verify：297项Java测试，0失败/错误/跳过；150个Java文件格式检查、11项架构检查、行/分支双80%门禁通过。73项Node通过。两路独立Standards/Spec审查scoped PASS。

修改前171个文件与0005 manifest一致；原283项测试逐项保留且44个旧Java测试源文件无修改。当前源码/JAR/覆盖率见[source-manifest](changes/0006-ingestion-authorization/source-manifest.json)，用例核对见[test-retention](changes/0006-ingestion-authorization/test-retention.json)，红绿/真实子进程/审计回滚/限制见[完整验证](changes/0006-ingestion-authorization/verification.md)。前端详情页、schema及解析算法未改；未推送或部署。Java整体仍为IMPLEMENTATION，旧指纹不认证本次源码，当前仍无最终RAG golden、真实provider/Milvus、多模态或生产证明。

## 历史基线：0005 Spring 分层重构 · 本地验收通过（2026-09-07）

Java 整体仍为 IMPLEMENTATION，未推送或部署。旧万能类和重复实现已移除，Controller / Service / Repository / Model / Security 等职责实际拆开，没有生产兼容壳。最后一次 Java 源码修改后于 10:52:26 +08:00 完成 clean verify：283 JUnit，0失败/错误/跳过，148 Spotless 文件，原双 80% 覆盖率门禁及 11 项架构检查通过；73 Node 测试通过。重构前 256 项 Java 用例逐项保留。

准确源码/JAR/覆盖率绑定见 [0005 source-manifest](changes/0005-spring-layering/source-manifest.json)，旧测试迁移见 [test-retention](changes/0005-spring-layering/test-retention.json)。[完整验收记录](changes/0005-spring-layering/verification.md)包含红绿、独立 Standards / Spec 审查、本地真实进程及浏览器流程：上传解析、元数据整理、索引取消、失败重试与成功发布。模型和 Milvus 使用本机 HTTP fixture，不是实服务验收。

原有摄取后台 ACL 复验差距仍登记；前端解析后索引按钮需要列表重载的状态同步问题另列，负责人明确本轮只改 Java。当前仍不能问答，readiness 503 不变，没有完整 RAG、多模态、真实 provider/Milvus 或生产证据。下面全部是历史过程记录，不认证本次源码；根 source-manifest 仍为 0003 历史证据，不覆盖 0005。

## 历史过程：0004 文本索引发布 · 重构前（2026-09-07）

当前工作树增加显式索引任务、独立索引JVM、完整Milvus读回验证、v3 publication/active sidecar和索引界面。正在执行最终组合回归与独立审查；本节下方0003的190项测试、71文件manifest/JAR及浏览器证据只认证提交bc82a7a，不认证0004新增源码。当前根source-manifest仍是0003历史内容，完成全量冻结后才更新，不把定向测试当完整覆盖证明。

已执行定向证据：索引首尾schema/index一致性修复先1项红后35项完整retrieval测试绿；合成active语义先1项红后40项管理/摄取/迁移/索引测试绿；HTTP能力声明先红后配置/运行时/HTTP组合17项绿。真实子进程取消、立即重试不重叠、撤权中断、总timeout、部分写入不发布与关闭恢复已覆盖。新runtime测试最初6个错误是测试夹具误用id而非document_id，修正夹具后完整7项通过，不作为产品缺陷或红测证据。

### 进程崩溃与generation定向证据（最终全量冻结之前）

- 09:13:52 +08:00完成14个相关测试类组合，共108 JUnit、0失败/错误/跳过：管理/摄取/迁移/索引authority 43，完整projection 37，worker/lifetime/真实父死/process 17，runtime 7，两种鉴权HTTP 4。此次是`mvn ... -Dtest=IndexingHttpTest,IndexingJwtHttpTest,IngestionAuthorityTest,IngestionMigrationTest,IndexingMigrationTest,IndexingAuthorityTest,ManagementModuleTest,MilvusRestProjectionTest,RetrievalProjectionTest,IndexWorkerLifetimeTest,IndexWorkerTest,IndexWorkerParentDeathTest,ProcessTextIndexerTest,IndexingRuntimeTest test`，不是clean verify或当前完整覆盖率。它早于下述P2新增红测，不认证P2待修源码。
- P1真实父死红测：仅kill实际父JVM，旧embedding返回后仍能追加upsert，预期1次实际2次。修复采用每次claim新UUID projection generation、共享physical ID摘要、不可变attempt和source→physical→digest entries；source revision/segments保留。新真实进程测试分别覆盖父死时尚未发送upsert、已接受旧upsert在新generation receipt之后才提交且不破坏新generation完整verify。后者使用HTTP替身receipt作为验证时点，不冒充实际authority重启整链或真实Milvus测试。
- protocol v2绑定父PID/startInstant；main watchdog终止worker，普通run不halt宿主；本机同用户collection lease与generation隔离各自承担不同职责。独立源码复核确认原P1覆盖风险关闭，不能据此宣称已接受HTTP可被撤回。
- physical ID helper新增2项红测后完整Interface 9项绿，覆盖确定性摘要、两个generation的8192个不冲突ID、分隔歧义、非法ID与中断。authority新增旧generation/伪造claim/不可变映射及早期WIP v3拒绝打开回归，在上述43项中通过。
- JWT真实HTTP使用每次生成的测试secret，验证Bearer优先、Cookie交换、错误组织/缺身份/跨Origin/开发身份头拒绝，以及取消后显式重试至第二attempt发布。静态TempDir与配置路径一致性断言保留。首轮JWT配置空值覆盖为夹具错误，改用实际环境属性名后通过，不算产品红测。
- 新P2锁回归在09:23:40和09:24:08 +08:00连续两次真实失败：同JVM等待者超时close后，仍处持有期的A无法阻止另一JVM C取得同一lease；两次均预期`indexing_timeout`、实际`acquired`。持有者自身deadline未到且控制探针正常，非fixture故障。修为固定64条带本地准入先于channel打开、幂等close且确认关闭才释放后，09:28:05完整Lifetime 4项通过，0失败/错误/跳过、无unused try编译告警。另一JVM证明B超时后仍挡住C，A释放后C才能取得；重复旧close不会释放新holder。独立源码复核关闭P2，须最终全量门禁，不把条带碰撞描述为不同collection独立并行。
- 前端两项原P2已由独立只读复核关闭：accepted poll刷新详情终态、取消/重试后保留未保存输入；web相关41项与Java镜像详情12项通过。新发现editor降级reader后旧保存按钮/闭包仍可发出被服务端拒绝的PATCH，受控DOM追加两项回归真实失败，正在修复。没有ACL突破，不能把受控DOM记为真实浏览器通过。

原四PDF和六golden保留；仍无最终问答/来源链路、Java完整RAG golden、图片/音频/视频、真实provider/Milvus、OS沙箱、性能对照或生产部署证据。0004定向本机替身不解除ready503/production gate。完整逐项结果、最终源码指纹及外部验收必须在实际执行后追加。

## 历史：0003 文本摄取 · 本地验收通过（2026-09-06）

范围：真实文本上传 → 持久任务 → 独立Java解析进程 → 权威页/segment。Java整体仍在IMPLEMENTATION，不是索引、问答、多模态或生产完成。以下结果绑定当前 [source-manifest](source-manifest.json) 的71个构建/源码/测试文件，聚合SHA-256为 `a2fb82286938a6bd428733642f0c21ff2ffc4432615792fcebdc4f3d868d54fd`。最终JAR SHA-256为 `96227b53f03cb38217833d620f5d63344b9674c883dcf1720b929d0c1c4a5ef4`；开发启动复制的不变JAR与之相同。文档由Git提交另外绑定，不属于源码manifest。

| 检查 | 最终实际结果 |
| --- | --- |
| `mvn -o -B -ntp -s .mvn/settings.xml -gs .mvn/settings.xml spotless:apply clean verify`（隔离本地依赖缓存） | 190 JUnit通过，0失败/错误/跳过；22:58:56 +08:00完成 |
| 原管理/鉴权/HTTP/SQLite/解析/Adapter基线 | 148项完整保留；完整suite在最后一次共享分块算法修改后重新执行 |
| JaCoCo line / branch | 2442/2531 = 96.48%；1442/1604 = 89.90%；80%双门禁未改动 |
| Spotless / `javac -Xlint:all` | 通过，无编译告警；生命周期测试的显式close仅作局部try告警说明，不改变断言 |
| `node --test ui-tests/*.test.mjs scripts/check-secrets.test.mjs` | 31项UI + 11项检查器测试通过，0跳过 |
| 独立只读审查 | Standards与Spec两个维度scoped PASS；71个文件、聚合、JAR和覆盖计数再次独立核验一致 |
| 真正打包JAR + 独立前端浏览器 | PDF上传、任务轮询、解析终态；空白TXT失败、两次显式重试、attempt 3后关闭重试；身份切换清除其他用户的资料及任务 |
| 真实HTTP补充验收 | 空白/emoji Markdown解析成2个分块；改显示名/标签不改PDF原文件hash/revision/attempt；其他身份读任务404 |
| 停止并重启同一隔离Java目录 | 3资料/3 revisions/2 pages/3 segments保留；3个locator全部精确对应页内原文；active均null；失败attempt 3及整理元数据保留；live200、ready503 |

本地运行OpenJDK22.0.2 / release21、Maven3.9.9、Node22.23.2。实际Java21结果须以本次提交对应的 [Actions](https://github.com/LingBengYing/ai-knowledge/actions) 为准，历史CI不能认证本次源码。未访问真实provider/Milvus，也未读取或修改其他服务数据。PDFBox合成PDF仍有替代字体警告；文字和locator断言通过，不证明渲染质量。

### 红绿与独立审查修复

HTTP入口最初上传期望202、旧实现实际404；authority、子进程、HTTP配置/限额/取消恢复分别有先失败回归。独立审查发现空白裁剪后重叠窗口可能产生重复分块起点：新增直接解析、真实子进程和authority提交三条回归，首次完整相关32项运行出现1 failure + 2 errors。修复推进窗口而不放宽严格校验后，最终190项完整通过。当前parser revision为 `java-text-parser-v2-monotonic-codepoints`；既有解析证据不就地改写。

取消/真实child终止/重试以及shutdown中断恢复由自动化测试验证；本次浏览器未手动验收取消、移动端或JWT上传流程。迁移测试覆盖v1一致性备份、失败拒绝升级和v2重开；不代表旧Python生产数据迁移演练。loopback权限和测试夹具错误均单独分类，获准后重跑，没有跳过或降低失败测试。

### 浏览器与未验证边界

独立前端版本为 `55a65ee2574380b0a27d3eb01a7a1e1a10dc5658`，其58项独立测试与 [CI](https://github.com/LingBengYing/ai-knowledge-web/actions/runs/34040356414) 通过；本次实际连接新Java JAR进行上述摄取验收，不用先前管理页面截图冒充。重启后截图确认三份资料和PDF解析任务，浏览器控制台读取为0条消息/错误/警告。截图、测试库和运行日志只留本地，不随公开仓库上传。

原六个golden仍未在Java最终问答链路执行，不能报告RAG6/6、召回率或事实支持质量。没有索引发布、当前active authority、最终答案/引用HTTP、多模态、真实provider/Milvus、OS沙箱、性能对照、生产身份与部署验收。`parsed`不等于`indexed`，未解除readiness/production gate。

## 历史：0002 文本 Adapter · 2026-09-06

本次仅新增独立模型/Milvus协议与显式环境配置，不提供新的业务HTTP能力。以下结果绑定 [历史source-manifest](changes/0002-text-adapters/source-manifest.json) 的55个构建/源码/测试文件，聚合SHA-256为 `80900f041c101c27b9faac7ecebd1cf80019d706ce2b604fdef3918870afb09d`；不是生产证明。

| 检查 | 最终实际结果 |
| --- | --- |
| `mvn -s .mvn/settings.xml -gs .mvn/settings.xml spotless:apply clean verify` | 148 JUnit通过，0失败/错误/跳过，20:38:47 +08:00 完成 |
| 原有管理/鉴权/HTTP/SQLite/PDF | 原100项完整保留并通过 |
| 新模型 / 检索 / 配置测试 | 21 / 21 / 6项全部通过；真实本地HTTP替身，不访问真实provider |
| JaCoCo line / branch | 1631/1657 = 98.43%；1055/1153 = 91.50%，80%双门禁未改动 |
| Spotless / `javac -Xlint:all` | 通过，无编译告警 |
| Node UI + secret checker | 20 + 11项通过，0跳过；launcher语法和diff检查通过 |
| 独立只读审查 | PASS，仅当前协议Module源码 |
| 新构建JAR隔离启动 | 全新临时库、独立端口；live200、ready503、management_slice、授权空列表200且total0 |

本地仍是 OpenJDK22.0.2 / release21、Maven3.9.9、Node22.23.2。新提交的实际Java21结果以 [Actions](https://github.com/LingBengYing/ai-knowledge/actions) 对应SHA为准，不复用下面0001的CI。PDFBox原合成PDF仍有系统替代字体警告；解析与定位断言通过，不代表渲染质量。

红绿记录：模型安全stub上17项行为先失败，首次实现后通过；深层JSON补测再次失败，限制深度后完整21项通过。Milvus经历缺实现、搜索/写入、workspace标记与取消先行的失败回归；最终21项完整通过。配置缺类型及不安全端点曾失败，完整6项通过。最末次取消/JSON deadline变更后主线程重新clean verify，不使用并行targeted运行的共享覆盖率报告来认证最终源码。沙箱loopback EPERM单独归类并在获准环境重跑，没有跳过测试求绿。

原六个golden未在Java最终问答链路运行，不能报告RAG6/6、召回率或事实支持质量。无真实Milvus/provider、语料authority、隔离worker、active revision发布、最终引用/答案、多模态、性能对照或生产验收。前端拆分仓库的只读浏览器加载不是这些未实现功能的验收；其证据见 [ai-knowledge-web](https://github.com/LingBengYing/ai-knowledge-web/blob/main/docs/VERIFICATION.md)。

## 历史基线：0001 独立公开快照

日期：2026-09-06。范围是本仓库 Java 管理工作台和独立文本解析 Module，**不是完整 RAG 或生产证明**。

## 本地已执行

| 检查 | 结果 |
| --- | --- |
| `mvn spotless:apply clean verify` | 成功，100 项 JUnit，0 失败/错误/跳过 |
| 原管理/鉴权/会话/真实 HTTP/SQLite 回归 | 94 项全部保留并通过 |
| 新 TextParser 回归 | 6 项通过，含4个原合成 PDF 与精确 code point 定位 |
| JaCoCo 行覆盖 | 820 / 835，98.20% |
| JaCoCo 分支覆盖 | 518 / 571，90.72% |
| Spotless / `javac -Xlint:all` | 格式通过，无 Java 编译告警 |
| `node --test ui-tests/*.test.mjs` | 20 项通过，0 跳过 |
| `bash -n run-dev.sh` | 通过 |
| 不变 JAR 隔离启动 smoke | 通过：live=200、ready=503、Java config=200、授权空列表=200 |
| Secret checker 的真实 CLI 回归 | 11 项通过，含历史已删除密钥、软链接、暂存/工作树差异与精确测试值规则 |
| 前端/检查器 JS 语法、Markdown 相对链接 | 通过 |

本地运行环境为 macOS、OpenJDK 22.0.2、Maven 3.9.9、Node 22.23.2，Java 编译目标 `release 21`。构建没有访问模型 provider。PDFBox 对部分系统字体有解析/替代字体警告；四个中文合成 PDF 的固定事实断言与定位校验仍通过。这不是 PDF 渲染质量验证。

## 红绿与发布范围

TextParser 在空实现上首次运行 6 项测试，其中 5 项报错；实现后全部通过。原管理切保留了先红后绿及浏览器验证，但本次发布没有修改 UI 源码、也没有重做完整浏览器验收，不能把过去结果当作新 RAG 功能证据。

独立快照从之前已验证的39个管理构建/源码/测试文件导出：唯一预期构建变更是加入 PDFBox，另加 TextParser 和解析测试。四个 PDF 均为合成评测资料，不是真实业务数据。为独立 clone 修正了测试资源路径。

本地正在设计但未实现的 model/projection/Authority 外壳及红测草稿没有进入此仓库，仍在原开发工作区保留。本次没有删除、跳过或放宽这些失败测试来声称其功能通过；它们不属于这一明确界定的公开快照。

## 凭据与审查

发布采用独立新 Git 历史，不上传旧 Python 项目历史、环境文件、数据库、聊天凭据、SSH key 或原部署配置。源码只读检查未发现真实 key、个人服务器地址或个人机器路径；同时补充 [.gitignore](../.gitignore)、空 [.env.example](../.env.example) 和 [SECURITY](../SECURITY.md)。

敏感文件/内容/历史检查器的 11 项测试全部通过；包含文件名、内容、历史中已删除凭据、不会输出命中值、暂存与未暂存差异、精确公开测试值以及检查失败关闭。源码表达式误报已有先失败后通过的回归；没有豁免整个测试目录。最终上传须重新扫描暂存区和历史。检查器不保证发现所有密钥形态；聊天中曾提供的 key 仍建议到 provider 轮换。

## GitHub 状态

2026-09-06，负责人完成 GitHub CLI 网页登录后，已正常推送到公开仓库 `LingBengYing/ai-knowledge` 的 `main`。首次源码提交为 [6bfeba9](https://github.com/LingBengYing/ai-knowledge/commit/6bfeba9db5227fa7aa94fd5ae0528350dde230c2)，远端 SHA 已回读核对；没有 force-push，也没有将登录凭据写入仓库。此前 integration 的 403 是历史阻断，现已通过负责人授权的 Git 登录完成上传。

[首次 Java 21 CI](https://github.com/LingBengYing/ai-knowledge/actions/runs/34031531932) 已成功完成（源码提交 `6bfeba9`，2026-09-06 11:54:21 UTC）。Java 21、31 项 Node 测试、全历史凭据扫描、Maven verify、双覆盖门禁与格式检查步骤均成功，不调用真实模型。后续提交的运行结果以 [Actions](https://github.com/LingBengYing/ai-knowledge/actions) 为准，不把历史 run 或排队状态自动视为新提交通过。

0001推送前再次检查：67 个跟踪文件、工作树 clean、历史扫描无命中；[历史source-manifest](changes/0001-java-publication/source-manifest.json) 中45个源码/测试/构建文件逐项与当时HEAD相同，不认证0002源码。聚合指纹按路径排序后连接 `path + NUL + sha256 + LF`，对其 UTF-8 字节求 SHA-256。发布状态文档不属于该源码指纹；Git 提交身份另外绑定完整仓库。

### 0001 当时未验收（当前以页首0003范围为准）

- Java 上传/worker/Milvus/三类模型/问答/引用、删除/重建、图片/音频/视频均未完成。
- `docs/evals/golden.json` 的6个问答预期尚未在 Java RAG 上执行；不能从 PDF 提取测试推导 recall、grounding 或拒答质量。
- 本地测试环境仍为 JDK 22；JDK 21 的独立 CI 证据见上方运行页，不等同目标生产镜像验证。
- 未重新执行浏览器全流程；未进行真实 provider/Milvus 集成、吞吐对比、生产迁移/恢复或发布审批。
- 可公开阅读源码不等于生产安全、可横向扩展或可处理真实机密资料。
