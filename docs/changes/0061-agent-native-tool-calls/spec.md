# 合同

- 使用OpenAI兼容chat/completions的function tools，固定knowledge_search、knowledge_read、terminate；工具schema由Java静态提供，不由问题/模型自由注册。
- Agent专用Adapter接收标准已知函数调用与合法对象参数，兼容可选调用index及多个只读search/read调用；整批先验证，终止调用必须独立，不丢弃后续调用。转换为内部规范ReAct动作或内部批处理动作供真实DB-GPT执行循环消费；内部转换不是让模型输出动作文本。普通问答、Wiki编译、嵌入和重排协议不变。
- 保留现有身份、来源分配/已读来源验证、DB-GPT执行循环、无自动重试、取消和迟到隔离；不执行Shell/SQL/任意HTTP工具。工具返回与文档仍为不可信数据。
- 无schema/依赖/数据迁移；Agent内部HTTP messages/content合同不扩散到公开API。
- 真实验收区分生产主机合成材料的协议质量与正式生产接口的搜索/阅读/引用；不把纯替身通过等同生产修复。
