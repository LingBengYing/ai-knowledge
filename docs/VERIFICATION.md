# 验证记录：Java 开发纵切

## 当前：0003 文本摄取 · 本地验收通过（2026-09-06）

范围：真实文本上传 → 持久任务 → 独立Java解析进程 → 权威页/segment。Java整体仍在IMPLEMENTATION，不是索引、问答、多模态或生产完成。以下结果绑定当前 [source-manifest](source-manifest.json) 的71个构建/源码/测试文件，聚合SHA-256为 `a2fb82286938a6bd428733642f0c21ff2ffc4432615792fcebdc4f3d868d54fd`。最终JAR SHA-256为 `96227b53f03cb38217833d620f5d63344b9674c883dcf1720b929d0c1c4a5ef4`；开发启动复制的不变JAR与之相同。文档由Git提交另外绑定，不属于源码manifest。

| 检查 | 最终实际结果 |
| --- | --- |
| `mvn -o -B -ntp -s .mvn/settings.xml -gs .mvn/settings.xml spotless:apply clean verify`（隔离本地依赖缓存） | 190 JUnit通过，0失败/错误/跳过；22:58:56 +08:00完成 |
| 原管理/鉴权/HTTP/SQLite/解析/Adapter基线 | 148项完整保留；完整suite在最后一次共享分块算法修改后重新执行 |
| JaCoCo line / branch | 2442/2531 = 96.48%；1442/1604 = 89.90%；80%双门禁未改动 |
| Spotless / `javac -Xlint:all` | 通过，无编译告警；生命周期测试的显式close仅作局部try告警说明，不改变断言 |
| `node --test ui-tests/*.test.mjs scripts/check-secrets.test.mjs` | 31项UI + 11项检查器测试通过，0跳过 |
| 独立只读审查 | Standards与Spec两个维度scoped PASS；71个文件、聚合、JAR和覆盖计数再次独立核验一致 |
| 真正打包JAR + 独立前端浏览器 | PDF上传、任务轮询、解析终态；空白TXT失败、两次显式重试、attempt 3后关闭重试；身份切换清除其他用户的资料及任务 |
| 真实HTTP补充验收 | 空白/emoji Markdown解析成2个分块；改显示名/标签不改PDF原文件hash/revision/attempt；其他身份读任务404 |
| 停止并重启同一隔离Java目录 | 3资料/3 revisions/2 pages/3 segments保留；3个locator全部精确对应页内原文；active均null；失败attempt 3及整理元数据保留；live200、ready503 |

