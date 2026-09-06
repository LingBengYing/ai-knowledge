# Security and secret handling

## 当前使用范围

此版本仅用于受控本地开发。生产模式被拒绝，readiness 为 503；尚未完成生产容量、恢复、发布审批和完整 RAG 安全验证。独立 TextParser 不是安全沙箱，当前没有上传接口，不能自行暴露给不可信公网输入。

JWT 模式也不等于公网安全：尚无限流、token 吊销表或最大签发期限政策；退出仅清浏览器 cookie，不会撤销仍有效的 Bearer 副本。当前忽略转发 header，同机 TLS 反向代理后的 loopback 请求可能不会得到 Secure cookie，因此不支持该部署方式。数据库为明文，未强制文件权限、加密、完整恢复或外部防回滚；请只存合成资料并使用受控本地账户。

## 密钥存放

- 当前唯一运行 secret 是 JWT 签名密钥 `RAG_JWT_SECRET`，从进程环境读取；无硬编码默认值、缺失即失败。不要将真实值填进源码或 `.env.example`。
- 配置的 `toString()` 隐去敏感字段；错误响应不返回底层异常、token 或配置。会话采用 HttpOnly / SameSite=Strict，非本机明文场景要求 Secure。
- 测试中的常量是明确的公开合成值，专门验证签名和失败路径，**不具备任何真实权限，也不能用于部署**。
- 模型业务接线尚未启用；独立 `TextAdapterSettings.load` 只显式校验环境配置，三种 provider key 和 Milvus token 走各自变量/secret manager，不进前端、不进 Git、不作为文档示例值。构造/加载配置不发请求，详见 [TEXT_ADAPTERS](docs/TEXT_ADAPTERS.md)。
- SSH 私钥属于机器凭据，绝不属于此仓库。不要复制 `.ssh`、PEM、个人 Maven settings 或浏览器会话。

## 提交前和历史检查

```bash
node scripts/check-secrets.mjs
node scripts/check-secrets.mjs --history
```

检查器拒绝常见 credential 形态与敏感文件，扫描 Git index/对应工作树文件以及（显式 `--history` 时）所有本地 refs 的可达历史；输出只有位置和规则类型，不显示命中值。没有 Git 仓库或读取失败会失败关闭。修改后的文件仍需暂存和再次检查。

这是一道确定性基线，不是完整 secret-detection 产品或绝对无泄露证明。`.gitignore` 也不能移除已跟踪文件或历史中的 secret。仍应做人工审查，使用 GitHub 提供的 secret scanning / push protection（如果仓库可用；本次没有替用户修改这些设置）。

## 已泄露凭据

1. 先到对应 provider 或身份系统撤销/轮换；删除 Git 字符串不能撤销已泄露权限。
2. 检查使用记录并通知负责人，不把密钥贴进公开 Issue、PR、日志或截图。
3. 从当前代码移除；若已进历史，协调负责人制定备份与历史重写计划，不擅自 force-push。
4. 清理相关副本并重新检查所有 refs；历史重写不能保证第三方副本消失。

如果曾在聊天里提供密钥，同样建议轮换。该仓库不包含聊天凭据；程序也不会从聊天历史取 key。

## 产品必须保留的安全约束

文档 ACL 先于统计/分页；metadata 整理不能改变证据身份。未来 RAG 必须把文档当数据而非指令，检索候选进入模型前授权回源，无证拒答，引用由服务器校验，全 selected-document 集合在提交答案事务中重验。尚未迁移的行为不能假称已经通过安全验证。

## 报告问题

不要在公开 Issue 中提供真实 secret、原始业务文档或可利用的用户数据。优先使用仓库 Security → Report a vulnerability（若维护者已启用），否则联系维护者确认非公开渠道。本项目尚未公布响应 SLA。
