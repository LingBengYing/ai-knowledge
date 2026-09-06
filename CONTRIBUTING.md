# 贡献与 AI-Native 开发

先读 [README](README.md) 和 [AGENTS](AGENTS.md)，按 [路线图](docs/ROADMAP.md) 选一个独立、可验收的变更。

## 版本化工件

每个新变更在 `docs/changes/NNNN-topic/` 写 `intent.md`（为什么）、`spec.md`（可观察行为与失败条件）、`plan.md`（实施与验收顺序）；读对应 `REVIEW.md` 后实施。已有能力以源码和自动化测试为准，设计不能倒推出已经实现。

1. 用最小负例证明未实现/错误行为，保留 red 的结果。
2. 通过 Module 的小 Interface 实现和测试；不为尚不存在的变化创建泛化框架。
3. 完整运行相关行为文件，然后执行全部 `mvn clean verify` 和 Node 测试；不要删、跳过、放宽测试或覆盖率来完成。
4. UI 变更需真实浏览器验证空态、错误、只读、延迟与窄屏，不把 DOM 工具错误当作应用错误。
5. 新上下文审查并记录验证证据、未验收项和偏离；生产发布是独立 gate。

## 本地门禁

```bash
mvn -s .mvn/settings.xml -gs .mvn/settings.xml spotless:apply
mvn -s .mvn/settings.xml -gs .mvn/settings.xml clean verify
node --test ui-tests/*.test.mjs scripts/check-secrets.test.mjs
node scripts/check-secrets.mjs --history
git diff --check
```

Maven 使用仓库内的空 settings，避免无意依赖个人镜像或凭据。测试只用隔离目录、合成文档及本地 HTTP，不使用真实 provider key。集成环境不可用时记录阻断，不伪造通过。

所有变更避免破坏原有管理行为。新解析/向量/问答功能必须保持 capability 描述真实；先补 ACL、scope、locator、prompt-injection 和竞态负例，再启用相应按钮与 HTTP 路由。

提交前检查文件和历史中的密钥及个人路径。分支建议 `codex/<topic>`；不强推、不修改他人的未提交工作。当前未指定开源许可证，贡献许可需由维护者另行确认。
