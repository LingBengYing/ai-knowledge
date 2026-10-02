# 内嵌字幕合同

## 正常路径与范围

- ST-01：读取真实 MP4/MOV 的 `mov_text`、MKV 的 `subrip` 和 WebM 的 `webvtt` 文本字幕包；同一有界 NativeMediaSession 内完整枚举最多 4 轨。使用显式真实 stream index，不取“第一轨”当完整结果。
- ST-02：完整保留每轨包顺序、原始整数 PTS/duration、有理 time_base、语言元数据、包 SHA 和文本。mov_text 长度前缀后的文本与样式扩展分离；空清屏包仍保留但不成为可检索事实。重叠或相同起点的 cue 不去重、不截短。
- 文本格式明确为 `subtitle-payload-utf8-v1`：SubRip/WebVTT 内联 markup 原样作为数据保留，不声称它已经渲染为纯文字，更不据此证明屏幕可见样式、位置或说话人。后续引用的是嵌入轨文本而不是“画面上可见文字”；渲染/样式处理单独推进。
- ST-03：时间在有理数域先减实际第一视频帧 epoch，再开始向下/结束向上取整到微秒。不得各自先取整再相减，不将字幕独立归零，不把 cue 区间称逐词对齐。非空 cue 早于视频 epoch 显式不可处理；真实字幕尾部可以延伸完整媒体末尾，但不生成虚假视频帧。
- ST-04：现有 20 MiB / 600 秒输入限制保持；总字幕包最多 2048，单包文本最多 4096 code points，总文本最多 500000 code points，原包总量最多 2 MiB。超限整体失败，不能截尾成功。ASS/SSA、位图字幕及未支持的 payload 格式不静默忽略。样式/布局信息不成为文字事实证据。
- ST-05：输入 Module 仍使用现有进程私有目录、总 deadline、有界输出、清空子进程环境、取消与确认退出。只有显式新构造启用字幕，冻结 decoder-v2 和 compiler-v3；旧构造及其 v1/v2 哈希逐字不变。
- ST-06：DecodedVideo / VideoCompilation 持有完整不可变字幕产物，绑定父源 SHA、decoder 和视频 epoch。新 compiler 必须收到完整字幕产物（无轨是合法空列表，缺产物不是无轨）；旧 compiler 不得静默丢弃新产物。字幕失败不得先向模型披露帧或 PCM。

## 同一主线的持久化、查询及摘要接线

- ST-07：新 schema 迁移保存完整字幕旁表/manifest 和不可变发布条目；索引、publication 完整性计数、physical ID 混合分类、scope 都含字幕。不改变旧迁移方法体或 ASR/OCR 身份。
- ST-08：`/v1/video-answers` 显式 `subtitle` 模式走共用文字证明；候选为当前 cue、上下文为完整同轨文本。尾部条件/否定不能因未被召回而丢失。旧 visual/transcript/joint/ocr 含义保持。
- ST-09：独立 typed 字幕引用：`video_subtitle` / `embedded_subtitle` / `subtitle_cue`；时间从 authority 取，不信任模型。封存真实轨/cue/CP/文本哈希/源视频 SHA，回读同版本视频及 Range。不伪造 frame、page、ASR 或 group。
- ST-10：文件摘要完整材料枚举及来源加入字幕，短/长文件都不漏尾；派生摘要和 caption 仍不作事实证据。

## 验收边界

步骤 1：真实 native 临时生成含非烧录字幕的视频，检验三种格式、多轨/重叠/非整微秒 epoch/尾部，以及无字幕旧行为；编译结果字幕独有文本不得出现在假 ASR 或帧描述中。仅完成此步骤不等于入库/问答已接通。

最终闭环：真实 FFmpeg → Spring HTTP 上传 → 持久任务/完整索引 → 字幕独有事实回答 → 时间/原视频 Range → 重启来源读取；完整摘要也包含末尾字幕。模型和 Milvus 可以使用本机协议替身，但不得称真实云质量或生产验收。
