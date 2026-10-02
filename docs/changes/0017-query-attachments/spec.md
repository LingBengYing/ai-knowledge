# 查询附件合同

## 输入与临时编译

- QA-01：原始文字问题保持原字节，复用现有非空及UTF-8 4096字节上限；每次至多3个附件，总原字节至多20 MiB。首切接受现有真实解码支持的PNG/JPEG、音频和视频MIME；实际类型仍由原ImageInput/FFmpeg核实。不能因扩展名伪造图片或把含视频文件当音频。
- QA-02：附件仅请求期存在，模型输入、结果与toString不泄漏到应用日志；不调用IngestionService、写资料/active/投影或伪造PublishedEvidence。完整库范围验证在首次附件模型调用之前，处理期间与提交前继续复验。
- QA-03：图片复用实际像素检查和VLM召回描述；音频复用完整解码/分段ASR；视频复用完整编译，保留实际原帧、完整音轨以及配置内的OCR/字幕。所有临时文本仅用于检索，不能作为TextModels.Evidence/GroundingText、引用或事实证明。
- QA-04：准备产物显式区分originalQuestion、retrievalText、queryImages与hash-only附件manifest。无附件时检索输入等于原问题，不调用编译器；有附件时原问题不被改写。retrievalText总量最多8192 CP；超限显式拒绝，不悄悄截掉原问题或附件文字尾部。
- QA-05：完整编译后查询原图按SHA去重，至多3张用于匹配。多帧视频按稳定、覆盖首尾的等距代表帧选择；若超过预算则明确记录query_visual_sampled，而不是称完整视觉覆盖。原始编译文字/原帧总数和完整内容SHA仍进入manifest，选帧只影响辅助匹配；不可放宽库内证明。
- QA-06：检查调用者current、线程中断、总budget以及冻结的模型/编译版本；原生生命周期复用，不引入额外全局缓存、自动重试或第二套进程控制。失败不返回伪造空附件成功；无声音等结果用稳定错误/说明，不输出模型原始异常。

## 真实双角色匹配

- QA-07：新的QueryRankingModels Seam明确query-images与authorized-candidate-images两种角色；现有VisionModels/FactVisionModels单图证明合同不变。生产Adapter采用标准chat/completions多图内容，测试Adapter走实际loopback HTTP。匹配不产生答案、引用、页码或时间。
- QA-08：每批至多20个candidate，完整、唯一的整数index和finite [0,1] score必须全部返回，未知/重复/遗漏/截断结果整体失败。原图只接受服务器已有bytes，不接受附件/文档/模型给出的URL；请求总字节有界，构造和revision读取不联网，无自动重试。
- QA-09：查询图片与库内原图/帧都可以影响召回后排序；但extract、TextGrounding、VisualAssessment、VideoAssessment以及joint FactPlan仍只使用原完整问题和库内原证据。附件含答案而库内无证据时必须拒答；库内joint缺一个事实时不能由附件图/ASR补证明。

## 授权、审计与HTTP闭环

- QA-10：保留完整selected set与all scope、当前ACL/active/publication、终态trace事务。独立hash-only trace绑定附件源SHA、媒体类型、编译版本、完整临时内容摘要、选中查询图SHA和采样说明；不覆盖questionSha256或保存原问题/转录/附件字节。回读旧回答不依赖查询附件，也不再调用模型。
- QA-11：新增显式opt-in附件入口，旧四个JSON问答入口形状与默认关闭行为不变。不得把已关闭multipart的框架默认行为当作上传限制；明确有界接收与既有认证/同源校验。对外给出安全附件处理说明及原有typed库内来源。
- QA-12：真实临时PNG/WAV/MP4经后端附件入口辅助检索，答案与引用来自已发布资料；验证无附件兼容、附件命令为数据、附件独有答案拒答、完整尾部问题/joint事实不丢、无效范围零外部调用、重启来源回读。

## 验收层次

先完成QA-01～08的请求期编译和真实双角色模型通信Module；随后QA-09～12接授权答案、hash-only trace和HTTP。只有完整最后链通过才称附件后端已可用。所有真实模型/Milvus服务端质量、前端/播放器、生产和性能仍另验，不用替身或内部Module认证。
