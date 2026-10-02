# 0012 验收：原图知识库后端

2026-09-10本地实现与限定独立审查通过。完整多模态产品仍为 IMPLEMENTATION；不是云模型质量、真实 Milvus 实例、网页或生产验收。

## 已可观察的正常闭环

自生成无文字 PNG（左蓝圆、右红方），经真实 Spring HTTP 上传、持久摄取任务、SQLite v7 独立图片证据、实际索引子进程和生产 embedding/Milvus Adapter，完成索引、图片问答、typed整图引用与原始字节回读。HTTP 的模型和向量服务端都是明确的本机协议替身。

验收故意让 describe 返回“绿色三角形”的错误 caption：它只进入 embedding/rerank，没有进入原图 draft/verify。三次视觉请求使用同一原图，最终引用 source SHA 与回读原字节一致。数据库真实 pages/segments 和文字 publication/trace entries 都为0，独立图片 evidence/publication/trace 各1。引用没有假 page/start/end/quote；整图框不是物体定位框。

这验证的是数据流和证据协议，不代表真实模型已经识别图形或达到事实准确率。当前只评估重排第一张图，不能证明完整问题就整体拒答；不是跨图或音画联合回答。

## 规格与验收入口

| 规格 | 可观察验证 |
| --- | --- |
| VLIB-1/2 | VisualIngestionServiceTest、VisualLibraryHttpTest：显式视觉 profile、原图描述、版本冻结、0文字页/分块 |
| VLIB-3 | VisualIndexWorkerTest：真实索引子进程完整传递4096 code points / 16383 UTF-8 bytes；VisualIndexingServiceTest：不完整 manifest 不发布，完整发布后 indexed |
| VLIB-4 | VisualLibraryMigrationTest 与全部旧迁移文件：v7增量及新真实 FK；VisualEvidenceServiceTest：持久 trace 重启后回读 |
| VLIB-5/6 | VisualAnswerServiceTest、VisualLibraryHttpTest：caption不作为答案上下文、原图与完整问题、typed引用及同SHA内容、不同调用者不可读 |
| VLIB-7 | 原图存在不支持事实时整体拒答；生成期间未入候选的所选文字撤权，禁止下一次视觉请求；撤下后来源404；trace保存摘要不保存正文 |
| VLIB-8 | 显式空范围零外部调用；TextVisualCoexistenceTest：全库/显式文字图片混选仍可回答文字问题，未入候选图片撤权仍整体拒答且trace保留两份资料 |

## 红绿与最终门禁

使用实际 Temurin 21.0.12.1+1-LTS、Maven3.9.9；独立临时源码副本与Maven缓存，不覆盖运行中jar或旧数据。先前临时运行环境/原始日志已失效，本轮重新获取官方JDK、校验官方SHA并重跑，未借历史日志宣称成功。公开构建依赖的下载不计作模型请求。

| 检查 | 实际结果 |
| --- | --- |
| 2026-09-09初始行为RED/首绿 | 当时记录6项RED后34项GREEN；另DTO敏感toString用例先RED后修复。历史原始临时日志本轮已不可用，不编造其SHA；当前最终回归重新执行相关行为 |
| 2026-09-10混库RED | 14:04:53，67项中3项预期失败、64项通过：文字检索误混图片条目，正常文字回答变成 upstream_invalid；实际纯图HTTP已经通过 |
| 混库GREEN | 14:10:50，40项全通过：新3项、旧Answer/Evidence/Golden及图片Service/HTTP；无失败/错误/跳过 |
| 最终clean verify | **14:15:21，942项Java，0失败/错误/跳过** |
| 旧用例保留 | 原923项精确用例身份多重集合SHA与0011一致，新增19项；不是仅比较数量 |
| 格式 | 302个Java文件全部通过；最后格式化2文件，无行为改动 |
| 原双80%覆盖率门禁 | 行7505/8097=92.6887%；分支3757/4665=80.5359%；未放宽门槛 |
| Node | 73项通过，0失败/跳过；前端源码未改 |
| 独立审查 | Standards / Spec scoped PASS；混库P1实际复现并修复、复审关闭，见[REVIEW](REVIEW.md) |
| 云模型请求 | **0**；没有读取/使用密钥，未挪用旧文本额度，也未运行显式云IT |

最终命令（隔离副本，实际 JDK21 与专用Maven cache）：

```bash
mvn -B -ntp -s .mvn/settings.xml -gs .mvn/settings.xml clean verify
node --test ui-tests/*.test.mjs scripts/check-secrets.test.mjs
```

Maven命令另外显式指定专用 `maven.repo.local`，不读取个人/全局 settings。最终构建耗时1分34秒是构建时间，不是Java服务性能基准。默认 Surefire 不运行具名LiveIT；不把它们记作通过或跳过。最初离线缺插件/依赖是环境准备失败，不是产品测试失败。

## 源码与证据绑定

[source-manifest](source-manifest.json)绑定326个源码/资源/构建/Node输入，工作树与实际最终构建副本逐字一致；301个0011输入全部仍存在，变更路径逐项列出。前端静态资源、语料和共享TextGrounding语义实现与0011相同。

[test-cases](test-cases.json)保存942个精确 `(className, name)` 及通过状态，不规范化参数名、不去重。以0011已有测试类筛选出923个实例，按 `classname + '#' + name` 排序后对 `JSON.stringify` 的UTF-8求SHA-256，匹配历史 `cf4796d8d16badc093eba748aec564d53e92e22ad6303b1ce3799cdc865a1aaa`。旧迁移测试只适配当前v7及恢复旧触发器的夹具，原非法状态断言继续保留。

manifest另绑定最终JAR、JaCoCo XML、关键HTTP/混库JUnit XML、当前RED/GREEN/完整回归/Node日志SHA。原始日志留本机临时区域，不提交个人路径、模型响应、密钥或业务数据；源码冻结后只补文档，不能用该指纹认证未来改动。

收尾重新核对326个输入无变化、用例工件SHA一致；325个相关Markdown相对链接存在。tracked/index/worktree密钥扫描及96个未跟踪文件同规则扫描均无finding，git diff --check通过。扫描是当前规则的结果，不宣称穷尽所有形式的敏感信息。

## 计划偏离与后续

- 正常混库问答真实失败，因此增加一个最小兼容修复：Repository筛选真实文字发布、EvidenceService复验完整scope、AnswerService仅收窄检索generation。没有缩小最终资格、trace或来源回读范围，没有扩展权限体系。
- 新DTO使用camelCase + 显式JSON字段名和脱敏toString；runtime在visual优先时不再同时广告新OCR上传能力。这是接口真实性修正，不是新增通用框架。
- 尚未执行真实云视觉生成/召回/事实质量评估，也未在真实Milvus实例发布本切图片；此前文字Milvus结果不能代证。
- 下一业务主线为音频上传→带时间戳转录→检索问答→原音频区间来源；随后视频关键帧/转录和音画联合。视觉embedding、扫描PDF、摘要、页面和生产门禁继续保留。
- 新图片入口默认关闭且仅本地 development/test；前端、Python、旧服务及数据未改。没有创建Git分支、推送或部署，不解除生产gate。
