# 原图知识库：当前后端 Interface

对应[0012规格](changes/0012-visual-library/spec.md)。2026-09-10已通过[本地后端验收](changes/0012-visual-library/verification.md)及[限定审查](changes/0012-visual-library/REVIEW.md)，完整产品仍为 IMPLEMENTATION。不是生产版，也不是文件摘要功能；真实云效果、网页与音视频尚未验收。

## 正常路径

上传无文字 PNG/JPEG → 原图模型生成完整召回描述 → 独立图片证据 → embedding / Milvus dense+BM25 → 授权候选及 caption 重排 → 原图提出事实并逐项验证 → 整图来源及原文件回读。

机器描述只用来找图，不是原文/OCR证据，也不进入最终视觉评估。v7的 image_evidence 保存描述、原图尺寸和模型版本；纯图的真实页数/文字分块数是0。独立 image_publication_entries 绑定完整索引 manifest，image_trace_evidence 绑定最终引用。原图仍在既有权威存储，不复制到描述表。

这是“一张候选原图能够完整证明问题”的图片问答。一次提问只评估重排第一张图；未通过就整体拒答，不自动改问题或重试。多图联合、音画联合、自动混合路由和 CLIP 类视觉向量尚未实现。当前视觉事实验证是模型判断，不能当作形式证明或质量指标；真实效果仍需固定云模型和语料验收。

## 配置

所有能力默认关闭，仅 development/test + 字面 loopback；程序不读取 .env。启用 RAG_VISUAL_ENABLED=true 还必须启用 RAG_ANSWERS_ENABLED=true 并提供既有[文本模型/Milvus配置](TEXT_ADAPTERS.md)。上传/索引仍需分别开启原有开关；不会自动索引已上传文件。

| 变量 | 用途 / 默认 |
| --- | --- |
| RAG_VISUAL_ENABLED | false；启用图片专用问答与视觉上传模式 |
| RAG_VISION_BASE_URL / MODEL / API_KEY | 无可用默认值；OpenAI-compatible 图片 chat 接口及凭据 |
| RAG_VISION_DEADLINE_MS | 30000；每次视觉请求最多60000ms，无自动重试 |
| RAG_VISION_MAX_RESPONSE_BYTES | 262144；响应上限 |
| RAG_VISION_ALLOW_LOOPBACK_HTTP | false；仅本机协议测试明确开启 |

答案总时限和并发使用原 RAG_ANSWERS_TIMEOUT_MS / MAX_CONCURRENT，视觉请求还受自己的单次时限限制。摄取描述使用视觉请求时限，RAG_INGESTION_PARSE_TIMEOUT_MS 是旧文本/OCR子进程预算。

启用 visual 后新图片使用视觉描述；关闭时沿用已配置的 OCR 路径。旧任务依据上传时冻结的 parser revision 处理，不重写历史证据。visual 与 OCR 同时启用时，不广告新的 image_text_upload；已发表 OCR 原图仍可回读。旧前端尚未接图片专用问答。

输入上限仍为10MiB、12M像素、PNG/JPEG。描述完整保留至4096 Unicode code points / 16384 UTF-8 bytes；超限失败，不截断。索引协议v3只传 ProjectionItem 的身份/序号/完整召回文本/SHA；原 IndexSegment 的文字位置校验不变。

## HTTP

使用原认证、固定组织、当前 ACL 和完整所选资料集合；不会因选中的某篇不是图片而丢掉它的最终资格校验。图片模式只把图片 publication 交给检索，文字模式只检索有真实文字条目的 publication；scope/trace始终保留完整集合，混库文字问答不接收图片caption作为原文。显式空选择零外部调用。

| 方法与路径 | 行为 |
| --- | --- |
| POST /v1/documents?filename=shapes.png | 原文件字节上传；返回摄取任务，随后轮询原 /v1/ingestions/{id} |
| POST /v1/documents/{id}/index | 显式创建索引任务；轮询 /v1/indexings/{id} |
| POST /v1/visual-answers | JSON question 与可选 document_ids；未传为当前授权全库，空数组为显式空范围 |
| GET /v1/visual-sources/{answerId}/{ordinal} | 原回答者、完整当前 scope/active 仍有效时回读引用元数据 |
| GET /v1/visual-sources/{answerId}/{ordinal}/content | 同一来源校验后的原 PNG/JPEG 字节，no-store / nosniff |

图片引用字段包括 number、kind=image_region、document_id、revision_id、source_sha256、parser_revision、filename、media_type、width、height、bbox=[0,0,1,1]、coordinate_system=normalized_xyxy、model_revision、policy_revision、source_url、content_url。
它是整图 locator，不是物体框；没有 page/start/end/quote。旧 /v1/answers 和 /v1/sources 的文字引用不更改，也不接受 caption 冒充原文。

任一完整所选资料被撤下、撤权或换版，最终答案和旧来源均不能绕过现有检查。trace不保存问题/答案/事实正文，只保存摘要、版本及模型评分；引用中的历史视觉版本从持久 trace 读取，不使用当前模型配置替换。

## 开发与验收

代码导航：VisualAnswerController → VisualAnswerService → EvidenceService / EvidenceRepository；视觉通信由 VisionModels / OpenAiCompatibleVisionModels 承担；Config集中装配，Model区分Domain与公开DTO。没有第二套鉴权或空转ServiceImpl。

验收入口：VisualLibraryHttpTest（真实Spring/SQLite/HTTP与独立索引子进程；云模型和Milvus服务是本机协议替身）、VisualAnswerServiceTest、VisualEvidenceServiceTest、VisualIndexingServiceTest、VisualLibraryMigrationTest。替身通过不等于实际模型召回/事实准确率、真实Milvus视觉数据验收、网页或生产上线。
完整迁移会先一致性备份v6再升级v7；原v1-v6迁移和文本证据约束保留。备份不包含升级后的新增资料，不能把恢复旧备份当作无损回滚。
