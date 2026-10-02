# 0013 执行计划

只有一条音频业务主线，按依赖顺序接通，未完成部分保持显式不可用。

1. 冻结编译Interface与先失败行为：有界真实解码→按采样分段→标准multipart ASR→完整带时间转录，所有阶段保留取消/资格检查。先实现此必需输入，不以伪造转录启动知识库验收。
2. v8 audio authority/摄取接线→复用ProjectionItem与完整publication。独立音频类型，旧文字/图片证据不改身份。
3. 复用文字证明Module的真实上下文接口→音频回答/trace→typed时间来源与原文件单Range；完整scope永不变为候选子集。
4. 实际HTTP纵切、旧用例身份保留、全部verify/Node、限定独立审查、源码绑定。云/真实Milvus/浏览器/生产另列，没有证据不报完成。

使用fullstack-dev的端到端验证与codebase-design的小Interface，项目layer-first Spring规范优先。继续现有REST、typed DTO、JWT/开发身份、持久job轮询、安全异常/配置；不新增通用框架、连接池或重试体系。

并行责任：音频client代理仅负责AudioModels/OpenAiCompatibleAudioModels与multipart传输及其测试；解码代理仅负责AudioDecoder/ProcessAudioDecoder及其测试；主代理负责Domain/编译Service、工件、统一Maven与集成。共享树不回退他人变更；代理不运行Maven/云请求。后续authority与proof接线开始前另明确文件ownership。

2026-09-10进度：步骤1的编译/解码/ASR已完成本地联合与实际native验证；步骤3仅提前抽离其必需的无页码证明输入，复用原算法，尚未接音频回答。首个全量发现Tool异常类型直接依赖违规，保留原门禁修正；最终回归见[verification](verification.md)。此轮没有改authority/迁移/HTTP，步骤2以及步骤3剩余部分仍待接线；不是将完整音频目标降为若干独立Module。

步骤1冻结于15:04:42：1014 Java/321格式、双80%门禁、73 Node及2项显式native通过，原942用例身份和测试文件保留。不要因切换上下文重跑未变的编译诊断；下一步直接进入步骤2，把已验证的完整AudioCompilation接入现有claim/current与v8独立音频证据、再复用ProjectionItem和完整publication。仍不创建Git分支、不发云请求或部署。

## 步骤2实施范围

输入是原始音频上传，正常路径为既有持久任务→实际解码/完整转录→独立音频authority→既有索引子进程→完整publication。可观察输出是任务parsed/indexed、完整采样时间线和已发布非空音频段；本步不把未接线的音频问答或来源回放标记为可用。

- 数据库代理拥有v8迁移、AudioEvidence与IngestionRepository；索引代理拥有IndexingRepository及EvidenceRepository的完整publication计数；配置代理拥有Spring装配/Runtime；主代理拥有摄取Service/TaskProcessor、工件、统一Maven与端到端验证。共享文件改动必须先交接，不并行运行Maven。
- v8保留所有分段（包括空文本）及其源SHA、解码/模型/编译版本、完整转录SHA；只给非空段分配稠密index ordinal，稳定span ID仍基于原ordinal。旧文字/图片与新增音频共用完整scope、发布计数和manifest，不复制索引worker。
- 先失败验证已在15:15:54执行23项：10失败、11错误、2通过，明确缺少音频准入、持久化、迁移、配置和发布接线；测试不删不跳。后续结果单独记录在publication-verification，保留步骤1冻结证据。
- 有意偏离初始建表顺序：音频trace附表延至步骤3，与真实问答消费方一起实现；AUD-5/7/8的完整目标不变。原因是当前上传→发布无trace写入，提前建表会增加无消费方的结构，不符合奥卡姆剃刀原则。
- 实际本机HTTP验收使用显式native开关、固定合成WAV、真实FFmpeg/FFprobe和本机ASR/Milvus协议替身；不会读取或消耗真实provider密钥，不证明语音识别质量或真实Milvus部署。

步骤2冻结于2026-09-10 15:32:20：实际HTTP/native上传→parsed→indexed通过；最终1037 Java/329格式/原双80%门禁和73 Node通过，原1014精确用例身份保留。详见[publication-verification](publication-verification.md)。下一步直接接音频问答/trace、typed时间引用及原音频Range，不重做本步未变输入或退回编译诊断；完整0013仍未完成。

## 步骤3实施合同

输入为已发布音频和完整问题；正常路径复用一个AnswerService的并发、预算、检索、摘录、证明与最终trace。内部仅在文字/音频的候选读取与定位物化处分派，不复制完整问答Service或模型协议。文字仍使用真实页上下文，音频使用全部原ordinal转录以单个换行连接的完整上下文；空段不丢弃。摘录内部CP区间必须落在其检索span内，输出时间直接取该原span；跨段事实输出多条引用。

- 新增薄POST `/v1/audio-answers`、GET `/v1/audio-sources/{answerId}/{ordinal}`及`/content`，仅audio与answers同时启用。复用原请求映射、鉴权、错误、JSON命名和能力机制；不改前端或生产guard。
- typed音频引用包含kind=audio_span、document/revision/source SHA/compiler、文件/MIME、quote及SHA、start_ms/end_ms、machine_asr/server_chunk、source/content URL；不输出假page/start/end。Range在HTTP层，先完整授权读取原字节后解析单byte range，200/206/416；时间定位不是字节定位。
- v9独立audio_trace_evidence通过真实FK绑定完整trace scope、音频publication/span，保存内部CP、原span时间和source/text/transcript/quote SHA，保留三类引用合计seal。v1–v8迁移体不变；旧数据只在全新测试目录生成/升级。
- 文件ownership：问答代理拥有AnswerService及新问答测试；数据库代理拥有v9/Store/EvidenceRepository、TraceDraft及音频trace类型/迁移测试；HTTP代理拥有音频Controller/DTO/SourceAudio、Range与Runtime；主代理拥有PublishedAudioEvidence/AudioSourceEvidence、EvidenceService、共用合成fixture、工件和唯一Maven运行。共享树不回退他人修改。
- 先失败后实现，再运行包含真实codec的上传→索引→问答→原文件与Range回读；完整旧1037身份、Node、格式/静态和双80%门禁保留。合成ASR/模型/Milvus仅验证协议与控制逻辑，不声明识别质量或云验收。

步骤3冻结于2026-09-10 16:04:53：完整native HTTP上传→索引→两事实音频问答→typed时间来源/原文件200与单Range 206/416通过；最终1073 Java/349格式、双80%门禁、73 Node，原1037项精确身份与多重性保留。373输入绑定与限定独立审查见[answers-verification](answers-verification.md)。共用AnswerService与真实完整转录接线完成，没有更换原文字证明算法；v9随真实trace消费方增加，既定建表顺序偏离已闭合。下一业务主线为视频时间证据/音画联合，真实音频质量、非WAV codec、前端与生产未验项继续保留，不重复未变音频诊断。
