# Review

状态：本切独立Standards与Spec审查scoped PASS；最终190项Java、42项Node与实际浏览器/重启验收通过，见[验证记录](../../VERIFICATION.md)。不是完整RAG或生产批准。

重点：v1迁移及回滚/备份边界、single writer、原文件及页/segment不可变、claim与attempt竞态、当前ACL、取消/崩溃恢复、进程/HTTP配额、正文/凭据不泄露、解析不冒称索引、完整旧测试不回退。

最终由未参与本次修复的独立上下文只读复核；单测或进程分离不等于OS沙箱、真实Milvus/provider、完整RAG或生产批准。

已修findings：HTTP测试缺启动前后临时目录强制断言（已补）；前端旧终态会覆盖服务器新attempt（双向回归）；PDF库stdout日志污染子进程协议（独立子进程入口捕获协议流并隔离日志）；PID测试就绪早于写完（等待完整PID）；重复取消打断清理可能永久占住并发令牌（首次CAS与有界清理）。

平台假设修正：保留ProcessBuilder.environment().clear()，不注入或继承模型/DB变量。macOS会在进程启动时自动加入编码变量，尝试固定其值仍被系统改写；测试仅允许精确__CF_USER_TEXT_ENCODING单键及有界三字段numeric/hex locale值，其他平台仍要求空环境，禁止任何额外应用变量。这修正OS假设而非允许继承秘密；参考[OpenJDK进程测试](https://github.com/openjdk/jdk11u/blob/master/test/jdk/java/lang/ProcessBuilder/Basic.java)。

初轮交叉审查：UI实现上下文只读审查Java authority/HTTP/runtime及真实child组合测试；模型/parser上下文只读审查前端；authority上下文只读审查parser。各审查者未自批其实现。

最终独立审查另发现P2：空白裁剪与重叠窗口产生重复segment起点，被严格子进程/authority校验拒绝。三个层次的新增红测复现1 failure + 2 errors后修复推进窗口，保留非空白code point覆盖与严格定位，parser revision升级为v2。末次独立复核确认未削弱校验、既有证据不被改写，并在Standards/Spec两轴给出scoped PASS。随后独立检查71个manifest文件、聚合和JAR hash、190项Surefire与JaCoCo计数全部一致。

前端终态缓存重新打开详情可能回退旧attempt的独立发现已修复，当前回读最新列表行；对应正反回归保留。独立前端最终58项测试和CI通过，本次浏览器真实连接Java摄取服务验证。
