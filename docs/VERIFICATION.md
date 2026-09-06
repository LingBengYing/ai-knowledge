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

已配置 [Java 21 CI](../.github/workflows/verify.yml)，但截至准备提交时尚未在 GitHub 执行：现有连接可读仓库，写入返回 403，本机 SSH / GitHub CLI 尚未有可用登录。该阻断与代码测试结果分开，不能宣称已经上传或远端 CI 通过。需要仓库写入授权后正常 push，不强制改写历史。

## 明确未验收

- Java 上传/worker/Milvus/三类模型/问答/引用、删除/重建、图片/音频/视频均未完成。
- `docs/evals/golden.json` 的6个问答预期尚未在 Java RAG 上执行；不能从 PDF 提取测试推导 recall、grounding 或拒答质量。
- 本地尚未执行 JDK 21 运行，GitHub Actions 已配置 JDK 21，实际 run 结果以 Actions 为准。
- 未重新执行浏览器全流程；未进行真实 provider/Milvus 集成、吞吐对比、生产迁移/恢复或发布审批。
- 可公开阅读源码不等于生产安全、可横向扩展或可处理真实机密资料。
