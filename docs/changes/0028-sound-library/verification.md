# 验证 · 0028 独立声音知识库

2026-10-03最终clean verify通过：2268项Java默认测试，失败/错误/跳过均0；787个Java文件格式通过，原LINE/BRANCH双80%门槛保持，LINE22707/(22707+1644)=93.248737%，BRANCH12259/(12259+2854)=81.115596%。前端324、后端Node73及5项单列Native全部通过。803后端/49前端构建输入测试后无变，Native实际执行的全部586个生产class与最终target/JAR原字节一致。2042旧默认用例和299旧前端用例身份、多重性完整保留。

最终JAR为39107712bytes，SHA256 `cf2671bac3c9efcacda219a30dd9f3140d1a25b90adeed8ebcc9d157cf6f9988`。精确报告、输入、class/旧测试保留、失败归类见工作区`.tools/sound-library-verification/backend-final-evidence.json`及原件；交接入口`.tools/sound-library-handoff`。冻结及独立审计以实际SOURCE-MANIFEST/VALIDATION和独立sidecar为准，准备脚本不能充当通过证据。下文保留尝试历史。

本轮为独立声音主线，目标仍 active。三子智能体分别交付模型与HTTP、完整原声发布、授权检索与事实证明；root统一构建。声音描述只用于召回，回答必须从单个库内原PCM窗口核验完整问题。不同合格事实集合保守拒答。

初轮后端新增90项默认行为测试（A27/B43/C20）已执行；104项相关回归最终通过。完整clean verify的2132项测试全部通过，776个Java文件格式通过；LINE22521/24351，BRANCH11876/15113（78.58%），原80%分支门禁实际失败，初轮日志、全部报告和覆盖率原件保存在工作区`.tools/sound-library-verification/backend-full-initial-*`。三代理随后补有实际业务断言的边界测试，门禁、排除规则与旧用例保持。

相关初验曾发现Repository反向依赖tool层的真实架构失败，已将纯proof identity移至domain并保留tool安全过滤。其余初验失败是新夹具错误：解码后的非法路径预期状态不合旧AudioInput合同、跨包调用包内SQL API、Milvus替身collection/marker不合新target以及响应预算不足；分别修正夹具，不作为产品行为RED。实际尝试日志和报告均保留。

真实Spring/SQLite/FFmpeg声音Native初验1项通过，上传、完整索引、文字/参考声音问答、拒答、原文件Range与重启来源回读均有断言。初验PCM为常量信号，不能认证可听音调；最终夹具换成440/880Hz正弦音调和全零静音，与旧四条正常链统一重新编译执行后全部通过。

前端0017已执行：最终324项全量回归、JavaScript语法通过；模块9个真实断言RED→GREEN，实际DOM原3个断言RED→GREEN，原文件注册版本和完整声音能力门禁、纯声音发布不误触发旧入口均有独立失败与修复记录。两个代理的6个新HTTP测试在0027只读源码副本上全部实际断言失败，再在当前实现上通过。最初代理测试的external启动夹具漏entry-policy/config响应，三项external不能算行为RED；修正后的副本执行单列保留。独立审查又发现完整facts原字节预算与JSON转义/LF连接不一致，新增2项前端测试实际1项RED→12项GREEN后完整324通过。没有删除旧299项前端断言。

补充相关回归237项全部通过；新SoundLibraryMainlineNativeIT采用实际440/880Hz正弦音调与静音，加旧原声向量、原图向量、三媒体附件与语音共5项Native全部通过。第二次完整2268项中，仅旧ProcessImageOcrTest因PID文件可先创建后写入而读到空串出错；旧test和两个相关生产文件在失败前与0027字节一致，全部日志/报告/输入已保存。仅将测试夹具PID先写同目录文件再ATOMIC_MOVE发布，保留父端等待、真实PID、busy/close/进程结束和新admission全部断言，定向5项通过后完整门禁重新执行。342个旧test/resource中321字节不变、20仅schema适配、1仅同步夹具修正，2042旧用例身份及多重性须最终复验。

独立产品审查限定于完整scope、原PCM证明、来源和前端身份生命周期；发现的P2事实预算误拒已由root复现并修复，没有其余具体阻断。两个仓库tracked/history扫描为空；untracked四个精确合成夹具标记已审查（3旧、1新loopback cookie），新标记实际file/line/literal与旧baseline字节SHA绑定，扫描规则未改，真实凭据0。

未认证：真实Google/Milvus语义质量、真实ASR、网页和生产验收，原视频音画检索继续开发。页面由用户负责，本轮未访问浏览器、配置凭据或发起真实模型调用，未部署或执行Git写入；0027冻结包保持只读。usage/计费已取消，整体目标继续active。
