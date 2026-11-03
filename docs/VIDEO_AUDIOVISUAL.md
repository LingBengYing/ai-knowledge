# 原视频音画知识库（已删除）

2026-11-03起，0029原视频音画知识库已从后端整体删除：`POST /v1/video-av-documents`上传、`GET/POST /v1/documents/{id}/video-av-index`索引及其编译、视频音画解码器、索引子进程（`--video-av-index-worker`）、Google视频音画模型/embedding客户端、`rag.video-av.*`配置键（`RAG_VIDEO_AV_*`环境变量不再读取）和`video_av_upload/video_av_index/video_av_answers/video_av_sources`能力名均不再存在，请求这些路径返回404。问答、来源与参考视频提问接口已于2026-11-02先行删除（见[VIDEO_AV_QUERY](VIDEO_AV_QUERY.md)）。网页同步移除原视频音画入口。

不受影响：0014视频资料（视频上传、ASR、OCR、字幕、视频问答/来源，见[VIDEO_PUBLICATION](VIDEO_PUBLICATION.md)）、音频资料与原声向量、图片向量、查询附件和文档链路。

## 已有原视频音画资料（旧数据）

schema不变（仍为v43），不新增迁移。`video_av_originals`、`video_av_publications`、`video_av_windows`、`video_av_trace*`及`video_av_query_*`表连同建表/校验代码保留，旧行原样保存：

- 资料列表、状态筛选和详情照常显示旧原视频音画资料（`document_type=video`；有封存publication时为`parsed/indexed`、`segment_count`为窗口数，否则`ready/not_indexed`），原文件下载照常；
- 同资料原件更新：`GET /v1/documents/{id}/replacement`返回`pipeline=video_av`、`can_upload=false`，上传/准备返回409`replacement_state_conflict`；
- 仍处于待处理的旧自动索引请求会被标记为失败（`indexing_unavailable`）；
- 撤下/清理（tombstone→purge）照常擦除原视频blob，保留窗口与publication身份；旧远端`java_video_av_*`集合不再有已配置清理目标，远端资源记为`blocked`（`cleanup_target_unknown`），需要时由运维手动删除。

历史合同与验证记录见[0029验证](changes/0029-video-audiovisual/verification.md)，仅作参考；删除前的实现可从Git历史（`862e8e1`及更早）查看。
