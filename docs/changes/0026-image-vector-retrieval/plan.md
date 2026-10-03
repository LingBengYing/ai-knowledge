# Plan

1. intent/spec冻结完整用户路径和独立构建/投影/authority边界，先行为RED再实现。每次尝试独立代次，旧文字索引协议/任务保持。
2. pdf_config拥有独立ImageEmbeddingModels/协议Adapter及其新测试；backend_originals拥有build/receipt/publication领域、独立worker/protocol/ProcessImageVectorIndexer、新构建Service及v17 schema/store装配和对应测试；pdf_ingestion拥有ImageVectorRepository、ImageVectorScope、EvidenceService、原图查询/问答与dense投影模式及对应测试；root拥有配置/Runtime、HTTP/mappers、前端/代理、跨HTTP正常链、文档与统一验证。
3. 明确共享typed Interface后各代理先测试，根代理统一串行Maven/Spotless，不能把缺新类的编译中间态算RED。所有旧有效断言和门禁保留；新生产框架复用现有传输、worker生命周期与证据Module。
4. 跑通合成正常链与必要旧回归，绑定源码/制品，冻结新包；发布和页面仍归原责任方。云请求0，不接触真实凭据/旧数据，不改Git或旧冻结包。

实际分工中配置/Runtime、HTTP/mappers及其新测试委托pdf_config完成；root保留前端/代理、真实Spring跨HTTP/native正常链与统一门禁/文档/冻结。共享Maven/Spotless始终由root串行执行，三代理补测就绪后统一验证。
