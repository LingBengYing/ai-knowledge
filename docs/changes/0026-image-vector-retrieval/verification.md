# 0026 原图向量检索验证记录

2026-10-03，本切本机协议、构建、查询、显式native正常闭环和完整门禁通过，最终制品绑定已归档。本记录依据工作区`.tools/image-vector-retrieval-verification/backend-final-evidence.json`及对应实际日志，作者只读取证据、编写本文，没有重新运行测试。行为合同见[spec](spec.md)，分工与限定复核见[plan](plan.md)和[REVIEW](REVIEW.md)。

## 范围与执行方式

本切独立默认关闭`rag.image-embedding.enabled`，仅在development/test与字面loopback边界、既有indexing/visual/answers/query-attachments装配有效时开放。真实已发布PNG/JPEG由当前owner/editor显式建立原图向量；reader只读状态。图片Client、collection、profile及generation独立于文字索引，旧文字publication和来源保持。构建冻结原publication/source SHA/evidence与物理映射，远程工作在事务外，封存短事务重验当前权限、完整原图和回执；已有同profile有效回执不重复调用。

协议依据为根任务2026-10-03只读核对的[SiliconFlow Embeddings API](https://api-docs.siliconflow.cn/docs/api/embeddings-post)。Adapter使用单对象`input.image`的完整canonical裸base64，显式`model`、`encoding_format=float`和`dimensions`；不发送caption、URL或data URI。返回严格核对model、单个index=0 embedding、指定维度、有限float32及非全零。模型profile绑定协议、原图策略、endpoint/model/显式版本/维度，不绑定密钥；`imageTarget.embeddingIdentity`和`modelRevision`均为Client完整profile，HTTP `profile_fingerprint`为独立projection identity。

查询仅在IMAGE模式、存在查询图片且新模块有效时使用独立DENSE_ONLY召回。先冻结完整selected/all scope并要求其中全部图片具备当前profile回执，授权workspace/doc-generation过滤先于top-K；候选由authority映射到原库图，再RRF融合。向量、查询图和caption不成为事实或引用，最终仍由库内原图draft/verify及既有来源回读证明。

后端Maven/Spotless、相关回归及显式native均由根任务串行执行，没有代理并行写共享target。仓库为`github/ai-knowledge`，加载工作区`.tools/env.sh`并使用离线Maven、仓库`.mvn/settings.xml`和工作区`.tools/m2`。Client RED独立副本显式使用绝对缓存路径，不套用仓库的相对路径。完整门禁为`clean verify`；默认suite不执行`*IT`，新图片、旧附件、旧语音三项native单列，不并入默认测试数或用其覆盖率代替完整门禁。

## RED、编译夹具与覆盖率补测

1. Client有效RED使用冻结语音源码副本、可执行最小Client stub和六项新行为测试。03:58:15 +08:00实际为6项、6失败、0错误、0跳过，失败涉及原图请求/维度、profile及配置/输入/响应/上游错误行为。原日志为工作区`.local/image-embedding-client-red.log`，同名副本归档于本切验证目录。它是执行后的行为断言失败，没有把新类缺失或无法编译计作RED。
2. 此前离线缓存定位错误造成父POM无法解析，保留`image-embedding-client-cache-error.log`，没有执行测试，不计产品RED。修正执行路径后得到上述有效RED，没有放宽断言。
3. 04:20:06与04:21:31两次相关验证停在`testCompile`，分别保留`backend-targeted.log`和`backend-targeted-final.log`。实际错误均为新`ImageEmbeddingConfigurationTest`中重载`text`工厂的方法引用使`registerBean`歧义。第一次误定位到另一构造器引用，修后原错误仍在；随后将实际工厂改为明确零参lambda。04:24:23又发现新`ImageVectorIndexingServiceTest` helper未处理`IOException`，保留`backend-targeted-final2.log`，仅修新夹具异常声明/处理。这三次编译中间态均不计产品RED，没有改旧断言或生产代码求绿。
4. 首次完整`clean verify`在04:34:52完成1892项测试，失败/错误/跳过均0，670个Java文件格式通过；但BRANCH为9855/12371=79.662113%，低于原80%门槛，实际BUILD FAILURE。LINE为93.162089%。保留`backend-full-coverage-initial.log`和`first-full-test-pass-coverage-failure.json`，不能将测试通过写成完整门禁通过。
5. 保持原LINE/BRANCH双80%阈值，三个实施分组共补21项新测试：配置/Client 6项，Domain/独立协议9项，查询/证据6项。覆盖预算和并发边界、维度/collection/profile漂移的零dispatch拒绝、取消保留中断、畸形provider包络、receipt/manifest/物理映射与generation约束、独立协议损坏和错误输出，以及完整scope与查询资格复验。没有降低门禁、跳过旧case或把机械测试当作新的产品功能；补测没有独立RED声明。

所有早期失败日志保留原结果。其余新Domain/Service/配置/HTTP测试没有单独有效RED的，不从编译失败倒推RED。

## 已完成验证与最终门禁

| 检查 | 实际结果 | 完成时间（+08:00）/证据 |
| --- | --- | --- |
| 初次相关回归 | 87项，失败/错误/跳过均0 | 04:26:57，`backend-targeted-final3.log` |
| 新原图检索显式native | 1项，失败/错误/跳过均0 | 04:28:33，`backend-native.log`、`backend-native-final.xml` |
| 旧附件与旧语音显式native | 各1项，失败/错误/跳过均0 | 04:29:57，`backend-native-regression.log`及两份归档XML |
| 补测相关回归 | 95项，失败/错误/跳过均0 | 04:44:26，`backend-targeted-supplement.log` |
| 最终后端完整clean verify | 1913项=1839旧项+74新增，失败/错误/跳过均0；671个Java文件格式通过，原双80%门禁通过 | 04:47:43，`backend-full.log`、`backend-final-evidence.json` |
| 后端Node回归 | 73项通过，失败/取消/跳过均0 | `backend-node.log` |
| 前端配套回归/语法 | 287项及语法通过；含取消反馈修正 | 前端0015验证记录、`backend-final-evidence.json` |

最终完整默认回归覆盖率如下，保持原LINE/BRANCH双80%阈值；三项显式native不混入这些计数：

| 指标 | covered | missed | 比例 |
| --- | --- | --- | --- |
| LINE | 18377 | 1322 | 18377/19699 = 0.9328899944159602（93.2890%） |
| BRANCH | 9925 | 2446 | 9925/12371 = 0.8022795246948509（80.2280%） |

完整报告、case身份清单和JaCoCo XML由collector归档；首次未过覆盖率的结果仍单独保留。补测只增加21项测试，最终默认总数由1892增至1913，没有删除或跳过旧case。

## Native正常闭环

`ImageVectorRetrievalMainlineNativeIT`使用临时authority库、合成PNG和loopback模型/Milvus协议夹具，真实生产HTTP、独立parser/index worker与本机OCR。仅显式读取native开关和FFmpeg/FFprobe/Tesseract路径，Spring测试环境移除系统环境/系统属性来源，不读取真实provider凭据。

单项IT先关闭新模块上传并发布旧红色原图及另一身份私有图，在误导caption和无目标文字召回的夹具中，旧视觉问答为`no_image_evidence`，图片embedding调用为0。随后同库重启启用新模块、发布新蓝色原图：GET旧图为十字段missing且不调用模型，私有图GET为404；完整选择两图而尚无向量时，查询图片问答明确`image_vector_required`且不调用图片模型。用户分别显式POST建立两图向量，返回available和不同generation；再次POST旧图幂等回读，总图片embedding仍为2，原文字publication未替换。

再以与旧红图字节不同的红色查询PNG和完整两图范围提问，实际发送第三次图片embedding；三次请求的原图SHA与两张库图、完整查询图逐一一致，包络只有规定字段。图片集合仅执行一次DENSE_ONLY搜索，预top-K过滤含两份授权资料及各自vector generation，不含私有资料。回答为red square且不含误导caption的999；draft与独立verify各一次，只取得库内原红图、完整原问题，不把caption或查询图作为证明。

citation绑定旧红图document/source SHA，typed GET来源回读同一citation，原图content逐字节相等。再次重启后来源与完整原图仍可读取，全部provider请求数不增加。该验证证明真实生产路径正确传递原图和封存来源；loopback向量是合成夹具，不证明真实供应商的语义召回质量。

旧`QueryAttachmentLibraryNativeIT`与`VoiceQuestionMainlineNativeIT`各单列通过，保留完整附件、语音确认后旧问答及来源正常路径。它们和新图片IT不计入默认全量；最终collector已独立确认native时点生产class与最终JAR相等。

## 输入、旧测试与制品绑定

本切以`voice-questions-handoff`为旧基线。最终collector确认后端687个与前端44个输入清单完整，测试前后各自哈希均未变化。旧303个后端测试/资源文件中284个原字节不变，19个既有文件包含18项schema v17夹具迁移及`MilvusRestProjectionTest`追加DENSE_ONLY测试，详见`old-test-file-retention.json`。最终XML逐项核对旧1839个默认case的身份与多重性全部保留，没有删除、跳过或放宽旧有效断言求绿。

`old-index-protocol-preservation.json`已逐字节核对旧五个生产文件保持：`IndexClaim.java`、`IndexProtocol.java`、`IndexWorkerLifetime.java`、`ProcessTextIndexer.java`、`IndexWorker.java`。本切没有改变旧文字v3协议、8MiB界限或其生命周期，而使用独立ImageVectorProtocol。

最终collector逐个确认native使用的500个生产class与当前class及JAR对应字节一致，避免把native早期报告套到不同生产制品。最终`rag-java-0.1.0-SNAPSHOT.jar`大小38,842,704字节，SHA256为`2243a52da7f4e1b70a1f62e6f914bef8fa53bccb647c6c930ee38f0e6168d6bb`。collector同时确认旧已删除的`QueryAttachmentServlet.class`未恢复，既有`BoundedMediaQueryServlet.class`包含在已绑定生产class中。

## 未验范围与部署边界

本切真实provider/付费模型调用为0；没有读取真实凭据或旧数据，没有Git写入或部署。真实图片语义召回、真实Milvus、真实ASR质量和浏览器完整业务闭环不由合成loopback/native结果认证。页面继续由用户验收，usage/计费开发继续取消。

已部署状态以本切`deployment-observation.json`的只读本地发布记录为准：当前为`20261003-voice-tags`，对应旧语音冻结JAR SHA256 `590a53daedc9ff17cf991f71f54184bf6158398c2f553911f76a7548d791385b`，已包含标签、语音及原PDF能力。先前0025记录中的“仍部署PDF、不含标签/语音”属于当时状态，本切不沿用。既有协调者的入口/空状态检查不等于上传、转录、建议保存、问答质量验收；真实ASR正确性仍未解决。0026原图向量新实现尚未部署，本机制品与当前线上版本明确区分。
