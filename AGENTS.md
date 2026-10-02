# AI Knowledge：智能体工作约定

2026-09-22 17:39:50最新进展：又一组具名2次细粒度诊断获准并完成，累计6/20、未使用14；见[台账](docs/changes/0019-audio-video-provider-eval/provider-run.md)。原/full真实证明均命中instruction_in_field，转录SHA与诊断01相同，编号规范化731=true/AU=false。已定位同字段指令路径；不猜具体错字、不删样本/金标或放宽安全规则。下一步只读核对ASR配置后形成具名候选实验，不自动消耗余量。613输入不变、无重试、视频未开始、生产未完成。下方2/12等为历史，不是当前计数。

2026-09-22 13:54:22当前终态：0019获批最多12次及本批使用原密钥后，具名真实LiveIT在第2次请求后因`eval_audio_not_grounded`失败停止；ASR/摘录各1次、视频0次，10次未使用。先读[执行台账](docs/changes/0019-audio-video-provider-eval/provider-run.md)；不要重复运行或用余额追加诊断，后续模型诊断/复验须另行明确授权。当前仅知原文证明不支持，不能归因ASR或摘录，也不能套用9/21替身同名故障原因。613输入未改；本机冻结与真实失败分开，前端、旧服务/数据、Git写入/部署边界不变。下方9/21 NOT_RUN等属于历史快照。

本机验证命令注意：Git 明确使用 `/Library/Developer/CommandLineTools/usr/bin/git`；每一条会调用 Git 的 Node 测试或扫描命令都单独将该目录放在 PATH 首位，不能假定上一条命令的行内 PATH 赋值会继续生效。`/usr/bin/git` 可能是未接受 Xcode 许可的 launcher；其退出码 69 是工具环境问题，不修改产品测试、不代用户接受许可。多人编辑时等待整批 Java 就绪再复制构建，避免把方法签名中间态当产品编译失败。

Java 测试夹具不跨包调用 `SqliteAuthorityStore` 的包内 `rows/count/execute`；优先使用 Module Interface，必要的 SQL 断言仅通过测试临时库 JDBC。不得为编译夹具而开放生产 SQL API，`testCompile` 失败不作为行为 RED。`SynopsisFileInput` 没有值等同性，重建结果应逐项比较 publication、完整 fingerprint、全部来源 ID/SHA，而不是比较对象地址。G02 同样适用于新测试：显式 import 和控制语句大括号要实际复核，Spotless 成功不证明这两项。

完整配置测试和临时启动probe都按`TextAdapterSettings`/`MilvusRestProjection.Settings`生成参数：Milvus使用根URL及独立`java_`集合，三个文本模型共用`RAG_TEXT_ALLOW_LOOPBACK_HTTP`，不猜测各模型的独立同名开关。既有确定性配置测试持续拒绝未知字段/路径/集合；夹具不合合同的启动失败不算产品RED。运行能力实际入口是公开`GET /v1/config`，不得用未知路由的401冒充能力验收。

## 当前：0019音视频模型评测入口（2026-09-21）

先读0019 intent→spec→plan→REVIEW。[本机工具验证](docs/changes/0019-audio-video-provider-eval/verification.md)已冻结：09:43:17默认1685 Java/582格式/双80%、73 Node及15:46:13单列native23通过；613输入中旧606原字节、旧1680/22精确身份和443生产class保持，独立制品审计实质不符0。固定合成语音/视频完整解码、首请求前F+9预算、三个生产客户端共用计数、音频原文与同组双事实证明已本机跑通；无生产源码修改。操作见[AUDIO_VIDEO_PROVIDER_EVAL](docs/AUDIO_VIDEO_PROVIDER_EVAL.md)。LiveIT未运行，云0；需新授权及轮换后私有密钥/实际模型名，不挪用旧文本余额，不重复本机准备。真实provider/Milvus整链、前端和生产仍未完成；旧服务/数据、Git写入/部署边界保持。

## 历史：0018完整多模态配置装配（2026-09-20）

先读0018 intent→spec→plan→REVIEW。18:12:47最终clean verify1680 Java/578格式/双80%、73 Node及18:13:49单列native22通过，独立制品审计实质不符0；606输入、旧604原字节与全部旧用例身份/多重性见[verification](docs/changes/0018-multimodal-composition/verification.md)。仅RagApplication/真实生产Bean全开配置下的四类上传/任务/索引、附件问答、音频/视频OCR/字幕来源、短摘要及重启零调用已验；无生产代码和旧测试修改。操作入口见[MULTIMODAL_RUNTIME](docs/MULTIMODAL_RUNTIME.md)。模型/Milvus仍为loopback替身，ready503保持；不重复已完成装配诊断，下一主线为新授权下的真实provider/Milvus质量。云请求不得挪用旧余额；前端、旧服务/数据、Git写入/部署边界保持，总体生产目标未完成。下方为历史gate。

