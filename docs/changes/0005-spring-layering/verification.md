# Verification：Java Spring 分层重构

2026-09-07：本次重构的本地验收通过；Java 整体仍为 IMPLEMENTATION。没有推送、部署或改造前端详情页。当前能力仍是资料管理、文本摄取与显式索引发布，不是完整 Java RAG。

## 范围与源码绑定

比较起点是重构前重新验证的 0004 工作树，不是旧 Git HEAD：10:10:14 +08:00 完成 256 JUnit、0失败/错误/跳过；[baseline-manifest](baseline-manifest.json) 冻结当时源码、用例及 JAR。被替代的 ManagementModule、AuthenticationModule、IngestionRuntime、IndexingRuntime 及旧混合包已删除，没有生产兼容壳；旧源码和测试另有本地可恢复压缩快照，未删除服务数据。

最终源码包含 104 个生产 Java 文件、44 个测试 Java 文件。[source-manifest](source-manifest.json) 记录本次构建/源码/资源/测试文件的逐项及聚合 SHA-256、最终 JAR 与 JaCoCo 数值；不包含运行库、日志、个人路径或凭据。历史根 manifest 不认证本次源码。文档不属于构建源码指纹，尚未绑定新 Git 提交。

## 最终确定性检查

| 检查 | 实际结果 |
| --- | --- |
| 最后一次 Java 源码修改后 `mvn -o -B -ntp -s .mvn/settings.xml -gs .mvn/settings.xml -Dmaven.repo.local=<isolated-cache> spotless:apply clean verify` | 10:52:26 +08:00 完成，283 JUnit；0失败、错误、跳过 |
| 重构前用例保留 | [test-retention](test-retention.json)：256 个 class/method 身份逐项映射到新包/类，全部保留，missing 为空；其余为新增用例。数量核对与独立断言/行为审查共同使用，不仅以总数增加代替保留证明 |
| Spotless / javac | 148 个 Java 文件通过；最终日志无 javac 编译告警；原格式器配置保留 |
| JaCoCo | line 4435/4683，branch 2037/2344；原双 80% 门禁通过，未放宽阈值 |
| 架构检查 | 11 项通过，包含真实生产字节码与合法/违规 fixture，已计入 283 总数；不是只按文件夹名字检查 |
| `node --test ui-tests/*.test.mjs scripts/check-secrets.test.mjs` | 73 项通过（61 UI、12 凭据检查器），0失败/跳过；文档收尾阶段再次完整重跑通过 |
| 启动脚本 | `bash -n run-dev.sh` 通过；验收使用最终 JAR 的不可变副本，未在运行中的 target JAR 上打包 |
| 独立审查 | [REVIEW](REVIEW.md) 分 Standards / Spec；本次发现项已修复并由非该生产改动作者复核关闭 |

收尾检查：`git diff --check` 通过；36 份 Markdown 的 287 个本地相对链接无断链。`node scripts/check-secrets.mjs --history` 扫描当前跟踪内容及完整历史无命中；再把当前所有未忽略的已跟踪/未跟踪文件复制到独立临时 Git 快照，仅对该快照暂存后扫描，219 文件无命中，实际仓库索引未改。检查器不保证识别所有密钥形态，发布仍须重新扫描。

环境：macOS、OpenJDK 22.0.2、编译目标 release 21、Maven 3.9.9、Node 22.23.2。未执行本次提交的实际 Java 21 CI，不引用历史 CI 认证当前工作树。PDFBox 的合成 PDF 替代字体运行告警仍存在；文本和定位断言通过，不代表 PDF 渲染质量。

## 红绿与关键回归

