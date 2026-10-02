# Verification：图片文字证据首切

2026-09-08：本切本机后端正常路径通过。总体多模态/Java生产目标仍为IMPLEMENTATION，不推送、不部署、不修改前端或旧服务数据。

## 用户流程实际结果

- 合成PNG含 `Project A's budget is 650 USD.`，真实Tesseract 5.5.3/eng经固定stdin/stdout识别，不上传云端、不读取图片中的外部指令。
- ImageOcrMainlineIT通过真实Spring HTTP上传、持久化SQLite任务、图片OCR进程、原Java索引子进程、问答和来源接口；提问预算返回650 USD，来源绑定同document/revision/source SHA。
- source响应标明machine_ocr，整图1200×180；content回读与上传PNG逐字节相同。缺开发身份头返回既有422 invalid_identity；另一Actor404；撤下后旧content404。
- embedding/Milvus/rerank/generation均为明确的本机协议替身。真实OCR＋这些替身证明后端接线，不证明真实图片检索质量、实际provider/Milvus、多语种OCR准确率或性能。
- JPEG准入与同版本原图回读通过真实图像字节组件测试；本次原生OCR端到端固定样本是PNG英文图，未把它扩大为所有JPEG、中文、扫描PDF或图表验收。

## 验证结果

| 检查 | 结果 |
| --- | --- |
| 实际JDK | Temurin 21.0.12.1+1，macOS arm64 |
| 完整 `clean verify` | 2026-09-08 15:35:04 +08，871项，0失败/错误/跳过 |
| 原测试保留 | 前轮858个testcase全部存在，未删除、跳过或修改原断言 |
| Spotless / 分层 | 257个Java文件清洁；原架构检查通过 |
| 行覆盖率 | 6418/6850 = 93.6934%，原80%门禁保持 |
| 分支覆盖率 | 3230/3863 = 83.6138%，原80%门禁保持 |
| Node | 73项，0失败/取消/跳过 |
| 最后原生OCR复验 | 15:36:16 +08，ImageOcrMainlineIT 1项通过；4.036秒为整个测试含启动/清理，不是OCR性能基准 |
| 源码绑定 | [source-manifest.json](source-manifest.json)，271构建输入与执行副本逐字节一致 |
| 敏感内容 | 现有index/worktree扫描空结果，全部30个未追踪文件另用同一inspect逻辑扫描无发现；不是全机或聊天历史清除证明 |

## Red → Green与执行问题

1. ImageInputTest正常PNG/JPEG最初安全stub抛错；实现metadata/magic/像素限制后通过。
2. ImageOcrOptionsTest非法配置最初未拒绝；ProcessImageParserTest真实子进程转录最初stub失败，最小配置/进程实现后通过。
3. ImageIngestionServiceTest最初拒绝图片；ImageMainlineHttpTest上传422而期待202。接入显式图片配置后通过，不改原文本准入。
4. SourceImageTest PNG/JPEG最初source.image为null，两项均红；同事务原图回读后通过，再补原回答者reader、其他Actor/组织拒读、撤下和文本null兼容。
5. APNG单条负例最初允许动画；ImageInput只新增有界PNG chunk扫描拒绝acTL，原PNG/JPEG正例保留，最终完整文件2项通过。
6. 新HTTP测试误套JWT的401到development_headers；按已存在的RequestAuthenticator与AnswersHttpTest精确改成422并增加invalid_identity断言。保留首次失败记录，没有修改认证业务或任何原测试。
7. 首次绘字测试在无headless设置的macOS沙箱中JVM异常退出，另一本机HTTP测试因socket权限未执行；它们不算产品红。Surefire统一headless后用明确本机测试权限执行。
8. 工作树旧target中的历史重复class读阻塞，经线程栈与打开文件确认，停止确切测试JVM；转用一个新的临时构建目录，复制相同源码与原golden，不移动/隔离/恢复现有源码，不修改或删除旧target历史文件。所有后续验证在该单一干净副本完成。

## 可复现入口与证据摘要

标准命令与必要环境见[IMAGE_EVIDENCE](../../IMAGE_EVIDENCE.md)。本机使用同一Maven离线缓存，`-s .mvn/settings.xml -gs .mvn/settings.xml`隔离个人配置，默认测试不执行*IT。源代码采用最终版本后依次执行clean verify、Node、显式ImageOcrMainlineIT；没有自动模型重试或付费请求。

| 本地证据 | SHA-256 |
| --- | --- |
| image-mainline-final-verify.log | 4a9a49ae6ea603e16b6a0f653a763aa92e50c78159c900389363665d55260384 |
| image-mainline-native-final.log | 17a0adb1c3e98dc5213ba383da5e21002a9a73cac9f238878e66b4c4945d3852 |
| image-mainline-node.log | 39ac85ca6cf99905b937ed4c0700947fff56929ec52cfc35f9d9e1715be85b79 |
| ImageOcrMainlineIT最终JUnit XML | 3186261fdf41da9a6fe712610c83be1371f34d042d4214591266cdf4b48e712a |
| 最终隔离构建JAR | 5386329d79909f617ce7250859552c61f3921287ff5829da11fac210d82f311d |

日志和临时数据只保留本机，不纳入Git。最后IT正常退出，其Spring服务/OCR/索引子进程及协议替身均在测试生命周期关闭。本机新增Tesseract及其依赖；未访问生产主机、原集合或云端模型，前轮17次文本调用余额不变。

## 未完成范围与后续主线

精细bbox、扫描/图文PDF、纯视觉和图表事实、视觉向量与跨模态重排尚未实现；音频ASR/时间引用、视频关键帧/音轨/联合证据、摘要及问题附件仍待各自纵切。网页图片预览尚未接线，/content不是播放器或自由库文件预览API。原生进程有输入/输出/像素/deadline和退出控制，不是OS内存/文件/网络沙箱，不能对公网文件声称生产安全。

真实OCR英文单图不能证明中文、噪声、低置信拒答、一般OCR准确率或实际图片provider/Milvus质量。保留原本全部多模态与生产验收目标，非阻塞异常/权限增强/容器加固/清理B不抢占下一具名业务主线。
