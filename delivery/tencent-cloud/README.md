# 知识库 Docker 交付（2026-10-28）

从 Git 安装：进入 `delivery/tencent-cloud/oneclick-online`，Mac 双击 `启动.command`，Linux 执行 `./start.sh`。首次按需拉取固定镜像。Git 仅包含启动器、构建配方和脱敏验收记录，不包含压缩包或离线镜像。

轻量在线版：`knowledge-online-20261028-linux-amd64.tar.gz`，仅带启动器，首次自动下载镜像；私有仓库首次需登录一次，后续复用缓存。详见 `oneclick-online/README.md`。

离线版须另有镜像包，不能仅用 Git 中的 `oneclick` 目录启动。已交付的完整离线包： `knowledge-oneclick-20261028-linux-amd64.tar.gz`：解压后 Mac 双击 `启动.command`，Linux 执行 `./start.sh`。五个离线镜像已包含，不需要登录腾讯云、不需要重复输入仓库密码。默认仅本机 HTTPS 访问，模型密钥在页面配置一次。

具体要求与操作见 `oneclick/README.md`。后续仍运行同一启动入口；停止入口保留所有资料、索引与配置。

## 固定镜像

腾讯云私有仓库命名空间 `ccr.ccs.tencentyun.com/lingbenying-knowledge/`，架构 Linux AMD64：

| 服务 | 版本 |
| --- | --- |
| knowledge-api | 20261028-0094r2 |
| knowledge-web | 20261028-0062 |
| knowledge-agent | 20261028-0076 |
| knowledge-ocr | 20261028-0078r3 |
| knowledge-milvus | 2.6.24-0093 |

五个远端 manifest SHA 与构建输出一致。离线镜像身份见 `oneclick/images.lock`（标签、平台 manifest SHA、config SHA），整包完整性见 `images.sha256`。旧 `install/pull.sh` 仅供需要自行登录私有仓库的运维人员使用；普通安装不需要它。

## 实际验收

本机 Docker Desktop 4.43.0 的新容器曾卡在 Created。切换 gRPC FUSE 无效，已恢复 VirtioFS；通过 Docker 自带更新到 4.97.0 / Engine 29.9.2 后，标准 Docker 启动恢复。未重置或删除 Docker 数据，Dify 和 Open WebUI 原先运行的服务已恢复；原 Dify sandbox 重启故障仍为已有问题。

已实际执行一键启动、空库初始化、五个运行容器健康、验证证书的宿主机 HTTPS 200、会话/后端/Agent 配置读取、合成 TXT 上传解析与原字节读回、Milvus 独立向量写入和 Strong 读回、匿名访问拒绝。停止并重新创建容器后，同资料、同向量及密钥/证书哈希保持。五个离线镜像实际 `docker load` 成功。

云模型请求 0；不认证真实模型回答质量、全库吞吐、原生 ARM 或公网部署。本地浏览器的首次自签证书确认由用户完成。默认云 Paddle OCR 不启用，图片/扫描件使用本地 Tesseract。独立 OCR 镜像作为可选制品包含，默认运行的是 API、Milvus、Agent、Web 和 HTTPS gateway。

## 来源与边界

API：0094-r2 已发布 JAR（源码 e94a6e1）；Web：0062 已发布目录（源码 17fc742）；Agent：e94a6e1 跟踪源码；OCR：0078-r3 发布归档；Milvus：2.6.24 / 0093 Woodpecker 修复。

`evidence/` 保存源码输入、镜像摘要及脱敏验收结果。包内无生产资料、数据库、生产模型密钥、TCR 密码或本机新生成密钥。镜像构建与验收阶段不修改生产、不调用模型；本次将启动器源码纳入 Git，未发布的图片/OCR/问答工作树修改没有混入镜像。
