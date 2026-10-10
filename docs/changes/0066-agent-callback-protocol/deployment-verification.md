# Production deployment verification

2026-10-10 10:17:28 +08：负责人明确授权后，Java、Python Agent、Web0050 已配套上线 `20261010-agent-callback-0066`。本记录不代表真实模型验收通过。

## 制品与切换

- JAR SHA256：`d336481d487a0927654e72da53215ff3e0c9019d24153f71dd3e2cf0fe98256b`。JDK21 离线 `-DskipTests package` 成功；本轮打包不重复已实跑的75项相关 Java 测试。
- 对照当前线上包，两包1161项文件/960个生产class，只有本切4个Java源文件对应的9个class变化；没有新增/删除项，全部依赖、资源及manifest字节不变。
- Agent仅替换 `knowledge_agent/runtime.py`，SHA256 `17e2353a2744b2fc813f2b32fcd58108c7a7da3109deb1074c9b61946e5bdf85`。其余模块、锁定依赖和既有虚拟环境不变。
- 前端仅替换 `public/knowledge-agent.mjs`，SHA256 `c8835f2ffe0a99cc169979562c417ee9d73f1cc839755956f597d7f1c198a4c3`；生产定制免登录入口保持。
- OCR服务、模型配置和其他运行参数保持不变。检查实际活动进程UID/GID的制品可读性及Agent新目录导入，不以root权限代替。

## 数据与回滚

- schema35不迁移。真实数据库一致性副本用新旧JAR分别重开两次，97张表业务行与12个数据目录文件摘要完全一致。
- 关闭入口、排空任务后停止Agent/Java；保留三个旧unit、配置/密钥与停机数据备份。开放前核对97表、12文件与配置摘要不变。
- 切换成功，未触发回滚。回滚方式仅恢复三个旧unit并保留当前DATA，绝不以旧数据库覆盖开放后的业务写入；OCR不停止或回退。
- 首次stage命令早于上传完成，脚本尚不存在而未执行；上传完成后才运行stage、rehearse、activate。不是产品故障，无服务切换或模型请求发生于该准备失误。

## 在线验证

- Java、入口、Agent、OCR全部active，NRestarts均0。
- 入口11项只读HTTP检查全部200，含会话、资料/目录、检索设置、Agent配置、知识页/回收站、清理任务和新静态模块；内部callback路径仍404。
- 公网读取新前端模块200且SHA完全一致，Agent配置enabled=true。该公网检查使用现有自签名证书，不认证公共CA信任链。
- `/health/ready` 仍为已有503 gate，未将它报告为通过。
- 浏览器控制两次连接超时，未认证此次实际页面交互。

## 真实模型边界

发布流程模型HTTP0；真实验收请求在执行前被安全审批拦下，远端台账不存在，确认未发起模型请求。原因是待测试生产资料将发送至外部DeepSeek/硅基流动，审批要求具名确认资料及服务商。已向负责人说明并请求确认；没有绕过审批、自动重试、消费旧预算或改变资料。见[真实验证状态](provider-run.md)。

真实检索→阅读→回答→来源质量仍未验收；全仓历史ACL/readiness遗留也未因此关闭。
