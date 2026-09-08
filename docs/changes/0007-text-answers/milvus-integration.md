# 真实 Milvus 集成：执行边界与结果

状态：真实 Milvus 2.6.22 首轮及4096边界、卸载/显式重载补测通过（最新2026-09-08 09:04:14 +08:00）。只认证下述合成数据契约，不代替0007整体、真实模型质量、一般容量或生产验收。机器可读结果见[milvus-integration.json](milvus-integration.json)。

## 2026-09-08 补测

- 沿用同一隔离实例，健康复核通过；执行前冻结原 `b443a244…` 测试与工件，归档SHA256 `f256a5641622e12d0beced4d75b2827da70f5b9977eceb012f24ecc5a0e7378b`。旧测试和断言完整保留，以下结果绑定扩展后的测试SHA256 `800339486a4639efc677a70a469d101f60dcd6c01eb2724dc480341490af01e3`，不覆盖历史测试身份。
- 同generation的4096条中文/英文/emoji短正文、4维含相邻float值的向量，经16条/批公开upsert后完整verify通过；追加第4097条，再验证原manifest必须且实际抛出 `projection_invalid_response`。首次完整验证在相同60秒/4MiB预算下成功，超时、限长、传输错误不会满足负例。该方法53.965秒，不是最大正文、维度或吞吐容量验收。
- release后、新reader.prepareSearch后及reader.search明确失败后，服务器状态均为 `LoadStateNotLoad`；未发生自动加载。显式writer.initialize后有界等待 `LoadStateLoaded`，完整回执保持且dense/BM25恢复；该方法5.596秒。没有flush、进程重启或磁盘故障实验。
- 原范围/双路方法4.153秒；三个真实IT共63.719秒，加35项既有相关测试共38项，0失败/错误/跳过。09:04:14构建成功，213文件格式检查通过。冻结JUnit XML SHA256 `0dbcb1605b7bc117e49614c427222bb37f0006a261d2ab7f884fb09c615cff28`。
- 运行后专用数据库只读collection list仍为 `code:0,data:[]`；只删除测试自己拥有的随机集合，VM/卷/数据库保留。本批无生产Java修改。authority_layering精确diff归档并独立核对当前源码/XML/日志，Standards与Spec限定各0 finding。

