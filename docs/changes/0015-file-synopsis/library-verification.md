# 0015 持久文件摘要：本机后端验证

2026-09-20 13:54:10 +08:00，完整有界文件证据→显式摘要任务→持久结果→重启回读→typed原始来源的本机后端闭环通过。**不是完整长文件、多模态真实质量、网页或生产验收**。下一步直接做长文件分层，不重复已完成协议/存储/HTTP诊断。

## 用户正常路径

已索引的文档、无文字图片、OCR图片、音频和视频通过真实Spring HTTP提交摘要，轮询到available，再读概览/主题/术语/媒体时间线及来源。来源覆盖text/image/image_ocr/audio_transcript/video_frame/video_transcript/video_ocr七种材料；每个返回链接核对evidence ID/SHA，原文件校验内容SHA，原视频帧、真实区间和OCR像素尺寸/词框明确验证，单Range206通过，越权请求先拒绝而不泄露文件大小。

真实SQLite、v13迁移、既有Ingestion/Indexing Service封存、任务、来源与Spring服务均实际运行。HTTP夹具的媒体编译结果为合成输入，生成模型为真实loopback HTTP固定协议替身；**不冒称本次重新完成真实FFmpeg/Tesseract上传链或云模型摘要质量**。既有native门禁作为历史证据保留，未混入本批默认计数。云调用0。

- 完整六类publication物理条目先枚举、校验数量/身份/当前权限，再读取原材料；原文件每次load读取一次并核SHA，不对每段重复复制整视频。
- 生成前、每次模型调用前后和最终封存保持当前编辑权、完整publication、模型/策略与输入绑定。实际片段文字绑定实际时间，caption从未作原证据。
- v13四张独立关系表、真实来源FK、完整输入manifest和ordered条目/引用一次封存；失败没有部分摘要，已发布索引保持可读。
- 当前reader可共享查看已生成结果；重复提交同版本复用receipt，重启/读摘要/来源不发模型请求。遗留processing变为unavailable/worker_interrupted，即使功能关闭也恢复，无自动付费重试。
- 原有分层、鉴权和生产gate不放宽。摘要默认关闭，开启不依赖或隐式打开Answers/embedding/rerank/Milvus。

## RED、修复与最终门禁

| 阶段 | 时间（+08:00） | 结果 |
| --- | --- | --- |
| 存储/材料/Library首轮 | 13:34:04 | 18项，1failure/17error；schema旧版本、存储与Service桩失败；其中7材料项为新夹具projectionIdentity错误，不当产品RED |
| 修夹具后材料RED | 13:36:47 | 7项，1failure/6error；6项到notFound桩，1项超限夹具未真超过64段，单列修正 |
| Adapter/Runtime RED | 13:39:55 | 9项，1failure/6error/0skip；模型配置/Processor桩、HTTP未装配、runtime能力未声明 |
| 首轮集成 | 13:44:57 | 29项，2failure；真实来源链接1基/0基接线错误，及新HTTP夹具对既有开发身份协议的预期错误 |
| 定向GREEN | 13:48:23 | 29/29，全通过；完整HTTP文件重跑，首/末条目及每个来源ID/SHA校验 |
| 最终clean verify | 13:54:10 | **1429 Java，185 XML，0failure/error/skipped；旧1400身份与多重性全部保留** |
| 格式/架构 | 随最终verify | 494个Java文件格式通过，既有架构规则通过，无新增白名单 |
| 覆盖率 | 随最终verify | LINE13369/14322=93.345901%；BRANCH7025/8770=80.102623%；原双80%门禁不变 |
| Node | 本批 | 73/73，0failed/skipped |

真实JBR21.0.8+9-b1038.68、Maven3.9.9；根代理唯一Maven执行者，离线依赖、新临时隔离构建和SQLite，不替换运行中JAR或旧服务数据。构建目录`/private/tmp/java-synopsis-library.QWHp4T`保存定向/全量日志与报告；长期版本化绑定见下方JSON。

本批对5个既有生产输入仅追加v13 Store/Schema、独立恢复Bean/Service装配及runtime能力；13个旧测试文件只有25处当前格式12→13及空摘要sidecar恢复链必要适配，原case不删、不skip、不放宽。旧v1–v12迁移体逐字节保持。共26个新Java输入，521个当前输入全部绑定，495旧输入中477未改、18具名改动均列在manifest。

修正记录：材料fixture改用合法projection SHA，超限文字增至确实跨越64段；文本首尾夹具确实跨越chunk边界。新HTTP测试原写401/400，按既有AuthenticationFilterTest/IngestionHttpTest明确的422合同纠正，并增加invalid_identity/invalid_request及模型零调用断言，未改生产鉴权或旧测试。公开来源URL在Controller做1→0基转换；Config恢复事务归回Service。密钥扫描指出公开合成哈希被误命名TOKEN，改为准确CLAIM_HASH并注释，不修改扫描规则，最终全量重新冻结；不是发现真实凭据。

## 可复核工件与剩余项

- [library-source-manifest.json](library-source-manifest.json)：521输入、185报告、日志/覆盖率/JAR SHA，旧输入变化明细。
- [library-test-cases.json](library-test-cases.json)：1429精确case identity及多重性。
- [REVIEW.md](REVIEW.md)：独立Standards/Spec审查，两个具名发现已修复并重新验证。
- 首轮历史/跟踪扫描无finding；最终更名后335个未跟踪文件无finding，生成本报告与JSON后最终338个未跟踪文件仍无finding，`git diff --check`通过。未输出或写入真实密钥。

正常链先交付：超64证据/64000code points/8图/8MiB图像的完整文件明确unavailable/input_capacity_exceeded，不截尾。完整长文件分层是下一必要主线；模型/版本切换时旧pending结束前新建冲突列入backlog。时间为真实分段/帧边界，非词级对齐或整段持续证明。前端、真实模型质量、独立字幕轨、查询附件、生产与总体多模态目标仍IMPLEMENTATION。无分支、提交、推送、部署或云请求。

## 独立制品复核 PASS

非实现代理未运行生成helper/Maven/native，使用Ruby/REXML/Digest及unzip直接复算，全部无差异：521输入repo/build/manifest及独立枚举一致；477旧输入未变、18具名变化/26新增，v1初始化与v2–v12迁移体逐字节不变。185XML的1429/0/0/0、旧1400精确身份/多重性及三组RED身份均保留；185报告和9项日志/覆盖率/JAR摘要、格式/运行时/结束时间均一致。两JSON单LF，case原字节SHA为`de04c0c4f0bed3110a7ac4dae3c3368e109f3dbd51d8ef7a69398a17fca7ec55`；额外核对390个生产class，JAR与target/classes集合及逐字节摘要完全一致。13旧测试差异只有当前版本与空sidecar恢复链，无断言删除、跳过或放宽。
