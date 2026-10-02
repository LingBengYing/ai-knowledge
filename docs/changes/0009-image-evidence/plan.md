# Plan：图片文字证据

状态：IMPLEMENTATION。继承[文本主线实际结果](../0007-text-answers/mainline-live.md)，不复跑已完成的provider诊断。

本切结果：2026-09-08图片文字后端正常闭环及实际Tesseract英文PNG通过，最终871项Java/73项Node与双80%门禁通过，原858保留。详见[verification](verification.md)。这不是整个图片/多模态目标或生产完成。

1. 冻结本intent/spec和当前工作树；先新增图片准入/OCR、来源内容及正常HTTP失败测试。
2. 增加确定性ImageInput和本地ProcessImageParser；现有IngestionService按明确配置选择图片parser身份，文本路径不变。
3. 在原EvidenceService事务中回读同版本图片；Controller只处理二进制HTTP。原文档/引用/模型数据类型不机械复制，新增安全DTO/Domain只服务实际边界。
4. Config集中验证可执行文件、语言/版本和opt-in，Runtime准确宣告图片文字能力。不改前端详情页。
5. root串行执行相关红绿、真实OCR固定样本、原HTTP/文本回归和交付全量门禁；非实现者限定Standards/Spec审查。记录实际完成、未验证项及偏离。

Ownership：adapter_layering拥有ImageInput/ImageDimensions/ImageOcrOptions/ProcessImageParser、IngestionService/IngestionTaskProcessor及直接新增测试；authority_layering拥有来源原图HTTP/Domain/Repo/Service（含SourceEvidence、SourceResult、AnswerService.source必要组装）与直接测试；root拥有工件、Config/Runtime和组合测试。非本人执行限定审查，共享工作树不回退他人变更，只有root运行Maven及真实工具/外部请求。

取舍：本切以Tesseract本地文字识别复用Java文本投影，不新造多向量检索系统；完整图片视觉理解/精确bbox仍保留后续验收。整图locator由服务器生成，不相信模型生成坐标。小的Module Interface隐藏受限进程与原图授权读取，不增加空ServiceImpl/BaseRepository。

参考：[Tesseract CLI](https://tesseract-ocr.github.io/tessdoc/Command-Line-Usage.html)；后续视觉Adapter仍可按[SiliconFlow视觉输入协议](https://docs.siliconflow.cn/docs/userguide/capabilities/vision)使用OpenAI-compatible chat/completions，本切不调用远程视觉模型。
