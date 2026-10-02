# 0017 步骤3–4：授权附件答案、hash-only trace与HTTP

状态：2026-09-20 17:33:40 +08:00完整默认门禁通过，1680项Java/577文件格式/双80%与73 Node全部通过。17:35:00单列真实native21项通过，最终独立制品核对已通过、未闭合项0。只认证本机后端，未改前端、旧服务或旧数据，未发云请求、Git写入或部署。总体真实质量与生产目标仍开放。

## 用户闭环与证据

- 原文字问题+临时PNG/WAV/MP4→完整scope验证→完整编译材料/查询原图→有界完整检索与匹配→原问题/库内材料证明→原typed引用。附件不是资料、Evidence或来源，文件摘要不取代OCR/转录/字幕。
- `QueryAttachmentLibraryNativeIT`使用实际Tesseract PNG OCR、FFmpeg完整PCM/视频原帧/双字幕轨、标准OpenAI ASR/VLM/多图rank loopback协议，实际Spring Servlet/SQLite/索引publication；每段ASR、第二字幕轨最后cue进入检索。最终extract收到原问题及仅库内文字，附件提示的999不能进入650元的库内答案；重启来源回读不增加模型请求。
- Visual/Video服务测试区分实际query JPEG与库内PNG/原视频帧，验证同组joint最后事实不能由附件补齐。完整selected set包括未引用资料，撤权后拒答；空/不存在范围不编译附件。查询图抽样有明确说明，不宣称完整视觉覆盖。
- v16只增加hash-only preparation/assets/selected-images附表。成功和失败均绑定源SHA，失败编译字段NULL；原问题SHA双重绑定、终态同事务封存、不可变和回滚、旧v15备份/重启已直接验证。最终拒答保留准备记录且不保留引用。
- 新HTTP测试采用实际JWT和Spring：无身份401零模型、同源403、严格JSON、无附件兼容、20 MiB decoded/28 MiB body、模型阻塞→408→中断→再次200。原四种JSON结果字段不变，默认关闭，不开启multipart。native测试仅替换媒体装配，不把它当作完整production-config/Compose验收。

## 本次红绿与必要夹具修正

1. 17:01:33第一批编译成功RED：23项，10 failure/10 error/0 skip，来自Domain/迁移/Repository、Visual/Video、mapper/config stub。
2. 17:05:06第二批编译成功RED：10项，5 failure/5 error/0 skip，来自共享检索/Answer接线stub和实际HTTP501 stub。两个RED目录独立保存，不被后续GREEN覆盖。
3. 17:09:24首GREEN尝试43项有两个夹具问题：新长文本测试未initialize本机projection；新HTTP用开发头却期望JWT 401。分别补测试projection初始化、改真实JWT且保留401/零调用断言，未改生产认证或放宽期望。
4. 17:12:17随后编译失败仅因新测试直接访问package-private可信身份attach。改为经过原AuthenticationFilter的JWT链；不扩大生产方法可见性，不计为行为RED或能力故障。
5. 17:14:56定向90项全通过，其中新真实三附件native1项；覆盖旧AnswerService和最后修改的QueryPreparation/AudioCompilation/VideoCompilation完整行为文件。无skip、无云请求。
6. 17:20:37首轮完整clean verify的1665项全部通过、576文件格式通过，但BRANCH为8785/11021，未达到既有80% gate。未改门槛/生产逻辑，补新入口已实现输入合同与模型完整响应校验的直接测试，然后重新执行干净完整门禁；不把此次构建标为成功。
7. 17:25:06补充定向28项全通过；同命令错误地单独执行jacoco:check，没有读取绑定在verify execution里的rules，因此命令失败。这是工具调用错误，不是应用失败或最终门禁；累积报告仅用于确认新增合同覆盖，不替代随后clean verify。生产源码未因覆盖率调整。

root独占串行Maven，实际JBR21.0.8+9-b1038.68/Maven3.9.9离线依赖，隔离构建`/private/tmp/java-query-answers.4MDTg2`。Node73全通过。旧源码基线为步骤1–2的579输入/1619身份，开工逐一重算未变。

最终默认LINE为16387/17521（93.527767%），BRANCH为8838/11021（80.192360%）；来自最终clean verify的原XML，不用此前诊断累积数据或随后native覆盖数据提高比例。源码/报告/JAR绑定见[answers-source-manifest](answers-source-manifest.json)，精确身份见[answers-test-cases](answers-test-cases.json)。

默认221份XML、native9份XML分开保存；旧默认1619身份/多重性全部保留，新增61项；旧native20保留并新增真实三附件HTTP1项。604输入中542旧输入原字节不变、37修改、25新增、删除0；repo/build逐SHA一致。37项修改为20个生产/资源接线与17个必要schema测试夹具，25个新增Java为12个生产文件/13个测试及fixture。步骤1–2原manifest/test-cases及verification保留，不把旧报告混成新结果。

## 独立限定审查

非对应实现代理分别核对共享接线/DTO、HTTP/config、v16 trace，Standards/Spec主线阻断均0。遵循既有Spring分层，Service隐藏编译/检索复杂行为，新增Seam仅双角色模型生产/测试Adapter；不造权限体系。完整scope、原问题/查询提示/库内证明分离、所有候选批次、现场依赖revision、hash-only事务和超时许可已核对。审查不能代替实际测试或云质量。

17个旧migration/repository夹具只调整当前schema断言15→16及一行降级链调用，保留内部真实15→14断言与全部测试身份；不删除/跳过/放宽失败测试。旧v1–v15迁移方法经独立比较保持原字节。

最终非本轮实现代理未运行生成helper、Maven、Git或网络，未改文件，以独立解析/散列/归档读取复核：604输入集合与repo/build逐SHA一致；221默认XML、9 native XML及2组RED文件集合、统计与逐XML SHA准确；全部旧默认/native及RED用例的className/name与多重性保留。JaCoCo根节点总计、577格式、73 Node、两个最终时间和14项证据SHA均与工件一致。JAR443个生产class与target/classes集合及逐entry原字节完全一致，无缺失、多余或重复。独立制品审计未闭合项0。

最终`git diff --check`通过；现有密钥模式检查覆盖工作区/历史及446个未跟踪文件，finding为0。这是限定模式扫描，不宣称绝对不存在所有类别敏感信息；未写入真实provider密钥，未执行Git写入/推送。

## 尚未验证

真实provider/ASR/OCR中文和复杂视频质量、Milvus服务端召回质量、生产配置组合/容器、负载性能、前端附件与播放器、目标主机发布。本机替身只认证协议和后端链，不能将其扩为完整多模态生产验收。
