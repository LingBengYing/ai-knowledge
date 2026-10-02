# 0016 步骤 1：字幕输入与编译本机验证

2026-09-20 15:10:56 +08:00，内部字幕输入/编译 Module 冻结。真实视频 → 全部内嵌文本轨/原包 → 同视频 epoch 的精确时间 → 完整编译产物已通过；**未接字幕上传后的持久化、索引、问答、摘要或来源 HTTP**。不将内部 Module 宣称为完整字幕知识库，更不是云质量、网页或生产验收。

## 正常路径与可观察结果

- 真实 FFmpeg 合成 MP4/mov_text、MKV/subrip、WebM/webvtt，每个文件两条独立字幕轨。完整保存 `SUB-482 alpha🙂`、后续正文及第二轨 `TAIL-917 字幕尾部`；第二轨至 4.5 秒，超过 4 秒视频帧尾部仍保留并扩展媒体时长，不制造新帧。
- 原画面逐像素确认为纯红色，没有烧录上述字幕；音轨/帧描述替身也不含字幕编号。原生编译保留独立字幕产物，没有把它写成 ASR 或 caption。
- MKV/WebM 实际重叠显示区间 `[0.5,2.25)` 与 `[1,3)` 秒均保留；同 PTS 的不同包在子进程协议测试中保留独立序号。多轨不按时间混成一轨。
- 真实非整微秒视频 epoch 为 `60001 × 1/30000` 秒；首正文字幕 PTS 为 2500000、duration 750000、时基 1/1000000。先精确相减再向外取整得到 `[499966,1249967)` 微秒，初始 PTS=0 的空清屏包不触发错误或假事实。
- 真实 mov_text 样式 atom 没有被解码成正文；WebVTT 内联 markup 原样保留为 `subtitle-payload-utf8-v1` 数据，不冒充渲染纯文字、说话人、位置或样式证明。
- 无字幕文件：旧构造返回原产品及原 decoder-v1，新的显式构造返回完整空轨产物、相同帧字节/时间；旧构造仍拒绝带字幕轨文件。新 compiler-v3 要求完整产物，旧 compiler 在模型调用前拒绝非 null 字幕，不能静默丢失。
- 源 SHA、原包 SHA、原始时基/PTS/duration、语言元数据、完整文字/空包顺序都参与相应身份/manifest。缺轨、畸形包和超限整体失败；新 probe 共用原 native 总 deadline 与确认退出，不新增资源生命周期。

上述新增 native 编译案例的 ASR/VLM 为进程内固定测试替身；既有 VideoCompilationNativeIT 回归使用真实本机 HTTP 模型协议替身。均无云请求，不认证自然语言模型质量或端到端检索效果。

## RED → GREEN 与门禁

| 阶段 | 时间（+08:00） | 结果 |
| --- | --- | --- |
| Domain/Compiler/native 协议 RED | 14:57:48 | 56 项，24 failure / 4 error / 0 skip；显式可编译 stub 缺功能，不是编译/环境错误 |
| 真实 native RED | 14:58:36 | 10 项，0 failure / 10 error / 0 skip；实际生成的视频被新 stub 拒绝 |
| 新旧编译定向 GREEN | 15:02:20 | 62/62，包括旧视频/选帧 OCR 编译合同 |
| 完整相关新旧定向 GREEN | 15:04:17 | 119/119，含原生子进程协议、旧配置/Domain/Compiler |
| 最终 native 冻结 | 15:07:19 | 14/14：新增字幕 10、旧 VideoDecoder 3、旧 VideoCompilation 1 |
| 最终 clean verify | 15:10:56 | 1541 Java / 196 XML，0 failure / error / skip；旧 1485 身份及多重性全部保留 |
| 格式 / 架构 | 同最终 verify | 520 Java 格式文件、原架构门禁通过，无新增白名单 |
| 覆盖率 | 同最终 verify | LINE 14370/15369 = 93.499902%；BRANCH 7678/9548 = 80.414747%；原双 80% 不变 |
| Node | 本轮 | 73/73，0 failure / skip；前端未改 |

