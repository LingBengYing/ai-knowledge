# 0070 原生工具执行器、对话与上下文压缩

负责人明确要求参考 Codex 开源源码替换服务端 DB-GPT，而非调用 Codex 桌面、托管 Agents API 或切换 OpenAI 模型；随后要求一并补齐对话管理与上下文压缩。

正常路径：新建/选择对话 → 保存问题 → 按需压缩历史 → 原生工具循环搜索/阅读 → Java 校验并保存回答 → 刷新后继续追问。模型、Milvus、原资料及引用权威保持。

影响范围：Java AgentMessage/AgentProtocol、模型 Adapter、任务编排、SQLite 对话 Repository/迁移、配置与 Controller；Python 执行循环/HTTP/依赖/离线安装；独立前端问答页/对话管理/API 与三类入口的代理路由。旧 DB-GPT 依赖和 Action/Observation 转换被替换并移除，历史变更证据不改写。

基线为负责人优化后的后端 fb0171d、前端 5c5fd79。不得恢复已删除的经典页、语音提问或无调用模块。本轮不自动推送、部署或消费已停止的模型批次。
