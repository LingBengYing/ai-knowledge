# 0013 音频主线验证记录

音频编译与无页码证明输入Module已完成本地验证；音频知识库HTTP纵切仍为IMPLEMENTATION。不是音频已可上传、可检索或生产可用的报告。前端、旧服务/数据没有改动，云请求0，未推送/部署。

## 已执行的可观察验证

2026-09-10实际Temurin21.0.12.1+1-LTS、离线Maven3.9.9，在独立`/private/tmp/java-audio-library.8JDybu`副本执行，不使用工作树旧target报告。

| 验证 | 实际结果 | 边界 |
| --- | --- | --- |
| 初始Module RED，14:32:14 +08 | 43例，35失败/4错误/0跳过 | Stub不提供所需行为；其中一个新用例对旧否定理由的预期写错，保留旧`incomplete_evidence`语义，仅纠正新夹具期待，仍逐项比较新旧结果 |
| 4096 CP候选补充RED，14:34:29 | 1例失败，snippet长度实际0而非4096 | 保证音频完整候选尾部可证明，而不是截取前1200 CP |
| 解码/编译/音频Client及旧文字/视觉Client联合GREEN，14:45:15 | 59通过，0失败/错误/跳过 | HTTP为本机替身，默认decoder测试使用受控真实JVM子进程 |
| 全部TextGrounding行为文件及新增上下文GREEN，14:47:24 | 332通过，0失败/错误/跳过 | 包含中文/英文、正反例、全文冲突、条件、指令、程序及资源边界，不只跑新增用例 |
| 实际FFmpeg/FFprobe 8.1.1，14:50:33 | 2项显式native IT通过，0跳过 | 合成WAV→实际probe/decode；实际解码→本机multipart→完整编译，不证明ASR质量 |
| Node回归 | 73通过，0失败/跳过 | 既有UI/敏感文件检查，未做浏览器或前端改造 |
| 首次全量，14:52:44 | 986例中1项架构断言失败，其余通过 | Tool直接捕获ApplicationException违反依赖白名单；已删除直接依赖，纯本地上下文映射保留invalid_quote拒绝，不放宽门禁 |
| 分层修复后完整证明与架构，14:55:15 | 343通过，0失败/错误/跳过 | 纯上下文转换的类型依赖修复，原语义算法与架构断言保持 |
| 支持格式/Domain/编译合同及native再验，15:02:28 | 51通过，含2项native，0失败/错误/跳过 | 受控子进程补6种格式，准入补无ID3 MP3；完整PCM/转录合同、源SHA与阶段预算直接验证，无新增生产分支 |

首次联合GREEN尝试因执行沙箱不允许绑定本机测试端口出现`Socket Operation not permitted`，这是环境限制，不是模型协议失败；获准本机端口后同一代码59例通过，没有云请求或自动模型重试。

## 最终冻结结果

2026-09-10 **15:04:42 +08:00**，最后一次生产代码修改后完整`clean verify`通过：

- 1014项Java用例，0失败/错误/跳过；321个Java文件Spotless通过，全部原架构白名单断言保留。
- 行覆盖7986/8638（92.451956%）；分支4115/5114（80.465389%），原双80%门禁通过。未降低门禁、删除或跳过失败用例；native IT不混入默认覆盖率。
- 0012原942项用例的精确`className + Surefire name`和重复数量全部保留，旧测试/语料文件未修改。仅5个既有生产文件变化：共享HTTP multipart入口、GroundedQuote上下文注释，以及TextGrounding/EvidenceConflicts/ProcedureEvidence的真实上下文适配。
- 73项Node回归通过；2项显式native IT在15:02:28相关验证中再次通过、结果单列；没有浏览器或真实ASR质量验收。
- [source-manifest.json](source-manifest.json)绑定345个构建输入、执行日志、Jar、覆盖率与native报告摘要；[test-cases.json](test-cases.json)记录1014个最终精确用例身份。当前工作树与隔离构建输入0不一致，未缺失0012输入。
- 独立只读复核再次确认1014精确用例多重集合、原942身份、345输入双向SHA、Jar/JaCoCo/native报告均相符；不是只核对汇总数字。最终tracked与123个untracked文件敏感项检查均为空，`git diff --check`通过；89份Markdown中的556个本地文件链接有效（不认证外链或锚点）。

先前分支覆盖预检低于80%的缺口，通过补充已承诺格式和完整时间/PCM合同测试关闭，不添加新生产分支；范围仍是编译Module。限定独立审查见[REVIEW](REVIEW.md)，不解除完整0013或生产gate。

## Native输入与结果

`AudioDecoderNativeIT`生成16,321个PCM采样，实际解码逐字节一致，时长为1021ms。`AudioCompilationNativeIT`生成32,001个采样（64,002 bytes），通过真实解码与本机HTTP后保留三个分段：`[0,1000)`、`[1000,2000)`、`[2000,2001)`毫秒；中段转录为空，尾段只含最后一个采样，仍完整保留。

HTTP测试逐字段校验canonical WAV header与原PCM对应切片、multipart file/model、Authorization、POST路径和恰好3次请求。响应故意包含不可信start/end/segments，最终时间只来自采样；源SHA与decoder/ASR/compiler版本全部一致。不存在真实录音/人员资料或真实provider凭据。

## 规格进度与未验证项

- AUD-2/3/4：编译Module实现并有上述本地行为证据。实际native仅验WAV，其他格式目前为准入/受控probe测试。空转录拒绝不是VAD；非空转录不是准确性证明。进程/HTTP自身有deadline，编译总预算在阶段边界检查，详见[Interface](../../AUDIO_COMPILATION.md)。
- AUD-6：已实现并回归无页码完整上下文证明Interface，文字继续使用真实页。音频authority提供全文、摘录映射实际span与最终typed引用尚未接线，不能标记AUD-6全部完成。
- AUD-1/5/7/8：音频运行开关/上传、v8附表/索引完整发布、音频问答trace与原文件Range尚未实现。
- 真实ASR质量（CER、时间误差）、真实音频Milvus检索、浏览器播放器、说话人/声音事件、视频/联合事实、摘要及生产发布仍待后续。无新云授权时不挪用旧文本额度；不通过假页码、caption或合成结果冒充正式知识证据。

## 可重放命令

在独立副本使用JDK21与项目Maven设置；构建期间不替换运行中jar，不启动旧服务：

```bash
mvn -s .mvn/settings.xml -gs .mvn/settings.xml clean verify
node --test ui-tests/*.test.mjs scripts/check-secrets.test.mjs
RAG_AUDIO_DECODER_IT_ENABLED=true \
RAG_AUDIO_DECODER_IT_FFMPEG=/absolute/path/to/ffmpeg \
RAG_AUDIO_DECODER_IT_FFPROBE=/absolute/path/to/ffprobe \
mvn -s .mvn/settings.xml -gs .mvn/settings.xml \
  -Dtest=AudioDecoderNativeIT,AudioCompilationNativeIT test
```

IT配置缺失硬失败，默认`clean verify`不运行需显式配置的`*IT`；本次两项native结果单列，不混进默认用例数。
