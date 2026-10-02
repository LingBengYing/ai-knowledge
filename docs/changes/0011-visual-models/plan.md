# Plan：视觉模型 Module

本切本地Module已交付：2026-09-09 10:30:13最终923 Java/73 Node、277文件格式与双80%门禁通过，原900项保留，限定独立Standards/Spec审查PASS。云请求0，真实provider质量和authority/检索/HTTP接线仍待完成。详见[verification](verification.md)，不标记完整多模态/生产目标完成。

1. root 冻结本工件、Interface 和独立验收；当前不同时修改 schema/上传/索引/HTTP。先实现并验证实际图片通信与完整逐事实评估，然后再推进 authority 接线，避免用文字引用占位。
2. adapter_layering 拥有 client/model 中 VisionModels、OpenAiCompatibleVisionModels、包内有界 HTTP Module，以及原 OpenAiCompatibleModels 的纯传输提取和直接客户端测试。旧文本行为、所有失败断言和版本指纹保留。
3. authority_layering 拥有 model/domain/VisualImage、VisualAssessment、service/VisualAssessmentService 及其直接测试。先写失败用例和可编译占位，root 运行红测后再实现；不修改数据库、原 AnswerService、权限或 runtime capabilities。
4. root 拥有本工件、真实本地 HTTP 的 Module 验收与显式云 IT、验证运行和最终文档。只有 root 运行 Maven/模型请求；仅一个新的临时构建目录，不覆盖历史900项基线报告或运行中 jar。共享工作树不得回退他人修改。
5. image_ingestion_map 做冻结源码的非实现者限定 Standards/Spec 审查。最后一次源码修改后执行对应完整测试与一次全量，不反复运行未变的全量。

接口冻结：`VisionModels.Description(String recallText)`；`Draft(boolean refused,List<String> claims)`；`Verification(boolean complete,List<Boolean> supported)`，核验列表已按请求索引排序。`VisualImage(String mediaType,byte[] content)`，派生 `sha256()`。`VisualAssessmentService(VisionModels)`，`assess(String question,VisualImage)` 返回 `VisualAssessment(List<String> claims,String sourceSha256,String modelRevision,String policyRevision,String refusalReason)`，reason=null 表示通过评估，不代表已授权知识库回答。

技术来源：[SiliconFlow 官方视觉输入](https://docs.siliconflow.cn/docs/userguide/capabilities/vision)、[官方 Chat Completions 协议](https://docs.siliconflow.cn/docs/api/chat-completions-post)。使用标准字段，不因协议兼容假定某个模型支持图像，也不自动探测/切换模型。fullstack-dev 用于正常链路验收，codebase-design 用于小 Interface/真实 Adapter；用户指定 Spring layer-first 优先。
