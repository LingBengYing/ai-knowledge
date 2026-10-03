# Plan：并行实现，root串行集成

状态：IMPLEMENTATION。

1. 保存完整0030私有RED基线；真实HTTP先旧DELETE202，再新GET清理状态预期200而实得404。1199文件执行前后不变，1真实断言失败、0error/skip；本机记录`.tools/document-cleanup-verification/baseline-red-result.json`。首snapshot脚本metadata类型比较错误未执行产品测试，已修正记录，不能计RED。
2. A负责AuthoritySchema/SqliteAuthorityStore、新DocumentCleanupRepository及清理domain/entity、IngestionRepository容量与真实SQLite迁移/guard/purge/恢复测试。保持旧迁移方法原字节。先与B固定Repository/claim/session Interface，真清17正文表；不是仅台账。
3. B负责DocumentCleanupService/执行job、专用ProjectionCleanup接口及Milvus delete/verify、全部generation写前登记、owned临时资源及恢复journal/backup操作。未知外部证据blocked，纯本地新资料须实际completed；与A同步schema和session，不并改A文件。
4. C负责Controller/settings/config/DTO/Runtime及真实HTTP配置测试、前端独立CleanupSession/批量/记录/代理/DOM测试。新HTTP合同见spec；旧A/旧501与disabled capability保持。B Service public Interface由A/B确定后同步C。
5. Root负责正式合同、LibraryOperationGate及全部根操作准入/实际线程退出的装配、跨层协调、真实正常链及所有共享Maven/Spotless/target串行。gate类别保持既有架构层；不放宽ArchitectureRules或coverage/test gates。agent不可运行共享构建。
6. 最后源码变更后针对性全相关文件、完整Java/Node/frontend/syntax、原双80%、必要Native纯合成链、旧case身份多重性与输入不变、完整class/JAR绑定、密钥扫描、两轴独立审查与新只读handoff。不得覆盖0030/旧冻结报告；首次真实失败留存，修复之后的新证据另存。

每次接口变动先协调所有owner；依赖源码中间态不当产品失败。补充行为RED可在私有0030 copy验证，不运行旧冻结helper。新migration tests旧fixture只扩展当前版本断言和旧格式隔离，不删旧失败断言。
