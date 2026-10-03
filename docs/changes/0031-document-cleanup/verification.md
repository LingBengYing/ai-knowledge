# Verification：0031真实执行记录

状态：LOCAL_VERIFIED_IN_0032。本切已随0032最终整合通过本机门禁，下面保留各失败尝试。

完整clean verify第二轮实际2723 tests全PASS（0failure/error/skip）、950 Java格式检查PASS，但原BRANCH80门槛失败：15025 covered/3822 missed，LINE28753 covered/2318 missed。966输入前后相等；报告/Jacoco和失败日志独立保留backend-clean-verify-second-reports，日志SHA312ca66c961a7fd56636e9ffd2dbd1e3a79cfe9ccf28eaa4f1520f9721f14a32。门槛未修改。随后基础配置0032相关113项PASS包含新增清理领域33项；最终完整门禁待独立attempt。

已保存0030完整私有基线：1198后端、172前端共1370source，冻结/current SHA全匹配。加入单列HTTP RED夹具后1199输入执行前后相等。

真实命令在私有copy以JDK21/offline指定m2运行`mvn -B -ntp -o -s .mvn/settings.xml -gs .mvn/settings.xml -Dtest=DocumentCleanupBaselineRedTest -Dspotless.skip=true test`。2026-10-03 11:42:14完成：1 test、1failure、0error/skip；旧DELETE202已通过，新GET预期200实际404，非编译/配置/夹具失败。日志SHA99c5bb58872809164da8fbd9137e1c6f9f639a73705c9afac6149f9fec3e557f，夹具SHA910e69cb8dd3de498dca2f0275a7309c962bd8e74833d2b12ec5cbaf7a1b11dc。

资源72表/17正文表与策略/API准备均为只读，不能当清理已执行。产品实现、真实SQLite purge/compact/backup/restore、loopback delete、全操作drain、前端与全量门禁待后续真实报告追加。0030及更早冻结包只读，无真实模型/Milvus/Git/部署/旧库/浏览器验收。

整库栅栏模块已在另一个0030私有copy执行：仅加入Gate、WorkContext和6项测试，1201输入执行前后SHA完全相等。`LibraryOperationGateTest`实际6 PASS、0failure/error/skip，覆盖维护互斥、真正退出前不能清理、跨线程关闭拒绝、异步提交前预留以及未开始任务撤销。日志SHA39799eec13c3eb8abe10fc65202c8c326065816112eb29eb3c7fcb6c56264c6f。此结果只证明栅栏模块，不代表0031数据库/HTTP/worker整合或全量门禁通过。

root协调前端首轮实际`npm test`394 PASS、0fail/cancel/skip及`npm run check`通过；两条命令各自以`/Library/Developer/CommandLineTools/usr/bin`置PATH首位，60构建输入before==after。日志与summary见工作区`.tools/document-cleanup-verification/frontend-coordinator-first-result.json`。C报告旧370case多重集保留及新增24case；root最终整包绑定待后端完成后核验。元数据提取首次预期TAP的`#`，实际Node输出`ℹ`，仅报告parser修正，执行命令及原日志未改，不是产品失败。

root后端首轮整合在编译阶段失败，实际0 tests，不能计行为RED：Jackson 3节点字段枚举API不符，以及四个worker启动失败处理误用不存在的Semaphore。966构建输入before==after；原日志/结果已保留在`backend-related-first-result.json`，日志SHA1ec43035d5de06a476611b447b9fd4816eabadbfe33aba5f2e7995bf6782f2d0。窄修后第二轮实际执行193 tests，1failure/50errors/0skip，依然失败。第二轮966输入before==after，日志SHA0918fe6cf13f684be2b916f743aa3740b57eb79a9b5b1ca0906d5b8a3a149a20；全部当时target报告独立保留，不能用混有旧运行的报告总数代替本轮193。真实故障包括v22新增列后两条生产INSERT列数不符、nullable唯一键碰撞语义误判、macOS临时目录祖先路径别名误拒，以及空query控制夹具传输待定位。修复与后续实际结果另存，不覆盖首次失败。

用户最新优先级已调整为基础模型配置、连接测试与召回测试闭环。0031仅继续收敛已有整合，后续不增加高级解析/索引功能；Dify源码研究留作设计参考。真实provider、部署、浏览器、旧业务资料与Git仍未操作。

第三轮相关实际196 tests、1failure/1error/0skip，966输入前后相等，日志SHA9b7a0efaf9b406119dba0dcbe863144305aac79b639894e1b01656d6bf62ff8e。旧18个摘要guard计数因新增三个cleanup guard需明确区分原集合，新snapshot夹具仍用macOS祖先路径别名；均只修真实fixture、保留旧断言并补新guard完整集合。该轮其他新清理/HTTP/worker测试通过，不代表全量通过。

第四次格式化后首轮完整clean verify实际2723 tests、24failure/1error/0skip，966输入前后相等，日志SHA27a943a3f2ba0e8e0f59fa51f348ced1edb0b6e9ed486245f1d87bbc671c7df2。失败后没有进入coverage/check/package，不能称双80%或构建已通过。全部报告独立留存为backend-clean-verify-first-reports；具体25用例见同名result.json。真实暴露包括Store匿名SQLite function override将SQLException暴露为受保护签名（原架构规则拒绝）、三个旧仓库测试漏适配当前版本、故意损坏资料的测试临时JDBC新增guard/函数夹具需适配，以及两项旧撤下原件清零断言需要以本合同明确的实际B维护清理为触发。原A撤下必须保留原件直到B，未放宽普通purge或原来源损坏拒绝；窄修及后续门禁另存。旧用例名和原有效断言保留，不将测试夹具故障伪报生产可用。

0031+基础配置0032整合完整clean verify首轮实际2864 tests全部PASS、996格式PASS，但原BRANCH80仍失败（15775/19844=79.495061%，LINE30211/32732）。1012输入相同，独立日志SHA47ccb8bd5e91f62b8a543806f30a31c1f19af4d406277411571accdea30da108，0032 active目录保留全部报告与Jacoco；本切仍不宣称全量通过。最终相关70 PASS已包括清理权限27、真实v22→23增量迁移3与清理迁移，现仅补必要失败/输入边界。

0031最终纳入0032第四轮独立clean verify：3011tests、1004格式、原LINE92.527439%/BRANCH80.309133%及六Native通过，1020/64输入与759完整生产class/JAR绑定。曾暴露的ownership迟到首次取消竞态有真实单项RED（ClosedByInterruptException→cleanup_failed→后继busy），八类worker同锁terminal阶段修复保持原进程/后代/输入/stderr和耐久确认，详见[0032最终实际记录](../0032-model-setup-retrieval-test/verification.md)。v23只扩两个明确模型配置安全错误枚举，历史清理v22行为与备份/坏库拒绝保留。未运行生产清理、未读取真实资料或部署。
