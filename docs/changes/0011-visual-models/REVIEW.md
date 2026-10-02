# Review：视觉模型 Module

状态：Standards scoped PASS；Spec scoped PASS，0个实质finding。2026-09-09非实现者image_ingestion_map完整核对本切四工件、AGENTS/Java规范和冻结增量，回读10:30:13最终执行日志及source-manifest；未自己运行测试、云请求或改文件。

限定审查范围：Spring 分层、caption 与事实分离、实际原图及完整问题/事实传输、严格核验结果、旧文本协议不变。本切没有 authority/检索/公开端点接线，不认证纯视觉知识库、真实模型质量、音视频或生产。

## Standards

Domain不可变并脱敏；Service实施顺序、完整性及整体拒绝；Client封装协议与HTTP。没有越层SQL/环境读取、公开端点、默认能力接线或新的权限体系。包内共用HTTP Implementation收拢重复的传输规则，旧public配置保持，不引入无实际替换需求的泛化框架。

## Spec

- draft与verify携带相同原图、完整问题和全部有序事实；Service不调用describe，caption不参与证明。
- 核验索引完整唯一且按原序归位；缺项、unsupported、complete=false、版本变化及中断不返回部分事实。
- 对0010基线逐hunk核对，原TextModels提示词、revision函数、1MiB限制及严格JSON/TLS/错误/取消行为保持。视觉单独使用16MiB请求限制与至多60秒deadline，构造不请求、不自动重试。
- 新增Domain/Service、client及PNG/JPEG实际loopback HTTP测试对应具名行为，云IT要求独立授权环境变量；不能拿本机响应替身证明实际视觉质量。
- 回读最终923项通过且原900项保留、277文件格式及双80%门禁成功，manifest构建输入无差异。执行详情与未验证范围见[verification](verification.md)。

审查调度中曾误用只发消息、不触发空闲代理的新任务方式；主线程确认状态后重新派发，以上PASS来自实际完成的新审查，不是此前探查结果。此工作规则已写入AGENTS。
