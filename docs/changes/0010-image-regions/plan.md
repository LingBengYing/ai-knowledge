# Plan：图片OCR区域主线

本切本机后端验收完成：2026-09-09最终900 Java/73 Node、264格式与双80%门禁通过，原871项保留；冻结代码的真实Tesseract合成英文PNG HTTP词框闭环通过。模型/Milvus仍为协议替身，完整图片/多模态/网页/生产目标保持IMPLEMENTATION。详见[verification](verification.md)，未推送/部署。

1. 冻结本工件与最小接口，先写具名失败用例，再实现。
2. adapter_layering拥有ParsedImage/ImageTextRegion、ImageOcrOptions、TSV纯解析Tool、ProcessImageParser及直接测试。
3. authority_layering拥有v6 Schema/Store迁移、IngestionRepository/Service/TaskProcessor、EvidenceRepository/Service、SourceImage/DTO和AnswerService.source组装及持久化/来源测试。复用原事务/权限，非本人改动不回退。
4. root拥有本工件、正常HTTP/native IT测试、运行与最终文档。只有root执行Maven、本地Tesseract；无云调用。采用单个临时构建目录，源码留共享工作树；不打包覆盖运行中jar。
5. image_ingestion_map提供只读结构探查后，对冻结源码作非实现者限定Standards/Spec审查。完整验证只在最后源码冻结后执行，不重复相同全量。

接口约定：ProcessImageParser.parse返回ParsedImage；图片TaskProcessor调用IngestionService.completeImageIngestion(claim, image)。原completeIngestion(claim, ParsedText)及ParsedText/ParserProtocol不机械扩展。IngestionRepository单独保存词框，SourceImage增加regions并保留旧四参数构造。

技术参考：[Tesseract官方TSV格式与固定参数](https://tesseract-ocr.github.io/tessdoc/Command-Line-Usage.html#tsv-output)。fullstack-dev用于职责边界和正常HTTP验收，用户指定的Spring layer-first优先于技能通用feature-first建议。
