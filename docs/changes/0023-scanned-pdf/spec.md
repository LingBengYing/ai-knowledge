# Spec

1. 默认关闭的独立PDF OCR配置；明确本机Tesseract可执行文件、语言及固定版本身份，仅沿现有development/test及loopback条件。开启后所有PDF逐页渲染并OCR整页，避免有文本层/嵌图混合页被静默遗漏。不把提取文本和OCR文字重复拼接。关闭时原TextParser及TXT/MD行为保持。
2. PDF原始字节1..20MiB、页数1..500，拒绝加密与不合法尺寸。每页有界渲染（最多1200万像素），按原顺序保留空页和原页号；无文字页可为空，整文件无可用文字失败。最多100万code point、4096段，采用原规范化/分块及parent locator校验；任一页失败、超时或取消均不得提交部分parsed结果。
3. 新解析版本`java-pdf-ocr-v1:<profile SHA256>`绑定OCR配置及固定渲染/编译算法，claim与complete必须相符。既有TXT/MD、数字PDF、图片音视频的旧identity和已保存revision不可改写。已有ParsedText/pages/segments承载页级OCR文字，引用页与CP对应持久识别文本，无词框/字符框或PDF原文本层精度声明；不新增数据库迁移。
4. PDFBox在既有受限Java解析子进程中工作，Tesseract复用既有ProcessImageParser的清环境、固定命令/TSV和确认关闭；总deadline仍有界。PDF worker绑定父PID/startInstant、总期限与关闭OCR生命周期，父取消需确认原生子进程退出后释放本轮准入，不能直接遗留OCR后代。
5. 现有摄取authority事务、完整来源SHA/claim/ACL、索引完整publication、问答完整scope/证明/拒答、source当前权限/版本与原PDF内容接口继续复用。`pdf_ocr_upload`只在配置与摄取实际启用时公布。扫描件用文字证据模式问答，摘要沿完整持久文本枚举，不将机器识别准确率写成有保证。
6. 新合成扫描PDF应包含至少两页、混合文字层/扫描图页并核对尾页事实和服务器页码。定向单元/协议/真实原生验收及必要完整构建均记录；真实OCR须本机Tesseract，模型/Milvus替身只认证接线与证明。无网页像素验收、真实中文OCR质量、云模型或生产声明。

后续保留：PDF区域框、复杂布局/表格/图表理解、中文和真实资料质量；不占本轮正常流程，也不恢复已取消usage/计费开发。

实现细节（0023后端定向窗口）：原ParserProtocol v1请求/响应及固定失败帧保持；仅PDF OCR worker启动参数附带显式OCR配置、父PID/startInstant与剩余总预算。TXT/MD及null配置不进入PDF生命周期。144dpi/RGB按合法PDF UserUnit渲染原CropBox，旋转/尺寸需合法；单页PNG最多10MiB，复用ImageTsvParser的4MiB TSV和50000词边界。超过任一界限整体失败，不交部分页。

profile SHA256精确输入为UTF-8 `java-pdf-ocr-v1\0pdfbox-rgb-144dpi\0full-page-tsv-compile-v1\0java-text-parser-v2-monotonic-codepoints\0` + `ocr.parserRevision()` + `\0` + 规范化绝对可执行路径。OCR identity本身包含显式运行/语言版本和TSV算法；配置路径变化也使旧claim拒绝。已有存储身份不回写。

PDF取消清理预算为5秒，先正常destroy并给worker关闭OCR的有界窗口，再强制兜底并确认已跟踪的PID/startInstant后代退出；只有确认子进程、后代、输入/观察线程和本轮目录关闭才释放既有共享parser permit。默认文字仍原2秒/立即强制停止。PdfWorkerLifetime仅在隔离PDF JVM中绑定父生命周期和总deadline，父失联先关闭OCR再退出；它不是OS沙箱，验证范围仅为本轮实际close/timeout/interruption/应用父进程强制退出用例，不宣称任意OS故障下的进程回收。
