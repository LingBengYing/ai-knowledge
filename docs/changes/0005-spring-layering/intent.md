# Intent：传统 Spring 分层与人机协同规范

状态：IMPLEMENTATION，2026-09-07。负责人已明确批准包含 Security 的分层方案，并授权完整重构和删除被替代的混乱旧实现。保留的是行为、数据契约和有效测试，不是旧类或兼容包装。

本次 Java 分层重构的本地验收已完成，结果见 [verification](verification.md)；整体产品仍处于 IMPLEMENTATION。负责人后续明确本轮只完成 Java 重构，前端独立详情页另行处理，不混入本轮变更。

目标：让维护者和 AI 使用同一套类型语言、职责边界、依赖规则、例外与验证要求；按 Controller / Service / Repository 严格分工，遵守奥卡姆剃刀，不以增加空接口或目录代替设计。

依据：[开发规范](../../JAVA_DEVELOPMENT_STANDARDS.md)、[阿里研究](../../research/alibaba-java-conventions.md)、[Spring 校准](../../research/java-spring-conventions.md)。AI-Native 工件与安全 gate 继续有效。

不包含：新增业务能力、数据库/ORM 迁移、生产部署、推送仓库、改变当前 API/认证/ACL/索引语义。0004 的行为和有效测试须保留；被重构替代的源码可移除。其验收和本次结构迁移分别记录。
