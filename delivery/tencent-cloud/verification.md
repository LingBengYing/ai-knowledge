# 镜像发布验证

2026-10-28：5/5 build、5/5 push、5/5 remote manifest readback passed。原始构建、推送、检查日志保留在工作区 `.local/tcr-build-20261028/`，不随交付包发布。

逐角色不可变摘要见 evidence/images.json，远端 raw manifest 重新计算 SHA-256 等于 Descriptor.digest，并与各自本地 build exporting manifest 一致。控制台可见 Java 20261028-0094r2 / amd64 / SHA 3ccc12f550d4…；五仓库私有可见。

验证分层：API /health/live startup passed（隔离 BuildKit）；Agent TestClient health passed；OCR socket start/stop passed；Web syntax passed；Milvus binary SHA/ldd passed。完整 Compose、生产发布、真实模型、全库质量和容量未验证。

本机两次普通容器启动 Created 卡住，未输出 State.Error，原因未定，不猜测与镜像有关或 Docker 已修复。两临时容器已删除；未重启 Docker 或操作其他运行容器。

TCR temporary login logged out and private temporary Docker configuration removed. Password not included in delivery files.

## 一键包续作（2026-10-28，覆盖上文待验状态）

- 用户允许本机 Docker 重启及 VirtioFS → gRPC FUSE 诊断；切换无效，已恢复 VirtioFS。普通容器启动卡在 Desktop volumeShare gRPC 调用，而原始引擎诊断能启动；安装器未采用诊断 socket。
- Docker 官方仓库同类记录：https://github.com/docker/for-mac/issues/7712 。通过应用自带更新入口从 Desktop 4.43.0 更新至 4.97.0 / Engine 29.9.2。标准新容器探针恢复，无数据重置。其他原先运行服务恢复；Dify sandbox 旧退出2重启问题保留。
- 早期一键编排因集合名缺 java_ 前缀、模型配置未允许私有 loopback 端点而失败；修正 compose 后实际五服务健康、启动器完整通过。不是应用源码修改。
- synthetic TXT 首次验收脚本 MIME text/plain 被415拒绝；按既有上传合同修正 application/octet-stream 后 parsed，原文件字节完全一致。
- 独立合成集合写入1条2维向量，Strong 查询 code0 且 id=1；无 token 的集合列表401/code1800。
- stop.sh 不删卷；重新创建全部本项目容器后同task/document/revision原件保持，同collection Strong读回，common.env/tls.key/tls.crt SHA完全一致。
- 首次重启验收期间修改正在执行的 shell 文件导致一次 `er: command not found`；此执行失败记录保留。随后冻结脚本再次验证，不以该轮脚本失败宣称启动入口通过。
- 5镜像 images.tar 实际 docker load 成功，archive仅含指定5个tag；整archive SHA验证。标签使用平台manifest/config双身份校验以兼容容器存储实现；当前仅实际验证Desktop containerd。
- 密钥与业务内容不写入交付证据，私有运行日志仅 .local/tcr-build-20261028。真实模型0、生产变更0。首次浏览器自签证书确认/公网部署/全库规模/真实模型质量不在本轮认证。
- 最终冻结版 start.sh 重复执行退出0；没有重复导入镜像、没有重建密钥，五服务healthy，页面/会话/配置检查通过。完整结果见 evidence/oneclick-acceptance.json。

## 轻量在线包（2026-10-28）

根据用户反馈“大包太大”，新增 oneclick-online，不改变已有离线包或运行服务。只带 Compose、配置、四个默认所需镜像的固定身份清单及启动脚本。未启用的云OCR不提前下载。缺失镜像按仓库@manifest拉取，校验OS/架构/manifest或config，再贴固定Compose标签；全量校验通过后才启动服务。

本机真实Docker缓存复用通过，没有登录或下载调用。独立fake-Docker流程7项通过：全缓存、已认证下载、首次鉴权登录、无终端拒绝登录、网络失败不要求密码、登录后仍拒绝停止、镜像身份错误不贴标签。首轮发现while读清单占用stdin导致登录无法识别终端；已改独立fd3读取后全过。详见tests/online_flow_test.py及evidence/online-flow-tests.json。模拟校验不认证新机器真实下载成功；五远端manifest此前已核对，本轮未新建凭据或外传密码。

原有数据身份按.env复用规则在README明确说明，包内不含.env、logs、运行卷、镜像tar或密码。仓库继续私有；首次下载约1GB不会因入口包变小而消失。默认目标仅本机访问，不自动修改公网权限。

## Git 源码交付检查（2026-10-29）

将交付目录的脚本、构建配方、镜像身份清单与脱敏验收记录纳入后端仓库；不包含大镜像包、压缩包、运行配置、模型密钥或业务数据。Git 克隆后使用 `oneclick-online`；`oneclick` 离线源码仍需另行提供 `images.tar`。

提交前将初始化脚本的随机凭据写入改为带格式的 printf，字段和随机长度保持不变；隔离临时目录验证两种启动入口均生成正确格式，二次初始化不改变凭据与证书。验证 Dockerfile 的固定合成测试值使用 env 显式传入。原有已交付压缩包和镜像摘要保持不变，这些源码整理未重新发布镜像。

29 个 Shell/Node 入口语法检查、两份初始化生成及重复执行检查、7 项隔离下载流程检查通过。以上不新增模型调用或生产操作，也不替代新机器完整拉取及真实模型验收。
