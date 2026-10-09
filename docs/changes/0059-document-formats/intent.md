# 0059 基础文档格式

2026-10-09 负责人明确“发布生产吧”，授权本切前后端增量发布，覆盖下面的本轮不部署边界；仍不包含 Git 推送、模型调用或将本机业务文件/测试资料导入生产知识库。发布流程见 [deployment-plan.md](deployment-plan.md)。

负责人报告 XLSX、DOC 无法上传，明确要求 PDF、PROPERTIES、HTML、VTT、CSV、MSG、MARKDOWN、EML、PPT、DOCX、DOC、TXT、PPTX、MDX、XLS、ODT、MD、XLSX、XML、EPUB、HTM 全部支持。主线为原始文件上传 → 本地完整文字解析 → 既有持久证据 → 同版本原件读取。保留图片/音视频既有能力。本轮不调用模型、不改问答、不部署或推送。
