# Verification：视觉模型 Module

状态：本地协议/评估实现通过；独立Standards/Spec限定审查PASS，0个实质finding。不是无文字图片知识库端到端、云模型质量、浏览器或生产验收。

## 已真实验证的正常行为

自生成无文字 PNG/JPEG（左蓝圆、右红方）经实际 loopback HTTP 发送原始 bytes，describe 返回的错误描述没有进入 draft/verify。Service 保留完整问题、原图 SHA 和全部有序事实；验证请求再次发送同一原图；索引乱序正确还原，任一 unsupported 或 complete=false 都不返回部分事实。结果没有页码、offset、模型 URL 或伪造引用。

这不是“模型真正识别了图形”的质量结论：默认验收的 provider 回应是明确的受控协议替身，断言覆盖原字节、请求、结果和编排，而非真实 VLM 准确率。未接入 authority、Milvus、HTTP/页面或源引用封存。旧 TextModels 的公开配置、提示词与revision函数、1 MiB输入限制保持；本切仅将共有有界HTTP传输提取到包内Module。

## 红绿和最终门禁

使用实际 Temurin `21.0.12.1+1-LTS`、Maven3.9.9，独立临时构建目录和独立Maven cache；没有覆盖运行中jar、旧服务或数据。所有执行以本轮冻结源码为准。

| 检查 | 实际结果 |
| --- | --- |
| 编译夹具准备 | root新增测试一次方法拼写错误，修正后编译；不算产品故障或行为RED |
| 初始行为RED | 7项，4失败/3错误，stub未实现导致；包含真实loopback请求缺失与Service结果不符，无跳过 |
| Domain/Service首绿 | 14项全通过，2026-09-09 10:20:23 +08:00 |
| 实际HTTP及旧模型首绿 | 新视觉HTTP3、client core1、原TextModels21，共25项全通过，10:21:14 |
| 最终clean verify | **923项Java，0失败/错误/跳过**，10:30:13；完整保留原900项，新增23项 |
| 新增23项 | Domain5、Service9、VisionClient6、原图HTTP3 |
| 格式 | 277 Java文件检查通过；格式化9文件，无行为调整 |
| 原双80%覆盖率门禁 | 行6878/7353=93.5401%；分支3522/4269=82.5018%，未放宽门槛 |
| Node | 73项全通过，0失败/跳过 |
| 显式云IT的无授权防误用检查 | 10:33:20明确移除授权变量后运行，1项预期硬失败，停在第一请求前；没有请求阶段日志、没有模型构造/密钥读取，不是云验收失败 |
| 云请求 | **0**。没有收到本轮新增视觉预算回复，未读取或使用密钥，未挪用旧文本额度 |

最终完整命令（在隔离副本，JDK21环境）：

```bash
mvn -o -B -ntp -s .mvn/settings.xml -gs .mvn/settings.xml clean verify
node --test ui-tests/*.test.mjs scripts/check-secrets.test.mjs
```

运行时另显式指定专用本地仓库，避免个人/全局 settings；Maven总耗时4分34秒仅是本次构建耗时，不是产品性能基准。默认未发现/运行具名LiveIT，不把它计入923通过或跳过。

## 证据与来源绑定

[source-manifest](source-manifest.json)绑定301个Java/资源/构建/Node输入、全部case身份及日志/JAR SHA；工作树与最终构建输入逐字一致。相对于0010已有输入，只有 `OpenAiCompatibleModels.java` 因共有传输提取变化，其余为新增文件；本切没有修改现有schema、授权、解析、索引、问答编排或静态资源。

本机原始日志名称：`visual-models-behavior-red.log`、`visual-models-domain-green.log`、`visual-models-http-green.log`、`visual-models-final-verify.log`、`visual-models-node.log`。原始日志留在临时工作目录之外的本机临时区域，不进入仓库，摘要与SHA进入manifest。独立审查结论见[REVIEW](REVIEW.md)，不能用0010审查代证。

收尾检查：301个已验收输入再次核对无变化；tracked/index/worktree敏感扫描无finding，63个未跟踪文件复用同一检查逻辑无finding；293个相关Markdown相对链接均存在，git diff --check通过。未新增个人路径、密钥、模型原始返回或业务数据入库工件。文档收尾不修改上述已冻结执行输入。

## 明确未完成

- `VisionModelsLiveIT` 已提供显式四请求入口，缺授权/配置在第一请求前失败；本轮未运行真实云验收，不认为硅基流动某个模型已兼容或达到图像质量阈值。
- 原图核验仍是模型判断，不是确定性事实证明；真实视觉错误、细节/图表/注入、中文质量与多图仍需独立eval。
- 下一纵切：独立immutable图片证据与recall_text→同publication/scope的Milvus召回→实际原图评估→typed图片引用封存/回读。不得将caption写成OCR字符来源或另造权限链。
- 视觉embedding、扫描PDF、音频、视频联合、摘要/附件、页面与生产仍待交付。本切不改frontend、生产readiness或既有安全基线；未创建Git分支、推送、部署。
