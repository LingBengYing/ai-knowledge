# Plan

用户流程与成功条件：上传扫描PDF，解析任务成为parsed后可显式索引；发布后文字问题命中扫描页事实，引用保留原PDF页码与识别文本区间，可按同版本SHA打开原文件对应页。完全空白、任一页失败或deadline停止不得当完整解析成功。

1. 新增PDF OCR配置/版本身份与失败测试，不改默认TextParser合同。
2. 受限worker逐页渲染/OCR与完整结果校验，接原持久任务/索引/文字来源。父/子原生生命周期复用与必要补充，非通用进程重构。
3. 真实合成PDF本机Tesseract→持久化/索引/问答/原PDF回读验证；根并行前端0012引用与原文件绑定、按页预览/打开/下载及生命周期。
4. 相关测试通过后一次完整构建，必要源码/产物绑定与新交接；发布、用户页面、真实模型质量保留实际未验状态。后端无并发Maven写target，根等待实现代理结束定向窗口后执行总回归。

Ownership：backend_originals拥有0023所需后端生产与测试及本文档的技术补充；root拥有前端0012、后端总入口说明、最终验证与冻结工件。此前0020–0022/前端0010–0011未提交改动全部保留，不覆盖已冻结交接包或独立部署副本。

实际分工：backend_originals负责ProcessTextParser、ParserWorker、新PdfOcrCompiler/PdfWorkerLifetime、RagApplication worker入口和TextParser.compilePages及ProcessPdfOcrTest；pdf_config负责PdfOcrOptions、PdfOcrConfiguration、RuntimeService/RuntimeConfiguration、application.properties和9项配置/identity/runtime测试；pdf_ingestion负责IngestionService/TaskProcessor、Persistence/Ingestion配置及7项摄取测试，并接手完善4项PdfOcrCompilerTest；root负责PdfOcrMainlineHttpTest/PdfOcrMainlineIT、前端及最终构建。ParserProtocol未修改。测试编译/Maven由backend_originals串行运行，最终全量门禁交root。

已执行本轮RED→GREEN：首次HTTP因夹具声明application/pdf违反现有octet-stream上传合同而415，属于夹具修正，不计产品RED；保留pdf-ocr-mainline-red.log。改为既有合法上传后旧task虽parsed，扫描page2遗漏事实：1项/1失败/0error/0skip（pdf-ocr-mainline-red2.log）。首轮新路径21项全部通过（pdf-ocr-targeted-first.log）；后续64项相关回归全部通过，含真实native IT1和生命周期5（pdf-ocr-targeted-native.log）。日志位于工作区私有.local；没有删除或跳过有效断言。

显式native Tesseract路径为工作区.tools/media/bin/tesseract，revision=conda-forge-tesseract-5.5.3-leptonica-1.87.0-20261002，eng；合成PDF空页1、图像事实A650页2、文本层C810与图像B470混合尾页3经真实Tesseract→持久化→索引→三问题→精确页码/来源及SHA/原PDF回读通过，所有模型/Milvus服务是明确loopback协议夹具，新云调用0。原文字/图片HTTP链及原v1 parser协议、固定PDF语料、架构11项也在64项内通过。后端整套clean verify/package由root接续，不以定向结果替代全量或网页像素验收。

完整门禁测量补充：根的首次clean verify中1776项测试和604文件格式通过，但分支9067/11363=79.79%未达原80%门禁。保留原失败日志，不改pom、排除或阈值。PdfWorkerLifetime实际在未插桩子JVM执行，主报告为0/36。仅新增PdfWorkerLifetimeProtocolTest两项真实worker协议/父身份/参数拒绝测试，子JVM严格提取当前Surefire JaCoCo agent，清环境且不继承其他JVM参数，顺序append同target/jacoco.exec；每个child正常退出后断言执行数据增长及既有完整前缀SHA不变，主Surefire仅最终退出dump。JUnit宿主不构造有效Lifetime，不启动halt/后代kill watchdog。

命令：`mvn -o -s .mvn/settings.xml -gs .mvn/settings.xml -Dmaven.repo.local=../../.tools/m2 -Dtest=PdfWorkerLifetimeProtocolTest test jacoco:report`，2项/0失败/0错误/0跳过，日志pdf-ocr-instrumented-lifetime.log。真实append报告10sessions，Lifetime25covered/11missed；原1776全量执行数据与本轮2项合并后总分支9094/11363=80.03168177%，行16842/18017=93.47838153%。这是增量测量，不替代根后续fresh clean verify；生产/pom/原测试和语料均未改变，既有native/64行为证据沿用。随后605文件Spotless apply/check及git diff --check通过（pdf-ocr-instrumented-format.log）。

最终收尾：原生产输入在64项native验收后保持；新增2项测试后1778 Java/605格式/双80%与后端Node73全部通过，前端245通过。总回归前后621输入SHA不变，旧277测试/语料文件逐字节保留，最终JAR独立入口通过；详见[verification](verification.md)。冻结后才继续下一纵切；本包未部署，用户页面验收保留。
