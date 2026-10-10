# Verification

2026-10-10：LOCAL_VERIFIED，未发布、未推送，外部模型 HTTP0。基线 Java `7abf866d56518370e746376303d22631639bfdbd`；配套 Web0050。

## 缺陷与复现

此前只读生产诊断发现：实际失败任务先完成多次生成/检索传输（HTTP200），最后内部 Agent 返回502。原响应正文没有留存，不能证明原任务一定属于某一种格式分支。

实际旧 JAR 的合成探针表明 auto 允许的普通文本结束被 Agent 严格工具解析拒绝，且原回调无分类，最终隐藏为 internal_error/agent_callback_failed。本切回归经过真实 CallbackController→Service→ProblemHandler，不只是单独调用通用异常处理器。

## RED → GREEN

- AgentProtocolClientTest：旧实现9项中2项预期失败（auto/required、不调用工具的原因码），修复后最终12/12。含真实 loopback 服务按请求模式选择响应，旧 auto 会返回文本而失败，新 required 返回合法工具；每次只请求一次。官方端点/代理/近似域名、批调用、terminate 和无重试仍覆盖。
- KnowledgeAgentServiceTest 新增2项：旧实现真实HTTP边界预期503却500、检索模型错误预期agent_tool_failed却agent_failed。改正测试线程断言放置后保存上述精确红结果，再实施生产修复；最终相关14/14。
- Python：旧回调6项具名分类全部变成agent_callback_failed；新分类及负例23项通过。完整实际DB-GPT0.8.2/CPython3.10.17及TCP服务回归56/56，2个已有依赖弃用警告。
- Web：新增原因码旧版拒绝，1项红；相关7项绿。完整串行671/671及语法检查通过。第一次完整回归660通过/11失败均为本机默认Git退出69使合成仓库创建失败；仅调整测试PATH选择已可用Git后全套重跑通过，未修改断言或接受Xcode许可。

## 最终合并验证

Java使用JDK21、离线Maven与独立临时构建目录：

```sh
mvn -o -s .mvn/settings.xml -gs .mvn/settings.xml \
  -Dtest=AgentProtocolClientTest,KnowledgeAgentServiceTest,KnowledgeAgentControllerTest,OpenAiCompatibleModelsTest,OpenAiCompatibleWikiModelsTest,OpenAiCompatibleSynthesisModelsTest,TextModelProtocolLogTest test
```

结果75项，0失败/错误/跳过。7个修改Java文件的定向Spotless检查与两仓库diff检查通过；代码只记录固定原因、阶段、UUID及次数，不输出模型、资料、密钥或原异常。

Agent执行 `python -m pytest -q`：56 passed，最终重跑5.54秒。独立临时环境安装现有锁定依赖，项目锁文件未改，无模型/生产调用。Web使用 Node22执行 `node scripts/check-syntax.mjs` 及 `node --test --test-concurrency=1 ui-tests/*.test.mjs tests/*.test.mjs scripts/check-secrets.test.mjs`：671/671，41.08秒。这里包含扫描器自身测试，不宣称已经完成发布暂存区/完整历史密钥审计。

独立差异审查未发现阻断问题。测试未接真实模型，不把协议替身判断当成语义质量或生产可用性证明。

## 变更与发布边界

- Agent请求改为required；只有官方DeepSeek HTTPS端点的Agent请求显式关闭thinking。普通生成、模型选择、索引revision、token参数不改。代理/其他思考模型是否支持required尚待真实兼容验证，不自动降级或重试。
- 模型缺工具、非法返回、超时、不可用及资料工具失败分开；未知问题仍安全兜底。证据必须已阅读且通过Java权威校验，自由文本不能伪装正式答案。
- 没有schema/数据/权限/运行配置变化，没有重启或替换运行包。后续须配套发布Java、Python Agent、Web0050；旧失败任务不重写、不自动重发。
- 尚未执行真实问题复验、完整Java全仓/覆盖率、浏览器生产验收。历史ACL/readiness及真实检索质量问题没有因此关闭。
