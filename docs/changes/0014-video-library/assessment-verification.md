# 0014 步骤3：视频逐事实证明本机验证

状态：内部证明 Module 已实现并通过本机验证；0014及完整多模态/生产目标仍为 IMPLEMENTATION。原[步骤2上传与索引冻结](publication-verification.md)保留，不用其报告认证新增源码。

## 这次实际接通什么

完整问题→服务器共同事实身份→一个真实 EvidenceGroup 的原帧/转录逐事实证明→全覆盖或整体拒绝。分别验证视觉独有、转录独有和联合模式。完整转录可否决矛盾结论，只有同组 span 的精确摘录能贡献文字支持；caption 不进入证明。

真实 FFmpeg 合成蓝色指示块与音轨，经原 VideoCompilationService 和标准 ASR/视觉/文字本机协议 Adapter，实际得到“原帧颜色＋转录等待时间”双事实 proof。检查实际帧字节/像素、250000µs 原帧与1000ms转录的真实交集、共同事实 ID、CP 回读；错误红色/99秒 caption 没有成为证据。它不是公开 HTTP 问答，也不认证云识别质量。

## 最终门禁与源码绑定

- 2026-09-20T11:19:41+08:00，实际 JBR 21.0.8：完整 `clean verify` **1216项Java、0失败/错误/跳过**，148份默认XML；399个Java文件格式检查通过，架构检查通过。
- 默认覆盖率：行 10148/10934=92.811414%；分支 5318/6647=80.006018%，原双80%门槛未修改。
- 最终源码单列 `VideoAssessmentNativeIT` **1项通过**，完成于2026-09-20 11:20:10 +08:00，不计入1216。旧native10项源码保留，本轮没有把旧运行结果计成重新执行。
- Node **73项通过**，0失败/跳过。前端和检查器源码未改。
- [source manifest](assessment-source-manifest.json)绑定423个源码/构建输入；相对步骤2仅4个已有生产文件变化、16个新增Java文件，旧测试文件未变、无缺失输入。
- [case identities](assessment-test-cases.json)保留旧1171用例的精确类名、名称及多重性。不能仅用新增总数声称旧测试保留。

隔离构建目录标识为 `java-video-joint.E78Akq`；默认报告先归档 `default-reports`，最终native报告另存 `native-final-reports`，不混合计数。原步骤2临时目录已不存在，使用已安装JBR21和新Maven缓存恢复，不降级到JDK17或旧JUnit依赖。只下载公开构建依赖，没有云模型请求。

命令（以显式、已核实的仓库/隔离构建目录执行）：

```sh
mvn -o -B -ntp -s .mvn/settings.xml -gs .mvn/settings.xml \
  -Dmaven.repo.local=<isolated-cache> clean verify
RAG_VIDEO_DECODER_IT_ENABLED=true \
RAG_VIDEO_DECODER_IT_FFMPEG=<ffmpeg> RAG_VIDEO_DECODER_IT_FFPROBE=<ffprobe> \
mvn -o -B -ntp -s .mvn/settings.xml -gs .mvn/settings.xml \
  -Dmaven.repo.local=<isolated-cache> -Dtest=VideoAssessmentNativeIT test
node --test ui-tests/*.test.mjs scripts/check-secrets.test.mjs
node scripts/check-secrets.mjs --history
```

## RED、修复与未通过记录

1. 可编译stub阶段24项新测试实际执行：2失败、20错误、0跳过；2个支持边界负例已拒绝，其余因缺实现失败。随后399项新旧相关行为通过。
2. 独立审查发现摘录模型拒答可隐藏转录反证；具名 `cannotHideAConflictingTranscriptByRefusingItsExtraction` 实际RED为错误返回supported。改为独立完整上下文反证否决，补同组/组外负例和正常无颜色转录正例；保留原安全上下文/程序过滤，未将反证升级成支持。
3. Domain跨span引用具名测试实际RED。VideoAssessment新增当前group span身份约束，原断言随后通过。
4. 第一次完整1215测试有1个架构失败：Tool直接catch业务Exception类型。改为窄范围处理Domain构造失败，未放宽架构白名单。第二次1215功能全过，但分支5311/6647低于80%；记录失败后补显式模型依赖与总预算Interface检查，最后1216和门禁通过。
5. 执行/夹具错误单列：一次Service误作静态调用导致编译失败；native初次遗漏显式opt-in；ImageIO夹具二次close；新native摘录预期包含了旧SourceFields不返回的终止句号。均修正接线/夹具，不修改旧语义或删除断言；CP回读与像素断言保留。
6. Node初跑12个Git夹具因系统Git launcher返回69失败；使用已安装CommandLineTools Git后73项通过，没有自动接受许可。两次仓库/构建cwd误用已在AGENTS记录，制品脚本增加目录角色检查。环境错误不算产品能力RED。

## 审查、限制与下一步

限定独立审查发现的跨模态反证P1和Domain同组约束问题已关闭；分层由最终架构测试再验证。不声称完整阿里规范、通用语义或全产品安全认证。

最终制品另经独立只读复核：423输入的仓库/构建/manifest SHA、148份默认XML的1216用例、单列native1、Node73、旧1171身份及多重性、两JSON单LF/raw摘要、coverage和18项证据摘要全部匹配。case identities原始文件SHA-256为 `3fed80a2c4cdfd6f595945a047db148a807fcfe69151b14735be5ed7cb869986`。旧native10文件未修改且本轮未重跑，不并入本轮native结果。

收尾文档更新后重新核实源码/报告绑定；`git diff --check`通过，10份入口/当前文档的344个本地链接均存在。现有密钥检查器扫描tracked/history及224个untracked文件均无发现；history扫描须使用已核实的CommandLineTools Git PATH，系统launcher导致的scan-failed不作为通过结果。

规划不是通用NLP：完整问题最多4096 UTF-8字节/8事实；不能保留共享条件/时间/所属关系时明确拒绝，不能用前缀或裸连接词拆分制造成功。详细 Interface 见 [VIDEO_ASSESSMENT](../../VIDEO_ASSESSMENT.md)。

下一步直接接现有 AnswerService/EvidenceService 的完整授权scope、视频候选与publication身份、v11 trace，再接typed时间/关键帧/原视频Range。当前 Module 不开放runtime能力或HTTP，不直接发布内部候选句柄。字幕/OCR、摘要、视觉/声音向量、真实模型质量、网页与生产仍未完成。没有修改前端、旧服务/数据，没有创建分支、提交、推送或部署；没有读取/使用真实密钥，云请求0。