状态名称按[固定2.6.22服务端](https://github.com/milvus-io/milvus/blob/v2.6.22/internal/distributed/proxy/httpserver/handler_v2.go#L732-L880)核实：`LoadStateNotLoad`，不是个别文档示例的NotLoaded。以下各节为2026-09-07首轮历史；其“未验证4096/卸载”边界由本节补充，不追改历史证据。

## 固定输入

- 本批前像包含635项通过所用源码、Surefire/JaCoCo报告及JAR；归档SHA256为 `d60a6e3de98e16b002cfb44554b87e1fb449acdef506f46849a797495bcdc7ff`。235项既有构建输入在原工作树/干净副本逐项核对一致。
- Milvus版本 `v2.6.22`；官方Registry manifest中唯一 `linux/arm64` 为 `milvusdb/milvus@sha256:defbbd212727ff06507acc8b4a21531d269be85d467ea304fb1c1eff514d00e9`。主线程实际获取与另一审查者独立解析一致；这只证明镜像选择，不是已启动或测试通过。
- 固定tag的[官方嵌入脚本](https://github.com/milvus-io/milvus/blob/v2.6.22/scripts/standalone_embed.sh)仍引用其他镜像版本，不能直接执行并称为2.6.22。测试实例使用上述摘要、内嵌etcd及local存储，仅供合成数据集成，不作为生产部署方案。
- 不启动旧Docker Desktop，不使用旧集合、数据库、业务文件或聊天凭据。运行包、缓存、VM磁盘与配置放全新临时目录；无home/tmp共享挂载，只使用新实例的专用数据库和随机集合。

## 测试入口

[MilvusLiveIT](../../../src/test/java/com/evidence/rag/client/vector/MilvusLiveIT.java)使用真实 `MilvusRestProjection`，不调用模型。需要操作人先创建新隔离实例和专用 `java_it_*` 数据库，通过进程环境提供：

| 变量 | 约束 |
| --- | --- |
| `RAG_MILVUS_IT_ENDPOINT` | 字面loopback HTTP与显式独立高端口；拒绝默认19530/9091端口、远程地址及URI凭据 |
| `RAG_MILVUS_IT_DATABASE` | 预建 `java_it_*` 数据库，拒绝default |
| `RAG_MILVUS_IT_TOKEN` | 如实例启用鉴权则从secret环境传入；空值仅用于明确隔离的无鉴权测试实例 |

```bash
mvn -s .mvn/settings.xml -gs .mvn/settings.xml -Dtest=MilvusLiveIT test
```

缺配置直接失败，不使用assumption/skip。默认 `clean verify` 不选择 `*IT`，因此不能将默认回归通过写成真实Milvus通过。不要把已运行应用的target作为构建目录；使用干净副本和不变JAR。

## 验收内容

1. 随机生成collection，先确认不存在，公开Adapter初始化后才记录本次拥有清理权限。
2. 通过公开upsert写入同文档新旧generation及其他文档；使用含非二进制精确小数的4维合成向量，逐manifest即时回读精确ID、正文及float32摘要，不靠sleep/flush掩盖可见性问题。
3. 测试专用REST helper在本次集合中种入同document/generation的跨组织行并确认其确实存在；此操作发生在完整验证之后，只用于检索负例，不再将早先receipt宣称可发布。
4. 新建只读Adapter执行prepareSearch与dense/BM25查询；断言精确合法physical ID和双路RRF贡献。空scope、source revision误作generation、其他document的generation均不得命中。
5. 只清理本次成功初始化的随机collection并确认消失；初始化部分失败时不擅自drop，保留随机名称供隔离环境的拥有者处理。没有list/批删或既有集合清理。

## 已执行证据

- 2026-09-07 16:22:03 +08:00：实际JDK21编译与显式IT执行，1项因缺少 `RAG_MILVUS_IT_ENDPOINT` 明确失败，0错误/跳过；这是配置保护检查，不是产品协议失败或真实集成结果。
- 16:28:59 +08:00：干净副本默认 `clean verify` 通过635项，0失败/错误/跳过，213个Java文件格式检查及既有双80%覆盖率门禁通过。默认构建没有执行新IT，不计为636项；该次用时6分6秒，不作性能基准。
- 最终测试源码SHA256 `b443a2444aa88c12e296e78995549ff893ec187da8bca20fcdd7fa5022302a03`：独立审查发现top-1可能掩盖跨组织并列行，已将正例查询limit改为2且继续严格断言唯一合法ID及双路分数；Standards/Spec限定复核关闭。16:35:31实际JDK21再次格式/编译成功。上述635全量发生在此测试专属加强之前，不能声称已执行真实IT；235项既有构建输入哈希仍全部未变。
- 官方Colima0.10.3/Lima2.2.0与独立Linux镜像SHA已完整校验，镜像超时续传后校验通过。VM于16:34:49完成初始化；新Docker29.5.2/aarch64，4CPU，约7.74GiB可用内存。实际findmnt没有virtiofs/9p/sshfs主机共享目录，宿主Docker上下文仍为desktop-linux。
- 新VM的Registry请求在DNS解析阶段失败；同Registry经现有无凭据宿主代理得到正常401挑战。按[Docker官方daemon代理配置](https://docs.docker.com/engine/daemon/proxy/)只为新空引擎保留原配置并增设代理，配置验证后重启；固定摘要镜像已开始正常分层下载，未将DNS故障当成Java产品失败。
- 未更改Java生产代码、前端详情页、旧服务或生产gate；未推送或部署。

## 真实执行结果

- 16:44:50新容器启动，启动日志明确 `Version: 2.6.22`、`GitCommit: 830fdd6806`；实际RepoDigest与固定arm64摘要一致。健康为healthy，宿主healthz返回200；实际监听仅127.0.0.1:19531/9092，只有新命名数据卷，无主机bind mount，未发布etcd端口。测试容器7GiB内存上限，不以该配置作生产容量结论。
- 16:45:37首次真实IT通过，1项、0失败/错误/跳过，测试4.848秒。专用库由root预建，测试创建随机集合，完成写入、即时float32/正文摘要验证及双路范围查询后精确清理。
- 16:46:37仅在临时构建副本故意移除workspace过滤，IT在首个双路查询的scope响应校验处真实失败（1 error），不是连接/配置故障。实际工作树生产文件未改；随后恢复临时副本，235项既有输入在两处重新逐SHA核对一致。
- 16:47:38恢复后真实IT重新通过（4.180秒），同时执行完整MilvusRestProjectionTest 28项和MilvusSearchPreparationTest 7项，共36项，0失败/错误/跳过，213个Java文件格式检查通过。这35项已包含于635项默认回归，不重复相加为新测试。
- 16:48:27只读查询专用数据库collections/list为 `code:0,data:[]`，成功与故障注入运行均未残留测试集合；数据库/新容器/VM保留用于后续集成，不触碰其他资源。错误CLI探针 `milvus --version` 不被该二进制支持，实际版本由启动日志和镜像摘要证明，不将此探针记为产品故障。
- 本批未调整生产Adapter契约或放宽schema/摘要校验。Node73项重新通过，历史及含新增测试/文档的候选快照敏感规则扫描无发现；扫描是有限规则，不是凭据绝对不存在的保证。

边界：本轮没有真实embedding/rerank调用，没有固定PDF→真实模型→实际Milvus→问答全链路；没有4096+1完整manifest、flush/reload、冷加载、重启或容量验收。只读prepareSearch的零写入行为仍由本机协议测试检查，本次真实新reader成功不等于通过服务端写审计证明所有请求只读。真实provider质量、网页、多模态与生产仍按后续gate推进。

独立复核：authority_layering核对三次日志/XML、源码/manifest哈希与运行捕获一致；Standards/Spec限定问题已关闭，详见[REVIEW](REVIEW.md)。不将测试编写、root执行和独立只读复核混为一项。
