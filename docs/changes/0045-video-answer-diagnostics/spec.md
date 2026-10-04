# 本轮契约

仅修改`VideoAssessmentService`。在既有`TextModels.Failure`转换为拒答之前，记录固定阶段、异常类名、白名单错误码、评估耗时和新生成的不透明`assessment_id`；既有模型结果形状拒绝记录固定`model_result_invalid`。视觉草拟和独立核验分别定位，转录提取沿同一现有评估类记录自己的固定阶段。

不记录问题、事实、回答、模型返回、原件、文件名、路径、密钥、异常message或堆栈，不向Logger传Throwable。正常成功不新增日志；HTTP响应、拒答reason、模型协议、调用顺序、预算、取消和证明规则保持。

`assessment_id`只标识一次内部候选组评估，不能冒充页面回答ID。回答ID在后续持久化完成时产生，本轮单文件日志不宣称能够按已有回答ID回溯旧请求。
