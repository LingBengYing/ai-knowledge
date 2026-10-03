# 原视频参考问答

已启用原视频音画资料与问答时，携带一至三份原视频参考可帮助召回库内资料。选择VISUAL只使用实际画面，AUDIO使用实际原音轨，JOINT使用两路实际媒体；答案仍须由库内证据覆盖完整文字问题，引用打开库内原视频。参考文件不入库、不成为引用，也不调用ASR或描述模型。

新接口为精确POST `/v1/video-av-query-answers`，使用question、mode、attachments及可选document_ids。每份附件只有filename、media_type和canonical base64的content_base64；只支持对应真实magic的MP4/WebM/QuickTime/Matroska，总原文件20MiB、JSON28MiB。显式空范围不会扩大为全库；全部所选资料及所有索引receipt先合格再处理参考。

每份参考沿既有真实视频编译器，保留全部连续画面、精确有理时间、原PCM及音轨absence。整批至多1201窗口、600000毫秒、64MiB clips和19,200,000实际PCM bytes，统一处理期限至多120秒、最多两请求。逐件编译后立即检查资源和模态；任一失败整批not_prepared，不处理后续媒体或发布部分成功。AUDIO/JOINT含无音轨参考会整批拒答；VISUAL可接受无音轨视频。已准备但召回或证明失败仍返回prepared receipt。

所有适用参考窗口和完整文字问题各自召回，同一次搜索的全部candidate完成权威映射后才融合；全部参考及尾窗完成后才截取最终64项并证明。v1保留长参考更多召回票数的规则，不抽样或暗中归一化。参考不进入证明或来源，附件独有事实与库内半问题保持拒答。

新响应为`mode/result/query_attachments`三字段，result保留旧七字段答案；每份receipt绑定原source SHA、compiler、whole manifest、全部窗计数、实际音轨、模式和整批准备status。v21仅追加两个hash-only侧表；来源回读复验完整组、digest、parent和当前profile，原无附件trace仍合法。配置独立能力`video_av_query_attachments`由实际新servlet及VideoAv对象图驱动，旧附件开关不能开启新路。

实现、本机真实媒体与完整门禁见[0030验证](changes/0030-video-av-query/verification.md)，严格合同见[spec](changes/0030-video-av-query/spec.md)及[interfaces](changes/0030-video-av-query/interfaces.md)。默认随独立VideoAv开关关闭，不自动配置真实供应商、补建旧资料或迁移生产数据。新能力未部署，网页由用户验收；真实语义质量仍待独立授权验证。
