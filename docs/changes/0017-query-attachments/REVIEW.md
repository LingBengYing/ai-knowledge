# 0017 限定审查

当前：步骤3–4授权接线、v16 hash-only trace与有界HTTP已实现，真实三类媒体正常链已通过；本轮限定Standards/Spec审查主线阻断为0，完整冻结证据见[answers-verification](answers-verification.md)。真实provider质量、前端与生产仍未完成，完整目标保持IMPLEMENTATION。以下为步骤1–2历史审查，不用旧报告认证新源码。

步骤3–4最终制品独立审计已通过：604输入/1680默认/21 native/两组RED/旧身份多重性、14项证据SHA、JaCoCo全局统计和JAR443生产class逐字节一致，未闭合项0。原生处理与Spring/SQLite真实，模型/Milvus为loopback替身；不扩为云质量、前端或生产结论。

开工只读确认：Java当前四个答案入口只收文字JSON，无查询附件；全局multipart关闭。单图VisionModels的图像参数是库内待证明原图，不能偷换成查询附件。已有Audio/VideoCompilationService可复用且不写authority；旧资料UploadServlet不可用于临时查询附件。

审查按Standards/Spec两轴执行：Spring层级、小Interface和真实Seam；原问题/查询提示/库内证据三者隔离；完整范围先验、总budget与终态复验；附件hash-only trace；完整模型响应；无附件兼容；真实HTTP/native与源码/旧用例身份绑定。输入/匹配Module不等于附件HTTP已交付。

## 实际红绿

- 16:29:12，编译成功后的Domain/Preparation行为RED：12项，8 failure / 4 error / 0 skip。仅新stub没有实现合同，不是编译失败。
- 16:32:04，ranking行为RED11项，7 failure / 2 error / 0 skip；native另1项error。native已经先完成真实Tesseract PNG与FFmpeg WAV/双字幕轨MP4检查，在prepare stub处失败。
- 16:34:18，输入编译GREEN：12项Domain/Service与单列真实native1项全部通过。完整PCM请求逐包字节、每张原图、双字幕轨最后cue、采样和hash变动均有实际断言。
- 16:35:22，追加旧文字C1兼容回归RED：Domain6项，1 failure。旧AnswerCommand接受U+0085，新PreparedQuery的retrieval校验误拒；只修新谓词，保留原问题UTF-8/控制字符合同，没有放宽旧测试。
- 16:36:41，ranking GREEN11项通过，实际loopback检查PNG/JPEG角色/bytes、候选完整性与请求限制。

## 独立Standards / Spec审查

两名非对应实现代理分别只读审查preparation四个生产文件/两个测试与ranking三个生产文件/两个测试，主线阻断均为0。Spring layer-first保持；无新authority写入、权限框架或进程管理；复杂编译复用原Module，真实双角色协议藏在小Interface后。原问题/检索材料/库内证据职责分离，完整文本、未选帧和字幕尾部绑定SHA，抽样仅影响查询原图列表。

ranking审查独立重算0016基线SHA确认VisionModels、FactVisionModels、OpenAiCompatibleVisionModels、ModelHttpTransport原字节未变。实际协议包含明确邻接role/index标记，完整JSON最终16 MiB限制仍由共用transport执行，匹配结果不能成为答案/引用。不存在新通配import、裸控制语句或日志泄漏。

该审查只覆盖内部Module，不宣称完整授权答案、终态事务、查询trace、HTTP、真实模型质量或生产通过；尚未接线的QA-09～12不当成已完成能力。

16:41:06默认1619/0/0/0、552格式及双80%通过；16:41:52单列native20/0/0/0通过，Node73通过。579输入与旧567原字节、旧1595精确测试身份及多重性保持，详见[verification](verification.md)及source-manifest；不以历史0016报告直接认证新增12文件。

最终非实现代理使用独立Ruby JSON/REXML/Digest与unzip核对，未运行生成helper、Maven、Git或网络、未修改文件：579输入路径集合和repo/build/hash一致，旧567不变/新增12/删除0；210默认XML与8 native XML、四组RED统计/hash准确，所有旧及RED身份/多重性保留。10项证据（8日志+JaCoCo XML+JAR）原字节SHA一致；覆盖率、552格式、73 Node、两个最终时间与文档吻合。JAR430个生产class与target/classes集合和逐entry字节完全一致，无缺失/多余/重复。未关闭制品审计项0；该结论仍只认证步骤1–2内部Module。
