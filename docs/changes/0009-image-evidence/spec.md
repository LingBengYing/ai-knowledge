# Spec：独立图片文字证据正常闭环

## 当前输入与输出

- 显式配置 `RAG_IMAGE_OCR_ENABLED=true` 才允许PNG/JPG/JPEG上传；默认关闭保留原PDF/TXT/MD行为。复用原上传接口、持久任务与主动索引接口，不引入第二套任务/权限系统。
- 文件真实类型、原字节大小和图像尺寸有界验证。上限10MiB、1200万像素；后缀、MIME、magic和图像头须一致。不接受SVG、远程URL、动画或客户端提供的识别文本。
- Config注入本地Tesseract可执行文件、语言和显式运行版本。Worker通过固定参数与stdin/stdout执行，不经shell、不继承应用凭据、不自动重试；deadline与取消遵循既有摄取任务。缺本地工具或语言数据不伪造成功。
- OCR输出只作为机器转录：规范化成page 1的TextPage/TextSegment，保留所有原code point与事实校验。独立parser_revision绑定OCR运行配置，文档类型为image、原PNG/JPEG SHA与不可变revision保留。
- 本切不改原TextParser算法、协议、旧schema或Milvus投影。OCR文本经原有授权embedding、dense/BM25、重排/摘录与服务器验证；不声称纯视觉向量检索或通用图表理解。

## 引用与原图

- 文本答案原字段语义不变。图片来源另提供明确的 `image` anchor，范围为服务器固定的整图 `[0,0,1,1]`，尺寸来自原图；这是整图定位，不是字级/行级bbox。
- 识别文字的page/start/end仅定位OCR转录，不能冒称原图字符坐标。界面或调用方必须标明机器识别，可回看原图核验。
- 新增 `GET /v1/sources/{answerId}/{ordinal}/content`：只回读该回答当前有效引用对应的PNG/JPEG原字节。复用同一事务中的原回答者、完整scope、ACL/active和引用校验；按保存的source SHA验证原字节。返回正确MIME、no-store与nosniff，不接受客户端路径、URL、bbox或revision替换。
- 本切单图最大10MiB，一次有界返回；音视频Range/播放器不在此切假装实现。文本引用content返回不可用，不开放自由文件下载。
- 取消、删除/撤权及无证拒答复用原规则；不改原安全机制，也不扩展细粒度权限体系。

## 验收

1. 图片开关关闭仍拒绝；开启后真实PNG/JPEG上传成image任务，OCR结果有独立parser身份且parsed不等于indexed。
2. 完整正常HTTP流程通过实际Spring/SQLite/任务和受控协议Adapter完成：上传→OCR→索引→答案→转录和原图SHA回读。替身明确标注，不冒充真实OCR/provider/Milvus。
3. 固定合成图片另跑真实Tesseract识别，核对唯一数值与原字节；未安装/未执行则如实保留缺口。真实远程图片模型测试须另明确数据/预算，不能把前轮文本17次余额自动扩大用途。
4. 必要的无身份/其他用户/撤下来源不可读回归，既有858项Java与73项Node及双80%门禁不删除、跳过、放宽。网页、真实模型/向量质量与完整图片/多模态仍分开验收。

## 后续主线（本轮不并行开工）

区域OCR/扫描与图文PDF/纯视觉证据 → 音频ASR和时间引用 → 视频关键帧、音轨及联合证据 → 摘要/跨模态问题 → 网页和完整生产验收。每条先正常路径，非阻塞异常/容器/权限增强保留backlog。