## 历史：0017查询附件授权答案与HTTP（2026-09-20）

先读0017 intent→spec→plan→REVIEW。17:33:40最终clean verify通过1680 Java/577格式/双80%、73 Node；17:35:00单列native21通过。604输入、旧1619身份多重性与必要17个schema夹具适配见[answers-verification](docs/changes/0017-query-attachments/answers-verification.md)。完整scope后编译PNG/WAV/MP4→完整检索/原图匹配→原问题与库内证据证明→v16 hash-only trace→默认关闭的有界HTTP已接通；重启来源不再调用模型。不要重复已冻结编译/匹配/授权trace/HTTP诊断，下一主线为完整配置装配与真实provider/Milvus质量验收。云调用需新增授权；前端、旧服务/数据、Git分支/提交/推送/部署边界保持。真实质量与生产目标未完成，下方均为历史gate。

## 历史：0017查询附件输入/匹配Module（2026-09-20）

先读0017 intent→spec→plan→REVIEW。16:41:06默认1619 Java/552格式/双80%/73 Node、16:41:52单列native20通过；579输入中旧567原字节不变，旧1595测试身份/多重性保留，见[verification](docs/changes/0017-query-attachments/verification.md)。[内部Interface](docs/QUERY_ATTACHMENTS.md)保持originalQuestion、retrievalText、queryImages和hash-only manifest分离，标准多图角色只是匹配，不作事实证明。无新Controller/Repository/Config/运行开关/schema；当前不是附件问答HTTP。下一步按plan直接接完整EvidenceScope之后准备/匹配、原问题/库内证据证明、终态trace与有界HTTP，不重复编译/排序协议诊断。前端、旧服务/数据、Git分支/提交/推送/部署及云调用边界不变；真实质量和生产仍未完成。

## 历史：0016完整字幕后端（2026-09-20）

先读0016 intent→spec→plan→REVIEW。16:10:40完整1595 Java/540格式/双80%/73 Node及单列native19通过：v15完整字幕authority/索引→同轨全文文字证明→typed时间/原视频Range→短/长摘要完整字幕材料→重启零模型回读；567输入、旧1541身份多重性与必要旧夹具适配见[library-verification](docs/changes/0016-subtitle-tracks/library-verification.md)。Runtime显式opt-in默认false；不把字幕当ASR/画面OCR，raw markup不是渲染文字，cue时间不是逐词。共享文字policy v5修复中性标签更正且保留示例非事实，最终全共享语义已覆盖。下一业务主线查询附件，不重复字幕/native/迁移/HTTP诊断；真实云质量、前端和生产未完成。前端、旧服务/数据、Git分支/提交/推送/部署及云调用边界保持。

## 历史：0016内嵌字幕输入/编译（2026-09-20）

先读0016 intent→spec→plan→REVIEW。[输入Module](docs/VIDEO_SUBTITLES.md)于15:10:56本机冻结：真实MP4/MKV/WebM全轨原包、同视频有理epoch/精确时间/完整字幕尾部、显式decoder-v2/compiler-v3；1541 Java/520格式/双80%/73 Node与单列native14，547输入及旧1485身份多重性见[verification](docs/changes/0016-subtitle-tracks/verification.md)。旧构造/hash和无字幕行为保持，Runtime尚未激活；空清屏不作事实，raw markup不是渲染文字，字幕不等于ASR。下一步直接字幕authority/完整索引计数→同轨文字证明/typed时间来源→完整摘要枚举，不重复输入协议实验。字幕入库/问答、云质量、网页、生产仍未完成。前端、旧服务/数据、Git分支/提交/推送/部署与云调用边界不变。

## 历史：0015完整长文件分层后端（2026-09-20）

先读0015 intent→spec→plan→REVIEW。14:33:25完整1485 Java/512格式/双80%/73 Node冻结：长文字/长音频/9帧视频在原持久任务和typed来源HTTP可用，全部原批复查可否决遗漏的尾部条件，重启不调用模型；539输入及旧1429身份多重性见[hierarchy-verification](docs/changes/0015-file-synopsis/hierarchy-verification.md)。v14保留旧历史，短模式/原v1指纹/协议不变。下一业务主线为独立字幕轨与typed时间来源，不重复本切协议/存储/HTTP诊断。真实质量、查询附件、网页与生产尚未完成；默认关闭，摘要不进入事实证据。前端、旧服务/数据、Git分支/提交/推送/部署与云调用边界不变。