本地运行OpenJDK22.0.2 / release21、Maven3.9.9、Node22.23.2。实际Java21结果须以本次提交对应的 [Actions](https://github.com/LingBengYing/ai-knowledge/actions) 为准，历史CI不能认证本次源码。未访问真实provider/Milvus，也未读取或修改其他服务数据。PDFBox合成PDF仍有替代字体警告；文字和locator断言通过，不证明渲染质量。

### 红绿与独立审查修复

HTTP入口最初上传期望202、旧实现实际404；authority、子进程、HTTP配置/限额/取消恢复分别有先失败回归。独立审查发现空白裁剪后重叠窗口可能产生重复分块起点：新增直接解析、真实子进程和authority提交三条回归，首次完整相关32项运行出现1 failure + 2 errors。修复推进窗口而不放宽严格校验后，最终190项完整通过。当前parser revision为 `java-text-parser-v2-monotonic-codepoints`；既有解析证据不就地改写。

取消/真实child终止/重试以及shutdown中断恢复由自动化测试验证；本次浏览器未手动验收取消、移动端或JWT上传流程。迁移测试覆盖v1一致性备份、失败拒绝升级和v2重开；不代表旧Python生产数据迁移演练。loopback权限和测试夹具错误均单独分类，获准后重跑，没有跳过或降低失败测试。

### 浏览器与未验证边界

独立前端版本为 `55a65ee2574380b0a27d3eb01a7a1e1a10dc5658`，其58项独立测试与 [CI](https://github.com/LingBengYing/ai-knowledge-web/actions/runs/34040356414) 通过；本次实际连接新Java JAR进行上述摄取验收，不用先前管理页面截图冒充。重启后截图确认三份资料和PDF解析任务，浏览器控制台读取为0条消息/错误/警告。截图、测试库和运行日志只留本地，不随公开仓库上传。

原六个golden仍未在Java最终问答链路执行，不能报告RAG6/6、召回率或事实支持质量。没有索引发布、当前active authority、最终答案/引用HTTP、多模态、真实provider/Milvus、OS沙箱、性能对照、生产身份与部署验收。`parsed`不等于`indexed`，未解除readiness/production gate。

## 历史：0002 文本 Adapter · 2026-09-06

本次仅新增独立模型/Milvus协议与显式环境配置，不提供新的业务HTTP能力。以下结果绑定 [历史source-manifest](changes/0002-text-adapters/source-manifest.json) 的55个构建/源码/测试文件，聚合SHA-256为 `80900f041c101c27b9faac7ecebd1cf80019d706ce2b604fdef3918870afb09d`；不是生产证明。

| 检查 | 最终实际结果 |
| --- | --- |
| `mvn -s .mvn/settings.xml -gs .mvn/settings.xml spotless:apply clean verify` | 148 JUnit通过，0失败/错误/跳过，20:38:47 +08:00 完成 |
| 原有管理/鉴权/HTTP/SQLite/PDF | 原100项完整保留并通过 |
| 新模型 / 检索 / 配置测试 | 21 / 21 / 6项全部通过；真实本地HTTP替身，不访问真实provider |
| JaCoCo line / branch | 1631/1657 = 98.43%；1055/1153 = 91.50%，80%双门禁未改动 |
| Spotless / `javac -Xlint:all` | 通过，无编译告警 |
| Node UI + secret checker | 20 + 11项通过，0跳过；launcher语法和diff检查通过 |
| 独立只读审查 | PASS，仅当前协议Module源码 |
| 新构建JAR隔离启动 | 全新临时库、独立端口；live200、ready503、management_slice、授权空列表200且total0 |

本地仍是 OpenJDK22.0.2 / release21、Maven3.9.9、Node22.23.2。新提交的实际Java21结果以 [Actions](https://github.com/LingBengYing/ai-knowledge/actions) 对应SHA为准，不复用下面0001的CI。PDFBox原合成PDF仍有系统替代字体警告；解析与定位断言通过，不代表渲染质量。

红绿记录：模型安全stub上17项行为先失败，首次实现后通过；深层JSON补测再次失败，限制深度后完整21项通过。Milvus经历缺实现、搜索/写入、workspace标记与取消先行的失败回归；最终21项完整通过。配置缺类型及不安全端点曾失败，完整6项通过。最末次取消/JSON deadline变更后主线程重新clean verify，不使用并行targeted运行的共享覆盖率报告来认证最终源码。沙箱loopback EPERM单独归类并在获准环境重跑，没有跳过测试求绿。

原六个golden未在Java最终问答链路运行，不能报告RAG6/6、召回率或事实支持质量。无真实Milvus/provider、语料authority、隔离worker、active revision发布、最终引用/答案、多模态、性能对照或生产验收。前端拆分仓库的只读浏览器加载不是这些未实现功能的验收；其证据见 [ai-knowledge-web](https://github.com/LingBengYing/ai-knowledge-web/blob/main/docs/VERIFICATION.md)。

## 历史基线：0001 独立公开快照

日期：2026-09-06。范围是本仓库 Java 管理工作台和独立文本解析 Module，**不是完整 RAG 或生产证明**。

## 本地已执行

| 检查 | 结果 |
| --- | --- |
| `mvn spotless:apply clean verify` | 成功，100 项 JUnit，0 失败/错误/跳过 |
| 原管理/鉴权/会话/真实 HTTP/SQLite 回归 | 94 项全部保留并通过 |
| 新 TextParser 回归 | 6 项通过，含4个原合成 PDF 与精确 code point 定位 |
| JaCoCo 行覆盖 | 820 / 835，98.20% |
| JaCoCo 分支覆盖 | 518 / 571，90.72% |
| Spotless / `javac -Xlint:all` | 格式通过，无 Java 编译告警 |
| `node --test ui-tests/*.test.mjs` | 20 项通过，0 跳过 |
| `bash -n run-dev.sh` | 通过 |
| 不变 JAR 隔离启动 smoke | 通过：live=200、ready=503、Java config=200、授权空列表=200 |
| Secret checker 的真实 CLI 回归 | 11 项通过，含历史已删除密钥、软链接、暂存/工作树差异与精确测试值规则 |
| 前端/检查器 JS 语法、Markdown 相对链接 | 通过 |

本地运行环境为 macOS、OpenJDK 22.0.2、Maven 3.9.9、Node 22.23.2，Java 编译目标 `release 21`。构建没有访问模型 provider。PDFBox 对部分系统字体有解析/替代字体警告；四个中文合成 PDF 的固定事实断言与定位校验仍通过。这不是 PDF 渲染质量验证。

## 红绿与发布范围

TextParser 在空实现上首次运行 6 项测试，其中 5 项报错；实现后全部通过。原管理切保留了先红后绿及浏览器验证，但本次发布没有修改 UI 源码、也没有重做完整浏览器验收，不能把过去结果当作新 RAG 功能证据。

独立快照从之前已验证的39个管理构建/源码/测试文件导出：唯一预期构建变更是加入 PDFBox，另加 TextParser 和解析测试。四个 PDF 均为合成评测资料，不是真实业务数据。为独立 clone 修正了测试资源路径。

本地正在设计但未实现的 model/projection/Authority 外壳及红测草稿没有进入此仓库，仍在原开发工作区保留。本次没有删除、跳过或放宽这些失败测试来声称其功能通过；它们不属于这一明确界定的公开快照。

## 凭据与审查

发布采用独立新 Git 历史，不上传旧 Python 项目历史、环境文件、数据库、聊天凭据、SSH key 或原部署配置。源码只读检查未发现真实 key、个人服务器地址或个人机器路径；同时补充 [.gitignore](../.gitignore)、空 [.env.example](../.env.example) 和 [SECURITY](../SECURITY.md)。

敏感文件/内容/历史检查器的 11 项测试全部通过；包含文件名、内容、历史中已删除凭据、不会输出命中值、暂存与未暂存差异、精确公开测试值以及检查失败关闭。源码表达式误报已有先失败后通过的回归；没有豁免整个测试目录。最终上传须重新扫描暂存区和历史。检查器不保证发现所有密钥形态；聊天中曾提供的 key 仍建议到 provider 轮换。

## GitHub 状态

2026-09-06，负责人完成 GitHub CLI 网页登录后，已正常推送到公开仓库 `LingBengYing/ai-knowledge` 的 `main`。首次源码提交为 [6bfeba9](https://github.com/LingBengYing/ai-knowledge/commit/6bfeba9db5227fa7aa94fd5ae0528350dde230c2)，远端 SHA 已回读核对；没有 force-push，也没有将登录凭据写入仓库。此前 integration 的 403 是历史阻断，现已通过负责人授权的 Git 登录完成上传。

[首次 Java 21 CI](https://github.com/LingBengYing/ai-knowledge/actions/runs/34031531932) 已成功完成（源码提交 `6bfeba9`，2026-09-06 11:54:21 UTC）。Java 21、31 项 Node 测试、全历史凭据扫描、Maven verify、双覆盖门禁与格式检查步骤均成功，不调用真实模型。后续提交的运行结果以 [Actions](https://github.com/LingBengYing/ai-knowledge/actions) 为准，不把历史 run 或排队状态自动视为新提交通过。

0001推送前再次检查：67 个跟踪文件、工作树 clean、历史扫描无命中；[历史source-manifest](changes/0001-java-publication/source-manifest.json) 中45个源码/测试/构建文件逐项与当时HEAD相同，不认证0002源码。聚合指纹按路径排序后连接 `path + NUL + sha256 + LF`，对其 UTF-8 字节求 SHA-256。发布状态文档不属于该源码指纹；Git 提交身份另外绑定完整仓库。

### 0001 当时未验收（当前以页首0003范围为准）

- Java 上传/worker/Milvus/三类模型/问答/引用、删除/重建、图片/音频/视频均未完成。
- `docs/evals/golden.json` 的6个问答预期尚未在 Java RAG 上执行；不能从 PDF 提取测试推导 recall、grounding 或拒答质量。
- 本地测试环境仍为 JDK 22；JDK 21 的独立 CI 证据见上方运行页，不等同目标生产镜像验证。
- 未重新执行浏览器全流程；未进行真实 provider/Milvus 集成、吞吐对比、生产迁移/恢复或发布审批。
- 可公开阅读源码不等于生产安全、可横向扩展或可处理真实机密资料。