JBR 21.0.8+9-b1038.68、Maven 3.9.9、离线已有依赖、新隔离临时副本 `/private/tmp/java-subtitle-input.UPtIoG`。Root 是唯一 Maven 执行者且串行运行。`native-reports` 只存本次三份 XML，`native-red-reports` 只存一份，避免旧定向报告混入计数；最终默认报告来自 clean verify。

## 输入绑定与独立审查

- [source-manifest.json](source-manifest.json) 绑定 547 个输入、默认/native 报告与日志/覆盖率/JAR 摘要。[test-cases.json](test-cases.json) 记录精确 Surefire 身份及多重性，原字节 SHA `45235dc77064b4bd767de4c7fc05ce8aaa7767750a9fb7a611214e33d388a374`。
- 上一 539 输入中 534 未变，仅五个生产文件扩展可选字幕合同：DecodedVideo、VideoCompilation、VideoCompilationService、ProcessVideoDecoder、VideoNativeOutput。旧测试、模型/向量客户端、Runtime/Config、Repository/schema、前端和权限代码未改。
- 新增八个 Java：三个字幕 Domain、一个原包解码 helper、三个默认测试文件和一个 native IT。既有 v1/v2 构造及哈希保持，当前 Runtime 没有激活 v3。
- 独立 Standards / Spec 审查未发现 ST-01～06 主线阻断。G02 测试大括号/import 小项已修复；最终默认/native 门禁均基于修正后源码。制品独立复核另在下方追加，不以源码审查代替执行证据。
- 本轮两次 Git launcher/PATH 使用问题是工具命令问题，未计产品 RED；正确 CLI 路径后重新完成扫描。规范已明确每条间接 Git 命令单独设置 PATH，不修改产品测试或接受 Xcode 许可。
- 最终跟踪内容/可达历史扫描及 375 个未跟踪文件扫描均无 finding，`git diff --check` 通过；工件生成后重新计算 547 输入与两份 JSON，全部匹配。只报告计数和类型，不输出实际凭据。

## 当前限制及下一步

按照 codebase-design 的小 Interface 和 fullstack-dev 的明确合同/集成验证方法，保留项目 layer-first Spring 分层；只复用现有 native 生命周期、ASR 和帧描述，不增加新框架。

本步只完成 ST-01～06。ST-07～10 的 authority/完整发布计数、字幕 `subtitle` 模式、同轨完整文字证明、typed 时间来源和文件摘要枚举仍待接线，下一步直接做这些消费者；不重复已验的原生协议实验。暂不激活 Runtime，是避免未接存储时丢失产物，不是撤销后续闭环范围。

字幕最大四轨/2048 包/单包 4096 CP/总 500000 CP/2 MiB 原包；ASS/SSA、位图字幕、全视频烧录字幕识别未实现。原文本 markup 不是渲染结果；cue 时间不是逐词对齐。真实 provider、前端、查询附件及生产验收仍未完成。未调用云、修改旧服务/数据、创建 Git 分支/提交/推送或部署。

## 独立制品复核 PASS

非实现代理使用 Ruby JSON / REXML / Digest 与 unzip 直接复核，没有执行生成 helper 或 Maven：547 输入在仓库/build 一致，534 未变/5 修改/8 新增；196 默认 XML 为 1541/0/0/0，旧 1485 及默认 RED 56 的精确名称/多重性完整保留。native 14/0/0/0 及 native RED 10 身份也全部保留。

case 原字节 SHA、默认/native/native RED 报告摘要及八项日志/覆盖率/JAR 摘要一致；LINE/BRANCH 计数、520 格式、Node73 与两次最终时间均已回读。408 个生产 class 的 JAR 条目集合与字节全部等于 target/classes。默认 RED 三份 XML 也独立解析为 56/24/4/0，随后补充直接 report_sha256 绑定，不以失败日志代替报告。

该复核只认证上述输入/编译切片，未扩展为字幕入库、问答、摘要、真实云质量、网页或生产验收。
