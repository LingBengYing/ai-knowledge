# 0018：完整多模态配置装配

状态：本切LOCAL_VERIFIED，总体IMPLEMENTATION。MC-01～06本机验收与独立审计通过，见[verification](verification.md)。承接0017已冻结的附件后端和0004独立Java迁移，不重复其编译、匹配和HTTP诊断。

用户正常路径：在一个独立Java进程内启用已有多模态能力，上传合成资料、索引、带附件提问、生成文件摘要，再重启回读原始来源。要证明生产Configuration可以共同装配，而非测试手工替换Service/Bean后各自运行。

本切仅本机、合成媒体、真实SQLite/FFmpeg/Tesseract和loopback模型/Milvus协议替身。它是后续真实provider/Milvus质量与发布的前提，不解除development/test、literal loopback或readiness gate，不访问旧服务/数据，不改前端，不作Git写入或部署，不使用历史云调用额度。
