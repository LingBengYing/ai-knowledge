# 生产发布验证

2026-10-11，发布 `20261011-agent-result-0073`。配套Java、原生Python Agent和Web0054，OCR、模型active7、中文索引、免登录及既有配置保持；schema36→36，不重建检索、不导入本地资料。

## 固定源码与制品

- 后端代码 `107b2f10262b10ef7ee6f22001e30f35ecc12068`，前端代码 `a2097a32bd13a8ba0fa0c9a0cab188f897cf084e`，均已推送并核对远端main。后续提交只补脱敏验收文档，不改变冻结代码制品。
- JAR SHA256 `acb642ee0d79f5e5257007e0a3a05f108c3f586cb4a55caa8785d2cae2fb4e10`。
- Agent源码归档SHA256 `d3624af33f4acd320b7144cdc4537faae0c7df8c1612df9d808d9c1bb9fed7bb`；使用既有Linux离线wheelhouse，在最终目录建立新venv，没有下载新的运行依赖。
- Web归档SHA256 `97ec4e97f56356ab9fa5d226e981ca51adeaa18c7870756f30598ad179061100`，226运行文件与manifest精确一致，无额外AppleDouble文件；当前源码上的4项既有免登录入口定制保留。

## 验证与切换

合并后的Java相关71/71、前端完整串行531/531、Python同源码131/131；格式、打包、当前index/worktree扫描通过。实际定制入口226项、归档SSE/会话代理19项通过。全历史前端扫描空；后端仅两条旧提交中同一合成正文变量SECRET的误报，已核对为非凭据、当前更名PURGED_TEXT，扫描器不放宽/历史不重写，不宣称历史扫描全绿。

发布前按真实服务UID/GID检查Java、entry及Agent实际新模块读取/路径权限，entry/Agent不可读数据库或私密配置。生产数据库副本用新JAR连续两次重开，schema36/99表14文件保持；停入口并排空、停Agent/Java后备份旧包、units、配置、数据库和原件。切换后三服务使用配套新目录，OCR不变；全旧行/原件与配置摘要核对保持。

真实问答与重启零模型回读见 [provider-run.md](provider-run.md)。新授权批结束后移除临时计数参数，不把测试限额留在正常生产，也不续用旧余额。开放前再次核对数据快照，恢复原免登录入口后检查11个只读页面/API/模块路径、内部路径404、四服务active/NRestarts0。

本机通过正常TLS验证访问公网HTTPS首页，HTTP200/tls_verify=0；公网同一会话GET completed/answered、5条引用及答案SHA与验收一致，无重新提问或模型调用。Chrome页面导航被本机客户端ERR_BLOCKED_BY_CLIENT拦截，未见证页面最终显示；没有绕过浏览器限制，不能把公网API证明等同页面视觉验收。

备份留在生产本次release的0700 rollback目录；不提交私密运行工件。故障回退只能恢复兼容代码并保留当前数据，不允许旧数据库覆盖新增业务。旧Java全仓/覆盖率/readiness503按既有明确限定发布例外保持，非全量门禁通过。
