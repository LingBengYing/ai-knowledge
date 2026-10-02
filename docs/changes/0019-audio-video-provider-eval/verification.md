# 0019 本机评测入口验证

最新真实进展（2026-09-22 17:39:50）：累计6/20、未使用14，第二组具名诊断定位到原/full真实证明的instruction_in_field；规范化731=true/AU=false，转录SHA与前次诊断相同。细粒度探针7场景/6类拒绝原因双阶段共12命中，限定独立审查通过；诊断完成不是质量通过，视频未开始。详见[执行台账](provider-run.md)。原613输入和下述本机证据保持，没有放宽规则或重跑全量。

状态：LOCAL_TOOLING_VERIFIED。直接6项、最终全量默认/native集合及限定独立实现审查已通过，最终制品审计另记下节。2026-09-22真实LiveIT已执行并失败，安全码`eval_audio_not_grounded`，2/12后停止，视频未开始，见[执行台账](provider-run.md)。下述2026-09-21本机证据及NOT_RUN为历史快照，不因此变成云质量证明。

开工复核0018全部606输入SHA，变化0。新固定资源来自已有合成fixture原字节，音频353508字节、视频89623字节，SHA及来源见[语料说明](../../../src/test/resources/multimodal-provider/README.md)。真实解码预检结果见[plan](plan.md)，不代替新入口测试。

本切没有生产Java、旧测试、前端或旧服务/数据修改；不提交、推送或部署。真实provider、Milvus整链质量、完整网页、同镜像staging和生产门禁保持。

## 直接验证

隔离构建目录`/private/tmp/java-av-provider.OzvouA`，实际JetBrains JBR21.0.8+9-b1038.68/Maven3.9.9，离线依赖；真实FFmpeg/FFprobe8.1.1，模型仅loopback HTTP替身。

2026-09-21 09:30:37首跑编译通过，6项中默认5项通过，Native1项因`eval_audio_not_grounded`失败。最小真实TextGrounding探针两次均返回`incomplete_evidence`；仅补齐摘录所遗漏的“音频独有事实：”字段前缀后`supported`，仅去除相邻指令仍不通过。新Native模型替身摘录没有覆盖原完整字段，是夹具错误；不是生产代码RED。修正两行常量，ASR转录UTF-8原字节不变、注入句保留、所有断言保留。原XML及日志保留于`evidence-red-av-first`/`av-first.log`，诊断探针仅在隔离目录`debug-probes/`。

09:33:17原完整6项通过，0 failure/error/skip。实际音频176715个采样、视频97280个音频采样和6095000µs时间线，全部3帧描述；准备时预留最多12次，正常链实际11次。HTTP断言逐字节核对两份完整WAV和实际PNG、完整双问题/事实，错误caption未进入证明，首组真实交集为0–100000µs，两模态分别贡献一事实。该组时间是原帧/完整ASR段的交集，不是词级对齐。另起本机失败场景只发生1次503请求后停止，未重试或换组。

日志只保存样本SHA、revision、计数、耗时、范围/贡献及安全失败类别；元数据分值1表示本fixture该事实通过，不是质量统计。LiveIT没有运行，不将本机结果标为真实provider通过。

## 独立实现审查

非实现代理Standards/Spec限定正常链阻断0：完整预算、共享delegate前计数、首组不重试、未注入金标和无生产修改经只读复核。行为修正后的Native SHA256为`f45890955f7688a011d11c5c6bc1a2559f6a1a52674e4b7bf8b4f58421beb87f`；独立内存反向两行修正精确匹配前次审查SHA，并核实ASR转录原字节及其他三个Java文件不变。

09:38:15首轮完整默认1685项全过，但最终Spotless检查发现上述常量声明须由两行合并为一行，整轮构建因此失败；未把测试通过写成完整门禁成功。09:39:01实际formatter修正仅此一处，最终Native SHA为`21bbaec8439b930c5addcc476ce9e0924e1ec5cd3352cf27a78d7a5dab7c76c2`；独立反向换行核对精确恢复上个SHA，确认无语义变化，旧606输入仍原字节保持。保留`verify-first.log`及`format-final.log`，随后再次完整clean verify，结果如下。

## 最终完整门禁

同一隔离构建副本与同一实际JBR21。2026-09-21 09:43:17完整`clean verify`为BUILD SUCCESS，15:46:13单列native `test`为BUILD SUCCESS；两次运行时间分开记录，未把native计入默认套件。

| 门禁 | 实际结果 |
| --- | --- |
| 默认Java / 222份XML | 1685项，0失败/错误/跳过；旧1680精确class/name身份及多重性完整保留 |
| 真实媒体native / 11份XML | 23项，0失败/错误/跳过；旧22精确身份及多重性完整保留，新增评测入口1项 |
| Spotless | 582文件，0需修改 |
| JaCoCo LINE | 16388 / 17521，93.5334741% |
| JaCoCo BRANCH | 8839 / 11021，80.2014336% |
| Node | 73项，0失败/跳过 |
| 构建输入 | 613个仓库/隔离副本SHA一致；旧606原字节保持，新增4个测试Java及3个资源，无删除 |

