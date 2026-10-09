# 0057 生产发布验证

当前状态：DEPLOYED / PUBLIC_READ_ONLY_VERIFIED。2026-10-09 14:06:50 +08完成负责人明确授权的发布，版本`20261009-dbgpt-agent-0057`，三服务均active/enabled且观察NRestarts=0。真实模型问答质量和完整发布回归全绿尚未认证。

## 正式切换结果

- 最终Java包SHA256 `35fff38ca5eae6634804c5a5866bd31b9b9f678846ddf926b4309c99fb0cd34c`，绑定当前1207源码/资源输入，938个生产class/resource与对应直接回归编译目录逐字节相同。
- 真实数据库一致副本先完成schema30→32和再次打开；正式停机后完整备份数据、配置、密钥与旧unit，再启动新后端完成相同迁移。开放入口前再次核对91个旧表的业务行、21个原有数据文件，旧资料/索引/模型及检索配置保持，新增3张Wiki表为空。迁移登记与schema版本按预期变化。
- 新后端PID1382012、新入口PID1382079、Agent PID1378410；后端/入口WorkingDirectory均为新release，Agent指向其中agent-service。检查时三者分别约195/20/110MiB内存，实际运行账户读取校验再次通过。
- 公网`https://<DEPLOYMENT_HOST>:18443/#/ask`已切到Wiki工作台，Agent配置enabled=true，免登录共享身份保持。公开12项服务器侧路径检查（含拒绝内部回调）及19项外部HTTPS只读检查通过：根页/旧入口、会话、Agent开关、Wiki页/草稿/审阅/资料目录、检索设置、静态模块摘要；一份旧原文件按服务器版本回读，字节数与SHA256一致。
- 本轮没有触发回滚。回滚包位于新release下`rollback/`，保留原unit和停机一致数据/配置/密钥；`rollback/`及`private/`实测0700，Agent秘密配置0600。上线后回退只关Agent并用schema32兼容后端，不能用schema30旧JAR读新库或恢复旧库覆盖用户写入。
- 浏览器控制通道两次读取生产页超时，随后全局getState也超时；另通过应用打开页面返回queued。这是尚未取得生产浏览器渲染证据，不把公网HTTP成功冒称浏览器视觉验收。此前本机合成页面验收仍是本机证据。

本机原始记录位于`.local/release-0057-20261009/`的`STAGED.json`、`MIGRATION.json`、`SMOKE.json`、`DEPLOYED.json`、`public-smoke.json`、`bundle.json`；服务器同release保留对应记录。记录不含模型密钥或原文正文。实际切换执行脚本由服务器private目录留存。

## 已核实的生产基线

- 知识库实际HTTPS入口为 `https://<DEPLOYMENT_HOST>:18443/`，TLS验证通过；默认443属于其他应用且证书已过期，本轮不改。主机在公开工件中脱敏，完整运行证据保持私有。
- 原0054后端为固定 `ai-knowledge` 用户，入口为DynamicUser；保留免登录、共享组织身份、当前资料/模型/检索配置。
- 原Java制品SHA256 `3e7e9aa272992b9ed879e7720ea4792b7433c3e381257c9ec28b3fc75fc6673a`；原定制入口SHA256 `d47c6fd3e5a01cbafd2095e2b93d801726376d070fc4fcb6455e2336cc3e2ff2`。
- 数据库schema30，完整性与外键正常，无待执行任务；10份资料、9份有效发布。不能把所有已导入资料等同于已可回答。

## 安装与隔离

2026-10-09 13:59:28 +08，服务器完成新release安装：`/srv/ai-knowledge/releases/20261009-dbgpt-agent-0057`。Python使用固定CPython3.10.21和锁定Linux wheelhouse离线安装，真实DB-GPT0.8.2 Agent核心import与继承关系通过。未从服务器临时拉取未锁定依赖。

`ai-knowledge-agent.service`真实启动，loopback18088健康返回`dbgpt-0.8.2`；DynamicUser、512MiB上限，观察内存115499008字节。systemd仅允许loopback网络。实际进程有效UID/GID证明可读执行制品、不可读生产数据库/provider密钥/Agent环境文件；环境文件0600由systemd注入。新token仅在服务器生成，未回显。

前端225个运行文件均有摘要，使用真实线上入口分支，保留免登录逻辑并添加Wiki/Agent精确路由。前端包SHA256 `d1417b676c2f8edb6151bef8deb32a7a48e285a8a2162a3730f92e0bff862800`；Python离线包SHA256 `54fd3449a73511e36de68874026a4addd0dbf9156606ae22cf898581b1426211`。

## 发布回归发现

完整前端串行586项为585通过/1失败，原文件独立复验又有另一旧异步就绪断言失败；未放宽断言。语法、实际待发布入口219项、Wiki传输11项及源码/历史/制品密钥扫描通过。详情见前端0043 `deployment-preparation.md`，不能将不同轮次拼为全量全绿。

Java修复前快照完整verify在生产发布完成后仍未结束，本轮为收尾主动结束该运行并保留全部已完成结果；停止前仍有进度，不能称其死锁或产品超时。门禁明确为NOT_COMPLETED且已有失败，不能当成通过。完整记录另见`deployment-java-full-verification.md`。除了与0053组织共享合同冲突的旧断言，发现`SqliteAuthorityStore.compact()`压缩后仍硬编码校验schema29。用原0054/schema30和新schema32真实生产字节码、各自新建合成资料执行公开清理路径，两者均在database_file阶段失败，确认既存生产问题。最小修为当前schema32完整验证，未放宽结构校验；修复后的合成清理通过，后续累计13个相关断言失败与3个压缩错误均在最终完整直接文件回归中消除。

最终修复直接回归65项为60通过/5失败/0错误/0跳过，剩余5个断言仍要求组织内逐文档ACL隔离，与现行共享合同冲突，原样保留。Agent完整9/9（含先红后绿关停race）、清理迁移6/6、Wiki迁移3/3通过；完整清理文件均已跑至结束。最终1191文件格式检查通过。详细日志、用例身份和制品绑定在`.local/verification-0057-java-release/`。修复前快照的全量verify结果单独保留，不以最终65项拼成全量成功。

## 迁移与回退约束

生产副本演练使用只打开`SqliteAuthorityStore`的Java探针，不启动Spring/后台工作者/模型客户端。实际30→31→32、旧业务行/文件不变、新Wiki三表为空、再次打开成功。旧0054 JAR已实际验证拒绝schema32。

部署脚本经独立审查及合成30/31/32与恢复场景验证，SHA256 `36062e87ff7e73e04eb8dd437410e518891521272b031b42bb4d4d6c154e8342`。停机后cp-a保留属主和权限，private/rollback0700；切换前使用实际进程账户验证全路径可读。入口开放前失败只有在完整证明无新业务写入后才恢复数据库；开放后回退采用schema32兼容后端、旧UI、关闭Agent，绝不覆盖新业务数据。详见本机 `.local/release-0057-20261009/script-review.md`；实际正向发布通过，本轮未实际触发生产回滚，不把本机合成恢复当成生产回滚演练。

## 验证边界

本轮部署自发云模型请求0，没有提交真实问答、解析、索引或编译。真实Agent服务商效果、多轮质量、全量回归全绿、长期稳定性仍未认证；没有推送Git或使用其他任务旧预算。

15:15前后的最终只读复查：三服务仍active、NRestarts=0，Agent健康返回dbgpt0.8.2。停止的仅为本机0057合成联调三服务及修复前快照测试进程；没有停止线上服务或另一个0056本机实例。
