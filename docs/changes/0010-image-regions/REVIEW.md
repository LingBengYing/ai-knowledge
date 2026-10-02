# Review：图片OCR区域

状态：Standards scoped PASS；Spec scoped PASS，2026-09-09。非实现者image_ingestion_map只读核对最终源码，无未关闭finding；格式化未改语义，最终执行结果另见[verification](verification.md)。只认证本切正常区域证据闭环，不是全图片、多模态、网页或生产验收。

- 一次TSV同时生成转录和词框，最后逐字相等守卫拒绝规范化导致的偏移错位；原ParsedText/TextPage/TextSegment/TextParser/ParserProtocol无修改。
- Controller/Service/Repository/Model/Tool/Worker保持原Spring职责；SQL和v6迁移留Repository，DTO只暴露词级安全定位，Row与含转录结果脱敏。
- authority复验原图尺寸、全部非空白code point单次覆盖和有界词框；文本/区域在同事务写完再parsed封存。
- v6备份后增量迁移，旧v1–v5语义及v5继承检查保留；旧migration测试使用实际历史格式继续测试原失败行为，不因版本推进放宽断言。
- source在原actor/完整scope/ACL/active/引用SHA校验后，按保存的引用半开区间求交；v2缺框失败，legacy省略区域，原图内容接口不改。

审查者回读首轮13项（含真实Tesseract HTTP）和最终解析7项通过记录，但没有自己运行测试/调用模型/编辑文件；完整回归和源码绑定由root记录。未扩展非阻塞异常、权限或生产加固。
