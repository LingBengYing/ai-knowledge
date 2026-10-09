# 真实协议诊断

负责人授权生产自行调用模型测试修复。本批`agent-native-tools-repair-20261009`自限12次，全部服务商合并计数。只留安全结构/枚举/摘要，不保存模型正文、思维或密钥。失败停止当前实验，不原样自动重发。

1. 旧0060提示与实际DB-GPT首轮：DeepSeek/deepseek-flash HTTP200/stop，解析出1个knowledge_search动作，无Observation；但action_input不是对象，实际终态agent_invalid_tool_input，搜索0次。这证明文本动作协议存在真实参数不匹配，不据此推断先前agent_invalid_action的具体正文。
2. 新0061实际Java原生tool协议与真实DB-GPT合成闭环：首轮model_invalid_response，搜索0次，停止；不是质量通过。当前累计2次。下一实验只读模型结构以区分普通文本返回、原生信封差异与参数形状，不读取业务原文。

3. 首轮结构观测：HTTP200/tool_calls，1个choice/index0，但包含2个knowledge_search调用；均为合法query对象，无legacy/refusal，每个call另含1个元数据字段。实际严格校验未通过。由此定位多调用被单调用适配器误拒；[DeepSeek官方示例](https://api-docs.deepseek.com/guides/thinking_mode/)亦展示原生调用带index，协议修复补齐可选index和多只读调用，不特判模型名。

前三次均使用活动配置v6，不修改生产模型选择。私有逐请求台账保留在服务器诊断目录；此文不是生产上线或真实业务引用验收。新增导入验收纳入后，自限已说明调整到20次，累计3次，下一实验须为修复后的新协议，不重发相同失败实现。

4–6. 补齐合法index和只读多调用后，新独立合成闭环通过：实际Java Adapter + 实际DB-GPT，3个模型回合，1次search、1次read，3条陈述包含需求/实现/验收并全部引用已读合成source-1。累计6次；本次provider没有返回批量调用，多调用由确定性回归覆盖，不冒充云端批量已验。此结果证明协议正常链，不代替随后正式生产检索与来源回读。

最终0061协议JAR SHA `58142bb24b6f557cd8ed429f1d149a9754059256ece51f622556344107cf6c05`；Python runtime SHA `b15863987a7d78f157af9c335426fc0c4c45612c54af3569f07a66d519c64f63`。原单调用失败包未激活生产，只有私有暂存。后续正式生产更新将与0062自动索引整合，保留上述历史结果。

## 正式生产验收（0062/0062b）

两切已上线。第一次页面导入自动索引成功，但旧索引子进程丢日志，HTTP实际次数无法追溯，台账单列null，未猜1或写0。0062b补齐固定字段转发后，另一份新合成TXT实际1次硅基流动embedding，完整started/finished闭合，无手工索引或重试。

只发起一次生产页面问答：实际检索→阅读→完成，3次DeepSeek、硅基流动embedding/rerank各1；正确三步骤，单条引用绑定新文档同revision。source API和原件SHA核对通过，浏览器独立原文页可读并返回保留回答。任务`b057bf76-d718-4e7e-b9c2-5503481f6c3d`，回答`0fd3f0e3-d4d4-4be0-9bed-0c9ff20f56ba`。

窗口关闭：保守台账13条传输事件，其中1条内部loopback Agent HTTP；确认外部模型HTTP12次（DeepSeek9、硅基流动3），另第一次索引计数缺口未知。此统计不等于供应商收费账单，也不是精确全批次数。未保存模型正文/思维/密钥，未改旧业务资料；真实页面截图只含合成验收回答。详细部署与边界见0062/deployment-verification.md。
