# 验证

2026-10-13，LOCAL_VERIFIED_LIMITED。没有真实模型请求、生产写入、旧资料变更、Git提交或推送。

## 反馈环

- Python新增3项真实run_agent/Java回调协议的合成回放：旁支阅读→定向检索→发现新候选→阅读→综合；每轮即时已读/未读状态；无关候选不强制阅读、不增加固定规划/总结轮次。分别在缺少checkpoint和输出合同处先红后绿。预编排模型返回只证明执行和提示合同，不证明语义质量。
- Java新增出站模型HTTP合同测试，旧工具说明缺少核心任务约束时1失败，更新后通过。工具仍为search/read/terminate三项，输出字段及单次请求数不变。第一次沙箱Socket权限错误不是产品RED，开放仅本机测试端口后才得到实际断言RED。
- Web新增5项历史问答呈现回归；独立审查发现带括号文件名被当内部标记删除，新增相同输入先红后修。普通文件名/代码保持，不改变保存答案。

## 最终结果

| 检查 | 结果 |
| --- | --- |
| Python全套 | 134/134，含真实本机HTTP；1条既有AnyIO弃用警告 |
| Java问答相关 | 68/68：AgentProtocolClientTest、AgentModelStreamingTest、KnowledgeAgentServiceTest、KnowledgeAgentControllerTest、ConversationServiceTest |
| Java生产打包/完整格式 | package成功；1223文件Spotless通过。打包跳过测试，测试证据来自上行独立执行 |
| Web直接相关 | 最终109/109，包括旧SSE/历史恢复、来源编号和XSS负例 |
| Web最终完整串行 | 540项537过/3失败；未修改的代理极短截止时间用例，原断言隔离复跑3/3通过，不称全仓单轮全绿 |
| syntax/diff/当前已跟踪源码秘密扫描 | 通过；扫描未覆盖未跟踪文件或全历史，新增文件另行人工检查 |
| 实际浏览器 | 本机真实前端+合成只读HTTP：正文段落/标题/列表/强调；默认折叠的2份资料3处引用；展开后同资料两片段同组；正文[2]打开对应原文/字符定位；返回及刷新恢复通过 |
| 独立审查 | 最终无阻塞发现；确认来源权威、结果结构及SSE发布时机未改变 |

Java第一次限定格式命令参数未匹配文件，后一次误把regex当glob；实际修正后仅格式化新测试，并重新执行完整Spotless通过。生产源码未被格式命令扩大修改。

Web前两次完整记录及Git环境失败保留在Web0055。最终3个失败为：`dev-server.test.mjs`的摘要与查询附件读取期望502但先发生504；`wiki-transport.test.mjs`的短截止前请求计数3/4。相关测试/代理文件与HEAD一致；隔离原断言通过不抹去完整运行失败。

## 可复现命令

```sh
# agent-service目录，独立本机Python环境
PYTHONDONTWRITEBYTECODE=1 python -m pytest -q -p no:cacheprovider

# 后端目录，JDK21+；rag.build.directory使用新临时目录
mvn -o -s .mvn/settings.xml -gs .mvn/settings.xml -Dtest=AgentProtocolClientTest,AgentModelStreamingTest,KnowledgeAgentServiceTest,KnowledgeAgentControllerTest,ConversationServiceTest test
mvn -o -s .mvn/settings.xml -gs .mvn/settings.xml -Dmaven.test.skip=true package spotless:check

# 前端目录；PATH使用已验证Git和Node22
node --test --test-concurrency=1 ui-tests/*.test.mjs tests/*.test.mjs scripts/check-secrets.test.mjs
node --test --test-concurrency=1 --test-name-pattern='file synopsis generation retains ordinary deadline|query attachment answer deadline covers incoming bytes|only proposal compilation receives the answer deadline' tests/dev-server.test.mjs tests/wiki-transport.test.mjs
```

## 交付与未验边界

Agent prompt追踪版本为`java-native-tool-knowledge-agent-v3-answer-focus`；模型/索引指纹未改，schema36不变。未来发布应同步Python提示、Java工具说明和Web0055。

生产真实语义验收尚未执行：需要固定原问题、真实模型与检索配置，核对是否补读关键证据、首段是否回应意图、无关资料是否消失。不得用本机手写合成答案冒称原题修复。也未重跑/关闭旧全仓Java verify、覆盖率、readiness及全库质量门禁。
