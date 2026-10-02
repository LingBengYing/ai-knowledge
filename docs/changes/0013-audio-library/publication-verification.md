# 0013 步骤2：音频上传与完整索引发布验证

2026-09-10 15:32:20，当前本机后端已完成原始音频HTTP上传→持久摄取任务→真实解码/完整转录→v8独立音频证据→独立索引子进程→完整publication。未实现音频问答、时间引用、trace或原文件Range；本记录不是完整0013、多模态、网页或生产验收。

## 可观察验收

| 验收 | 输入与可观察结果 | 实际范围 |
| --- | --- | --- |
| AUD-1/4 摄取接线 | WAV原字节进入既有配额/任务；冻结compiler profile；事务外完整编译，同事务复验claim/current/SHA后保存；取消和错profile/source不能提交 | 5项AudioIngestionServiceTest，临时SQLite与明确编译替身 |
| AUD-5 v8 authority | 3段完整时间线，中段空转录也保留；源/decoder/model/compiler及转录SHA冻结；重启回读一致；原文字页/段均0；v7文字发布原样升级 | 6项AudioLibraryMigrationTest，真实SQLite/FK/迁移/备份；v1–v7迁移体不改 |
| AUD-5 完整发布 | 非空原ordinal 0/2对应稠密索引ordinal 0/1；4096 CP召回文本不截断；缺完整manifest不能激活；完整text/image/audio all/selected scope及旧文字trace保持3份文档 | 3项AudioIndexingServiceTest；真实索引子进程，本机embedding/Milvus协议替身 |
| 配置与能力 | audio默认关闭；仅显式开发/测试loopback+ingestion装配；独立ASR配置；启动不转写；Runtime仅audio_upload/audio_index，不宣告问答 | 8项AudioConfigurationTest与1项AudioRuntimeTest（循环32种组合，不计作32项） |
| HTTP/native正常闭环 | 合成64002字节PCM的WAV，经HTTP202→parsed→索引202→indexed；实际FFmpeg/FFprobe归一化后时间0–1000、1000–2000、2000–2001ms；ASR收到3个精确WAV切片，authority保留全部3段，只embedding并发布2个非空段；同一原字节与版本可内部读回 | 单列AudioPublicationNativeIT，真实Spring HTTP/SQLite/codec/索引worker；ASR与Milvus服务端为本机替身 |

HTTP验收从公开上传进入，不直接seed/complete，不替换Spring生产Bean。static `@TempDir`启动前断言非空且RagProperties.dataDirectory精确匹配；移除Spring系统环境与系统属性源，端点与凭据在测试内构造，只读取3个显式native执行变量。原字节回读是内部Repository验收，**不是已有公开音频来源接口**。

## RED → GREEN → 最终回归

- RED：15:15:54，新增23项实际10失败、11错误、2通过，缺音频准入/持久化/v8/配置/索引均可见。记录`/private/tmp/audio-publication-red.log`，未删/跳过/放宽测试。
- GREEN：15:24:31，93项直接相关测试及原图片/迁移/配置回归全部通过，`/private/tmp/audio-publication-green.log`。一次zsh未引用通配参数的命令在Maven启动前失败，修正命令后运行；不计产品测试故障。
- native HTTP：15:28:15，1项通过，FFmpeg/FFprobe实际8.1.1。日志`/private/tmp/audio-publication-native.log`；XML归档`/private/tmp/audio-publication-native-evidence.e0Otxb`，不与普通Surefire项数混算。
- 最终实际Temurin21.0.12.1+1-LTS，离线干净副本`/private/tmp/java-audio-publication.l7IP0A`的`clean verify`：1037 Java，0失败/错误/跳过；329文件Spotless；架构/静态门禁保持。行8247/8904（92.621294%），分支4231/5244（80.682685%），原双80%门禁通过。日志`/private/tmp/audio-publication-final-verify.log`。
- Node：73通过、0失败/跳过，`/private/tmp/audio-publication-node.log`。前端/fixture与语义算法没有本轮改动；原1014项精确用例身份及多重性全部保留，包含原942基线。
- 旧测试仅必要适配：6个MigrationTest的当前版本7→8及版本降级夹具，另IngestionSettingsTest追加空audio provider参数；旧业务断言、名称与用例均保留。没有宣称旧测试文件全未改。
- `git diff --check`通过；tracked秘密扫描无发现，最终另134个untracked文件扫描无发现；90份Markdown的565个相对文件链接有效。未读取任何真实key、发起云请求、接触旧数据、推送或部署。

精确用例、覆盖率、codec/Jar/日志摘要与353个构建输入绑定见[publication-source-manifest](publication-source-manifest.json)和[publication-test-cases](publication-test-cases.json)。步骤1的[verification](verification.md)、[source-manifest](source-manifest.json)及其干净副本保持不覆盖；它们只认证当时Module，不冒充本轮验证。

独立只读封存复核已完成：353个工作树/构建输入、1037个XML精确用例、1014基线多重集合、覆盖率/Jar/native/logs/codec摘要均一致。复核发现cases JSON末尾多一个LF导致原字节SHA与声明不符，已仅移除制品多余空行并重新核对一致；未改源码、测试行为或报告数字。v1–v7迁移体与步骤1逐字一致，旧测试必要适配范围已另行复核；无剩余封存发现。

## 下一步与不变边界

继续同一音频主线步骤3：完整转录映射到既有证明流程→音频答案/trace→typed `audio_span`引用→当前授权下同版本原音频与单byte Range回放。不按字符比例编时间，不以caption/ASR常识代替已证明摘录。音频trace附表随真实消费方增加，v8只建本次使用的结构，这是已记录的建表顺序偏离，不取消AUD-5/7/8。

本机替身不证明中文ASR质量、真实Milvus部署、词级对齐、非WAV codec兼容、吞吐或生产条件。前端播放器、视频/音画联合、摘要与完整生产目标保留；新增云请求仍单独授权，不挪用旧文本余额。
