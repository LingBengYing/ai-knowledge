# 0059 本机验证

2026-10-09。格式支持为真实本地解析，不涉及真实模型请求。前端对应0045。

## 红 → 绿

- 原白名单拒绝 DOCX/XLSX，未发上传请求。新增解析测试先失败，再实现 DocumentFormat 与本地解析 Adapter。
- 第一轮真实 HTTP 23项中，HTML/HTM因解析库回填 MIME 的 charset 参数被精确比较误拒。改为比较规范 MIME 基本类型后完整23项通过，未弱化断言。
- 新主动格式原件下载头测试先因缺 Content-Disposition 失败；统一HTTP响应保护后完整 MediaContentResponseTest 通过，包含范围响应。
- 原 PDF/TXT/MD 解析器版本与算法保持。新增8项 ExpandedDocumentParserTest 包含21真实样本、格式指纹、脚本/实体不执行、XML键值、properties Unicode、multipart邮件正文与附件边界、DOC无本机个人元信息。

## 最终相关回归

固定构建目录独立于运行中JAR：Maven运行 TextParserTest、ExpandedDocumentParserTest、ProcessTextParserTest、ParserWorkerTest、IngestionServiceTest、IngestionTaskProcessorTest、DocumentOriginalHttpTest、DocumentFormatsHttpTest、MediaContentResponseTest、ArchitectureRulesTest。

- 94项实际运行，92通过、2失败、0错误/跳过。
- 2项分别是 IngestionServiceTest.readsAndActionsRequireCurrentAclAndWorkspaceInTheTransaction 与 DocumentOriginalHttpTest.currentAclMissingOriginalAndPinnedRevisionAreRequiredForBothReads：旧逐资料ACL预期与既有组织共享合同不一致。[0057部署前全量记录](../0057-dbgpt-knowledge-agent/deployment-java-full-verification.md)已经登记同名失败，本次保留原测试，不改权限或断言。不称全量门禁通过。
- 本轮21格式的 DocumentFormatsHttpTest 23/23、解析16项、子进程/协议15项、分层11项及原件响应3项均通过。
- 11个本切Java文件 Spotless 检查及Git diff检查通过。
- 为本机页面验证，相关回归完成后单独使用 `-DskipTests package` 制作运行包；这一步只作打包，不能覆盖前述失败或认证测试。
- JAR SHA256：`8ad9e713f2d2a082948737554fb9cc1eff267f07f580c0de8d20122473f1b536`。

前端完整598/598及语法检查通过；输入稳定证据见Web0045。旧HTML拒绝断言因用户明确新增HTML支持，改为不支持SVG仍拒绝，另增HTML下载/转义正文断言；身份、版本和SHA校验不放宽。

## 真实页面

本机工作台运行当前前后端。旧本机进程已正常停止，保留旧JAR和原数据，新运行目录使用关闭后的数据副本，无数据库迁移。

负责人提供的11356字节XLSX从真实文件选择器上传；页面出现“已解析，尚未发布索引”，进入详情后成功读取同版原件并显示下载。页面无错误/警告。未点击索引/知识编译/问答，统一真实模型请求记录为0；原文件与正文未放进Git、文档报告或模型。截图和运行日志仅保存在忽略的本机目录。

## 验收边界

21格式完整HTTP链、真实Excel页面解析及原件读取通过。未跑本轮真实embedding/重排/知识编译或问答质量；未承诺Office物理页码、工作表单元格或独立VTT媒体关联、Office图片OCR/邮件附件递归。未重新运行整个Java全仓及覆盖率门槛；2项历史失败保留。不推送、不修改线上，线上仍为此前版本。
