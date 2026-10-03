# 0030 行为合同

Q01 新精确POST `/v1/video-av-query-answers`；认证、Origin、Host与旧JSON信任边界相同，无query。严格JSON只允许question/mode/attachments/document_ids?，必需前三项；mode精确VISUAL/AUDIO/JOINT，完整问题≤4096 UTF-8，selection沿旧完整128上限，[]永不回退全库。附件1..3，每件只有filename/media_type/content_base64，canonical base64，严格UTF-8/重复/未知字段/尾JSON拒绝；只接VIDEO四MIME及相应扩展/真实magic。总原字节≤20MiB，JSON≤28MiB。接收30秒，统一处理预算≤120秒、最多2在途，复用BoundedMediaQueryServlet；原普通JSON/无附件路不扩大。

Q02 任一参考解码/外部调用之前，完整all/selected库内scope（包括未引用项）必须当前ACL、source、profile及两路verified/ABSENT receipt合格。缺一份索引409且0decoder/provider；空scope安全abstained且0decoder/provider。每次准备/embedding/search/proof前后和终态事务复核完整scope、配置与同一deadline；无撤权/变更/超时/取消后的晚成功。

Q03 每份参考一次compileQuery，不伪造DocumentOriginal，不入库/投影，不调用ASR/describe/OCR。沿原compiler/decoder精确epoch、全帧manifest、连续真实无音轨MP4及全部原PCM，包括静音、延迟、单sample尾、短轨和音频尾窗。窗口临时ID按sourceSHA+compilerRevision确定，与资料revision无关。每份沿原20MiB/600秒/1201窗/clip8MiB；全部参考准备完且整体预算合格之后才有首provider。整批windows≤1201，clips合计≤64MiB，PCM实际bytes≤19,200,000，逐件BigInteger精确ceil(durationTick*1000/L)之和≤600000。这是媒体payload限额，非JVM峰值承诺；不得部分准备、截尾、选帧或重复解码来避开预算。

Q04 VISUAL每件必须有实际visual；AUDIO和JOINT每件还必须hasAudio及audio count>0，否则整体abstained `query_modality_missing`，0provider。单窗口nullable的实际缺路保持；JOINT音频尾窗可只走audio，短音轨后的画面窗可只走visual，不造静音/帧。任一编译失败或整批预算超限整体拒答，`query_preparation_limit`用于aggregate资源超限。不静默丢弃缺轨附件。

Q05 完整问题embedText走模式适用的库内集合；每参考每实际适用MP4/PCM窗口分别embedVideo/embedAudio及对应集合DENSE_ONLY。每次≤64个candidate全部authority hydrate（包括最后一项）之后才RRF；key为publication+window，排名贡献1/(61+index)，各调用累计，稳定key打破同分，全部参考及尾窗处理完才最终≤64和证明。v1保留长参考更多召回票数的语义，不暗中抽样/去重/归一化。任何尾窗/provider/candidate失败整体abstained；预算不够不发布部分答案。

Q06 原完整文字问题与库内完整同窗媒体独立draft/verify、事实身份、贡献与关系证明规则不变。参考媒体、原件名、参考事实不进入证明或citation；附件独有/库内半问题/JOINT缺贡献仍拒答。沿0029原typed来源及完整同版本原件，来源重启不需query媒体或provider。

Q07 新返回精确三字段 `{mode,result,query_attachments}`，result是原七字段VideoAvAnswerResult，mode与result.mode相同。query_attachments按请求ordinal完整1..3，各精确11字段：ordinal/source_sha256/media_kind/compiler_revision/content_sha256/window_count/visual_window_count/audio_window_count/audio_present/used_mode/status。media_kind固定video；used_mode等于mode；status为prepared或not_prepared。全批完成编译/资源/模态核验才全部prepared，其content SHA/count/audio_present有效；否则全部not_prepared，content/count/audio_present为null。0-based ordinal连续，source SHA对应原文件，compiler revision为实际固定编译器。已准备但召回/证明失败保留prepared；answered只能全部prepared；空scope全部not_prepared。不返回filename、原字节、转录、描述或epoch明细。旧无附件七字段响应不变。

Q08 v21只追加两个hash-only sidecar表，不改v20任何publication/index/旧query/Sound语义。VideoAvQueryTrace绑定完整question SHA、mode、实际profile、embedding revision、固定preparation policy与按序11字段manifest SHA。contentSHA绑定原source/compiler与全epoch+window/clip/frames/PCM/WAV/absence的现有window manifest；不持久query bytes/向量值/文件名/正文。准备失败也保存全部输入sourceSHA/ordinal，不能伪造prepared。children→header→原video_av_traces在同一终态事务插入；完整count/ord/status、mode/question与parent绑定，sealed/no_replace/no_update/no_delete guards。原无附件trace无sidecar合法；不能给已封存旧trace追加sidecar。新source回读验证有sidecar时完整组/hash/parent及当前profile，重启0query decode/provider。

Q09 capability `video_av_query_attachments`仅实际新servlet、VideoAv answer/library图齐备时出现，独立于旧query_attachments。前端三模式仅此cap允许VIDEO附件，完整File读取/canonical encoding，mode/身份/范围/问题/附件变化、取消及迟到响应沿epoch隔离；原控件保持文字编辑与选中集合。两个Node代理只新增精确新route的28MiB/180秒，不自动重试。页面由用户验收。
