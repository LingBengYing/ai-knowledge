# 原视频音画本机验证（2026-10-03）

原视频保存与整理、显式完整音画双路索引、VISUAL/AUDIO/JOINT完整文字问题、单窗口共同事实独立核验及原视频时间来源已接通。A实现模型/HTTP与真实Native正常链，B实现native/compiler/worker/v20，C实现authority/证明/生命周期；root接前端0018并串行执行共享门禁。

## 最终实际结果

| 核验 | 实际结果 |
| --- | --- |
| 完整 `clean verify` | 2567 Java，0失败/错误/跳过，BUILD SUCCESS |
| 格式 | 878 Java文件，0修改/跳过 |
| 原双80%覆盖率 | LINE 26181/(26181+1934)=93.1211097%；BRANCH 13948/(13948+3371)=80.5358277% |
| 前端 `npm test` / `npm run check` | 344全量及syntax PASS；86相关PASS |
| 后端 Node | 73 PASS |
| 输入绑定 | 后端894、前端55个完整输入执行前后字节不变 |
| 单列Native | 新视频音画与旧声音、原声向量、原图向量、查询附件、语音六套各1 PASS，未混入默认 |
| 制品绑定 | 660个完整Native生产class与最终target及JAR逐字节相同 |
| 旧用例 | 后端2268、前端324的名称及多重性保持；新增默认299、前端20 |
| 旧源码保留 | 372旧测试/资源全部保留，351字节不变、21必要schema夹具适配；42旧worker/index/protocol/lifetime字节不变 |

最终JAR为39330460 bytes，SHA-256 `8c118bfbc1b7a4a564e6fb2ed79853023a2bdd7f572c0d80dd6c453797a1dbdb`。前端55输入包括entry-public/deployment-tests文件，仅证明这些字节未变，不表示部署测试已运行。日志和精确报告位于工作区`.tools/video-audiovisual-verification`：`backend-full.log`、`full-surefire-reports`、`frontend-final.log`、`frontend-syntax-final.log`、六套Native XML与全部class inventory。交接入口`.tools/video-audiovisual-handoff`的冻结与独立制品审计只由实际SOURCE-MANIFEST、VALIDATION和sidecar认证；旧0028交接只读保留。

## 实际正常链与红绿证据

新Native用实际Spring、临时SQLite、FFmpeg/FFprobe跑七组合成MP4/MOV：30秒后分窗、真实非名义帧边界、VFR、声音尾窗、无音轨、短音轨、30000 time-base denominator/L240000非零epoch。源与所有输出完整RGBA逐帧SHA/PTS/duration及已知PCM拼接核对；声音含375ms前延迟、真实静音和末尾样本，不填尾音。所有MP4/WAV模型与双Milvus条目、完整receipt/hash/epoch和幂等身份相符；无音轨索引合法且无虚假WAV。

HTTP正常链验证Unicode原上传201/零ASR与零解码、真实四cap、缺完整范围409/零provider、显式空范围、三模式完整问题和独立verify、允许独立联合事实但拒绝缺关系支持、精确nested来源/事实ID及SHA、原片200/单Range206、重启来源零模型/零解码。Google和Milvus是本机协议替身，真实模型调用0；完整原媒体材料不认证实际供应商采样或语义理解充分。

双集合命中实际同一lease stripe的自死锁P2已重现：同名测试修复前8.3秒耗尽预算，修复后完整双路receipt通过。新worker逐路获取/释放独立lease，并共用一个总deadline；旧lifetime不改。证据为`backend-worker-lease-red-green.json`、原RED日志/XML及同名GREEN XML。

首轮完整2406项默认全部通过，但BRANCH13726/(13726+3593)=79.2539985%未达原80%，BUILD FAILURE。首轮日志、完整XML和JaCoCo全部保存。三个代理追加161个真实合同测试，299相关全部通过；最终完整构建通过。未降低门禁、删旧断言或排除新生产类。

新long-hold夹具第一轮存在重叠区间，修正为实际连续长hold后保持原unsupported断言；新有理epoch夹具首轮encoder量化为2.002秒，显式固定encoder timebase后保持60000PTS/L240000与PCM延迟断言。前端旧harness缺新模块及新fixture路径/ID长度错误保留，均不计产品RED。真正前端代理缺路由RED是在旧0028只读副本用正确夹具重放，当前四个同名用例GREEN。

## 审查、扫描与未验范围

A的最终限定审查绑定当前authority/证明/管理接线和合同源码，前端8产品文件另有SHA绑定；C核native/worker/protocol/schema并指出上述已修复lease问题。原预格式化报告保留，不能挪用其旧SHA认证最终源。旧测试/生产保留及最终补测报告逐文件绑定；独立制品审计在冻结后读取实际包执行。

两库tracked/history secret检查与全部候选source扫描单列。完整候选扫描保留6个合成夹具标记（5个旧项、1个复用公开literal的新请求cookie），逐项绑定源/旧基线SHA审阅，无实际私人密钥；不修改扫描规则，也不把它写成零发现扫描。最终范围和结果以实际scan inputs/review/manifest为准。

执行仅本机合成材料/临时库/loopback协议替身。新切未部署；已核对的部署记录为20261003-voice-tags，其JAR不同且不含新原图/原声向量、声音库或本切。网页由用户验收，原ASR真实失败、实际Google/Milvus语义质量、浏览器封装兼容/非零epoch定位、一般容量、发布及新视频参考附件仍开放。usage/计费取消，无凭据/旧业务数据/服务器/Git写入，完整目标active。
