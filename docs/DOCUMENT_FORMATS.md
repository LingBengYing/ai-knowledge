# 文档上传格式

0059 扩展原始资料上传与替换，后缀不区分大小写：

| 类别 | 格式 |
| --- | --- |
| 文档与演示 | PDF、DOC、DOCX、PPT、PPTX、ODT |
| 表格 | XLS、XLSX、CSV |
| 文本与标记 | TXT、MD、MARKDOWN、MDX、PROPERTIES、XML、HTML、HTM、VTT |
| 邮件与电子书 | MSG、EML、EPUB |

原有 PNG/JPEG 图片、音频与视频继续使用各自上传类型。文档沿用20MiB原文件上限，解析仍在受控独立 Java 进程内完成。不会执行宏、JavaScript、MDX组件，或访问文件内的外部链接/实体；邮件附件不自动作为另一份资料导入。

## 证据和原件

- PDF保留原有页码；新增格式提取完整正文为逻辑文本单元，并使用既有Unicode码点位置和不可变版本。它不是Word的排版页码、Excel单元格或视频播放定位。
- VTT作为独立字幕文字导入，不自动关联某个视频；MDX作为静态原始文字，不运行组件。
- TXT、Markdown、MDX、CSV、VTT和PROPERTIES沿用UTF-8文字输入（支持UTF-8 BOM）；PROPERTIES另外解码Unicode转义，不把原始属性文件重写成配置对象。其他编码尚未纳入本轮验收。
- Office表格和演示正文、工作表名等由本地解析器提取。原文件保存原始字节、MIME、SHA；HTML/XML/邮件/Office等以附件下载，不作为应用同源网页执行。
- `parsed`仅代表文字证据已保存。后续索引和知识页编译仍须按产品入口执行，不把解析成功当成真实模型质量验证。
- 密码保护、损坏、无可提取正文或超出已有解析资源边界时明确失败，不静默截尾、不假报完成。扫描PDF沿用既有OCR配置；本次不新增Office内嵌图片OCR或邮件附件递归导入。

## 实现入口

`DocumentFormat`统一后缀/MIME合同；`TextParser`保留旧PDF/TXT/MD路径，新格式由`LocalDocumentParser`封装本地格式库。`ProcessTextParser`、持久摄取、索引和服务器来源校验继续复用，不新增数据库迁移。原件HTTP通过`MediaContentResponse.protectDocument`设置下载与隔离头。

解析库选型参考 [Apache Tika支持格式](https://tika.apache.org/3.3.2/formats.html) 与 [官方维护版本](https://tika.apache.org/download.html)。实际验收、未验证项见 [0059 REVIEW](changes/0059-document-formats/REVIEW.md)，不是依据第三方支持列表直接宣称本产品验证通过。
