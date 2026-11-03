# 独立声音知识库（已删除）

2026-11-03起，0028独立声音知识库已从后端整体删除：`POST /v1/sound-documents`上传、`GET/POST /v1/documents/{id}/sound-index`索引及其编译、索引子进程（`--sound-index-worker`）、Google声音模型/embedding客户端、`rag.sound.*`配置键（`RAG_SOUND_*`环境变量不再读取）和`sound_upload/sound_index/sound_answers/sound_sources/sound_query_attachments`五项能力名均不再存在，请求这些路径返回404。问答与来源接口已于2026-11-02先行删除。网页同步移除声音入口。

不受影响：音频资料（ASR转录、音频问答/来源）、原声向量检索（[AUDIO_VECTOR_RETRIEVAL](AUDIO_VECTOR_RETRIEVAL.md)）、视频资料、查询附件和文档链路。

## 已有声音资料（旧数据）

schema不变（仍为v43），不新增迁移。`sound_originals`、`sound_publications`、`sound_spans`及`sound_trace*`表连同建表/校验代码保留，旧行原样保存：

- 资料列表、状态筛选和详情照常显示旧声音资料（`document_type=audio`；有封存publication时为`parsed/indexed`，否则`ready/not_indexed`），原文件下载照常；
- 同资料原件更新：`GET /v1/documents/{id}/replacement`返回`pipeline=sound`、`can_upload=false`，上传/准备返回409`replacement_state_conflict`；
- 仍处于待处理的旧自动索引请求会被标记为失败（`indexing_unavailable`），不再悬挂批次；
- 撤下/清理（tombstone→purge）照常擦除原文件blob与召回描述，保留publication身份；旧远端`java_sound`集合不再有已配置清理目标，远端资源记为`blocked`（`cleanup_target_unknown`），需要时由运维手动删除该集合。

历史合同与验证记录见[0028 spec](changes/0028-sound-library/spec.md)、[接口](changes/0028-sound-library/interfaces.md)和[验证](changes/0028-sound-library/verification.md)，仅作参考；删除前的实现可从Git历史（`862e8e1`及更早）查看。
