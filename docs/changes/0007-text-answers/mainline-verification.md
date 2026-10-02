# 文本正常 HTTP 主线：本地接线验收

2026-09-08：固定 PDF 已通过 HTTP 完成上传→解析→索引→指定文档问答→来源回读。实际 Spring 应用、SQLite、解析/索引 Job 和子 JVM 参与执行；模型及 Milvus 使用明确的本机协议替身。**这是正常路径接线通过，不是真实模型质量或生产通过。**

## 输入与可观察结果

- 原固定语料：`src/test/resources/corpus/星河制造差旅政策.pdf`，未修改。
- 问题：`上海住宿标准是多少？`，只选择刚上传的文档。
- 上传返回 202；实际解析任务变为 `parsed`，分块非空，此时没有 active revision。
- 显式索引返回 202；实际索引任务变为 `indexed`，worker 写入的行形成 publication，列表 active revision 与上传 revision 一致。
- 问答返回 `answered`，答案含 `650`；服务端引用包含“上海住宿标准为每晚650元”，document/revision、PDF 摘要和原文件名均与上传一致。
- 通过返回的 `/v1/sources/{answer_id}/1` 回读，answer ID 和完整 citation 相同。
- 两路向量/关键词搜索使用实际索引 worker 经 HTTP 写入替身的行；没有手工 publish、install 或直接完成任务。重排/摘录请求到达本机协议替身。

## 变更范围与执行

仅新增 `TextMainlineHttpTest` 一条正常流程，并为共享 `IndexingTestServer` 增加搜索响应支持（41 行新增，原失败模式及旧断言不删改）。不修改生产 Java、依赖、配置、原 PDF、前端或发布 gate。本次补齐缺失的整条 HTTP 验收，不宣称修复了先红后绿的生产缺陷。

在新建隔离构建副本中，以实际 Temurin **21.0.12.1+1 / macOS ARM64**、Maven **3.9.9** 执行；复用本地依赖缓存，离线运行，不覆盖运行中的 JAR。以下命令从隔离副本根目录执行，实际另指定该次本地 Maven 缓存：

```sh
mvn -o -B -ntp -s .mvn/settings.xml -gs .mvn/settings.xml \
  -Dtest=TextMainlineHttpTest test

mvn -o -B -ntp -s .mvn/settings.xml -gs .mvn/settings.xml \
  -Dtest=TextMainlineHttpTest,DocumentRemovalProcessTest,IndexingTaskProcessorTest,IndexingHttpTest,IndexingJwtHttpTest,IndexWorkerLifetimeTest,IndexWorkerParentDeathTest,IndexWorkerTest,ProcessTextIndexerTest \
  test spotless:check
```

- 首轮主线测试：14:05:01 +08:00 完成，1 项通过，0 失败/错误/跳过；执行前已应用 Spotless 格式。
- 最终直接回归：14:06:22 +08:00 完成，9 个 suite、35 项通过，0 失败/错误/跳过；Spotless 241 个 Java 文件通过。主线 test 的最终 suite 耗时 5.413 秒，仅表示本机替身测试时间，不是线上性能指标。
- 直接回归包含共享 fixture 的全部八个既有使用者，不另扩展权限/异常场景。没有重新运行完整默认 Java、Node、实际外部 IT 或 CI；历史 857 项全量结果仍绑定原快照，不能写成当前新增测试已通过全量。
- 执行后的两个变更测试文件与工作树逐字节相同；原 manifest 中 152 个生产/配置构建输入不变。原 253 个输入仅共享 fixture 的摘要改变，新测试另计。
- 主线程检查执行结果，另一智能体只读核对正常路径及运行副本一致性，未发现本次接线验收阻断；不扩大为完整独立产品审查。
- `git diff --check` 通过；既有敏感信息扫描器检查 index/工作树返回零发现，另外扫描本轮全部 6 个未跟踪文件亦无发现。5 份当前协作文档的 49 个相对链接均存在；本轮没有重新扫描全部 Git 历史。

## 复核摘要

| 输入或报告 | SHA-256 |
| --- | --- |
| 固定 PDF | `7baf4e2206a9779b59a6252217c86e610897d8e6980e06b1f05a512732f46752` |
| TextMainlineHttpTest.java | `e5dba4c2d2912b4217141bc1d183aea66e0ad96fdd0141a6e36da76bd8c2120a` |
| IndexingTestServer.java | `202a6aeea57e32ff5f1e691e494d25109e6a9629df009fbc1220e3516381b443` |
| 最终 TEST-com.evidence.rag.web.TextMainlineHttpTest.xml | `efdfc529b32d4ceaf875d431088339773b8ddddc4d7a4677f905ba2db2b07051` |

JUnit 原报告保留在本次隔离构建的 `target/surefire-reports/`，不提交含环境信息的原始运行日志。可通过上述命令重放行为，报告摘要只绑定本次执行。

## 剩余主线

真实生成/摘录仍没有成功证据；此前授权的模型请求已用完，新增调用等待负责人明确授权。本轮没有模型计费请求、真实 Milvus 操作、网页验收、Linux 容器运行、推送或生产部署。下一步仍按 [mainline.md](mainline.md) 完成真实外部链路，不转回非阻塞分支。