## 历史：0015持久文件摘要后端（2026-09-20）

先读0015 intent→spec→plan→REVIEW。13:54:10完整1429 Java/494格式/双80%/73 Node通过；完整authority材料→v13持久任务/结果→重启无调用读取→七种typed原始来源/Range已本机接通，521输入与旧1400身份多重性见[library-verification](docs/changes/0015-file-synopsis/library-verification.md)。真实Spring/SQLite+合成编译媒体/loopback模型，不认证云质量或native上传重验。摘要默认关闭、独立配置，关闭仍恢复遗留任务；失败不影响索引，摘要不进入答案事实证据。下一步直接完整长文件分层，超限当前显式不可用不截尾；不重复已完成模型/存储/HTTP诊断。前端、旧服务/数据、Git分支/提交/推送/部署与云调用边界不变；网页/真实质量/生产及总体目标未完成。

## 历史：0015内部生成Module（2026-09-20）

先读[0015](docs/changes/0015-file-synopsis/intent.md)的intent→spec→plan→REVIEW。完整有界原证据→候选→逐条实际来源核验→版本/内容SHA/服务器时间已通过本机1400 Java、468格式、双80%、73 Node；[verification](docs/changes/0015-file-synopsis/verification.md)绑定495输入，旧485输入未变、1352测试身份/多重性保持。仅内部Module，不能说文件摘要API已可用；下一步完整authority枚举/分层材料→持久摘要任务→专用来源HTTP，不重做模型协议诊断。调用方负责当前ACL及最终事务，摘要不进入事实证据；首切超限显式失败不截尾。完整长媒体/真实质量/网页/生产仍待验，前端/旧服务/数据/Git/云调用边界不变。

## 历史：0014视频选中原帧OCR已本机冻结（2026-09-20）

上传→真实选帧OCR→v12完整封存/索引→独立ocr问答→frame-local CP/相交像素词框/真实frame interval/原帧与原视频已接通。12:49:55完整1352 Java/458格式、双80%、73 Node通过，12:50:46单列native6通过；485输入、旧1299身份及多重性与独立制品审计见[ocr-verification](docs/changes/0014-video-library/ocr-verification.md)。OCR只处理已选原帧，非全视频字幕；旧visual/transcript/joint、完整scope和caption只召回规则保持。使用[VIDEO_OCR](docs/VIDEO_OCR.md)合同，不重复已完成视频输入/发布/证明/HTTP/OCR诊断。下一业务闭环推进文件摘要与可追溯来源；独立字幕轨、查询附件、中文OCR/云模型质量、网页与生产仍开放。前端/旧服务/数据不动，不创建Git分支/提交/推送/部署，不发云请求或挪用旧额度。下方为历史入口。

## 历史入口：0014视频文字证据（2026-09-20）

视频授权问答/来源主链已本机通过：原上传/索引→显式visual/transcript/joint→同组完整事实证明→v11 trace→typed时间、封存原帧和原视频Range。12:14:03完整1299 Java/437格式、双80%、73 Node及单列native5通过，461输入与旧1216用例身份/多重性见[answers-verification](docs/changes/0014-video-library/answers-verification.md)，接口见[VIDEO_ANSWERS](docs/VIDEO_ANSWERS.md)。完整all/selected scope保持；caption只召回，publication physical ID不能用内部span句柄替代。下一主线从视频原帧OCR文字与真实词框/帧时间引用开始，独立字幕轨、摘要、查询附件、真实质量、网页与生产保留；不重复已完成授权HTTP主链诊断。不改前端/旧服务/数据，不创建Git分支/提交/推送/部署，不发云请求或挪用旧额度。先读0014 intent/spec/plan/REVIEW，下方为历史基线。

## 历史：0014视频授权答案与来源（2026-09-20）

内部逐事实证明Module已冻结：完整问题共同事实身份→同一真实group的原帧/转录证明→全覆盖或整体拒答；完整转录独立反证否决不受摘录模型拒答影响。最终1216 Java/399格式、双80%、73 Node与单列native1通过；423输入和旧1171身份/多重性见[assessment-verification](docs/changes/0014-video-library/assessment-verification.md)，Interface见[VIDEO_ASSESSMENT](docs/VIDEO_ASSESSMENT.md)。这不是视频问答HTTP或授权trace验收；下一步直接沿用完整scope接视频检索/publication身份、v11 trace及typed时间/关键帧/原视频Range，不重复输入/入库/证明Module诊断。内部span候选句柄不是Milvus physical ID，禁止直接发布为引用。前端、旧服务/数据不动，不分支/提交/推送/部署，不调用云模型或挪用旧额度；字幕/OCR、摘要、真实质量与生产目标保留。下方为历史基线。