实际执行使用离线Maven3.9.9与隔离本地依赖；命令均带`-o -B -ntp -s .mvn/settings.xml -gs .mvn/settings.xml`。默认目标`clean verify`，Node命令`node --test ui-tests/*.test.mjs scripts/check-secrets.test.mjs`。native显式设置已验证FFmpeg/FFprobe8.1.1及Tesseract5.5.3的路径和对应ENABLE变量，目标为：

```bash
mvn -o -B -ntp -s .mvn/settings.xml -gs .mvn/settings.xml \
  -Dtest=AudioVideoProviderEvaluationNativeIT,MultimodalCompositionNativeIT,QueryPreparationNativeIT,QueryAttachmentLibraryNativeIT,VideoAnswersNativeIT,VideoCompilationNativeIT,VideoOcrAnswersNativeIT,VideoPublicationNativeIT,VideoSubtitleLibraryNativeIT,VideoDecoderNativeIT,VideoSubtitleDecoderNativeIT test
```

默认XML先独立保存，native只收集这11个`*NativeIT` XML。两集合的报告SHA、原红6项报告SHA、完整日志、JaCoCo XML及JAR SHA均由[source-manifest](source-manifest.json)绑定；[test-cases](test-cases.json)保存1685默认精确身份，native23身份在manifest内。历史0018工件未覆盖。

## AVE合同与验证层

| 合同 | 可观察证据 |
| --- | --- |
| AVE-01 | 固定WAV/MP4原SHA；Native逐字节核对实际PCM封装的WAV和真实PNG；金标只在loopback响应/断言，不进入ASR请求 |
| AVE-02 | 默认`completeTwoFactPlanAndEveryFrameAreReservedBeforeTheFirstRequest`与Native完整解码；F=3预留12，不能缩帧/截尾换取通过 |
| AVE-03 | Native同一评测流程音频原文证明、视频同组完整双事实，错误caption不进入证明；实际正常11次调用 |
| AVE-04 | 默认三客户端共享预算/失败占额与配置缺失硬失败；Native503场景仅一次请求，无重试或换组；LiveIT默认不运行 |
| AVE-05 | 默认失败正文脱敏、Native安全报告SHA/revision/实际时间/贡献断言；固定fixture分值不冒充总体质量 |
| AVE-06 | 最终1685默认+23 native+73 Node、格式/覆盖率及旧身份/源码保留；限定独立两轴审查 |

这些是评测工具与本机协议验收，不是云ASR/VLM/生成质量。LiveIT未运行，不能把本表标成真实多模态provider acceptance通过。

## 最终制品独立核对

非实现代理已独立重算默认222 XML、native11 XML、覆盖率根counter、73 Node及613输入；未运行root证据生成器。JAR SHA为`c970c846f4d3ae7f8ab61ffe38a437c5fcd00b2231cea69fe21148d7571d75df`，443个生产class与0018 JAR集合/逐字节完全相同，也匹配当前target/classes。

最终独立制品审计实质不符0：manifest全部235份报告SHA（222默认、11 native、2红）、10项日志/coverage/JAR证据SHA及613输入双副本匹配；旧606原SHA、1680默认/22 native精确身份与多重性保持，红6与直接绿6及最终集合身份保持。默认及native XML实际runtime均为JBR21.0.8+9-b1038.68；独立复核先格式失败再最终成功的终态，未误认中途日志。manifest SHA为`bf48f0299ff511ef1d847a90927def4111611afd0bfd29dfd447e288a249c2dd`，test-cases SHA为`e1ae135555ccbca41600a4b3060f6f16cd6e972842549e22f5a9d4d70dd655b1`。此结论只认证本地工具与所列制品。

## 收尾检查

文档和生成工件落盘后复核613输入，变化0，manifest/test-cases SHA仍匹配独立审计。`git diff --check`通过；仓库`check-secrets.mjs --history`结果为空，另以同一规则扫描470个未跟踪文件，findings为空。该扫描只覆盖已实现的模式，不是全面安全认证；未提交、推送或部署。没有再次重跑未变源码的全量门禁。

## 未验证与下一步

本批最多12次授权及原密钥确认后已执行该具名链路，实际2次后原文证明断言失败，未使用10次且无重试；详见[执行台账](provider-run.md)。不重复本机工具准备、不自动进行新模型诊断或复验，后续云诊断需另行明确授权；密钥不写入本报告。模型配置与操作合同仍见[AUDIO_VIDEO_PROVIDER_EVAL](../../AUDIO_VIDEO_PROVIDER_EVAL.md)。

真实provider/Milvus索引检索/HTTP整链质量、一般中文识别与噪声长媒体质量、浏览器实际流程、同镜像staging/备份迁移回滚和生产发布均未由本切证明。既有“是什么时间”问法边界保留于plan backlog；没有修改生产parser。全体多模态和生产目标仍未完成。
