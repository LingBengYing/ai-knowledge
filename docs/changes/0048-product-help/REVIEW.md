# 0048 当前记录

最新：负责人明确取消独立产品帮助功能，改为知识问答内部跨类型检索与LLM综合回复，转[0049](../0049-unified-knowledge-answers/intent.md)。本页下方保留历史。

星辰最多6次续作已批准，实际转录成功，PDF/视频都已indexed；页面查询在embedding和文档rerank成功后因本地v4候选读取白名单漏接而失败，累计8/20、本组5/6，模型入口已halted，无自动重试。保存转录的三个操作事实与固定合成口播一致。v4转录/字幕/OCR读取源码已修，实际页面复验未运行，详见[provider-run](provider-run.md)。22:57:34打包因target重复主类失败，不能称修复包通过；由0049干净构建统一接续。

最新选择：负责人已指定星辰 `XingChenAGI/XingChenASR-V3.2-Ultra`；本轮启动配置和仓库外置配置示例已调整，不改Java业务逻辑、旧证据或线上配置。官方价格页当前免费已核实，实际转录兼容/质量未验。当前已用3/20且halted；具名最多6次续作待负责人答复。`fullstack-dev`仅用于外置配置/密钥隔离，用户停测和无重试约定优先，未运行测试或调用模型。

## 语音/字幕续作（当前）

最终停点：第3/20次SenseVoice ASR请求60秒超时，22:38:34 +08新任务failed/parser_failed；已再次关闭转发且无重试，剩余17次不自动续用。未执行索引、产品查询、PDF结果按页或视频结果播放；本切业务端到端仍未完成。代码构建/应用迁移成功不能代替真实链路验收。

负责人批准首轮20次及失败后的剩余18次具名续作。真实第1次ASR成功，第2次VLM描述60秒超时后即停；详细台账见 [provider-run](provider-run.md)，不以HTTP200代替转录质量验收。

已新增默认关闭的v4文字证据视频编译，跳过VLM而完整保留原帧/PTS/ASR/字幕/OCR；v28迁移支持caption明确缺省及真实投影计数，不写假描述。Config分开摄取/索引与视觉答案能力，该模式不装旧查询附件链。`codebase-design`促成本条显式小Interface选择，原视觉默认模式保留；无新模型框架或权限体系。

2026-10-04 22:36:19 +08，JDK21离线skip-all-tests package成功5.206秒；593源码编译，测试未编译/未运行、检查审计未执行。22:36:58–22:37:00，以新不可变JAR启动本轮18086，实际独立库完成v27→v28迁移；18084及远端服务不动。应用模型配置仍有效，实际capabilities保留video_upload/video_index/product_help，video_answers为unavailable。原PDF与失败视频记录继续保留。

已用同一新合成视频创建文字证据模式的新摄取任务，不重试旧VLM或手改旧任务；后续索引/检索/来源结果以provider-run最终记录为准。下方首版空库记录保留为历史，不能覆盖当前实跑状态。

状态：首版源码与运行包已实现；本机未配置模型页面已实际查看，真实检索/来源播放尚未验收。

负责人批准产品使用帮助首版。源码现状已确认：旧TextRetrievalTestService仅文档/图片OCR；视频转录、帧OCR和字幕有独立authority/publication与来源；旧AnswerService全请求按SourceKind分派，不能通过拼接两份答案冒充混合证明。

## 本次实现

新增 `POST /v1/product-help/search` 与 `product_help` 能力。Controller/请求转换、Service编排、Repository authority读取、Domain/DTO和Config分层；复用managed文字模型与Milvus，单次查询嵌入后分别召回文档/视频、分别可选重排。完整范围与发布身份保持，显式空集合不回退全库。

结果返回原文及真实定位：文档页码/码点，视频ASR服务器段、字幕cue或OCR帧区间。所有物理候选先核对authority，caption/摘要不伪装成上述文字证据。排序分不是事实置信度，未设未经校准的通用分数阈值；top-K候选不代表全部相关或已经证明可回答。

独立前端0036分组显示说明文档和使用视频，复用原件metadata、完整原字节SHA与Blob阅读/播放。未新增数据库schema、产品主数据、混合答案trace或云模型类型。详细合同见 [PRODUCT_HELP](../../PRODUCT_HELP.md)。

## 实际构建与本机页面

- 2026-10-04 21:37:42 +08，JDK21，离线 `mvn -o -ntp -s .mvn/settings.xml -gs .mvn/settings.xml -Dmaven.test.skip=true package` 成功，4.493秒。测试资源未复制、测试源码未编译、测试未运行；未执行verify、格式/静态检查或审计。编译器仍有警告，不称零警告或回归通过。
- 此前两次构建在旧target打包阶段受阻：第一轮停止；第二轮最终明确失败，检测到 `RagApplication 2.class` 与正常主类两个入口。线程栈也显示正在读取多份带2/3后缀旧class。旧target完整移至工作区 `.local/build-before-product-help-0048-20261004-2139` 保留，新target打包成功；未修改POM或推定这些副本的产生原因。
- 21:37:57 +08，`run-workspace.sh` 从不可变JAR副本启动18086，独立新数据目录 `.local/product-help-0048.XWBRG3`；未配置模型/凭据，不读取或复制旧库。原18084服务不动。
- 前端18087代理到18086，实际内嵌浏览器打开 `http://127.0.0.1:18087/#/product-help`：导航、文档/视频分组、范围展开、每类数量5/重排设置及未启用能力提示可见。未配置模型时查找按钮禁用，没有提交查询。当前页面截图在工作区 `.local/product-help-0048-preview.jpg`；该截图仅证明此状态。

## 边界与下一步

自动化测试/检查/审计按负责人要求全部NOT_RUN；未改动或放宽旧测试。真实provider/Milvus双类召回、中文产品相关性、完整所选资料入口、实际结果卡、PDF按页和视频时间播放均未在本切验证，RAG acceptance为NOT_RUN。没有合成成功结果代替真实验收。

新增云调用0，没有旧数据修改、Git提交/推送或远端部署。完整生产gate保持。下一步需在明确授权的样本/调用预算下，以同产品说明书和教程视频走真实检索→原件阅读/时间播放；跨类型生成答案、显式产品metadata和Qwen视觉补充仍为后续增量。
