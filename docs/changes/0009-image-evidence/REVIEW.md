# Review：图片文字证据

状态：Standards scoped PASS；Spec scoped PASS。只覆盖本切图片文字后端正常主线，不认证完整图片/多模态或生产。

限定审查当前图片正常主线：Spring职责与配置、图片转录身份/引用、原图SHA和现有当前ACL/active校验是否保持；替身和真实OCR结果是否区分。不借此扩大生命周期B、通用权限、全量异常或生产加固。

非实现者image_ingestion_map只读复核：Config集中配置，Controller仅HTTP、SQL留Repository；OCR固定参数、不经shell、清环境、有界IO/deadline及退出确认。原文本路径、claim/ACL/不可变证据规则保留。

source在一个原有事务内复验原Actor、完整trace scope、当前ACL/active及引用，再以revision/source SHA读取不可变原图并校验字节摘要/类型/尺寸；finish/hydrate不读图片。machine_ocr和整图bbox标识准确，不伪装字符区域。

最初未完成的APNG拒绝已由具名红测锁定；最终有界chunk扫描在读取/转换前检查长度，明确拒绝acTL，PNG/JPEG正例及APNG负例2项通过。新增HTTP测试从误用JWT401改为既有development_headers422 invalid_identity；审查者确认RequestAuthenticator及原AnswersHttpTest无diff，未放宽旧断言。

审查者另回读最终ImageOcrMainlineIT：2026-09-08 15:36:16，1项、0失败/错误/跳过、BUILD SUCCESS。所有执行由root完成，审查者未运行测试或编辑实现。完整871Java/73Node、源码绑定和边界见[verification](verification.md)。本切未留限定finding；模型/Milvus仍为本机协议替身，不扩大为实际图片检索质量。
