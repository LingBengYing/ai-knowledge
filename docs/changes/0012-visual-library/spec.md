# 0012 行为规格

## 业务闭环

- VLIB-1：独立显式 visual 开关启用时，新 PNG/JPEG 上传走视觉描述；不用 OCR 的异常作为分流信号。关闭时既有图片 OCR 和文本路径保持。10 MiB/12M 像素原图限制不变；描述完整保留至4096 code points，不截断、不给假页/文字 offset。
- VLIB-2：同一 source revision 下新增不可变 image_evidence（整图尺寸、完整 recall_text/SHA、description revision）。纯视觉 revision 的真实 pages/segments 数为0。描述与模型版本绑定上传 preparation revision；外部 describe 在 authority 事务外，领取/执行/提交继续复验创建者和 source。
- VLIB-3：ProjectionItem 表达 ID/ordinal/recallText/SHA，不承担文字来源定位。文本仍由原 IndexSegment 校验后映射；索引协议升级v3，视觉单项上限4096CP/16384UTF8 bytes，总量/数量/进程预算保持。每图一个 item。复用实际 embedding、Milvus generation/manifest/digest，不新增假索引 Adapter。
- VLIB-4：v7只做增量迁移；新 image publication/trace sidecar 使用真实 FK，沿用现有 publication、完整 scope 和 trace header。原v1-v6迁移不改，活动发布与 trace 封存计数涵盖两类条目。旧文本 identity/不可变约束继续生效。
- VLIB-5：新增显式图片问答接口 POST /v1/visual-answers，输入与现有 AnswerCommand 相同。此切回答一张候选原图能够完整证明的问题；不是跨图或音画联合回答。全部所选文档仍进入冻结 scope，不能以候选子集替代最终资格检查。caption 只供 embedding/rerank，不能进入原图 assessment 或 TextGrounding。
- VLIB-6：输出 VisualAnswerResult，成功引用 kind=image_region、整图 normalized_xyxy [0,0,1,1]、源 revision/SHA、原文件名、模型/策略版本；没有 page/start/end/quote。GET /v1/visual-sources/{answerId}/{ordinal} 及 /content 仅向原回答者在完整当前 scope/active 有效时回读；正文是原 PNG/JPEG，不是生成图。
- VLIB-7：原图逐事实支持不完整、无候选、超时、撤下/换版或配置变化不发布部分答案。trace只保存问题/答案/事实摘要和版本，不存原问题、caption或模型原始输出；描述本身只在对应证据表。
- VLIB-8：显式空范围零外部请求；本切不把视觉证据塞入 /v1/answers 的文字引用。文字接口仅检索有真实文字条目的发布，图片接口仅检索图片发布；两者均保留完整所选 scope 的复验与 trace。混库的全库/显式混选文字问答继续可用，未入候选的所选图片撤权仍使回答拒绝发布。混合模态自动路由和跨图联合回答不宣称实现。

## 验收

真实 SQLite、实际 Service/Controller/HTTP、生产模型/Milvus Adapter 与独立索引子进程在本机协议替身下重放：无文字图上传→描述→零文本证据→索引→问题→原图事实→引用→同SHA内容。替身明确标识，不等同于真实云视觉能力或召回质量。
保留原923项默认 Java 用例及73项Node，执行针对性红绿、全量门禁和独立限定审查；不删除、跳过、放宽失败断言。检查完整scope撤下、错误caption不能独立支撑回答、来源授权以及真实迁移。真实provider/Milvus、网页与生产验收另列，未获新授权不调用云。
