# 0013 步骤3：音频问答、时间引用与原文件回读验证

本记录覆盖同一音频主线的最后一段本机后端接线：共用AnswerService读取完整转录、逐事实证明、v9音频trace、typed `audio_span`引用、同版本原音频200/单byte Range 206/416。音频时间是服务器真实PCM分段，不是模型时间或词级对齐。本机协议替身不证明ASR质量、真实Milvus效果、前端播放器或生产状态。

## 可观察验收

| 验收 | 输入与可观察结果 | 验证范围 |
| --- | --- | --- |
| AUD-6 音频回答 | all与selected文字+音频完整scope；两个事实分别引用原span 0/2，空白中段不丢失；尾部未召回矛盾仍拒答 | 4项AudioAnswerServiceTest，经共用模型/检索协议及真实临时authority |
| AUD-6 CP与时间 | emoji、换行、空段保留全文及CP；单摘录限制在原索引span，时间直接读取原span；不允许跨span伪造时间或错原字节SHA | 4项AudioSourceEvidenceTest、4项AudioEvidenceServiceTest |
| AUD-5/7 v9 trace | v8备份升级、完整转录字节计数/回读、文字+图片+音频联合连续ordinal、三个真实FK与不可变seal；错时间/摘要/跨段回滚 | 6项AudioTraceMigrationTest及全部旧迁移回归 |
| AUD-7/8 HTTP合同 | 薄Controller双开关；snake_case typed时间字段无假page/start/end；原音频先完整授权后Range；200/206/416及防缓存头 | 2项AudioAnswerControllerTest、7项AudioContentResponseTest、2项AudioDtoTest、7项SourceAudioTest；不将隔离Controller测试当完整装配验收 |
| 完整范围不缩小 | 未被引用的所选文字撤权，音频回答应scope_changed，旧来源回读失败；末次资格超时不能封存音频子引用 | AudioAnswerServiceTest与AudioEvidenceServiceTest；沿用旧scope/ACL/active机制，无新权限体系 |
| AUD-1..8 本机HTTP纵切 | WAV和所选文字均由HTTP上传/解析/索引；真实FFmpeg产生3段，其中中段转录为空、尾段仅1个采样；问答返回原ordinal 0/2的0–1000和2000–2001ms两条引用；来源与原字节SHA相同，三种单Range 206及416通过；未引用所选文字撤权后来源与Range均404 | 单列AudioAnswersNativeIT，实际Spring/SQLite/FFmpeg/FFprobe/索引子进程；ASR、embedding、rerank、摘录和Milvus服务端仅为本机协议替身 |

## RED → GREEN

- 15:46:55，`/private/tmp/audio-answers-red.log`：新增领域、来源、迁移与HTTP的33项，27失败、5错误、1通过。此时音频问答测试尚未进入副本，不声称它已执行。
- 15:49:48，`/private/tmp/audio-answer-service-red.log`：同步副本恰遇Store调用先于v9方法落盘，编译失败。不是行为RED或产品故障，未回退共享文件；等待完整实现后重新同步。
- 15:51:59，`/private/tmp/audio-answers-preintegration.log`：37项中，前述33项全GREEN；AudioAnswerService四项真实RED，stub均返回incomplete_evidence而非answered/conflicting_evidence/scope_changed。
- 15:56:35，`/private/tmp/audio-answers-green.log`：共享AnswerService实现后，474项全部通过，0失败/错误/跳过；包含所有新增测试、旧Answer/Evidence、图片共存、全部迁移及完整`*Grounding*Test`。未修改文字证明算法或模型协议。

## 独立限定审查

非实现代理分别核对音频EvidenceService/Domain/Controller/Range，以及v9/Store/Repository/trace Model，无P1/P2发现。主代理核对共用AnswerService的单一并发/预算/检索/证明/finish流程和原文字映射，未发现具体主线阻断。审查不代替运行测试，也不扩为真实ASR/前端/生产认证。

旧测试必要适配限于7个MigrationTest的当前v9版本及降级夹具、1个AudioRuntimeTest的已实现音频问答/来源能力；旧业务断言与测试身份保留。v1–v8历史迁移体保持不改。

## 最终冻结证据

- 16:02:29，显式native HTTP纵切1项通过，0失败/错误/跳过，实际FFmpeg/FFprobe 8.1.1。日志`/private/tmp/audio-answers-native.log`；XML归档`/private/tmp/audio-answers-native-evidence.JSqyXv`，不混入普通1073项。
- native从公开HTTP上传进入，未直接seed/install/complete，不替换生产Spring Bean；ASR仅按测试内预设文本回复，额外模型时间字段不被采信。static `@TempDir`启动前非空，配置数据目录精确匹配新临时目录；移除Spring系统环境/属性源，只读取3个显式native变量，无真实key或服务连接。撤权只修改该临时库的测试记录。
- 16:04:53，实际Temurin21.0.12.1+1-LTS，离线干净副本`/private/tmp/java-audio-answers.622SOm`执行完整`clean verify`：1073 Java、0失败/错误/跳过，349文件Spotless及原架构/静态门禁通过。行8675/9360（92.681624%）、分支4421/5492（80.498908%），原双80%门禁通过；日志`/private/tmp/audio-answers-final-verify.log`。
- Node73通过、0失败/跳过，`/private/tmp/audio-answers-node.log`。前端、旧证明算法/语料及模型协议本轮未改；此前1037项精确测试身份与多重性全部保留。
- 373个构建输入与最终工作树逐项一致；精确用例、Jar/日志/codec/native/coverage摘要见[answers-source-manifest](answers-source-manifest.json)与[answers-test-cases](answers-test-cases.json)。`git diff --check`、tracked与untracked密钥扫描、Markdown相对文件链接核对通过。
- 步骤1与[步骤2](publication-verification.md)的历史记录、源码绑定和干净副本保持不覆盖，不借旧指纹认证本轮修改。最后一次源码修改后已运行上述完整回归，没有删、跳过或放宽失败测试。

最终独立只读制品复核无偏差：373输入、128份Surefire XML的1073精确用例及1037基线多重集合、cases原字节SHA、覆盖率/Jar/7份日志/native/codec摘要一致；两个JSON均单LF结尾。v1–v8迁移体共57,890 bytes逐字相同。最终157个untracked文件秘密扫描无发现，91份Markdown的577个相对文件链接有效；tracked扫描与diff检查亦通过。

## 交付边界与下一步

0013本机后端正常链已接通并完成上述范围验收。未证明中文CER、真实ASR语义准确率、词级对齐、非WAV格式的实际codec兼容、真实Milvus质量、吞吐或生产条件；没有前端播放器/浏览器验收。音频事件、说话人、摘要和完整多模态/生产目标仍未完成。

下一业务主线为视频时间证据和音画联合：复用已实现的音频时间线与原图事实证明，不能拿转录或caption代替另一模态的事实。前端、旧服务/数据不动，不创建分支/推送/部署；新增云调用仍需独立授权，不挪用旧文本余额。
