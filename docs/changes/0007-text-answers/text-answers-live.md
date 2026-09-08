# 固定PDF真实链路入口：本地检查通过，外部尚未执行

本入口将原合成差旅PDF经真实解析子JVM、索引子JVM、embedding/Milvus完整校验和权威publication，接到AnswerService、来源回读与trace校验。它不是HTTP或网页验收，也没有替换原四PDF/六golden回归。实现见[TextAnswersLiveIT](../../../src/test/java/com/evidence/rag/service/TextAnswersLiveIT.java)和仅传非秘密代理参数的[LiveIndexWorker](../../../src/test/java/com/evidence/rag/worker/indexing/LiveIndexWorker.java)。

## 执行保护与当前阻断

- 外部执行尚未获授权。之前两次[生成诊断](generation-diagnostics.md)已耗尽，不能算成本入口的授权。
- 必须新建且预先为空的 `java_it_answers_*` 数据库、显式非默认loopback端口和真实Milvus鉴权token。此前无鉴权实例的结果不能冒充此配置验证；不能用无效token让未启用鉴权的服务忽略它来求绿。新鉴权实例已单独通过零模型验证，见[鉴权记录](milvus-authentication.md)，不代表本PDF链路已获授权或执行。
- 真实 `TextAdapterSettings.load` 统一验证白名单配置与身份，不旁路生产配置、不复制私有身份算法。需要实际JDK21和全新的JUnit临时数据目录。
- 固定 `星河制造差旅政策.pdf`，SHA256 `7baf4e2206a9779b59a6252217c86e610897d8e6980e06b1f05a512732f46752`；问题“上海住宿标准是多少？”，须得到有来源的650元答案。拒答、超时或仅索引成功不能算通过。
- 付费前用真实解析分块数核对当前生产batch公式：整份PDF必须一次embedding完成。上限是4次应用模型调用：索引embedding一次、问题embedding一次、rerank一次、extract一次；任一失败停止，无应用自动重试。不保证供应商账单或底层网络包次数。
- 单次模型请求60秒；索引总预算3分钟、回答处理总预算4分钟。Milvus管理请求15秒，接收期间最多64KiB；不是扩大单次生成超时来规避失败。
- 空库和随机集合不存在检查后，只有writer初始化成功才记owned；退出仅删除本次owned集合并回读确认，不删除数据库、卷或其他集合。初始化不完整时不盲删，明确报告可能残留的随机集合名。

## 显式环境与命令

真实secret仅经安全进程环境提供，不写命令参数、代码、文档或日志。需要以下变量；名字不表示已经授权或配置：

| 前缀 | 必填后缀 |
| --- | --- |
| `RAG_TEXT_ANSWERS_IT_` | `ENABLED`（true）、`MAX_MODEL_REQUESTS`（4）、`HTTPS_PROXY_HOST`（127.0.0.1）、`HTTPS_PROXY_PORT`、`EMBEDDING_REVISION`、`MILVUS_ENDPOINT`、`MILVUS_DATABASE`、`MILVUS_TOKEN` |
| `RAG_SILICONFLOW_IT_` | `API_KEY`、`EMBEDDING_MODEL`、`RERANK_MODEL`、`GENERATION_MODEL`、`DIMENSIONS` |

只有明确授权和隔离环境准备完成后，才可执行以下命令。父JVM代理参数必须与上述显式代理变量相等；代理bootstrap只存在于test main，不代表生产worker继承父进程代理：

```bash
mvn -s .mvn/settings.xml -gs .mvn/settings.xml \
  -Dhttps.proxyHost="$RAG_TEXT_ANSWERS_IT_HTTPS_PROXY_HOST" \
  -Dhttps.proxyPort="$RAG_TEXT_ANSWERS_IT_HTTPS_PROXY_PORT" \
  '-Dtest=TextAnswersLiveIT#fixedPdfIsPublishedByRealWorkersAndAnswersWithCurrentAuthorizedSources' test
```

无网络、无模型的响应上限回归可单独运行：

```bash
mvn -s .mvn/settings.xml -gs .mvn/settings.xml \
  '-Dtest=TextAnswersLiveIT#administrativeResponseLimitCancelsBeforeBufferingOverflow' test
```

默认 `clean verify` 编译但不运行任何 `*IT`。指定整个 `TextAnswersLiveIT` 会同时选择本地回归和真实链路，不能把它当作无网络测试命令。

## 实际检查结果

2026-09-08，实际Temurin21.0.12.1+1：编译通过；缺启用、缺预算、缺Milvus token三种配置均在第一个外部操作前以预期断言失败，0 skip。最初清空环境导致JUnit选择不可写临时目录，是执行环境错误；随后显式指定可写临时目录，不修改产品或断言。

独立Standards审查发现原管理响应先收全再验大小。09:58:21本地负例复现未及时取消（1 failure），修复后09:59:53通过（1 pass）：恰好64KiB完成，累计超过1字节立即取消且异常完成，迟到onComplete不能恢复。该回归没有联网。

10:02:53最终默认 `clean verify`：635项Java、217文件Spotless、原行/分支双80%门禁通过；Node73项通过。生产Java和原测试/固定语料未改，当前新增两个测试文件不在此前d43504f的GitHub CI范围内。源码与证据摘要、独立限定复核见[verification](verification.md)和[REVIEW](REVIEW.md)。这些结果不证明实际provider→Milvus→答案的链路已通过；外部执行、完整语义、网页、多模态与生产仍待验收。
