# Deployment verification

2026-10-10：最小 Agent 补丁发布为 `20261010-agent-action-0067`。只替换 runtime.py、service.py；Java/入口仍为0066，OCR仍为0065，schema35/依赖/模型配置不变。

运行账户按实际 Agent DynamicUser 有效 UID/GID核对新模块导入与依赖读取；该身份不可读取数据库和密钥文件。新目录完整模块集合与旧集合逐摘要比较，只有两个明确模块改变；复用旧不可变venv。

首次尝试入口探针误写为不存在的 `/v1/models/catalog`，检查404后自动回退旧 Agent 代码，没有恢复数据库或改动业务配置。修正为已经预检通过的 `/v1/config` 后第二次切换成功。两个独立回退目录及一致SQLite备份均保留；失败发布记录不隐藏。部署阶段模型请求0。

两次切换均短停入口并排空现有Agent连接。后端与OCR未重启，原资料/索引未重新处理；配置、凭据、Java JAR与三个未改变unit逐摘要保持。切换后四服务active/NRestarts0，公网HTTPS配置200；证书仅以明确insecure选项测试可达，不认证CA信任。

runtime.py SHA256 `23138aa16fd8b99a1a108eae85cf47e27bc19d51ee3a80098e3df22097bba6ac`。
service.py SHA256 `218bc992fa2338a690ede97d3f6d28415589baa23375117cdac2b83c7f0c5c8d`。

这是错误传播修复的实际发布，不是未知历史动作根因或回答质量全量验收。回退仅Agent unit/代码，禁止旧数据库覆盖上线后的新业务。
