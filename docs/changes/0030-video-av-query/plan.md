# 分工及验证

开工前已读取intent/spec/interfaces并固定ownership。共享Maven/Spotless/target只root串行；agents不构建、不改冻结包/Git。先写可观察行为验证再实现，旧断言/范围/架构/双80%不放宽。

- A `/root/pdf_config`：compiler compileQuery、新VideoAvQueryManifest/VideoAvQueryTrace、typed query command/result/receipt DTO、strict新mapper、AuthoritySchema/SqliteAuthorityStore v21、VideoAvConfiguration/RuntimeConfiguration新servlet及实际cap接线，相应新编译/manifest/迁移/mapper/config测试。RuntimeService及其测试由root。
- B `/root/backend_originals`：VideoAvAnswerService reference编排、VideoAvTraceDraft兼容query字段、VideoAvRepository同事务sidecar及source复验、相关服务/存储测试。与A仅Interface协调，不抢文件。
- C `/root/pdf_ingestion`：前端0019工件以root冻结版本为准；public/video-av.mjs/answers.mjs/query-attachments.mjs/api.mjs/app.js/index.html、两个proxy及相关新tests/必要旧test追加，不改已有断言；完整request/envelope/hash/迟到隔离、三模式控件与精确cap。
- root：正式工件、RuntimeService能力、真实合成Native HTTP贯通、统合审查与门禁/证据/独立审计。源码全部就绪后统一Spotless、相关验证、全clean verify/default+Node+前端以及需保留的六Native；对旧2567/344身份多重性和字节/新构建输入、class/JAR做实核。未修改代码不重复已完成检查。

验收正常链：单/三参考全window和两路调用、尾窗有效召回、原文字proof与来源；无音轨AUDIO/JOINT0provider且整批not_prepared、VISUAL无音轨成功；aggregate超限/尾窗错candidate/半问题/附件独有拒答；完整scope缺项409零decode/provider/[]不扩大；全scope撤权/配置/timeout/cancel无晚成功；hash-only准备记录与schema guards/旧v20保留；来源重启0query/model。真实FFmpeg含非零有理epoch、静音/尾sample/音尾/无音轨。与旧路兼容证据逐项记录，页面/真实质量/部署NOT_RUN。
