# 0013 审查

当前状态：0013本机音频后端纵切的限定Standards/Spec审查与实际HTTP/native、完整回归通过，最新范围见文末步骤3及[answers-verification](answers-verification.md)。真实ASR/非WAV codec/模型质量、前端与生产未验项保持；下方Module/步骤2为历史审查范围。

限定Standards：Spring职责、独立时间来源、小Interface、复用已有权限/任务/证明；不复制整套AnswerService，不用fake TextPage适配音频。
限定Spec：AUD-1..8真实可观察行为；原采样时间、完整编译、全scope、完整manifest、引用与原字节Range。逐项记录完成范围、先红后绿、外部替身和真实质量区别。

## 本轮限定Standards

独立代理对非其拥有的Service/Domain/Client/Proof与公开文档只读核对：符合L02/L04/L05与Model不变量、构造器注入和小Interface；没有重复AnswerService、假TextPage、空Controller/Repository或通用框架。原架构测试发现Tool直接依赖ApplicationException，已修成仅在纯上下文转换处捕获RuntimeException并返回invalid_quote；取消/语义算法在catch之外。2026-09-10 14:55:15实际343项完整证明+架构复测通过，原断言不变。

## 本轮限定Spec

独立检查AUD-2..4：完整实际解码输出、源SHA/profile复验、真实采样分段、每段ASR前后资格检查，以及全段完成后才返回，没有发现伪造时间、遗漏尾段或返回部分结果的问题。另由非实现代理对比冻结0012源码检查AUD-6：旧真实页上下文/CP范围保持，冲突扫描和程序覆盖使用完整上下文；TruthContext/QuestionFacts/SourceFields/SourceInstructions逐字节保持。新1200 CP单证明上限对旧合法<=1200 CP的IndexSegment不改变行为。

上述结论只覆盖Module，不能认证尚未实现的音频authority/索引/问答/Range，不能认证厂商ASR质量、其他实际codec格式、OS隔离或生产性能。`AUDIO_COMPILATION`与README明确这些边界；本机2项native验证已由主线程执行，不是代理静态审查代测。

## 本轮发现与后续

- 已关闭：Tool应用异常直接依赖，见架构RED与343项GREEN；不得放宽白名单。
- 已关闭：新增代码分支覆盖率预检不足，经直接输入/格式正常路径与时间不变量补测，最终分支4115/5114（80.465389%）、行7986/8638（92.451956%）通过原门禁；不改变业务或降低门禁。
- 后续主线：AUD-1/5/7/8及AUD-6的authority与时间引用接线仍未实现，按plan继续；完整0013仍不通过，不把这些待办隐去。

主线程最终验证：2026-09-10 15:04:42实际JDK21完整1014项Java/321文件格式、73项Node和2项单列native通过；原942项身份与旧测试/语料保留，345个构建输入与当前源码一致。见[verification](verification.md)与[source-manifest](source-manifest.json)。测试成功不取代上面的独立静态范围，也不扩大到真实ASR质量、库内音频检索/HTTP或生产。

最终结果另经代理独立只读复核：Surefire的1014项精确身份/通过状态、原942多重集合、345个工作树/构建SHA、321 Java计数、原139个测试/fixture/UI输入、Jacoco根计数与XML摘要、最终Jar和native2归档均符合清单，未发现数字或哈希问题。该复核不调用Maven/网络、不改文件，仍只认证编译+proof Module。

## 步骤2限定审查（2026-09-10）

已接通音频上传/独立authority/完整索引发布，具体结果见[publication-verification](publication-verification.md)。独立代理对非其实现的摄取Service/TaskProcessor核对AUD-1/4与Spring职责：编译在事务外，完整返回后才复验claim/current/profile/SHA并同事务封存，没有部分提交或假TextPage；限定Standards/Spec无阻断发现。

另一非实现代理核对音频Config/Runtime及索引Repository增量：独立配置、默认关闭、启动不调用模型；仅非空span投影且序号稠密，证据ID仍绑定原ordinal；完整发布计数和all/selected scope保持文字/图片/音频全集，模态筛选仅缩小候选。未发现具体问题，不以此认证尚未实现的音频回答。

主代理核对v8 schema/repository：完整头、全部span、真实FK、时间连续/末尾时长、不可变封存与三类发布计数；原v1–v7迁移体保持。实际v7升级及旧迁移回归通过。测试原有当前版本断言随v8准确更新，不删除原用例。

独立native IT审查确认真实HTTP/codec/索引worker、隔绝真实环境与临时数据、3段authority/2段投影断言。曾提出answers关闭时EvidenceService不存在的静态发现，经完整组件扫描源码核对撤回：既有`@Service`/`@Repository`常驻，IndexTarget由indexing配置提供；未据此改代码或扩大范围。最终限定IT审查无发现，实际native1通过仍由主代理运行。

本轮1037 Java/73 Node/329格式和原覆盖率门禁通过。无浏览器、真实ASR/Milvus或生产证据；AUD-6的音频映射及AUD-7/8仍在下一实施步骤，不得将本步审查扩为完整0013通过。

## 步骤3：音频问答/时间来源/Range限定审查

- 非实现代理审查EvidenceService及音频Domain/Controller/Range：完整scope和原actor复验，publication/span、时间、四类SHA与原文件一致，Range仅在授权读取后处理；typed字段无假page/start/end。限定Standards与AUD-6/7/8无P1/P2发现。
- 另一非实现代理审查v9/Store/EvidenceRepository与trace Model：全部原ordinal转录与空段/Unicode CP保留，原span时间/摘要/边界、三个真实FK、完整scope与三类引用联合seal正确；v8备份升级及v1–v8迁移体不变。无具体阻断发现。
- 主代理核对共用AnswerService：只有一份并发/预算/检索/摘录/证明/finish流程，真实文字页映射保持，音频只增加typed候选/来源；完整范围不缩为检索候选，原TextGrounding算法与模型协议不改。
- 实际native HTTP纵切于16:02:29通过，ASR/问答模型/Milvus仅本机替身，无测试Bean/seed绕过。16:04:53最终1073 Java/349格式/双80%门禁、73 Node通过，旧1037身份与多重性保留；373输入绑定见[answers-verification](answers-verification.md)。

上述是本机后端接线验收，不包含真实语音识别正确率、词级定位、非WAV格式真实codec兼容、浏览器播放器、吞吐或生产条件。完整多模态目标仍在实施，下一业务主线为视频/音画联合。

最终独立制品复核通过：373工作树/构建输入、1073精确XML用例及1037基线多重性、cases文件原字节摘要、覆盖率/Jar/logs/native/codec全部一致，v1–v8迁移体逐字保留。复核没有替代运行测试或扩大上述验收范围。

最终非执行代理独立检查353个源码SHA、1037精确用例与1014基线多重集合、全部日志/Jar/coverage/native/codec摘要。发现并关闭制品尾部多LF导致的cases SHA不符：只移除多余空行，重新核对原始字节摘要完全一致，不修改源码或测试。另确认v1–v7迁移体逐字不变、旧测试仅上述必要适配。无剩余封存发现。
