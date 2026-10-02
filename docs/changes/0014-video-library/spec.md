# 0014 行为规格

继承工作区已批准的多模态intent/spec/plan-video与当前Java规范。以下为完整视频主线；实施中的必要Module不能被写成完整视频支持。

## 解码与完整编译

- VID-1：默认关闭的显式video装配复用原上传/持久job/claim/current。输入MP4、MOV、WebM、MKV需magic、container与流探测共同匹配；先沿用20MiB及10分钟开发上限，不外推生产容量。至少一条真实视频流，首切明确支持单视频流及至多一条音轨；多轨选择和其他格式能力另列，不能默选错误轨。
- VID-2：固定FFprobe/FFmpeg与版本摘要，参数数组、文件/pipe协议和格式白名单、清空环境、有界进程输出、总deadline及退出确认；复用现有native生命周期Implementation，不复制两套进程控制。单机子进程控制不等于OS沙箱。
- VID-3：时间来自实际解码帧PTS及duration、音频PCM和音轨起点；记录归一化时间轴的原点，保留音画相对偏移。选帧包含首帧、场景变化与最大间隔兜底，不把固定间隔作为唯一策略；帧PNG/JPEG字节、SHA、尺寸与实际PTS一一对应。拒绝不完整或不可信时间结果，不按帧序号/帧率猜时间，不截断超限结果当成功。
- VID-4：复用PCM→标准ASR分段循环，不再次把原video交给独立AudioDecoder，也不放宽其旧准入。无音轨或全部空转录的视频仍可产生视觉证据，不能虚构音频事实；非空和空音轨段均保持真实时间线。缺少任一必要处理能力或阶段失败，不能返回半个编译结果。
- VID-5：原帧caption只用于召回；保留原帧字节供实际视觉评估，OCR/字幕保留各自真实定位与版本，不冒充原图证明。字幕/OCR未接通的首步Module必须明确声明，完整0014仍待这些链路。每个产物绑定parent source SHA、decoder/compiler/model版本、真实时间与派生字节SHA；caption SHA不能替代帧SHA或视频SHA。

## Authority、问答与来源

- VID-6：新增独立视频compilation/scene/frame/group及publication/trace附表，随真实消费方逐步加入真实FK和不可变seal，不提前创建空结构。EvidenceGroup关联同一视频revision及实际相交时间窗的原帧、转录/字幕/OCR；不能跨视频或任意拼接相近文字。完整generation/manifest/active发布复用，旧v1–v9迁移体不改。
- VID-7：画面独有、音轨独有、音画联合问题分别有验收用例；完整问题事实规划不能截断尾部。联合答案最终同一EvidenceGroup的visual/transcript pair覆盖全部事实，且两模态各贡献至少一个通过证明门槛的事实。召回/重排分数不是事实置信度，离散验证也不伪称校准概率；不能仅拼接两份“整问题未通过”的结果。
- VID-8：复用文字与视觉既有证明规则并保持旧Interface全覆盖语义；音画事实需要明确共同的事实身份，不将当前视觉claim SHA与文字requirement SHA直接join。只保存子问题哈希、证明结果/分数及版本，不保存原问题片段；无证、矛盾、预算中断或范围变化则拒答。
- VID-9：候选模态或group筛选不能缩小完整all/selected scope；所有远程上下文前及最终trace/source回读复验完整ACL/active/配置。原文件、关键帧、音轨均继承视频源权限，无公开直链或前端自造locator。
- VID-10：typed视频来源包含parent revision/source SHA、真实scene/start_ms/end_ms/frame_ms、帧SHA/尺寸及机器处理标记；转录引用和视觉引用区分，不输出假page/字符或词级时间。原视频字节先完整授权和SHA校验，再经原单Range规则输出200/206/416；帧来源读回封存原帧，不再次生成图像。

## 验收

2026-09-20授权问答合同细化（VID-7–10）：公开 `POST /v1/video-answers` 必须明确 `visual`、`transcript` 或 `joint`；完整 all/selected scope 不随视频检索子集缩小。v11 trace 保存同一 publication/group 的全部 fact ID、两个离散贡献值与模型/策略版本；citation 绑定真实 physical ID，内部 span 候选句柄须先映射。只读取选中原帧 BLOB，同时保留完整 ordinal 转录作反证上下文。