## 当前执行入口：0014步骤3音画联合事实（2026-09-12）

步骤2视频上传/持久任务/独立authority/真实时间组/完整索引已于16:04:38本机冻结：1171 Java/383格式、双80%、73 Node及单列native10通过，407输入和旧1134身份/多重性见[publication-verification](docs/changes/0014-video-library/publication-verification.md)。HTTP用显式video MIME选择处理合同，后台真实核实；旧octet/audio-only MP4/WebM不变。下一步直接共同事实身份→同组原帧/转录逐事实证明→typed时间/关键帧和原视频Range，不重跑已完成摄取诊断。caption只召回，不能拼接两份整问题失败结果或缩小完整scope。视频答案/来源、字幕/OCR、摘要、网页、真实质量和生产未完成；前端/旧服务/数据不动，不分支/推送/部署，不调用云模型或挪用旧额度。先读0014 intent/spec/plan/REVIEW，下方为历史基线。

## 当前执行入口：0014视频时间证据与音画联合（2026-09-10）

步骤1真实视频输入已于16:50:36完成本地冻结：原帧/实际PTS/同epoch完整音尾/共用PCM转写/帧召回描述；1134 Java、369格式、双80%门禁、73 Node及单列native8项通过，旧1073身份和多重性保留、393输入绑定见[verification](docs/changes/0014-video-library/verification.md)。下一步直接接视频持久任务/同组证据/完整索引，先解决.mp4/.webm与旧音频的真实类型分派，不重复已完成编译诊断。无公开视频API或联合证明，完整目标未完成。

先读[0014](docs/changes/0014-video-library/intent.md)的intent→spec→plan→REVIEW。当前落地真实视频输入：原帧PTS/尺寸/PNG、变化与兜底选帧、共同epoch的完整音轨，再接持久任务/同组证据/索引/联合证明及typed来源。使用[视频编译](docs/VIDEO_COMPILATION.md)的小Interface，复用既有PCM转写与native生命周期，不让旧AudioDecoder接受视频，不以caption/摘要/转录代替视觉证明。没有公开视频API/运行开关时不得假报视频可上传问答。前端、旧服务/数据不动，不创建分支/推送/部署，云调用需新增授权。以下0013及更早入口为历史基线。

## 当前执行入口：0013音频时间证据（2026-09-10）

先读[0013](docs/changes/0013-audio-library/intent.md)的intent→spec→plan→REVIEW。本机后端已接通真实解码/按采样分段、标准ASR协议、既有HTTP上传任务、v8完整索引发布、共用AnswerService的音频证明、v9 typed时间引用与原文件单byte Range。完整scope/ACL/active/trace保持，服务器分段时间不代表词级对齐或真实ASR质量。下一业务主线为视频时间证据/音画联合，不重做已完成音频诊断。前端、旧服务/数据不动，不创建分支/推送/部署，不使用旧文本预算发起音频云请求。以下0012是已验收历史基线。

0013步骤3已于2026-09-10 16:04:53实际JDK21完整1073 Java/349格式、双80%门禁及73 Node通过，单列真实FFmpeg/Spring HTTP上传→索引→两事实音频问答→时间来源/原音频200及单Range 206/416通过。ASR/问答模型/Milvus服务端仅本机协议替身，云调用0；原1037身份与多重性保留，7个旧migration与1个Runtime测试必要适配，373输入绑定及限定审查见[answers-verification](docs/changes/0013-audio-library/answers-verification.md)。15:32:20的[上传/索引基线](docs/changes/0013-audio-library/publication-verification.md)与15:04:42编译Module基线保留不覆盖。真实音频质量、非WAV codec、前端、视频/联合与生产未验项仍保留，完整目标未完成。

## 当前执行入口：0012原图知识库（2026-09-10）

先读[0012](docs/changes/0012-visual-library/intent.md)的intent→spec→plan→REVIEW。继续无文字图片上传→描述召回→Milvus→原图问答→typed整图引用主线；不伪造OCR页/字符位置。旧完整scope/ACL/active/trace继续复用。关闭visual开关保持既有路径；前端、旧服务/数据不动，不创建分支、推送、部署；新增云请求单独授权，不挪用文本余额。下方0011是已验收Module历史基线。

