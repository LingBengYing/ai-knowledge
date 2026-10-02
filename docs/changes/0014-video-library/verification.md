# 0014 步骤1：视频输入与编译验证

状态：步骤1本地输入/编译验收通过，0014整体仍为IMPLEMENTATION；步骤1不能代替视频RAG、真实模型效果或生产验收。

## 用户路径已推进到哪里

当前Java可以将完整视频字节处理为实际选中的原帧PNG、真实PTS/时长、统一时间轴的完整PCM、按样本分段的转录，以及仅供召回的帧描述。无音轨视频保留视觉产物，空转录不会伪造声音；原帧SHA与父视频SHA各自保存。当前还不能通过新视频API上传、索引或音画联合提问，因为相应authority/HTTP消费者尚未接线。

传统Spring职责保持：Service只编排完整处理，Worker控制native生命周期和协议，Tool做准入，Domain保存不可变时间/字节/版本；共享NativeMediaSession和AudioTranscriptionService是真实双消费方，不增设空层。仅重用旧AudioCompilationService及ProcessAudioDecoder，原音频哈希、拒绝视频、全静音拒绝和测试保留。

## 已执行行为证据

| 验收点 | 结果与边界 |
| --- | --- |
| 实际视频时间与选帧 | 真实合成VFR MP4，首帧/变化/静态兜底得到0/2/3/5秒，PNG尺寸和实际颜色匹配；不是帧号除FPS |
| 延迟与完整尾音 | 实际音轨相对画面延迟约1.25秒；真实声音延伸到画面末尾之后仍保留，duration取完整音画末尾较大值 |
| 无音轨 | 实际无音轨MP4仍返回完整4个选中原帧，audio为null |
| ASR/VLM协议 | 实际解码后的每个PCM样本以标准multipart WAV送本机ASR；原帧PNG原样base64送本机VLM，结果和源/模型/编译版本绑定 |
| 完整编译和取消 | 无/空音轨、真实尾样本、全帧预校验、资格撤回、尾段失败、配置变化及整体预算直接用例通过；不返回部分结果 |
| 原音频兼容 | 真实native解码、编译/ASR HTTP、Spring上传/完整索引、两事实问答/typed时间与原字节Range四项旧IT重新通过 |
| 视频RAG验收 | 未执行：视频authority、EvidenceGroup、索引、联合逐事实证明及typed HTTP尚待接线；旧音频问答case只是回归，不是视频问答成绩 |

RED：16:25:09，59项（52 failure、7 error、0 skipped），均为编译成功后的stub行为失败；16:30:32 ASR跨阶段profile缺口另1项真实RED。GREEN：16:32:28完整62项编译/Domain/旧音频编译直接回归；16:43:09为81项，补充有理时间基后16:48:21最终82项含新视频/旧音频native受控子进程测试全部通过。新增配置测试纠正项目异常类型、PNG临时缓存和showinfo夹具协议修复详情见[REVIEW](REVIEW.md)，未删除或放宽失败断言。

有理时间基正例通过Decoder Interface及真实JVM子进程验证1/30000时间基：非零epoch为66,666µs，选中帧起点0/50,000/83,333µs、时长16,667/33,334/16,667µs，完整视频100,000µs；该正例验证有向取整，不冒称实际NTSC视频native质量已验。

显式native：最终格式化生产源码于16:39:12单列8项通过：VideoDecoderNativeIT 3、VideoCompilationNativeIT 1，以及4项旧音频native IT。实际FFmpeg/FFprobe 8.1.1；所有ASR、视觉、问答模型和Milvus服务端均为本机协议替身，云调用0。首次HTTP bind被运行沙箱阻止后，在明确允许loopback的执行环境重跑，不把沙箱报错误算产品失败。

## 构建与绑定

临时构建为`/private/tmp/java-video-library.QruOoh`；保留0013冻结副本`/private/tmp/java-audio-answers.622SOm`不覆盖。native XML单列保留在`/private/tmp/video-native-evidence.QDACq7`。

2026-09-10 **16:50:36 +08:00**，实际Temurin21.0.12.1+1-LTS在最终临时副本执行完整`clean verify`成功：1134 Java，失败/错误/跳过均0；369 Java文件格式通过；JaCoCo行9187/9941=92.415250%，分支4737/5915=80.084531%，原双80%门槛不变。首次1133全过但覆盖率79.9662%的失败记录仍保留，最终只增加已存在时间合同的有理时间基正例，生产逻辑未改。

[source-manifest.json](source-manifest.json)绑定393个构建输入及日志/制品摘要，与实际构建副本零差异；[test-cases.json](test-cases.json)保存最终1134项精确className/name/参数索引及多重性，原0013的1073项全部保留。旧373个输入无删除、无旧测试变更，仅两个旧生产输入变化：AudioCompilationService共享转写循环、ProcessAudioDecoder共享native生命周期。新增61项默认用例，另列native8项不混入默认测试数量。

Node 73项通过；tracked与untracked密钥检查、`git diff --check`及相对Markdown链接检查通过。没有读取/调用真实key，没有旧数据复制、Git分支创建、推送或部署；前端未改。测试中的本机随机凭据和合成内容不是云服务访问。

## 未验证项和下一个正常路径

- 非零epoch只有当前受控子进程正例；早前真实MKV平移实验是协议证据，不是当前完整实现的native验收。当前native完整实现主要验证合成MP4，其他容器、真实录音/画面质量、旋转/字幕/更复杂轨道场景不由它代证。
- 控制上限可能拒绝较复杂/较长视频，拒绝不等于截断成功；1200万像素、128帧和32MiB总PNG是开发边界，不是已验生产容量。
- 继续步骤2：接完整视频编译产物到既有持久任务与独立authority，按同一视频版本/真实时间窗口建立EvidenceGroup并完整发布索引。随后按统一事实身份接视觉、音轨和联合证明，最后typed时间/原帧/授权原视频Range；不得将两个整题失败结果拼为联合成功。
- 字幕/OCR、跨模态检索质量、摘要、真实provider/Milvus评估、网页播放器和生产gate保持开放。缺少新云授权时只推进本地工作，不挪用旧文本额度；不将其记为当前本地开发阻塞。
