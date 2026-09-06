# Intent: Java 文本模型与检索 Adapter

状态：IMPLEMENTATION。2026-09-06 负责人在独立 Java 仓库交付后授权“继续吧”，继续既定文本 RAG 迁移。

先实现可验证的 embedding、rerank、extractive generation 与 Milvus dense/BM25 Adapter，消除真实文本链路的外部协议缺口。沿用单组织、独立 Java 数据资源、ACL 前置、服务器引用和无证拒答约束。

这只是文本纵切的协议子切，不是完整 RAG。不得连接旧服务资源、自动读取聊天凭据、把本地 HTTP 替身当真实 provider/Milvus 证据，或解除 production/readiness gate。
