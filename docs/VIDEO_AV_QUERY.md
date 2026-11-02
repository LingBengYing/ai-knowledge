# 原视频参考问答（已删除）

2026-11-02起，`POST /v1/video-av-query-answers`及`video_av_query_attachments`能力已从后端删除，请求该路径返回404；同时删除的还有`POST /v1/video-av-answers`和`/v1/video-av-sources/*`来源接口（见[原视频音画](VIDEO_AUDIOVISUAL.md)）。网页此前已移除对应代理。原视频上传与索引保持可用；`video_av_query_attachments`、`video_av_query_preparations`表结构保留但不再写入或读取，不做迁移。

历史合同与验证记录保留在[0030 spec](changes/0030-video-av-query/spec.md)、[interfaces](changes/0030-video-av-query/interfaces.md)和[verification](changes/0030-video-av-query/verification.md)，仅作历史参考；后续若重新规划视频参考问答，按新需求另立合同。
