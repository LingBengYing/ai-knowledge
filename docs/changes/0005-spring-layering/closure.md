# Java 重构交付复核

2026-09-07。本次按负责人最新要求“先只完成 Java 重构”收尾；前端详情页、新问答能力、数据库迁移、推送与生产部署均不包含在本轮。整体 Java RAG 仍为 IMPLEMENTATION。

## 当前范围复核（14:50 +08:00）

按直接回复仅处理Java重构，未修改前端、生产代码、旧服务或数据。已读取0005/0006规格、审查和冻结回执，并确认旧ManagementModule、AuthenticationModule、IngestionRuntime、IndexingRuntime生产入口仍不存在。

下文13:54的297项Java/73项Node通过记录仅认证当时冻结基线。工作树后来恢复并修改了0007问答草稿，当前不再等于该快照；本轮原地保留这些源码和测试，不将其回滚、删除、再次隔离或宣布验收通过。0007已有局部红绿证据，但未取得当前完整clean verify及独立审查通过，不能混入重构完成声明。

最近一次0007集成构建在Spring扫描包含重复class文件的target目录时长时间停留，受控终止后于14:43:48退出143；只完成前20项测试，AnswersHttpTest无套件结果。该运行既不是问答通过证据，也未证明应用业务故障。本轮未重跑完整测试，未推送或部署；后续验证应使用干净构建输出并重新绑定实际源码。

## 最终范围复核（13:54:52 +08:00）

按最新直接回复，仅交付Java重构。期间继续开发的0007草稿已完整可恢复隔离：53新增文件、12个既有文件修改，归档逐项SHA核对一致，未丢失草稿或旧测试。详见[0007 verification](../0007-text-answers/verification.md)。以下12:31记录保留为历史，不再用其29文件清单代表最终归档。

隔离后重新执行完整 `clean verify`：40个suite、297项JUnit全部通过，0失败/错误/跳过，Spotless150文件通过，原双80%门禁不变。73项Node、Shell语法与diff空白检查通过；历史/跟踪文件扫描无发现。原297项class/name多重集与冻结快照逐项一致，173个构建输入逐项SHA一致，无额外src编译输入。

- 实际环境：OpenJDK22.0.2、release21、Maven3.9.9；不冒充实际JDK21运行验收。
- 当前行覆盖：4476/4726 = 94.71%；分支覆盖：2056/2366 = 86.90%。
- 当前输入聚合SHA-256：`93190b9c814714893d65d0f351677890f57c95f8343b910ab69e075be782cfa3`。
- 当前JAR SHA-256：`8096f42385344fa28a95a50762d35280e875de86f3eba67b0f8e6fafd4af6152`。
- 当前JaCoCo报告SHA-256：`4052f7440b695624ba61e6b45da9e20e38cf8c71bb9d4e1c8ba9f2206ecc64ba`。

没有新增生产代码需要借旧审查代证：恢复后源码与0006已审查基线字节一致；另有只读助手独立确认本次65项隔离范围和备份完整性。前端资源未改，未重新跑浏览器，不推送、不部署、不操作旧服务数据。完整RAG与生产目标仍未完成。

最终另建临时发布检查快照，包含236个现存跟踪/未跟踪且未忽略文件，逐项核对复制SHA后运行敏感信息检查：空发现；未改真实Git index。48份Markdown的334个相对链接均存在。这是有限规则扫描，不是对所有凭据形态的安全保证。

## 历史交付源码范围（12:31）

- 已完成的职责重构以 [0005 spec](spec.md)及[Java 开发规范](../../JAVA_DEVELOPMENT_STANDARDS.md)为准；Controller / Service / Repository、五类 Model，以及 authentication / authorization / security.web 分开。
- 保留已单独验收的 [0006 摄取授权修复](../0006-ingestion-authorization/verification.md)。本次没有修改其173个构建输入；原297项Java测试源文件及断言保持不变。
- 0007文本问答不在最新范围内，其29个新增未完成源码/测试文件已移到仓库外的本机草稿目录，逐项路径和SHA-256可恢复，移出前后摘要一致。保留[待实现规格](../0007-text-answers/spec.md)和[失败状态](../0007-text-answers/verification.md)，没有把草稿判为完成或删旧测试求绿。收到新的继续要求前不恢复实施。
- 原有工作树变更、Python服务、独立前端仓库、现有服务进程和数据未被回退、重启或发布。

## 本次重新执行的验证

实际环境：macOS、OpenJDK 22.0.2、编译目标 release 21、Maven 3.9.9。测试使用临时数据、真实本机HTTP/SQLite/子JVM及协议替身，没有真实模型或生产调用。

```bash
mvn -o -B -ntp -s .mvn/settings.xml -gs .mvn/settings.xml \
  -Dmaven.repo.local=<isolated-cache> clean verify
node --test ui-tests/*.test.mjs scripts/check-secrets.test.mjs
bash -n run-dev.sh
git diff --check
node scripts/check-secrets.mjs --history
```

| 检查 | 本次实际结果 |
| --- | --- |
| clean verify | 12:31:03 +08:00结束，BUILD SUCCESS，约61秒 |
| Java测试 | 40个suite、297项，0失败/错误/跳过；包含11项架构规则及规则自测 |
| 旧测试保留 | 当前class/name多重集与0006冻结tar中的297项报告逐项一致，不只比较总数 |
| 格式 | Spotless检查150个Java文件，0需修改 |
| JaCoCo行 / 分支 | 4477/4726 = 94.73%；2058/2366 = 86.98%；双80%门禁未变 |
| Node | 73项，0失败/取消/跳过 |
| Shell语法 / diff空白 | 通过 |
| 历史及已跟踪内容敏感信息扫描 | 返回空发现数组；不把这一检查冒充对所有凭据形态的保证 |

覆盖率为本次实测值，未照抄0006上一轮结果。构建前确认两个既有Java服务使用各自不变JAR副本，没有覆盖运行中的target JAR。PDFBox存在替代字体警告，解析和code point定位断言通过；不代表PDF视觉渲染验收。

## 源码与新产物绑定

逐项重算[0006 source-manifest](../0006-ingestion-authorization/source-manifest.json)中的173个文件，差异0；源码、资源和测试路径无额外编译输入。仍使用其文件清单，但下面JAR是本次重新构建的产物，不与旧JAR指纹混用：

- 输入聚合SHA-256：`93190b9c814714893d65d0f351677890f57c95f8343b910ab69e075be782cfa3`
- 本次JAR SHA-256：`648f1cc46169aef428393a43cf7ef2994ac4e5c375313814d6935696c728938f`

## 独立结构复核与边界

只读复核者未修改本次生产代码，确认ManagementController仅适配HTTP，ManagementService实施业务/授权/事务，ManagementRepository封装SQL，SqliteAuthorityStore保留共享连接和原子事务。Model区分Entity、DTO、Query、VO和Domain；安全HTTP提取、JWT验证和纯授权策略分开。未发现阻断本次重构交付的问题。

旧ManagementModule、AuthenticationModule、IngestionRuntime、IndexingRuntime及生产兼容壳已移除。不机械增加ServiceImpl或BaseService；这是按codebase-design与已批准奥卡姆剃刀规则对实际职责复核，不以目录数量作为设计质量证据。

本次没有网页交互改动，未重新运行浏览器验收；此前浏览器证据见[0005 verification](verification.md)。未推送或部署；完整Spring Security FilterChain、Java21实际运行、真实provider/Milvus、最终问答golden、多模态与生产验收仍未完成。前端详情页留待另行安排。