- 架构规则直接检查重构前真实 JAR 得到失败；规则自测曾发现遗漏方法 throws 类型依赖，修正后完整合法/违规自测与生产检查通过。失败导入、空测试或工具语法错误不计作应用红测。
- 六项 VO 集合快照用例在旧可变集合实现上先 6/6 失败，再 6/6 通过；最终含目录 DTO 共七项快照测试通过。PATCH 缺失/null/空集合三态保持。
- HTTP `unsupported_document` 兼容负例在 10:48:50 +08:00 实际失败：422 与错误码正确，但 detail 被重写；恢复直接透传原错误后，完整 IngestionHttpTest 随最终全量通过。
- 解析领域诊断脱敏有先失败回归，完整 TextParser 测试文件在最后修改后通过；页文本、Unicode code point locator、分块和 revision 行为未改。
- 重命名认证测试类使精确公开合成密钥白名单不再匹配，新检查器回归先失败，再仅补精确路径和精确值后完整 73 项通过；没有豁免整个测试目录。
- 全套继续覆盖共享 Store、跨 Service 事务、回滚/逐项部分成功、迁移、ACL/JWT/CSRF、安全错误出口、真实 HTTP、独立解析/索引进程、取消重试、父进程死亡、generation 与晚写隔离。源码审查结论不替代这些执行结果。

## 本地浏览器与 HTTP 验收

使用最终打包产物、独立临时数据目录与单独前端代理；embedding/Milvus 为合成本机 HTTP fixture，解析/索引是实际子 JVM。没有连接真实 provider 或 Milvus，也没有使用真实业务文件或已有服务数据。

1. 从空库通过网页创建目录并上传合成差旅 PDF，实际解析成功，attempt 1，得到一个证据 segment。
2. 修改显示名称、目录及手工标签，保留原文件名、2797 字节、文件 SHA、source revision；操作前后模型 fixture 请求数均为 0。
3. 网页明确确认后建立索引；阻塞 embedding 时取消，第 1 次尝试终止；切换无效向量 fixture 后显式重试，第 2 次失败，不发布 active。
4. 切换有效 fixture 后显式重试，第 3 次成功发布。最终 HTTP 回读与重新打开网页确认 `indexed`、active source revision 和 publication，文件身份与元数据不变；最终 fixture 只有一个已提交 upsert。
5. 无授权身份通过真实 HTTP 得到空列表；`can_answer=false`、live 200、ready 503 保持，不把索引完成显示成可问答。

截图 `spring-layering-indexed.png` 已保存并目视检查：新浏览器打开后显示一份已解析/已索引资料，问答禁用。截图、快照、测试库和日志只留本地，不进入公开仓库。验收浏览器、测试 Java/fixture 和前端代理已停止，本轮两个隔离端口不再监听；没有停止用户的其他服务。

浏览器边界：解析完成后，现有前端列表内的索引按钮需要列表重载才更新；Java 接口已返回正确的 can_index。该前端状态同步项登记为后续修复，负责人明确本轮只做 Java 重构。控制台只有所观察到的 favicon 404，无观察到的应用脚本异常；不报告零控制台错误。工具的控件引用拒绝及缓存目录权限错误单独归类，未计入产品失败或功能通过；最终截图仅捕获当前列表，未把未执行的补充点击算作验收。

## 风险与未验证项

- 摄取 currentClaim/完成路径尚未像索引一样复验创建者当前 ACL，是冻结基线已有差距；另列规格和撤权负例后修复，不以纯重构默改行为或宣称已复现越权。
- 当前是 Nimbus/JOSE 加自定义 Filter；没有完整 Spring Security FilterChain/方法鉴权迁移。依赖已存在与安全接线完成是两件事。
- 没有最终授权检索/问答/来源链路、Java 六个 golden 端到端结果、多模态、真实模型/Milvus、OS 沙箱、Compose staging、性能对照或生产部署证据；不能由本地高覆盖率推导这些能力完成。
- 当前工作树包含重构前 0004 增量，不将所有相对旧 HEAD 的差异归入本轮。没有 stage、commit 或 push；发布前还须重新绑定提交、扫描和执行相应 gate。
