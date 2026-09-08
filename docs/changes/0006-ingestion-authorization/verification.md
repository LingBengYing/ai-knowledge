# Verification：摄取后台授权复验

状态：本切本地验收通过，2026-09-07。整体Java RAG仍为IMPLEMENTATION，生产目标未完成；本记录只认证下面限定源码和行为。

## 修改前基线与红测

- 修改前逐项核对 0005 source-manifest 的 171 个构建/源码/资源/测试文件，无差异；完整 283 个基线测试及源码/JAR另有本地冻结快照。0005 报告不认证随后新源码。
- 2026-09-07 11:26:52 +08:00，`mvn -o -B -ntp -s .mvn/settings.xml -gs .mvn/settings.xml -Dmaven.repo.local=<isolated-cache> -Dtest=IngestionAuthorizationTest test`：10 tests、10 failures、0 errors、0 skipped，4.970 秒。错误是预期业务行为不符，不是编译/夹具故障：撤权仍 current/complete=true、队列仍领取失权资料、can_retry=true、恢复未取消、取消审计故障未触发。部分用例先通过了旧身份/坏内容保护，再失败于新增授权要求，不能称为十个互相独立的产品缺陷。
- 2026-09-07 11:29:13 +08:00，同样 Maven 隔离配置运行 `-Dtest=IngestionAuthorizationProcessTest test`：4 tests、4 failures、0 errors、0 skipped，14.367 秒。真实慢子进程 PID 已出现且存活，撤权后 5 秒仍未取消（其自身 deadline 为 30 秒）；queued 撤权仍解析，直接失效/旧 claim 的 factory 预期 0 次实际 1 次。不是进程无法启动或沙箱故障。所有新增红测确认后才开始修改生产代码。

## 最终全量绿测

最后源码修改与Spotless格式化后，由主线程串行执行，未与其他Maven共享target写入。环境为macOS、OpenJDK22.0.2、编译目标release21、Maven3.9.9；不是本次源码实际JDK21运行证明。

| 检查 | 实际结果 |
| --- | --- |
| `mvn -o -B -ntp -s .mvn/settings.xml -gs .mvn/settings.xml -Dmaven.repo.local=<isolated-cache> clean verify` | BUILD SUCCESS，11:33:24 +08:00结束，约61秒；40个suite、297项JUnit，0失败/错误/跳过 |
| 新Service / Process授权回归 | 10 / 4项全部通过，包含真实SQLite与实际子JVM，不是线程替身 |
| 原基线保留 | 283项按class/name多重集逐项匹配且全部通过；44个旧Java测试源文件与冻结快照逐字节一致 |
| 架构 / 格式 / 编译 | 11项架构测试通过；104主Java与46测试Java，共150文件Spotless通过；无Java编译告警 |
| JaCoCo line / branch | 4479/4726 = 94.77%；2059/2366 = 87.02%；原双80%门禁不变 |
| `node --test ui-tests/*.test.mjs scripts/check-secrets.test.mjs` | 73项通过（61项UI、12项检查器），0失败/跳过 |
| `bash -n run-dev.sh` / `git diff --check` | 通过 |
| 独立Standards / Spec | 两路只读scoped PASS，无待修findings；审查者没有运行上述测试，执行证据由主线程收集 |

完整摄取/管理/索引/两种鉴权HTTP/Parser/模型协议/Milvus协议及四份原合成PDF回归随clean verify重跑。PDFBox仍有中文替代字体警告，文本/locator断言通过，不代表PDF渲染质量验收。

## 新增行为的实际验证

- [IngestionAuthorizationTest](../../../src/test/java/com/evidence/rag/service/IngestionAuthorizationTest.java)：队列撤权、全部32项失权的有界跳过、合法后续任务、owner降editor仍可执行、降reader/移除ACL停止、跨组织隔离。
- 有效claim的isCurrent/complete/fail撤权都系统取消、幂等审计、零页/segment/parsed/active写入；原文件字节、hash和revision保持。伪造/旧claim不取消当前attempt；坏complete payload保留422顺序。
- 合法其他editor仍能取消；status/latest_job的can_retry一致；创建者失权retry返回409且attempt不变，恢复授权后显式重试，旧claim不能跨attempt。重开恢复分别取消失权任务、将合法processing恢复为worker_interrupted。
- 测试用SQLite触发器令系统审计写入失败，确认取消/token变更整体回滚且零证据；移除测试触发器后再次取消只产生一次审计。没有改变生产schema约束。
- [IngestionAuthorizationProcessTest](../../../src/test/java/com/evidence/rag/service/IngestionAuthorizationProcessTest.java)：实际慢JVM先出现存活PID；自身deadline为30秒，撤权后在5秒检查预算内退出。取消一旦可见即恢复权限并retry，第二parser factory确认旧PID已退出，新attempt真实解析成功。queued失权、直接失效/旧claim均不创建parser。

5秒是本机回归预算，不是生产SLA；已经交给子进程的字节不能因撤权原子撤回。提交前同事务复验阻止失权解析结果入库，进程清理不是OS沙箱。

## 源码与产物绑定

[source-manifest](source-manifest.json)覆盖173个源码/测试/资源/构建输入；相比0005只有4个生产文件修改、2个测试文件新增，无删除或其他源码变化。聚合按排序后的`path + NUL + sha256 + LF`求SHA-256：

- 源码：`93190b9c814714893d65d0f351677890f57c95f8343b910ab69e075be782cfa3`
- 最终JAR：`1880e5ccc813016aa10b55dcf119dee2cb7ad4925ec7a4ca3b4321006d52ac71`

[test-retention](test-retention.json)对照修改前冻结tar中的实际Surefire报告，保留283、新增14、缺失0、不通过0；没有仅靠总数增加推定旧测试保留。所有旧测试源SHA与0005 manifest/archive/current一致。Schema、Store、Job、ProcessTextParser、模型/Milvus Client、UI资源和构建输入均未修改；文档不属于源码manifest，公开版本最终还需Git提交身份绑定。本次未stage/commit/push或部署。

## 扫描与未验证范围

`node scripts/check-secrets.mjs --history`通过，结果为空数组。另将当前228个非忽略文件复制到独立临时Git快照，仅在该临时快照暂存并运行检查器，结果同样为空；包括未跟踪的新源码/工件，真实仓库index摘要前后相同。41份Markdown的320个相对链接均存在；主线程再次重算173文件及JAR，全部与manifest一致。未把本机临时路径或原始日志写进公开工件。检查器只能防御已知模式，不保证发现所有凭据形态。

未运行六个最终问答golden，不报告RAG 6/6；没有真实provider/Milvus、浏览器撤权操作、打包JAR的新人工界面验收、staging或生产验收。现有真实HTTP测试与实际parser进程验证不能替代这些证据；0005浏览器结果属于历史，前端详情页保持原样。完整授权检索、逐事实问答/引用、多模态、生产身份及OS隔离仍须后续完成，can_answer=false、readiness503和production gate不变。

计划无范围偏离。codebase-design/fullstack-dev用于收紧既有Service/Repository/Policy与事务测试边界，遵循项目批准的layer-first规范；没有按通用模板添加空层。code-review以冻结快照分别审Standards/Spec，见[REVIEW](REVIEW.md)。
