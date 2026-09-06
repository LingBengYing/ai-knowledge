# Intent: 接通真实文本摄取

状态：LOCAL_VERIFIED（2026-09-06）；Java整体仍为IMPLEMENTATION。继承已批准 Java 文本纵切与持续完成多模态生产目标的授权。结果与未验证范围见[VERIFICATION](../../VERIFICATION.md)。

让用户实际上传 PDF/TXT/Markdown，在原资料列表中看到持久任务、失败/取消/重试和可追溯解析结果；连接现有解析 Module，消除只有合成元数据而不能摄取文件的缺口。仍以完整文本有证问答、多模态和生产部署为最终目标。

本变更不冒充索引或答案完成：未投影的解析结果为 `parsed`，active revision 不发布，问答继续禁用。后续将既有模型/Milvus Adapter 接到这些真实版本化证据。只使用独立 Java 开发目录，不打开旧数据库，不读取聊天凭据或解除生产 gate。