0012本地切已于2026-09-10 14:15:21实际JDK21完整942项Java、302文件格式、双80%门禁及73项Node通过，原923用例身份保留；独立限定审查P1已关闭，见[verification](docs/changes/0012-visual-library/verification.md)。实际HTTP/SQLite/独立索引子进程闭环已验，模型/Milvus服务端为本机协议替身，云调用0。下一业务主线为音频时间证据，随后视频/联合；不重跑已完成文字诊断，不把子切验收扩大为完整多模态或生产完成。

## 当前执行入口：0011视觉模型（2026-09-09）

先读[0011](docs/changes/0011-visual-models/intent.md)的intent→spec→plan→REVIEW。当前实现原图视觉生成和逐事实评估Module，描述仅用于后续召回；不将机器描述写成OCR或现有文字引用。0010词框链已完成本地验收。纯视觉authority/检索/HTTP和完整多模态/生产仍待后续，不默认开启运行能力。新增云调用需独立授权，不挪用文本余额；不改前端、旧服务/数据，不创建分支/推送/部署。以下入口为历史记录。

## 当前执行入口：0010图片OCR区域（2026-09-09）

继续已授权多模态主线，先读[0010](docs/changes/0010-image-regions/intent.md)的intent→spec→plan→REVIEW。当前正常闭环为图片上传→一次TSV OCR→文本索引问答→同版本原图及相交词框回读。0009首切结果为历史基线，不能认证本切源码。保持Spring分层、既有权限/拒答；不动前端、旧服务/数据，不创建分支、推送或部署，不调用云模型。完整视觉、音视频与生产范围保留。

## 最新入口：多模态主线（2026-09-08）

负责人最新明确“多模态作为主分支开展落地”。先读[0009图片证据](docs/changes/0009-image-evidence/intent.md)的intent→spec→plan→REVIEW，当前只推进独立PNG/JPEG图片文字证据：上传→本地OCR→既有索引/问答→转录与同版本原图回读。图片视觉/区域、音频、视频与联合证据依次交付，不从范围删除。保留传统Spring分层和已有鉴权/ACL/拒答；不自动创建Git分支、挪用文本模型余额、推送或部署。下方“文本优先/当前0008”等为历史记录，不重新启动其非阻塞工作。

0009首切本地结果：实际Tesseract5.5.3英文PNG的完整后端HTTP流程通过，模型/Milvus仍为本机协议替身；最终871Java、73Node、257格式、行93.6934%/分支83.6138%，原858项保留。来源回读原图和转录，不是精细bbox/视觉推理/网页验收；后续区域与视觉、音频、视频及生产保持IMPLEMENTATION。详见[0009验证](docs/changes/0009-image-evidence/verification.md)，未推送或部署。

## 当前优先级：主线先完成

2026-09-08负责人明确要求先收敛需求，先完成主线，再处理分支、异常与权限增强。本条是当前执行入口，优先于下方历史阶段叙述；0008 A步成果保留，B步及容器加固暂后置。先读[文本主线](docs/changes/0007-text-answers/mainline.md)，再读0007 intent/spec/plan/REVIEW；其他历史报告仅用于确认已有行为，不循环重审已关闭问题。

- 本轮只有一条主线：上传文档→解析→索引→提问→回读引用。明确输入、正常路径、输出与验收，先完成可运行闭环，不并行扩展新功能或通用抽象。
- 非阻塞分支、异常组合、细粒度权限、容器/性能加固登记到主线backlog，完成正常路径后再排期。只有阻断主线或已复现数据损坏/泄露、破坏有据回答底线的问题插队，须给具体证据，不能用假设风险无限扩大任务。
- 保留已有鉴权、ACL、引用验证、无证拒答和密钥保护；不通过关鉴权、假模型或假引用制造成功，也不以“安全增强”另起一套权限系统。
- 迭代先跑直接相关验证，未改源码不重复整套回归；主线交付/发布保留既有全量及覆盖率门禁，不能删改失败断言求绿。汇报以真实业务进展为准。
- 传统Spring分层、Model/Security职责和奥卡姆剃刀保持。前端详情页、旧服务/数据不动；新增模型调用、推送与生产发布仍按明确授权执行。完整多模态目标后续逐条主线推进，不从范围删除。

规范详见[Java协作规则](docs/JAVA_DEVELOPMENT_STANDARDS.md)。以下“当前入口”、测试数量和未推送叙述含历史记录，以本节、主线清单和实际源码为准。

最新主线结果：2026-09-08本轮固定PDF真实文本后端流程通过，含实际模型/Milvus的Service与完整HTTP验收；追加30次额度实际用了13次，剩余17次。最终858项Java、73项Node及格式/覆盖率通过，详见[本批主线记录](docs/changes/0007-text-answers/mainline-live.md)。不复跑已完成诊断、不顺带扩展分支；网页、多模态及生产仍未完成，本批未推送或部署。

