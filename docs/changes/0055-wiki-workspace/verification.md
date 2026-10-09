# 0055 验证记录

日期：2026-10-09，Asia/Shanghai。仅全新临时SQLite、合成资料和loopback模型协议替身；真实模型请求0，未访问或升级旧数据目录，未推送、未部署。现有18090静态预览与18088/18089服务保持不变。

## 验收链与事实

`WikiWorkspaceHttpTest` 使用真实Spring HTTP、真实TextParser/authority发布与新临时SQLite。清除环境变量/系统属性配置来源，显式绑定127.0.0.1临时端口、development_headers；启动前确认TempDir存在，启动后断言配置数据目录等于该目录。

已通过：空知识页列表 → 从已发布合成TXT创建extractive提案201 → 正式页列表仍空 → 读取提案原文页码 → 同组织另一成员采纳 → 版本1/current → 重复采纳409 → 关闭进程并重启 → 相同版本/原文/原字节重新读取。公开响应没有内部projection target或私有路径。全过程零模型请求。

Service真实库用例另覆盖：更新生成新版本、旧历史不覆盖、竞争提案冲突、source撤回后stale且禁止采纳/原文回读、同文不同文件身份保留、dismiss、跨组织拒绝、缺模型配置503；实际ManagedTextRuntime租约内调用确定性模型替身，确认调用发生在authority事务外，期间来源变化时不留提案；模型错误和伪造引用503且无重试/残留。

编译协议用例使用本机HTTP `/chat/completions`，验证完整文字输入、严格结构化章节及来源ID、无token参数/无自动重试、旧模型指纹与旧prompt保持。170段末尾原文保留，6类文字材料（文本、图片OCR、音频转录、视频转录/OCR/字幕）保留typed绑定；纯图明确不支持。**这些测试不证明真实provider语义质量或实际多模态页面播放。**

## 红→绿与必要适配

- 新协议/Service测试先因Wiki能力不存在而编译失败（接口缺失证据，不冒充语义断言失败），实现后完整相关文件通过。
- HTTP初次启动被沙箱拒绝loopback bind，属于测试环境限制；获工具允许后执行同一隔离命令。新HTTP测试误将development_headers的缺身份状态写成JWT的401，实际旧合同为422；核对旧ManagementHttpTest后修正新测试，未改认证实现。
- model策略版本追溯：先新增精确断言，实跑期望包含独立Wiki prompt revision而实际缺失；补丁后完整11个Service用例通过。extractive策略和旧模型/index指纹不变。
- 初次341项扩展回归出现6个失败、2个错误（日志 `/private/tmp/wiki-0055-regression.log`）。其中备份固定数量/v29 verify未跟随当前schema更新，改为明确v31与每个vN→vN+1备份的名称、实际版本、大小和SHA检查，原行/guards/备份恢复断言保留。
- 两条历史权限期望未同步0053组织共享合同：Ingestion旧reader不可编辑/仅看自身上传，Audio旧同组织另一成员不可读trace。仅测试按已批准合同同步，新增同组织元数据/原件/引用完全相等正例，同时增加跨组织空列表/NOT_FOUND/null负例，并保留迁移前ACL原行及备份核对。生产权限代码未改，不用删除或跳过失败测试掩盖问题。

## 环境和执行方式

- JDK：PyCharm bundled JBR 21.0.8+9。
- Maven：`/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn`。
- 每条命令单独设置 `JAVA_HOME=/Applications/PyCharm.app/Contents/jbr/Contents/Home` 及PATH含 `/Library/Developer/CommandLineTools/usr/bin`；`-o -s .mvn/settings.xml -gs .mvn/settings.xml`。
- root独立构建目录 `/private/tmp/wiki-0055-http`，没有替换运行中的target JAR。
- 主线/HTTP/架构/请求形状17项通过；编译与原OpenAI相关66项通过；Service11项通过；存储/新迁移14项通过；历史迁移适配18项及共享合同适配9项通过。这些分批存在重叠，不能相加成独立总数。

最后合并回归选择：

```text
Wiki*Test,*MigrationTest,HistoricalSchemaV25FixtureTest,ArchitectureRulesTest,
OpenAiCompatible*Test,FactModelsProtocolTest,SynopsisLibraryServiceTest,
SynopsisMaterialRepositoryTest,KnowledgeAnswerServiceTest,ManagedTextMainlineHttpTest,
RetrievalSettings*Test,TextRetrievalTestServiceTest
```

执行 `-Drag.build.directory=/private/tmp/wiki-0055-http -Dtest=<上述集合> test spotless:check`。
最终合并结果：**2026-10-09 10:53:11 +08，341项、0失败、0错误、0跳过；1155个Java文件格式清洁，BUILD SUCCESS，用时4分19秒。** 最后修改后的完整相关文件已统一重跑，不以早期分批结果相加替代。日志 `/private/tmp/wiki-0055-regression-final.log`，JUnit XML在该构建目录的 `surefire-reports/`。

10:54:00 +08，同一隔离输出目录 `-DskipTests package` 成功（仅打包，非又一轮测试）。JAR：`/private/tmp/wiki-0055-http/rag-java-0.1.0-SNAPSHOT.jar`，SHA-256 `2e80196efa8dea9a5d1937ad61bcab774832204d2d8e0df41debd4b327304e7a`。日志 `/private/tmp/wiki-0055-package.log`。最终 `git diff --check` 通过；未启动/部署该JAR到旧服务。

## 独立审查与保留边界

独立只读审查未发现确认的P1/P2主线阻断，复核了事务/CAS、组织限定、真实source回读、实际OpenAI接线和提示追溯；审查不能替代测试。

本次没有修改原普通问答/检索选择策略，未把Wiki内容写入Milvus或作为原文证据。新增schema31只在临时库验证，生产仍schema30。没有运行全部仓库 `clean verify`/双80%覆盖率、真实Milvus/真实模型、前端接线和生产部署；不得把本切通过称作完整生产验收。

前端未改，因此没有重复浏览器/前端自动化；真实HTTP响应是后端证据，不是页面可用证据。下一条业务主线是把0041预览的知识页/审阅/来源操作接到这些API，再推进关联导航与增量更新。