当前没有真实 scene 实体，VID-6/10 的 scene 能力仍未实现；来源如实返回 `group_id` 和实际组交集时间，不伪造 scene。公开时间含整数 µs 与精确十进制 ms，标记 `group_interval`；帧是实际原帧时间，转录另返回真实 ASR 整段边界及 `server_chunk`，不声称词级对齐。`machine_vlm`/`machine_asr` 区分证明来源，`decoded_original` 区分原帧字节。caption 只召回，组的召回/重排锚点分数不是逐事实置信度。完整接口见 [VIDEO_ANSWERS](../../VIDEO_ANSWERS.md)。

- VID-11：固定合成MP4的真实FFmpeg解码、实际PTS、变化帧和静态兜底、延迟音轨、无音轨；真实本机ASR/视觉协议替身、SQLite及索引子进程、公开HTTP上传→问答→来源/Range。画面/音轨/联合三类分别报告，不用fixture caption证明原图语义，不把协议替身写成真实ASR/VLM/Milvus质量。
- VID-12：先真实RED再GREEN；完整旧1073精确测试身份及多重性、73 Node、原格式/架构/静态与双80%门禁保留，最后源码变更后冻结完整对应语义测试和全量回归。限制、未验项、plan偏离与源码/报告绑定同时交付。

真实中文识别质量、关键帧Recall@10≥0.85、分模态citation precision≥0.95/coverage≥0.90、时间误差、成本、前端播放器及生产gate仍按总spec验收。当前没有新增云授权，不连接真实模型或现有服务。

## 步骤3当前实现范围

内部 [VideoAssessment Module](../../VIDEO_ASSESSMENT.md) 实现共同事实身份与一个真实组的视觉/转录/联合证明；尚非 VID-9/10 的授权问答与来源闭环。规划沿用有界确定性语法；不能保留共享条件、时间或所属关系时明确拒绝，不声称通用语义规划。支持值是离散判定，不是概率。完整转录的明确反证独立于模型摘录执行否决；仅当前组 span 的精确摘录可以贡献文字支持，成功 Domain 不接受其他 span 的引用。

瞬态 QuestionPlan 含完整问题，QuestionFact 含 requirement；二者不入审计且日志脱敏。VideoAssessment 仅携带问题/事实摘要、证据材料和版本；后续 v11 trace 只能保存允许的摘要/定位/分数，必须重新按当前 publication 物化，不直接发布内部候选句柄。

## 视频原帧OCR合同（2026-09-20）

- VOCR-1：显式开启视频OCR时，新v2 compiler冻结OCR revision并逐个处理已有选帧，包含明确的“已处理无文字”结果。共用既有Tesseract进程生命周期/TSV严格解码；损坏结果不得变为空文字。旧独立图片无文字拒绝、旧v1视频编译和已发布身份不变。
- VOCR-2：OCR产物绑定同revision原帧SHA、尺寸、实际PTS/时长、完整文字、精确CP分块及同次TSV词框。只有非空分块进入投影。v12采用独立OCR附表和真实FK，保留v1–v11迁移体及v10原frame+span计数，完整发布计数加入OCR；任何帧失败不能parsed或发布部分结果。
- VOCR-3：`POST /v1/video-answers`增加独立`ocr`模式，复用AnswerService文字证明/预算/并发/完整scope，不改变VideoAssessment的visual/transcript/joint语义。OCR不是ASR或VLM贡献，caption/ASR不提供OCR事实支持。整帧文字上下文保留条件/反证，多事实须完整覆盖。
- VOCR-4：同generation召回可能混有caption/ASR/OCR；全部physical ID先经当前authority分类验证，再按请求证据类型筛选，未知/越权ID不静默丢弃，完整scope不缩小。旧三模式仍可处理新增OCR的视频。
- VOCR-5：OCR引用独立封存，kind为video_frame_ocr、proof_origin为machine_ocr、group_id为空、time_precision为frame_interval；仅按实际帧显示区间定位，不延长至下一选帧。来源返回精确摘录、CP与相交原像素词框、原帧和原视频回读URL；不得输出假page/scene/ASR或词级时间。全部来源仍须原回答者及完整当前scope复验。
- VOCR-6：真实合成英文大字视频包含无字帧，经FFmpeg/Tesseract、原HTTP上传/持久任务/索引、OCR问答及原帧/词框/时间回读验证。caption/ASR不给该事实，混合检索不通过专用纯OCR替身绕过；另保留旧三模式与独立图片回归。只证明本机英文合成链，中文OCR、云质量、字幕轨、网页和生产仍另验。
