# 0013 行为规格

## 音频主线

- AUD-1：显式audio开关和冻结处理profile；上传仍使用现有持久任务/配额/claim/current。支持WAV、MP3、FLAC、OGG、M4A/MP4 audio、WebM audio的magic+实际探测，拒绝带视频的输入。先沿用20MiB上传上限；开发解码预算最多10分钟，不对真实容量/性能外推。
- AUD-2：受限FFprobe/FFmpeg子进程使用固定可执行文件、参数列表、清空环境、允许的文件/pipe协议及格式、输出限长和总deadline；不把扩展名或客户端MIME当解码结论。归一化为16kHz/mono/s16le，时间来自实际解码采样数。长度超限失败，不截断成成功；单机进程控制不是OS沙箱。
- AUD-3：默认每15秒分段，可配置1–30秒；每个WAV chunk带ordinal与整数start_ms/end_ms。POST audio/transcriptions 使用multipart file+model和Bearer header，无自动重试/重定向。只取响应text，不采信模型时间、说话人或置信度；全文与单段均有限长，未知置信度不得伪报高置信。
- AUD-4：在事务外decode/ASR，执行前后及每个chunk请求前后复验调用方提供的当前资格。全部分段完成才返回完整编译结果；任何失败/预算中断不返回部分转录。冻结源SHA、decoder/ASR/profile版本。纯静音无可用转录不能伪造事实。
- AUD-5：增量新增独立audio compilation/span/publication/trace附表与真实FK；v8先落实际摄取与发布所需的前三类，trace表随步骤3的真实问答消费方增加，不先建未使用结构，不改v1–v7历史迁移。不构造TextPage或假page/offset。现有ProjectionItem/v3协议、完整generation/manifest与发布事务复用；全部非空音频证据纳入发布计数，空转录分段保留在authority的完整时间线中。
- AUD-6：复用现有文本摘录与证明算法，抽离真实“上下文文本+候选CP区间”的无页码输入；文字映射真实页上下文，音频映射真实完整转录。不得按字符比例编造时间：引用由已证明摘录映射到相交的服务器AudioSpan，回放整个真实分段。完整否定/条件/冲突规则保留并全量回归。
- AUD-7：薄的显式音频HTTP入口返回kind=audio_span、源revision/SHA、transcript excerpt与start_ms/end_ms、机器转写/时间精度标记；不能含假page/start/end。候选模态筛选不缩小完整所选scope、最终资格、trace或来源授权。
- AUD-8：原音频内容回读绑定原回答者、完整当前scope/active及同一原文件SHA；正常单byte Range返回206，完整读取200，不可满足416。时间定位与文件byte Range是两个概念；不从模型时间构造文件字节区间。每次回读都经既有授权链。

## 验收与边界

实际FFmpeg固定合成音频→实际本机multipart协议替身→完整编译结果→SQLite/独立索引子进程→音频问答→原文件及Range回读；另保留旧942 Java/73 Node及逐事实文字回归。先真实RED再GREEN，不删/跳过/放宽旧断言。

本机ASR/模型/Milvus协议替身不证明中文CER、真实语音识别、时间误差p95或云兼容质量；现有音频计划的真实质量gate保持。无新云授权时先实现并验证本地行为；不得将某个Module通过报告为完整音频库、网页或生产完成。

## 协议依据

2026-09-10核对[SiliconFlow官方转写接口](https://docs.siliconflow.cn/docs/api/audio-transcriptions-post)：multipart file+model，返回text，未承诺timestamp/confidence。由服务器分段生成locator的既有决策继续有效；不能将可选厂商字段当作权威时间。
