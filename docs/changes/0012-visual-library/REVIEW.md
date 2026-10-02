# 0012 审查

状态：Standards scoped PASS；Spec scoped PASS。2026-09-10本地942项Java/73项Node及完整门禁通过，证据见[verification](verification.md)。完整多模态/云效果/生产未验收。

限定 Standards：Spring责任边界、描述/来源分离、增量迁移和共享scope，不以新Service复制权限实现。
限定 Spec：VLIB-1..8可观察流程，纯图无假OCR位置，完整manifest/trace/原图回读，旧文字用例保留。

## 限定独立审查

- 前一阶段非实现者 image_ingestion_map 检查 VisualAnswerService、Config/Controller/DTO：Spring职责、camelCase显式wire字段、敏感toString、模型前完整scope检查、原图引用回读。DTO与runtime能力广告问题修复后限定两轴通过；本轮重新运行相关全部测试，不依赖失效临时日志。
- 2026-09-10非实现者 visual_authority_review 只读核对图片证据存储、增量v7、ProjectionItem/索引发布、Evidence/trace和旧文字兼容性，未独立完整审查摄取任务执行链。原v1-v6 Schema/Store代码与0011冻结指纹一致，新增独立图片FK/完整manifest/历史来源复验符合规格。
- 唯一新增P1：旧文字问答在混库中检索到图片条目，随后按文字hydrate导致误拒答。TextVisualCoexistenceTest先实际3项RED；修复限定三处，40项相关GREEN。独立复审确认只收窄检索generation，完整scope/current/ACL/finish/trace不变，P1关闭，无新增finding。
- 独立审查代理未执行Maven或云请求；实际最终验证由主代理运行。302文件格式、原923项精确身份保留、源码/构建输入一致及覆盖率证据与本切manifest绑定，不借0011通过数认证新代码。

收尾由 visual_authority_review 独立回读本轮隔离构建的日志、942项精确JUnit用例、JaCoCo、326输入与证据SHA，结果与工件吻合；仓库自身旧target不作为本切证据。

审查范围不包含真实VLM质量、真实Milvus新图片发布、扫描PDF/音视频/联合、网页或生产；其未完成不伪装为本切已验收能力。
