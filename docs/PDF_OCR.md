# 扫描 PDF 逐页识别

对应[0023](changes/0023-scanned-pdf/spec.md)。实现与验证状态以[变更目录](changes/0023-scanned-pdf/plan.md)为准。

启用独立PDF OCR后，每页都渲染整页画面，再由本机Tesseract识别；同页的文字层与扫描图片也整页处理，空白页保留原页号。关闭时沿用原文字层提取，TXT/Markdown和独立图片、音视频路径保持。

    RAG_PDF_OCR_ENABLED=true
    RAG_PDF_OCR_EXECUTABLE=/absolute/path/to/tesseract
    RAG_PDF_OCR_LANGUAGE=eng
    RAG_PDF_OCR_REVISION=installed-tesseract-language-data-revision

版本值是占位示例，实际应标识固定Tesseract和语言数据版本；不能使用latest/default/unknown。PDF开关独立于图片OCR/视觉模型，校验本机可执行文件、语言和版本。仍只允许现有development/test及loopback后端条件，不改变外部入口或readiness门禁。

新上传PDF解析版本为java-pdf-ocr-v1加profile SHA256。旧资料的原字节、解析版本、页和分块不重写。修改配置会改变新任务身份，在途任务不能在不同配置下悄悄完成；应恢复原配置处理或重新导入，不能直接修改数据库版本。

上传仍为1..20MiB原文件、最多500页；固定144dpi、单页最多1200万像素，整份最多100万Unicode code point/4096段。总解析期限沿用RAG_INGESTION_PARSE_TIMEOUT_MS（默认30秒，最大60秒）。超预算或任一页失败时整份失败，原文件保留供回看，不发布部分解析。Tesseract不调用云模型；随后显式索引和问答按既有模型配置执行。

摄取和PDF OCR都启用时，GET /v1/config增加pdf_ocr_upload。用户等待parsed后显式索引，以文字模式提问。引用保留原PDF页码和识别文本Unicode区间，这不是PDF字体字符坐标或图像词框。前端0012在来源验证后读取相同document/revision/SHA的原文件，完整校验后按页打开或下载。原文件接口与普通文本问答合同保持。

本轮交付页级文字和原文件核对。复杂布局、表格/图表理解、区域高亮及真实中文质量另验。合成原生测试与云模型/Milvus或浏览器验收分别报告。