## 先读

当前入口为[0008文档生命周期](docs/changes/0008-document-lifecycle/intent.md)，先读其intent→spec→plan→REVIEW。A步撤下、在途取消、旧引用失效及审计已通过2026-09-08 13:09:46实际JDK21完整857项Java、240文件格式、双80%覆盖率门禁及73项Node回归；原773项测试完整保留，证据见[0008验证](docs/changes/0008-document-lifecycle/verification.md)。本次按负责人最新“推送一下代码”提交同步，前端、旧服务/数据、模型调用与生产gate不变，不部署。物理清理仍待B步，不能将deleting/pending报告为硬删除完成；0008整体及完整目标仍IMPLEMENTATION。下方日期、旧开关状态与“不推送”均为历史记录，不认证当前源码。

当前按负责人最新“推送一下代码”同步示例语境和完整程序证据修复。2026-09-08 12:08:55实际JDK21干净副本完整773项Java、227文件格式与双80%门禁通过，Node73通过；原675项及91个测试/语料文件完整保留，policy为v4-procedure-context。本批限定两轴finding已关闭；完整语义/真实生成链路、网页、多模态与生产仍未验收。只交付Java源码、测试和安全说明，不调用模型、不改前端/旧数据、不部署。以下“尚未推送”是历史状态，远端提交和新CI仍须独立回读。最新证据以[0007验证](docs/changes/0007-text-answers/verification.md)与[REVIEW](docs/changes/0007-text-answers/REVIEW.md)为准。

2026-09-08 11:02:21本轮本地修复：同页未检索分块冲突不得被忽略；连接词密集页改为单调扫描并协作取消，计算取消仍留安全trace，policy升为v3-page-conflicts。实际JDK21完整675项Java、222文件格式、双80%门禁、73项Node通过；原635项和旧测试文件全部保留。新增修改尚未推送或部署；已推送5a30ea9的CI通过不认证此后修改。限定审查及仍开放的示例标签/多句程序风险见[0007验证](docs/changes/0007-text-answers/verification.md)与[REVIEW](docs/changes/0007-text-answers/REVIEW.md)，整体仍为IMPLEMENTATION。

本次代码同步收尾：d43504f之后新增真实PDF测试入口、生成失败诊断及零模型Milvus鉴权测试/记录，按负责人推送请求交付；不部署生产或修改前端。2026-09-08 10:34:01最新默认回归635项Java、218文件格式、双80%门禁及73项Node通过；鉴权IT另行真实通过，见[验证记录](docs/changes/0007-text-answers/verification.md)。完整PDF模型链路尚未获调用授权或执行，不能据此解除gate。以下历史“不推送”仅描述当时范围，不否定本次代码同步授权。

2026-09-08负责人追加授权“推送一下代码”：当前Java分层重构、文本链路、测试与说明已以 `d43504fc2341d9bae83352b773e79a1897dfccc7` 推送origin/main，GitHub CI通过；不代表生产部署、解除readiness gate或改造前端详情页。以下“不推送/未推送”叙述为此前执行范围或历史记录，以本条和当前验证记录为准。此后两次生成诊断均已执行并超时，授权次数已用完，不得自动重试或写成已通过。新增真实PDF端到端IT尚未获四次模型调用授权，当前只做本地验证。

当前执行[0007文本问答](docs/changes/0007-text-answers/intent.md)既有缺陷修复与本地验收，先读其intent→spec→plan→REVIEW→interfaces。0005分层重构和0006摄取授权是已交付历史基线，不将其收尾与新问答完成混为一项，也不反复隔离/恢复现存源码。前端详情页、旧服务和数据不动，不推送或部署。

2026-09-07 15:59:54 +08:00实际Temurin21.0.12.1+1干净副本完整clean verify通过635项Java、212文件格式检查和双80%覆盖率门禁；Node73通过。本批具名条件跨分块/前后顺序与Model输出不变量修复已通过限定两轴审查，源码绑定及旧测试保留见[0007验证](docs/changes/0007-text-answers/verification.md)。完整0007语义/真实provider/Milvus/网页、多模态及生产尚未验收，Java整体仍为IMPLEMENTATION。历史[0005 closure](docs/changes/0005-spring-layering/closure.md)与[0006 manifest](docs/changes/0006-ingestion-authorization/source-manifest.json)不认证新增源码。

