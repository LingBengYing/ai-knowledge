# 执行计划

## 基线与决策

- 上一冻结为 0015 hierarchy：1485 Java、512 格式、双 80%、73 Node；539 个输入在本轮开始逐一重算未变。不重跑未改的基线来替代业务推进。
- 使用 codebase-design 的小 Interface/深 Module，以及 fullstack-dev 的明确 API/测试合同；本仓库已有 layer-first Spring、SQLite、鉴权、任务轮询和统一错误，直接复用，不引入脚手架、ORM、第二套权限或自动重试。
- 字幕解析为现有 VideoDecoder Module 的 opt-in Implementation；生产原生进程与测试原生子进程是已有 Seam 的两个 Adapter。不增加另一套进程生命周期。
- Domain 保存原包和有理时基，显示区间按完整视频 epoch 派生；原包文本与样式分离，不以重新编码的毫秒 SRT 回配原始 PTS。
- Controller 不解析字幕，Repository 不调用 native/model，Service 编排已有 Module。字幕后续旁表不挤入 ASR 表。

## 步骤与可观察输出

步骤 2–4 已于 2026-09-20 16:10:40 本机后端冻结：v15完整字幕authority/索引→同轨文字证明→typed时间/原视频Range→完整摘要/重启回读。1595 Java/540格式/双80%/73 Node、单列native19通过，567输入及旧1541身份多重性见[library-verification](library-verification.md)。本切完成 ST-07～10；后续主线查询附件，真实质量、前端与生产仍未完成。以下步骤1及分工为历史执行记录，不触发重做。

步骤 1 已于 2026-09-20 15:10:56 本机冻结：1541 Java、520 格式、双 80%、73 Node，单列 native 14；[verification](verification.md) 与 547 输入绑定记录边界。下一步直接执行 2、3，不将内部 Module 当作已可检索字幕，不重复原生协议实验。

1. **输入及编译**：新增不可变字幕 Domain、原包读取、decoder-v2/compiler-v3 opt-in；保留旧构造与哈希。先写失败测试，再实现；真实合成 MP4/MKV/WebM 检查精确时间/全部轨和独立文本。此步不激活 Runtime 配置，避免未接 authority 时丢失字幕。
2. **持久发布**：新迁移/旁表、完整回读/manifest、完整索引计数和混合物理 ID；缺任何字幕不发布。适配现有 compiler-v3 gate，不改旧 v1-v14 方法体。
3. **文字证明及来源**：字幕模式、完整轨上下文、独立 trace/source；接完整摘要枚举和 typed 来源；真实 HTTP 端到端及重启回读。
4. **冻结**：按最终修改执行相关旧/新行为文件，完整 Java/格式/80% 覆盖率与 Node，native 单列；旧用例身份和多重性、源 SHA 绑定、独立审查。明确真实云质量、前端和生产未验收。

## 工作边界

Root 负责工件、接口协调、Maven 与最终证据；并行 worker 按 Domain/编译和 native/测试分别拥有文件，不能覆盖彼此或用户修改。只有 root 可运行 Maven，且一次一个。各步骤不删除、跳过、放宽测试或 gate。

### 步骤 2–3 接线合同

- 输入为已冻结的 `VideoCompilation.subtitles` 完整产品；正常路径为 v15 字幕旁表 → 所有非空 cue 完整发布 → `mode=subtitle` 共用文字逐事实证明 → 独立 typed 时间来源及原视频 Range。旧 null 产品与启用但无字幕轨的产品保持可区分。
- 轨身份绑定 revision 与实际 stream index，cue 身份再绑定原包 ordinal；完整轨文本按原包顺序以单个换行连接，包含空包的位置，不按时间重排或合并重叠。cue 的 CP 范围只定位自身文本，证明上下文包含完整同轨尾部。
- header 显式封存 `ocr_expected`，并分别保存 native 字幕 manifest 与 authority manifest；后者绑定原视频、编译版本、旧 base manifest 与 OCR 合同。v3 不能以缺失 OCR 旁表来推断关闭 OCR。旧 base manifest 与 projection_count 不改。
- 所有包进入 authority，只有非空 cue 进入 projection；索引顺序沿用 frame、ASR、OCR，最后按实际轨和原包 ordinal 追加字幕。所有模态共用 4096 projection 上限与既有完整发布门禁。
- 查询 trace 独立引用 subtitle cue，不借用 ASR/group/frame；来源标记 `video_subtitle` / `embedded_subtitle` / `subtitle_cue`。摘要完整枚举新增第八类材料，旧七类 fingerprint 不变。
- worker A 拥有字幕 authority Domain、摄取/索引 Repository；worker B 拥有 v15 schema、迁移与旧夹具适配；worker C 拥有查询、证明、trace 与 typed 来源；root 拥有摘要、运行时接线、真实 HTTP/native 验证及工件。共享接口先协调，root 记录可编译 RED 后才转 GREEN。
- runtime 启用必须等持久化、所有查询消费者和摘要全部接通；本机验证只用合成媒体和 loopback 模型/Milvus。无云调用、前端修改、Git 操作或部署。

## 调研来源

- [FFprobe 官方输出选项](https://ffmpeg.org/ffprobe.html)：整数 packet PTS/duration、stream、data 输出。
- [FFmpeg mov_text 解码器](https://github.com/FFmpeg/FFmpeg/blob/master/libavcodec/movtextdec.c)：长度前缀文本、空清屏包和后置样式块。
- [FFmpeg WebVTT 解码器](https://github.com/FFmpeg/FFmpeg/blob/master/libavcodec/webvttdec.c)、[SubRip 解码器](https://github.com/FFmpeg/FFmpeg/blob/master/libavcodec/srtdec.c)：文本与显示标记是不同概念。不能把原包任意字节解码为可见文字。
- 本轮真实协议实验 `/private/tmp/subtitle-protocol.mrFXPN`：mov_text 空包、MKV/WebM 重叠、多轨和 60001/30000 秒的视频 epoch。仅实验，不当作项目验收。

## Backlog

ASS/SSA、位图字幕 OCR、烧录字幕全帧识别、字幕上传/替换/选择 UI、字幕参与新的联合证明模式、真实 provider 质量、前端和生产按后续主线处理；不将现有音画 joint 的 transcript 偷换为字幕。
