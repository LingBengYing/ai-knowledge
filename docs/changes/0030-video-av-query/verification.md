# 0030 本机验证：原视频参考问答

2026-10-03，后端0030与前端0019实现并通过本机完整门禁。最多三份原视频参考按VISUAL/AUDIO/JOINT实际媒体帮助召回；答案仍由当前完整授权库内证据证明，来源指向库内原视频。未部署，网页由用户验收，新增真实模型调用0；完整目标继续active，usage/计费保持取消。

## 执行与制品

root在确认A/B/C源码READY后统一串行Maven/Spotless；子代理分工实现和独立审计，不并发写target。离线命令使用工作区已准备Java21、固定本地Maven依赖及项目/全局隔离settings：

```sh
mvn -B -ntp -o -s .mvn/settings.xml -gs .mvn/settings.xml \
  -Dmaven.repo.local=../../.tools/m2 clean verify
node --test ui-tests/*.test.mjs scripts/check-secrets.test.mjs
```

实际默认352份Surefire XML：2640 tests、0 failures/errors/skips；894份Java格式通过，原pom/架构/LINE及BRANCH双80%门禁未修改。LINE为26652/(26652+1945)=93.1986%，BRANCH为14200/(14200+3441)=80.4943%。相关回归389项另单列PASS；前端最终370、syntax与后端Node73项通过。

完整后端910输入（src、.mvn、pom）和前端57输入（含entry/deployment文件的字节范围）执行前后完全一致。六项显式Native均各1 PASS、0 skips：VideoAvLibraryMainlineNativeIT、SoundLibraryMainlineNativeIT、AudioVectorRetrievalMainlineNativeIT、ImageVectorRetrievalMainlineNativeIT、QueryAttachmentLibraryNativeIT、VoiceQuestionMainlineNativeIT。全部668份生产class的相对路径和字节SHA在两次Native组、最终target和JAR中完全一致；不能只据几个抽样类声称制品一致。

最终`rag-java-0.1.0-SNAPSHOT.jar`为39,356,541 bytes，SHA-256为`754ef7dfbc485d0cfce6527d3025b0b07a81698f4e15448170d96301eef57da8`。输入、XML、case清单、覆盖、class/JAR及扫描证据保存在工作区`.tools/video-av-query-verification`。新交接入口为`.tools/video-av-query-handoff`，冻结及独立审计仅以其实际manifest/validation为准；准备脚本不构成冻结证据。

## 可观察行为

- 真实合成FFmpeg/FFprobe连续MP4及完整PCM，包括非零有理epoch、短轨、音频尾窗和无音轨。三参考JOINT实际全窗visual/audio调用顺序与每份完整媒体SHA核对；文字向量故意无命中，媒体向量仍召回库内证据。查询参考不进入draft/verify/citation，不调用ASR/OCR/描述，不作为资料或投影持久化。
- 索引缺项409、越权404、显式空范围安全拒答，均在查询decode/provider之前处理，空范围完整记录全部not_prepared输入。AUDIO/JOINT含任一无音轨参考整批拒答且0provider；VISUAL无音轨、AUDIO有理时间参考成功。资源限额逐件检查，超限不再解码后续参考；未准备整批全部not_prepared。最终尾窗/candidate/半问题/贡献不足、配置变化/完整scope撤权/超时/取消保持拒答。
- exact三字段新envelope和11字段receipt、同批status、原source SHA、实际compiler/profile/embedding、whole manifest/digest及v21两侧表同事务封存已核。重启后旧来源和新参考问答来源均核同版本完整原文件SHA，0query decoder/provider。旧无附件七字段DTO、v20定义和24旧guard保持。
- 前端新cap独立于旧附件能力；真实File一次读取同时编码并计算SHA，完整selection/问题及三个模式保留，身份/范围/问题/附件/取消/迟到响应沿epoch隔离。两个代理只给精确新route 28MiB/180秒预算，保留普通route边界、无自动重试。DOM夹具不等于用户网页验收。

## 红绿、纠正与保留

feature RED在0029完整冻结源码的独立副本，仅追加同一真实Native新endpoint行为：原视频正常上传/索引已通过，POST新route实际404导致1失败、0errors/skips。随后当前真实Native新route200并完成召回/证明/来源；RED与最终测试间有扩展和顺序纠正，未宣称整文件SHA相同。

query侧车严格整数读取有真实行为RED：独立副本仅恢复未加严格类型检查的Repository，18个损坏输入中5个错误放行来源（fractional-count/window/ordinal/audio与overflow-count），其余13通过。临时数据库先验证正常guard拒绝，再仅在损坏夹具中关闭guard/check约束，保留原digest；不修改实际数据库。严格Integer/Long及范围读取后相关21项全部通过，完整2640也通过，旧AuthorityRows字节保持。未把静态发现或编译问题称为产品RED。

首次当前Native失败为root夹具顺序：新增问答放在旧“索引不调用证明”的断言之前；仅移动新增块至全部旧索引断言之后，保留原断言，第二次同真实媒体贯通PASS。首失败XML/日志、修改前测试和纠正记录均保留，没有通过删除断言求绿。两份RED绑定生成时的路径/用例名处理执行错误另有记录，不计为产品失败。

旧405份后端测试/资源全存在：381份字节不变、22份fresh-schema版本适配、1份新实际cap断言适配、1份Native扩展。旧2567默认用例身份及重复次数完整保留，前端旧344亦完整保留。旧worker32份生产/36份测试和4份具名寿命文件、pom/.mvn/架构/敏感扫描规则保持。49份本切Java经显式imports及控制括号静态复核；仅登记一处与旧冻结完全相同的VideoOcrMigrationTest存量for写法，本切未新增此问题。

A指出DTO及Web不能依赖Tool，最终mapper→compiler.validateQueryInput→Tool沿原分层，compileQuery重复验证真实envelope；未放宽L01/L07/G02。B按逐件预算/模态失败提早停止decode，C保持独立canUseVideoAvAttachments默认false和旧拒绝用例。新增manifest只声明完整准备媒体身份，不宣称向量执行完成摘要；所有向量调用由实际行为断言核对。独立输入/旧case/冻结制品审计以对应实际JSON为准。

## 未验证范围

全部新provider请求仅本机合成服务器；不代表真实供应商逐帧语义完备、原声音理解、独立证明可靠性或Milvus生产检索质量。真实ASR旧问题未改写为已解决。未读取凭据、旧业务数据库/服务器，未改Git HEAD/index、部署或旧冻结包。已核部署记录仍为20261003-voice-tags，不含本切及原图/原声向量、独立声音/原视频新能力；用户负责完整导入、整理、问答和来源页面验收，部署/Git归原责任方。
