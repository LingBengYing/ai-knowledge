# Verification：扫描PDF逐页OCR

2026-10-03本机0023与前端0012完成。完整Java `1778` 项、后端Node 73项、前端245项均通过，失败/错误/跳过为0；605个Java文件Spotless通过，JaCoCo行覆盖率 `93.478382%`、分支 `80.031682%`，原双80%门禁保持。页面由用户验收，当前未部署，新增真实模型调用0。

## 已验证的业务结果

真实合成三页PDF包含空白首页、第2页扫描图片预算A650、第3页文字层预算C810加扫描图片预算B470。实际Tesseract 5.5.3 / Leptonica 1.87.0、固定eng资料经逐页渲染识别后，原空页和页号保留；持久化、显式完整索引、三个问题的答案及第2/3页来源均通过，原PDF同版本SHA和完整字节回读一致。模型和Milvus服务端为明确的本机协议夹具，不能将这一结果写成云模型质量通过。

相关64项包含独立native IT 1项、配置/domain 9项、摄取7项、compiler4项、进程生命周期5项、默认HTTP1项及旧文字/图片/协议与架构回归。独立native IT不计入默认全量数字。所有生产输入在native验收后保持不变。

最后对打包Spring Boot JAR的真实 `--parse-worker --pdf-ocr` 入口再做1份合成PDF/Tesseract协议验收，完整页和分段定位、预期事实、进程退出通过；该probe不执行云调用或浏览器。最终JAR `rag-java-0.1.0-SNAPSHOT.jar`，38729580 bytes，SHA256 `12e26fe07787039be69e02a82551f8404affbd8089d5495558d0d7428d42e483`。

## 红绿与构建门禁

- 首次HTTP夹具使用不支持的application/pdf上传头导致415，修正为已有octet-stream合同；这次是夹具错误，不计产品RED。随后旧实现虽parsed但丢失扫描第2页：1项/1失败/0错误，形成真实RED。
- 新路径首次21项通过，完善后64项相关/native全部通过。前端新PDF协议6项先5失败/1原关闭路径通过，app DOM新增2项先失败，再56项定向和245项全量通过。
- 首次完整构建1776项全部通过，但JaCoCo分支为9067/11363（79.794%）而失败。原因是PdfWorkerLifetime在隔离子JVM执行，原父JVM测量没有记录它；失败日志保留，没有降低门禁或新增排除。
- 新增2项真实worker协议/生命周期测试：合法父身份时完整输出并确认OCR及worker退出；参数长度、模式、非正PID、期限上下界和父身份不符时返回固定安全失败帧且未启动OCR。仅测试子进程显式继承已经验证的JaCoCo文件agent，等待前一子进程退出并确认exec追加及原前缀SHA保持，再启动下一例；生产JVM参数和环境继承不变。
- 最终从clean重新构建通过。PdfWorkerLifetime分支实测25覆盖/11未覆盖，所有分支统计来自实际执行，没有在测试宿主启动可halt的watchdog。

## 实际命令与输入绑定

在后端仓库执行：

```sh
source ../../.tools/env.sh
mvn -B -ntp -o -s .mvn/settings.xml -gs .mvn/settings.xml -Dmaven.repo.local=../../.tools/m2 clean verify
node --test ui-tests/*.test.mjs scripts/check-secrets.test.mjs
```

native相关验证通过显式RAG_PDF_OCR_IT_EXECUTABLE、RAG_PDF_OCR_IT_REVISION与 `-Dtest=...PdfOcrMainlineIT,...` 单列运行；64项运行的实际测试类及结果保存在本地日志；未跳过配置缺失的IT。工具环境为现有Temurin21.0.12.1+1、Maven3.9.16、Node24。本轮没有安装或升级依赖。

最终构建前后621个src/.mvn/pom输入SHA一致；前端38个产品/测试/脚本输入在245项通过后保持一致。数字序列交付基线中的277个后端测试与语料文件逐字节保留。完整日志、逐项测试身份、输入清单、打包入口结果与本轮源码保存在工作区私有scanned-pdf-verification / scanned-pdf-handoff交接中，不提交运行日志或个人路径。

## 交付边界

前端先验证来源身份，再读取相同document/revision/SHA的原PDF；完整检查MIME、大小和SHA后才创建Blob，按服务器页号提供内嵌、打开/下载，离页、重试和迟到响应释放资源。来源明确标识逐页OCR文字，Unicode区间不是PDF字体字符坐标。

完整PDF像素、浏览器页码跳转兼容性、真实中文/复杂表格布局和云质量仍未验。现网仍按独立部署任务记录为数字序列版；附件0010、文件摘要0011和本轮PDF0012均在本地交付。部署方选择对应基线补丁，保留现有open-access定制，不能整包覆盖或叠加两份补丁。Git操作、部署、真实ASR复验及完整生产目标分别保留实际状态，整体目标未完成。
