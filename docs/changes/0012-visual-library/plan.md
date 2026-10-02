# 0012 执行计划

使用 fullstack-dev 的端到端验收和 codebase-design 的小 Interface 约束，项目传统 Spring 分层优先，不引入重复权限/基础框架。

1. 冻结纯图 authority、ProjectionItem、typed引用合同，先写直接可复现的失败验收。
2. 并行实现：authority代理负责v7/摄取；adapter代理负责共享索引协议与发布；主代理负责证据/trace接线、图片问答、Config/Controller和整链验收。共享工作树不回退其他修改；Maven/网络测试只由主代理运行。
3. 小步针对性测试→整链真实HTTP本机协议验收→旧用例保留核验/完整verify/Node/密钥扫描。
4. 独立审查与源码绑定；记录偏离/未验证项。无新云预算时不占用文本余额，不以本地替身证明云质量。

暂后置：通用多模态自动路由、跨图/音画联合、CLIP式视觉索引、扫描PDF、音频/视频、摘要、网页、物理清理及生产加固。后置不允许现有授权/有据回答底线失效。

2026-09-10执行结果：本地步骤1–4完成，942 Java/73 Node、302文件格式和双80%门禁通过；独立审查的混库正常路径P1以最小修复关闭，原923项身份集合保留。实际证据及偏离见[verification](verification.md)。本机协议替身不是云模型/真实Milvus质量验收，完整多模态与生产保持IMPLEMENTATION。
