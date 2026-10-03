# Plan

1. 已冻结0025与前端0014的正常路径：单音频准备完整问题文字，用户编辑确认后沿旧完整scope/mode问答；准备阶段不读取库、不入库或创建trace。
2. 实际分工与文件ownership：
   - backend_originals：新增VoiceQuestionCommand/VoiceQuestionResult、VoiceQuestionService、DTO/Service行为测试，以及显式运行的VoiceQuestionMainlineNativeIT；最后仅更新本plan与REVIEW。服务复用AudioCompilationService，不修改原解码/ASR/编译算法。
   - pdf_ingestion：共享BoundedMediaQueryServlet、旧附件配置迁移、语音mapper/VO/配置/Runtime/属性及相应新测试；迁移旧QueryAttachmentHttpTest的Servlet构造夹具，保持原行为断言。
   - pdf_config：前端voice-question.mjs Session与新Module测试。
   - root：前端页面/代理接线、统一后端Maven/Spotless及前端回归、证据归档、最终源码/制品绑定与验证记录。浏览器由用户验收，部署由原部署责任方处理。
3. 已有有效业务RED仅为Runtime能力缺失：根任务在冻结标签版的独立构建副本中运行VoiceQuestionRuntimeTest，2026-10-03 03:04:44 +08:00为1项、1失败、0错误/跳过，显式开启voice但未声明voice_questions。日志保留于工作区`.tools/voice-questions-verification/backend-runtime-red.log`。新DTO/Service测试先落盘再写实现，未将缺新类型或编译中间态算作RED，也未执行新HTTP路由404 RED。
4. 媒体接收器的共享复用需要删除`src/main/java/com/evidence/rag/web/QueryAttachmentServlet.java`，替换为BoundedMediaQueryServlet；旧附件与新语音分别注册精确路由和独立限额/handler。旧附件TEXT/IMAGE分派移至QueryAttachmentConfiguration，保持原请求大小、接收/处理预算、在途数、认证/Origin及安全错误。交接必须显式记录旧文件删除，不恢复兼容壳或保留双入口。
5. 旧QueryAttachmentHttpTest夹具原来传入visual=null。迁移时在handler中补回IMAGE模式的503 query_attachment_unavailable分支，等价于旧Servlet的无visual分派；没有把IMAGE改走文字答案或放宽既有断言。该修正属于夹具迁移完整性，不是新增产品缺陷RED。
6. 根任务统一执行的相关GREEN在03:21:20 +08:00通过121项、0失败/错误/跳过，覆盖27项新增默认测试、旧音频/附件分派与架构回归；日志为工作区`.tools/voice-questions-verification/backend-targeted.log`。Maven/Spotless始终由根任务串行执行，实施代理未并行写target。
7. 必要native路径为真实Spring配置与ProcessAudioDecoder处理合成3秒WAV，chunk=1秒经生产OpenAiCompatibleAudioModels向loopback发送3段ASR，再显式编辑确认通过旧selectedID问答并GET库内来源。首跑03:23:11 +08:00为1项、1失败、0错误/跳过，已到库内answered后因新IT错误期待quote含句号而失败。仅将两处精确assertEquals修正为既有字段摘录“上海住宿上限为650元”，保留上传原文“上海住宿上限为650元。”、旧server/产品/断言和首轮日志`.tools/voice-questions-verification/backend-native.log`；不把此次期望修正算产品修复或产品RED。
8. 根任务已完成新语音native1项及旧三媒体附件/重启来源native1项，默认全量1839、Node73、前端275、639格式和原覆盖率门禁通过。655后端与42前端源码输入未变，475个语音native生产class与最终JAR字节一致；默认suite不执行*IT，两项单列结果不并入1839。原冻结包不改，新交接纳入共享Servlet删除。

阶段：本机正常路径、完整回归及必要native验证完成，证据和偏离见verification.md；待新交接冻结，发布与用户页面验收尚未完成。新增云调用为0，没有读取凭据、旧数据或进行Git写入、部署；真实provider/中文ASR质量仍需单独验收。
