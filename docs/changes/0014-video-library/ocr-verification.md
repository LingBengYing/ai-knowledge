# 0014 视频选中原帧 OCR：本机后端冻结

2026-09-20：选中原帧 OCR → 完整持久化/索引 → 独立文字问答 → 原帧词框/时间/原视频来源已接通。完整门禁于 **12:49:55 +08:00** 通过，最终 native 于 **12:50:46 +08:00** 通过。仅认证本机后端，不代表完整多模态、网页、云模型质量或生产完成。

## 可观察结果

- 真实 FFmpeg 视频经 Spring 上传与持久任务解码，对全部已选原帧执行真实 Tesseract。英文两事实可识别，纯白帧明确封存无字结果；不是全部视频帧、独立字幕轨或中文识别质量验收。
- v2 compiler 绑定 OCR revision；v12 附表保存帧 SHA、文字、CP 分块、原像素词框和 manifest。旧 v10 基础计数保持，总发布/scope 计数加入非空 OCR 分块，无字帧不投影空向量。
- 独立 `mode=ocr` 消费同 generation 中描述/ASR/OCR 的混合命中，所有物理 ID 先验证再筛选。旧三模式在同一 OCR-enabled 视频上仍通过，OCR 不进入 v11 音画联合贡献。
- 引用返回 `video_frame_ocr`、`machine_ocr`、`frame_interval`、精确摘录/CP/词框及原帧 URL。时间使用原帧真实 PTS 加显示时长，不延长到下一选帧。原帧字节/SHA、来源 JSON、原视频完整字节和单 byte Range 均回读核验。
- 未命中的所选文档仍留在完整 scope；撤权后旧来源失效。未知命中、缺失问题事实和未启用的视频入口仍拒绝，不返回伪成功。

## 实际门禁

| 验证层 | 最终结果 |
| --- | --- |
| JDK / Maven | JetBrains JBR 21.0.8+9-b1038.68 / Maven 3.9.9，离线依赖缓存 |
| 完整 clean verify | 1352 Java，173 份默认 XML，0 failure/error/skipped |
| 格式与架构 | 458 Java 文件格式通过；Spring 分层确定性测试通过 |
| 行覆盖率 | 11935 / 12786 = 93.344282809% |
| 分支覆盖率 | 6341 / 7909 = 80.174484764%；原双 80% 门槛未改 |
| Node 回归 | 73 / 73，0 fail/skipped；前端未改 |
| 单列 native | 5 份 XML 共 6 项，0 failure/error/skipped |
| 旧用例 | 上一基线 1299 个精确 className/name 及多重性全保留 |
| 源码绑定 | 485 输入工作树/隔离副本 SHA 一致，旧 461 输入无删除 |
| 敏感信息检查 | tracked/history 与未跟踪文件无命中；未读取/上传真实密钥 |

Native 为 VideoOcrAnswersNativeIT 1、VideoAnswersNativeIT 1、VideoAssessmentNativeIT 1、VideoPublicationNativeIT 2、AudioAnswersNativeIT 1。HTTP/SQLite/索引子进程/FFmpeg/Tesseract真实运行；ASR、VLM、embedding、rerank、生成与 Milvus 服务端为本机协议替身。云请求 **0**。

小 Interface/共享 Adapter 复用已有 OCR 原生生命周期与 AnswerService 文字证明，没有新增权限、任务框架或第二套问答并发池。默认关闭，development/test 的 loopback 限制保持。

## RED、修复与保留

具名 stub RED：OCR 原生/TSV 17 项于 12:30:05，编译/Domain/Repository/请求映射 20 项于 12:31:30，问答入口 5 项于 12:34:18，配置/摄取 8 项于 12:36:13。RED XML 已绑定；合法旧行为可先通过，新行为实际失败。107 项定向验证于 12:40:32 通过，真实 OCR HTTP 首轮于 12:41:36 通过。

首轮完整 1351 项发现 1 项旧关闭视频来源错误码回归，未删改失败测试。恢复 requireVideo 前置，OCR 同样要求视频装配；新夹具使用正确 Service 并追加 legacy OCR 禁用用例。15 项定向验证于 12:47:38 通过后重新执行完整门禁。

旧测试只改 10 个 migration 文件及 VideoTraceRepositoryTest 共 11 文件：23 处当前版本 11→12，以及 restoreVersionTen 调用新增 restoreVersionEleven，确保旧合成夹具真实退回旧 schema；逆转这两类适配后逐字等于上一冻结副本。历史 v1–v11 迁移方法体和既有视频 publication helper 未改。清单给出精确文件名。

新增绑定输入中 scripts/container 三文件只是扩大清单覆盖，不是本轮新增代码。共享树中间态编译及 Node Git launcher 环境失败见 REVIEW，不冒充产品故障。G02 新增无大括号写法已修整，最终源码重新格式化和完整验证。

## 可复核材料

实现者之外的代理以独立 Ruby/REXML/Digest 审计通过：485输入、173默认报告及24项证据摘要匹配；两JSON单LF且摘要准确；全部旧用例身份/多重性保留；JAR内331个生产class与target/classes集合和逐字摘要一致。该审计不重复调用制品生成器，不扩大功能验收边界。

- [输入/证据摘要](ocr-source-manifest.json)：源文件、基线、旧测试适配、默认/RED/native 报告、日志、JAR 与覆盖率摘要。
- [精确用例清单](ocr-test-cases.json)：最终 1352 身份及多重性。
- [限定审查及修复](REVIEW.md)、[API/配置合同](../../VIDEO_OCR.md)。
- 隔离副本 `/private/tmp/java-video-ocr.C32hhN`；临时目录不是永久发布制品存储，仓库保留源码与版本化摘要。

## 未验收与后续

中文 OCR/真实检索及云模型质量、独立字幕轨、未选帧文字、摘要、查询附件、网页预览/播放器和生产仍未完成。英语合成视频不能代证。未改 Python/旧服务/数据，未创建 Git 分支、提交、推送或部署，未挪用旧文本额度。下一条业务闭环可继续文件摘要与可追溯来源，外部质量/发布保持既有授权边界。
