# 0016 review

状态：步骤 1 输入/编译历史冻结保留；步骤 2、3 已实现完整本机后端字幕闭环。当前冻结证据以 [library-verification](library-verification.md) 为准，不宣称真实模型质量、前端或生产完成。

检查重点：

- 原始字幕包与视频 epoch 的同时间轴、全部轨/空清屏/重叠/尾部不丢失；非法/超限不能伪造无字幕。
- 原有 decoder/compiler 构造、哈希和旧音画/OCR/摘要行为保持；Runtime 仅在完整 authority/查询/摘要接线后显式 opt-in。
- 发布必须包括字幕完整计数；字幕不写 ASR、不成为旧 joint 的声音证明。
- 最终证据应绑定实际输入与制品，保留旧测试身份/多重性，分开 native/loopback 与真实云/网页/生产层次。

独立审查与结果在各实际冻结步骤补录，不提前预写 PASS。

## 步骤 1 独立限定审查

非实现代理对比 0015 hierarchy 冻结输入，按 Standards / Spec 两轴只读审查，未执行 Maven/网络或修改文件。

- Standards：生产 Domain/Worker/Service 分层、既有 NativeMediaSession 复用均未发现阻断；新增测试 G02 wildcard import/控制语句大括号项已经实际修复并复核。
- Spec ST-01～06：完整轨/包与空清屏保留、精确有理时间、旧 revision 兼容、模型前缺产物/静默丢弃拒绝、新 v3 未提前在 Runtime 激活均符合本步合同。主线阻断 0。
- ST-07～10 未完成；暂未接持久化、索引、问答、摘要和来源 HTTP 是明确步骤边界，不拿本步 native 成功替代完整闭环。
- 最终真实结果见 [verification](verification.md)：1541 默认 Java、单列 14 native、520 格式、双 80% 和 73 Node；后续制品 SHA/用例身份核对单独记录。

独立制品复核已完成：547 输入 repo/build/manifest、196 默认报告和 native 报告、case SHA/旧1485及全部RED身份多重性、覆盖率/日志/JAR全部一致；408个生产class集合与字节匹配。详见 verification 的独立复核节，不认证后续未接线步骤。

## 步骤 2–3 限定独立审查

并行代理分别审查非本人实现的 authority/publication、Runtime/摘要/native HTTP 与共享文字语义；只读、未执行 Maven/网络/Git 写入。

- ST-07：v15 完整 track/cue/header 与 native/authority manifest、OCR 显式合同、完整发布计数、混合物理 ID 分类，未发现未关闭主线阻断。旧 v1–v14 迁移方法体不变，旧 v14 历史迁移有自动化回归。
- ST-08～09：完整同轨上下文、独立 trace、服务器 typed 时间与原文件 SHA/Range 保持；字幕不伪造 ASR/frame/group。真实尾部更正反证发现后已最小化并修复，见下段。
- ST-10：短/长摘要完整枚举包括尾 cue，保留完整 publication/fingerprint/全部 ID/SHA，typed 来源不依赖模型回读；Runtime opt-in 与关闭旧行为均有验证。
- Standards：64 个新增/修改 Java 的限定 G02 与职责复核未发现新增 wildcard import、无括号控制语句或 Repository 模型调用；4 个新测试的规范项已修复。旧 VideoOcrMigrationTest 的既存循环不作为本批新增阻断，不扩展主线。公开读取 helper 为实际查询/摘要生产消费者使用，不暴露测试专用 SQL API。

### 已关闭的真实证明缺陷

完整轨尾部 `更正：星港项目的预算不是47万元，而是53万元。` 没有否决候选47，最小化确认同主体识别把中性标签当成主体一部分。复用 `SourceFields.statement` 统一比较用语，不改变原文字节或 CP 定位；policy 更新为 `java-text-grounding-v5-neutral-label-context`。

独立复核随后补出三条真实回归：`示例：`、`例子：`、`Example:` 不得被当成真实更正。先 RED 11/3 failure，再仅在更正比较时复用既有 `SourceInstructions` 排除示例/指令语境；不扩词表、不递归 TruthContext。15:54:28 完整相关行为 408/0/0/0 通过。最后逻辑等价的短路顺序调整发生在该定向运行之后，因此最终源码由之后的完整 clean verify 和 native 重新冻结，不能只引用早先定向绿色。

SourceFields/SourceInstructions 与步骤1原字节相同；TextGrounding 只更新版本号。最终默认报告、native、旧身份多重性及制品校验见 library-verification；不能以源码审查代替执行证据。

最终全枚举夹具适配亦经独立逐字比对：SynopsisDomainTest 与 SynopsisServiceTest 各只追加一个 `VIDEO_SUBTITLE` 时间类型条件；旧七类、全部正常/反例断言不变。API 文档与 Config/Controller/DTO/Repository 的只读复核一致，未将 cue 时间、raw markup 或本机替身扩称为逐词/画面/真实质量/生产验收。

最终独立制品复核 PASS：567输入、206默认XML/1595全绿、旧1541及全部RED身份多重性、native19、case SHA、14项日志/覆盖率/JAR摘要一致；227个XML内部子状态与总数一致，419个生产class集合和字节匹配。原双80%、540格式、Node73保持。详见[library-verification](library-verification.md)，本机后端范围内剩余审查项0；不认证真实云质量、网页或生产。
