# 验证记录：Java 独立公开快照

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

[首次 Java 21 CI](https://github.com/LingBengYing/ai-knowledge/actions/runs/34031531932) 已实际触发；后续提交的运行结果以 [Actions](https://github.com/LingBengYing/ai-knowledge/actions) 为准。不能把上传、工作流配置或排队状态本身当作 CI 通过。工作流执行 Java 21、31 项 Node 测试、全历史凭据扫描、Maven verify 及双覆盖门禁，不调用真实模型。

推送前再次检查：67 个跟踪文件、工作树 clean、历史扫描无命中；[source-manifest](source-manifest.json) 中45个源码/测试/构建文件逐项与 HEAD 相同。聚合指纹按路径排序后连接 `path + NUL + sha256 + LF`，对其 UTF-8 字节求 SHA-256。发布状态文档不属于该源码指纹；Git 提交身份另外绑定完整仓库。

## 明确未验收

- Java 上传/worker/Milvus/三类模型/问答/引用、删除/重建、图片/音频/视频均未完成。
- `docs/evals/golden.json` 的6个问答预期尚未在 Java RAG 上执行；不能从 PDF 提取测试推导 recall、grounding 或拒答质量。
- 本地测试环境仍为 JDK 22；JDK 21 的独立 CI 证据见上方运行页，不等同目标生产镜像验证。
- 未重新执行浏览器全流程；未进行真实 provider/Milvus 集成、吞吐对比、生产迁移/恢复或发布审批。
- 可公开阅读源码不等于生产安全、可横向扩展或可处理真实机密资料。
