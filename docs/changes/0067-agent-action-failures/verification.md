# Verification

## 本地真实 DB-GPT 回放

2026-10-10：新测试修复前 2 通过、4 失败。三次搜索后合法拒答在有候选/无候选两种情况下均正常；未知或重复来源读取被上游包装成 agent_tool_failed，可缩减为一次搜索后读取，错误相同。

根因：CallbackBridge.read 已将特定失败写入 bridge.failure；DB-GPT run_tool 捕获异常并返回失败 ActionOutput；适配器再次将失败改写为 agent_tool_failed。修复为保留第一条已归一化安全原因，并在上游动作返回后优先传播该原因。不自动重试或接受未知来源。

新增 10 条回归包括完整循环、错误来源、回调缺少原文、固定动作日志与原因粘性；完整 Agent 66 条通过，包含 7 条真实本机 TCP/HTTP 用例，耗时 5.60 秒。最初沙箱禁止绑定本机端口导致 7 项 PermissionError，获执行器 loopback 权限后原用例全通过，未修改断言或跳过。保留两个依赖弃用警告。

命令：`PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=agent-service <verified-python> -m pytest -q agent-service/tests`。

## 证据边界

原生产任务的具体动作/来源参数未留存；回放证明吞码缺陷及相同计数路径，不证明历史请求唯一根因。真实新问题已取得 DeepSeek/硅基流动外发授权，计划一次正常问答；不续用旧批次，不改资料或重新索引。

只改 Python 两个模块，无 Java/前端/依赖/schema 变化，故不重跑不相关仓库全量测试。源码差异检查通过。后续生产已发布，新问题真实完成但拒答；实际结果见deployment-verification.md/provider-run.md，不能以66条替代真实答案验收。