每次任务依次读 [README](README.md)、[AI_CONTEXT](docs/AI_CONTEXT.md)、[ARCHITECTURE](docs/ARCHITECTURE.md)、[ROADMAP](docs/ROADMAP.md)。再读对应 `docs/changes/NNNN-topic/` 的 `intent.md`、`spec.md`、`plan.md`、`REVIEW.md`；这些版本化工件是 source of truth。[0005-spring-layering](docs/changes/0005-spring-layering/intent.md) 是已验收的结构基线，其历史指纹和0003已发布基线bc82a7a的测试/Java21 CI都不认证0006新增源码。总体边界见[VERIFICATION](docs/VERIFICATION.md)。

最新补充（2026-09-08）：[真实Milvus集成](docs/changes/0007-text-answers/milvus-integration.md)新增4096+1及卸载/显式重载通过，3个IT加35既有相关测试共38项通过。[SiliconFlow联调](docs/changes/0007-text-answers/provider-integration.md)嵌入/重排合成smoke通过、摘录60秒超时，整体仍失败且没有重试。两个显式*IT缺配置硬失败，默认635项不运行IT；本批未修改生产Java。新隔离运行环境保留，不启动旧Docker；不可把部分通过扩为完整模型质量、一般容量、重启、多模态或生产完成。

## 当前边界

- 0006 最终 `clean verify` 于2026-09-07 11:33:24 +08:00通过297项Java测试（0失败/错误/跳过），150个Java文件格式检查；73项Node通过。283项原测试逐项保留，新增14项撤权/真实子进程用例先红后绿；独立 Standards/Spec scoped PASS。没有修改schema、解析算法或UI资源。
- Java 分层按[开发规范](docs/JAVA_DEVELOPMENT_STANDARDS.md)完成本地验收：2026-09-07 10:52:26 +08:00 最终 `clean verify` 通过 283 项 Java 测试（含 11 项架构测试，0 失败/错误/跳过），Spotless 检查 148 个 Java 文件；原 256 项逐项保留，Node 回归 73 项通过。旧万能类和废弃入口已删除，不得重新引入生产兼容壳；有效测试与行为契约仍不可删除或放宽。规范是阿里裁剪与 deep Module / 奥卡姆剃刀的项目规则，不是完整阿里合规认证。
- 0005 授权仅为 Java 重构；现已根据持续目标进入上述 0006 独立安全变更。不修改前端详情页，未推送或部署；其他既有工作树变更属于原任务，不能借验收将其回退或发布。

- 这是独立 Java 资料管理纵切，不是完整 Java RAG 或生产发布。支持整理合成元数据、ACL、目录/标签和批量回执；能力契约以 [API](docs/API.md)和实际 routes 为准。
- 0003已接通受限本机上传、持久任务和独立Java解析进程。0004增加显式 `RAG_INDEXING_ENABLED=true` 的索引任务、独立Java索引进程和完整revision校验后的权威发布；仅允许development/test及字面loopback，启动须完整TextAdapterSettings，但运行只调用embedding与Milvus，不调用rerank/generation。索引和摄取开关独立；进程分离不等于OS文件/网络沙箱，不能放宽production gate。`parsed`与`indexed`分开，`can_answer=false`；检索、问答、来源和多模态仍未接业务链路。契约见[TEXT_INDEXING](docs/TEXT_INDEXING.md)。
- 不把 planned 写成 implemented；不把合成行的 ready、revision 字段、parser 测试或历史实现的报告当成当前可检索证据。
- 四份合成 PDF 与 `docs/evals/golden.json` 必须保留；前者用于解析回归，后者是未来 acceptance 输入，不是当前 Java RAG 评测通过报告。
- 仅使用独立 Java 数据目录；不读取、修改或迁移其他服务数据库、集合、文件与运行配置。没有相应授权和证据不得解除 production/readiness gate。
- v3增量迁移保留v2不可变证据约束；active只来自完整manifest验证后的publication sidecar，始终表示source revision。每次claim使用独立projection generation和共享physicalSegmentId，保留不可变attempt及source→physical→digest台账；迟到写入不能复用新attempt物理ID。未来AuthorizedScope从active publication取generation，再映射候选回source evidence，禁止直接填source revision。合成注册身份使用`registered_revision_id`，不能作为active。
- 协议v2绑定parent PID/startInstant；watchdog和固定/tmp跨JVM物理collection lease不等于OS沙箱或上游请求撤回。永不unlink lease inode，父崩溃私有job临时目录仍需受控回收。验证至提交冻结外部数据/collection/schema/index配置，多HTTP回读不构成跨请求快照；generation/异常父进程/真实Milvus验收仅按当前证据认定。缺generation/台账的早期WIP v3拒绝复用，不修改已发布v1/v2迁移。

## 工作方式与验证

