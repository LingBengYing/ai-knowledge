# Spec：同版本OCR词级区域

1. PNG/JPEG正常摄取使用Tesseract TSV一次输出文字与词框，固定stdin/stdout、语言、psm 6及tsv参数。仍有原IO/deadline/取消边界，无shell、环境凭据继承、自动重试；parser revision升级为java-image-ocr-v2-tsv，不改已存旧revision。
2. 新Domain ParsedImage(ParsedText text, ImageDimensions dimensions, List<ImageTextRegion> regions)，ImageTextRegion(start,end,left,top,right,bottom)使用page 1的Unicode code point半开区间和原图像素xyxy。按TSV阅读顺序拼接词（同一行空格、换行换行符、末尾换行）；不丢弃低置信度词或尾部事实。规范化/分块仍用原TextParser，ParsedText及文本子进程协议不变。
3. 严格UTF-8、12列有界TSV解析，页尺寸和原图一致、只允许page 1、词框非空且在图内、词序和offset有序、转录区间无重叠（不禁止图上矩形相交），最多50000词。非法结构/框/空转录报安全失败，不退回无坐标成功。纯函数不做I/O。
4. IngestionService在既有claim/SHA/当前权限与同一提交事务内验收整个图片结果：尺寸匹配原图，所有非空白转录code point都由一个词区间覆盖，坐标合法；文本complete不能接收新图片结果。旧ParsedText和文本语义保持。区域与page/segment一起写入，在parsed前封存。
5. 增量v6迁移先备份，新增immutable image_text_regions sidecar关联revision、page 1、ordinal、offset和像素框；不得改旧v1–v5迁移语义或删除旧证据。更新/删除/替换及parsed后追加被拒绝。旧图片无region仍可原图回读，不伪造区域或重新OCR；新v2图片引用不可悄悄回退整图。
6. GET source沿用完整actor/scope/ACL/active与quote SHA校验，只回读与保存的引用[start,end)相交的词框，不重新识别、不接受客户端坐标。image原整图bbox/content_url不变，追加region_kind=ocr_word与regions=[{start,end,bbox:[normalized double xyxy]}]，其中offset对应OCR页而非图像字符；词框可比引用字符范围宽。无旧区域时省略region_kind和regions；文本响应不变。原图content接口保持原字节。
7. 验收：解析多行/Unicode/重复词/引用子串，畸形坐标与截断输出拒绝；完整真实SQLite摄取/持久化/重启/来源及只交集测试；真实本机Tesseract合成PNG HTTP链路核对唯一金额及原图/坐标。模型和Milvus使用明确协议替身，不冒称真实检索质量；先红后绿、现有871 Java/73 Node保留、双80门禁和限定独立审查。

后续保留：扫描PDF、纯视觉理解、中文实际识别评测、音频时间证据、视频音画联合、摘要、网页及完整生产验收。此切不扩大异常/权限体系。
