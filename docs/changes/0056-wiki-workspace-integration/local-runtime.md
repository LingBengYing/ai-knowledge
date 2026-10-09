# 0056 本机页面联调运行入口

## 当前真实模型运行（2026-10-09 13:29 +08）

用户追加真实模型验证后，18090已切换到真实生产JAR的18092，不再连接下方18091替身。生成DeepSeek、嵌入/重排硅基流动均为线上同配置；Milvus是真实服务独立集合，详见[本轮真实验收](provider-run.md)。

- 前端终端session `94279`，`RAG_WEB_BACKEND_ORIGIN=http://127.0.0.1:18092`，浏览器`http://127.0.0.1:18090/`。
- 后端与计数/SSH联调进程session `26194`，主Java在本机18092，SF透明转发19090，Milvus隧道19534；不要停止18087/18088等其他任务进程。
- 不可变JAR与同库数据：`/private/tmp/wiki-real-direct-0056-qB7BNN/app.jar`、`/private/tmp/wiki-real-direct-0056-qB7BNN/data`；私密模型设置700/600，禁止打印或提交。
- 新Milvus集合`java_wiki_real_20261009_qb7bnn`。旧业务集合、生产服务、其他本机资料没有改写。
- 启动工具在工作区`.local/wiki-real-20261009/direct.mjs`。本轮`provider-live-run.json`已6次闭合halted；随后`--interactive`同库恢复供用户主动操作，`interactive-run.json`在重启/GET验收时0请求，不会自动测试、索引或生成。不要把交互运行误称仍可自动续用旧批次。
- 输入`stop`可停止本任务持有的真实联调进程；不得删数据/集合，除非另获清理授权。新台账的独占创建保护使重复启动会拒绝，不能随手清账绕过。
- 旧18091替身及其目录保持历史，不认证当前真实模型；下文隔离脚本默认18091仅用于确定性开发测试。

### 下方为替身阶段的可复现入口

这是生产 Spring / SQLite / parser / index worker 加 **本机确定性模型与向量协议替身** 的联调实例。启动器仅在 test-source，不进入生产包；不是云模型质量验证，不是真实 Milvus 验收，也不是生产部署。

## 新建一次隔离运行

在后端仓库运行：

```bash
bash scripts/run-wiki-integration.sh
```

前置要求：JDK 21、Node.js、本机 Maven 离线缓存齐全，`127.0.0.1:18091` 空闲。默认使用当前机器 PyCharm 自带 JDK 和 IntelliJ 自带 Maven，可显式覆盖：

```bash
WIKI_JAVA_HOME=/absolute/path/to/jdk21 \
WIKI_MAVEN=/absolute/path/to/mvn \
WIKI_NODE=/absolute/path/to/node \
bash scripts/run-wiki-integration.sh
```

`WIKI_TEMP_ROOT` 可指定已有、可写的绝对临时目录。脚本在其中 `mktemp -d` 新建唯一运行目录；`build` 和 `data` 是其独立子路径，没有复用旧资料目录的参数。保留运行目录，不自动删除证据或数据。

脚本先检查端口，跳过 Maven 用户启动 rc、使用仓库内隔离 Maven settings，离线运行 `WikiWorkflowHttpTest,WikiModelRebuildHttpTest`，然后用 Node 从该次 Surefire XML 解析并校验 `java.class.path`。只接纳本次新构建的 production/test classes 和已有依赖，移除尾部空 classpath，不将源码目录隐式载入。测试失败或报告不完整即停止；不读取私密模型配置、不访问云服务、不操作 Git。

通过后启动 `WikiLocalIntegrationServer`，它只接受一个不存在的绝对数据目录，配置本机模型/向量替身并监听 `127.0.0.1:18091`。运行期间不要对打印出的 `build` 目录重新编译；下一次脚本运行会创建另一份构建快照。

在运行终端输入：

- `restart`：重启 Spring 应用，继续使用同一 SQLite、同一模型/向量替身，不重新激活配置。
- `stop`：关闭应用及本机替身。EOF 也会关闭，不能通过关闭 stdin 的后台任务维持运行。

外部重新执行脚本会创建新的空白资料库，不复用上一轮数据。需要验证持久性时使用同一运行进程的 `restart`。

## 前端

在相邻 `ai-knowledge-web` 仓库的另一个终端执行：

```bash
npm run dev:workspace
```

使用命令打印的本机页面地址；workspace 同源代理转发到后端 18091。模型与向量为合成替身，页面导入只应使用新合成联调资料。

## 不启动服务的复现检查

```bash
bash -n scripts/run-wiki-integration.sh
bash scripts/run-wiki-integration.sh --build-only
```

`--build-only` 在新目录执行同一 HTTP workflow 测试并校验 classpath；测试使用临时端口，不占用 18091，不创建长驻联调实例的 `data` 目录。可在已有页面实例运行时使用，不覆盖其编译快照。

## 当前人工联调实例（历史定位，非脚本固定配置）

2026-10-09 主任务现有终端 session `51329`，数据目录 `/private/tmp/wiki-live-0056-20261009-final`，独立编译快照 `/private/tmp/wiki-0056-runtime-final`；前端使用 `npm run dev:workspace`。这些路径不由脚本读写，也不是可复用参数。请先由持有该终端的任务显式停止实例，再启动新的 18091 实例；不要杀未知进程或覆盖运行目录。

## 脚本实际验证

2026-10-09 11:52:26 +08，最终脚本 `bash -n` 与 `--build-only` 均退出 0。独立目录 `/private/tmp/wiki-0056-integration.eXAjEb/build` 中 `WikiWorkflowHttpTest` 1 项通过、0 失败/错误/跳过，Node 成功解析并校验本次 Surefire classpath、已编译启动器存在；实查同目录下 `data` 不存在。脚本未启动 18091，未停止或改写现有人工联调实例。

脚本的常驻启动分支与端口占用保护未在本次另起实例验证，避免与现有 18091 冲突；现有页面与启动器行为验收见主任务 verification，不从 build-only 通过推断完整脚本启动或实际浏览器通过。
