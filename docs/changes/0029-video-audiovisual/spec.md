# 行为合同

2026-10-03。协议/时间typed合同见interfaces.md与wire-contract.md；已冻结，仅当前实现后的实际验证可报告通过。

1. 默认关闭，独立development/test及loopback配置，须启用既有ingestion；无需旧ASR、video、sound或answer开关。真实原文件上传仅登记documents、owner ACL及独立original：0解码、0模型、0ASR。旧真正视频可显式构建，无自动补建或重试。
2. 源≤20MiB/600秒；全部真实帧和完整16kHz单声道S16LE声音按共同视频epoch编译。无音轨为显式absent，真实静音保留每个样本。连续MP4去除音轨，保留全部实际帧、分辨率和时序；具体实际PTS边界以本机prototype收口后的接口为准，不能以FFmpeg命令名称推定精确裁切。
3. 单MP4≤8MiB、完整组MP4≤64MiB，单窗口≤30秒，PCM完整总量≤19.2MiB；超限整次失败，不缩放、漏帧、截音或替换为截图。所有父源、epoch、编译策略、窗口actual时间、完整帧manifest、MP4/PCM/WAV SHA绑定。
4. 视频与音频分别进入独立collection，必须是同一显式embedding模型/版本/维度/策略空间。索引不调用描述或ASR；向量/分数只召回。只对真实存在的材料投影；不存在的路保留独立absence identity，禁止空VerifiedRevision或虚假静音receipt。新generation隔离迟到写入，全部有效路完整验证后才在短authority事务封存单publication。
5. all/selected≤128保留全部当前授权真实视频，包括未引用资料；显式[]不扩大。任一范围资料缺完整当前索引，409 video_av_index_required且0decoder/provider，无伪trace。每路全部候选先authority hydrate，再按同窗口融合≤64；非法尾候选也不能丢弃掩盖。
6. 显式VISUAL/AUDIO/JOINT模式。前两者仅发送各自实际材料；JOINT窗口必须同时有画面和声音。每个窗口须覆盖完整原问题，不跨窗口拼接半问题。服务端共同fact ID绑定question SHA、ordinal、完整claim及requirement；独立verify每次重新发送完整问题和同窗全部所需原媒体。
7. facts有VISUAL/AUDIO/JOINT需求；关系fact必须JOINT，各fact依需求有相应核验贡献。JOINT完整答案画面与声音各至少一项贡献，独立组合问题可分别对应两个事实。不以两项互不关联的观察推出同物发声或同步关系。任一事实不支持/覆盖不全整窗口不合格；所有候选检查完，完整事实集合不一致保守拒答。
8. 初始/每次外部操作前后/最终单事务均复验全部范围、当前ACL、父原件SHA、双路或absence资格与配置。Trace仅保存哈希/事实/贡献和全部范围；metadata-only范围不一次加载128份原件。Query按父资料逐个有界重编译，与完整封存组对账后才用真实媒体作证。
9. 来源仅原actor+workspace读取，重新核全部trace scope、事实proof、当前源/ACL/profile。content返回同版本原文件，200及单Range；重启0模型/0解码。服务器窗口时间不冒充模型逐帧或词级定位。
10. 完整deadline默认120秒，并发≤2/同资料build≤1。IPC≤128MiB/子JVM≤512MiB；实际provider JSON≤14MiB，序列化后dispatch前校验。失败取消、reap/cleanup及迟到结果隔离沿原生命周期。撤下后同事务擦除新raw blob，immutable元数据/receipt/trace保留。

验收需实际合成连续运动MP4和不同声音对照、非0epoch、音轨延迟、真实静音、实际尾样本、无音轨与纯声音尾窗；真实HTTP/SQLite/FFmpeg和原媒体model wire同时跑通。Loopback不能认证真实音画语义质量。

已实跑frame-aligned六组合成及16/16重复MP4 SHA，完整原型证据位于准备目录。v1对gap/overlap、单帧hold超过配置chunk、无法合法frame-boundary分组整次明确unsupported；最终新Native已实跑time-base denominator=30000、L=240000的非零epoch与音轨延迟；其他L、其他pixel formats与大容量仍须按实际native核，不从原型或该夹具扩大通过。音轨早于首视频epoch明确unsupported，不裁掉真实前部样本。MAX_WINDOWS=1201（变量frame-aligned窗不能继承Sound600上限）。这些处理限制仍需后续支持，不删除主目标的视频范围。
