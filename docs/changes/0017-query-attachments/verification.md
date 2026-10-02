# 0017 步骤1–2：查询附件输入与多图匹配Module

2026-09-20 16:41:06 +08:00完整默认门禁通过，16:41:52真实native验收通过。仅冻结内部请求期编译与双角色匹配，不是附件问答HTTP、授权trace、前端、真实模型质量或生产验收。0017整体保持IMPLEMENTATION，下一步直接按plan步骤3–4接线。

## 实际可观察行为

- 一次提交文字问题及PNG、WAV、带双字幕轨的动态MP4，原问题的首尾空白、换行和原字节不变；附件不经过资料摄取、authority或索引。
- 真实Tesseract识别合成PNG的`QUERY IMAGE TAIL 731`；真实FFmpeg解码音频/视频，所有PCM分段及最后不足整秒的片段逐包核对标准ASR请求WAV字节。ASR服务端是loopback替身，不是自然录音识别质量证明。
- 视频完整保留全部原帧描述、音轨和三条非空字幕，包括第二轨末尾`TAIL-917`。原帧/原图描述请求逐张核对原字节，选中匹配图片覆盖全局首/中/尾，SHA去重；有未选独有帧时manifest明确标记visualSampled。
- 未选帧像素或末尾字幕/转录变化仍改变完整content SHA；抽样不截断文字和完整产物身份。超8192 CP正文返回query_text_limit，不能静默丢尾。无附件保持文字合同、零媒体处理调用；C1字符兼容由旧AnswerCommand与新PreparedQuery共同回归。
- 标准chat/completions实际请求分别标注query/query_image/authorized_candidate，PNG/JPEG原字节与角色顺序可核对。交换查询图与候选图会改变对应wire角色，不是caption-only匹配。候选必须完整返回唯一整数index和finite `[0,1]` score；没有答案或引用字段。

以上native与loopback通信是真实运行，但模型描述、ASR、排序和回归中的Milvus服务端为本机协议替身，云调用0。调用方尚未连接完整EvidenceScope，因此current回调测试不认证真实ACL顺序；总墙钟任务生命周期、终态复验、hash-only持久trace及HTTP仍属于步骤3–4。

## 红绿、回归与源码绑定

实际RED→GREEN明细见[REVIEW](REVIEW.md)：输入12项RED、排序11项RED、真实native1项RED均已编译后执行；旧文字C1兼容另6项/1 failure后只修新谓词。没有删除、跳过或放宽失败测试，没有修改旧共享证明或原80%门禁。

| 门禁 | 本次最终结果 |
| --- | --- |
| 默认clean verify | 16:41:06，1619 Java / 210 XML，0 failure / error / skip |
| 单列真实native | 16:41:52，20 Java / 8 XML，0 failure / error / skip；本切新增1、旧视频19保留 |
| 格式/架构 | 552 Java格式文件，0需修改；既有架构规则无新豁免 |
| LINE | 15713 / 16783 = 93.624501% |
| BRANCH | 8368 / 10460 = 80.000000%，满足原门禁，未降低阈值 |
| Node | 73/73，0 failure / skip；前端未改 |
| 旧用例 | 0016全部1595精确class/name及多重性保留；所有本批RED身份保留 |

实际JBR 21.0.8+9-b1038.68、Maven3.9.9，离线依赖；root独占串行Maven，隔离构建`/private/tmp/java-query-attachments.i7fI2d`。默认XML来自最终clean verify并独立复制，native仅收集本次8份XML，不把旧报告混成新结果。JaCoCo XML仍是默认完整门禁输出，不以随后native追加执行数据重新计算或提高覆盖率。

[source-manifest.json](source-manifest.json)绑定579个输入、默认/native/RED报告、日志、覆盖率与JAR。0016全部567输入原字节不变，新增12文件为7生产+5测试，删除0；没有借Git当前diff将旧历史改动归入本切。[test-cases.json](test-cases.json)原字节SHA为`1f43b2b889fdb0bc37c4647d5308fe40a2725e26b8a40c5aee9c08993213424c`。格式化只触及新增文件。

codebase-design的小Interface/深Module用于准备与双角色匹配；fullstack-dev的接口测试方法用于真实协议/字节验证，服从本仓库Spring层级、无ORM/额外框架的选择。两名非对应实现代理已完成Standards/Spec限定源码审查，无未关闭主线阻断；最终制品独立核对另列，不以源码审查代替测试证据。

## 独立最终制品核对

非实现代理未运行生成helper、Maven、Git或网络、未改文件，使用独立Ruby JSON/REXML/Digest及unzip完成复核：579输入repo/build/manifest的路径集合和逐文件SHA一致；旧567不变/新增12/删除0。默认210 XML、native8 XML及四组RED的统计、hash与测试身份多重性准确；旧1595、旧native19及全部RED身份最终GREEN保留。

10项证据（8日志+JaCoCo XML+JAR）SHA一致，LINE15713/16783、BRANCH8368/10460=80%、552格式、73 Node和最终时间均由对应报告/已哈希日志确认。JAR独立解包核对430个生产class与target/classes集合、逐entry原字节完全一致，没有缺失、多余或重复。未关闭制品审计项0；不扩大为步骤3–4或真实模型质量验收。

root另行执行的tracked/history扫描无finding，最终418个untracked文件扫描无finding，git diff --check通过；没有Git写入或上传。扫描结果不是独立代理的Git认证，二者验证边界分别保留。

## 未完成与下一步

1. 在现有答案执行器、完整EvidenceScope之后调用准备；只有embed/search/rerank使用附件提示，extract/grounding/visual/video joint proof仍只看原问题和库内原证据。
2. 追加独立hash-only trace并接最终事务，回读来源不依赖临时附件或模型。
3. 新opt-in有界附件HTTP入口，验证三类附件辅助检索、附件独有事实拒答、完整scope及原问题尾部不丢、重启来源。

无新运行开关/API/数据库迁移，未启动或修改旧服务、数据、前端，未创建Git分支/提交/推送/部署。真实provider质量、网页、生产及整体多模态目标继续保留；本次没有花费或挪用旧云调用额度。