- 仓库与隔离构建副本是不同目录。Git 检查使用显式 `git -C <已核实仓库>`；归档日志使用已核实构建目录的绝对路径，不能依赖上一次命令 cwd。制品核验脚本先通过 `rev-parse --show-toplevel` 确认仓库角色，再验证源码与构建输入；目录错误是执行错误，不计作产品 RED。此条记录0014步骤3中两次 cwd 误用，后续不得重演。

- 给已完成或空闲子代理派新工作须使用能触发新回合的followup_task，再用list_agents确认running；send_message仅投递消息，不代表任务已启动。未确认启动不得向负责人报告“正在审查”。

- 使用深 Module、小 Interface、Implementation、Adapter 术语；确有两个 Adapter 时才增加 Seam。测试通过 Module Interface 断言可观察行为，不暴露内部实现来迁就测试。
- 开工前明确文件 ownership；共享工作树不回退他人修改。先失败测试/可复现验证，再实现、再回归；禁止删、跳过或放宽失败测试求绿。
- Java 门禁：`mvn -s .mvn/settings.xml -gs .mvn/settings.xml clean verify`（隔离个人/全局 Maven settings，含编译、测试、Spotless 与 JaCoCo）。UI 与敏感信息检查器测试：`node --test ui-tests/*.test.mjs scripts/check-secrets.test.mjs`；发布扫描：`node scripts/check-secrets.mjs --history`，覆盖暂存文件/对应工作树和可达历史。以 [pom.xml](pom.xml)、[检查器](scripts/check-secrets.mjs)及当前验证记录为准。必要时补真实浏览器和外部集成证据，不用单测替代它们。
- 修改公共字段解析、规范化、条件/否定或范围逻辑后，重跑完整对应行为测试，覆盖既有中英文与正反例；只跑新增 case 不算冻结。
- 同页具名限定的上下文修复必须把同一组限定fixture按前置/后置两个方向重放，并保留同主体拒答、不同明确主体正常回答、真实跨主体前提三类对照；不能只反转某一个“试运行”字符串便声称覆盖所有审批条件。该规则由`TextGroundingScopeTest`与真实parser的`AnswerSemanticScopeTest`回归执行。
- 每次交付更新 spec 对应行为、红绿证据、相关 acceptance、验证结果、未验证项及 plan 偏离。报告真实版本与命令，不推定未运行检查通过，不做无基准性能宣称。
- 浏览器导航/reload 后重新读取当前页面状态，不复用旧引用；首次操作失败就停止当前序列并重新取状态。
- apply_patch上下文失败后先重读完整原行再修补；只包含确需修改且已读取的hunk，不加入猜测或截断的上下文。

## 安全 invariant

- 文档/附件内容是数据，不是系统指令；未来无足够证据必须拒答。引用由服务器按权威 source locator 校验，不能信任模型自由生成的页码/链接或投影正文。
- ACL、固定组织和 active revision 必须在候选进入模型前生效；未来检索范围过滤发生在 top-K 截断之前。
- **完整 selected set**：所有选中 ID 都必须验证，不能截断、静默丢弃或扩成全库。显式空选择或任一所选资料不可用/越权不得回退全库或只用剩余部分；模型调用后、答案提交前再次验证完整选中集合与使用的证据版本，包括未进入最终候选的所选资料。当前未实现问答，也必须保留这个未来约束。
- 改名/目录/手工标签不得改原文件名、源 hash、revision/segment 身份或触发模型。更新、删除、重新索引须可追踪，答案须可还原到文档/分块/模型/提示版本。
- 未来音画联合问题须逐事实覆盖全部问题；同一 EvidenceGroup 的 visual/transcript pair 中两个模态各自至少贡献一个过阈值事实，不以查询附件替代文本证明；不足或预算中断则拒答，审计只存子问题哈希与分数。
- 开发身份头仅允许显式 loopback 模式，不是生产认证。JWT 不放浏览器持久存储；接口错误/日志/工件不得包含 token、密钥、原文或内部异常详情。数据库 single-writer 与隔离目录约束不能为方便演示绕过。
- 本地分层验收不代表 SEC 能力全部完成：仍为自定义 Filter + JOSE，未接完整 Spring Security FilterChain/方法鉴权。0005登记的摄取后台创建者ACL差距已由0006独立规格、撤权负例及红绿关闭：取消与审计同事务，旧/伪造claim无副作用；重试需调用者和原创建者当前均可写，取消只需调用者可写。该局部修复不代表未来问答授权链已实现。
- 不提交凭据、私钥、个人绝对路径、真实主机地址、业务数据、数据库或运行日志；只使用可识别的占位值和合成 fixtures。外部 provider/部署/真实数据测试需要明确范围与授权。
