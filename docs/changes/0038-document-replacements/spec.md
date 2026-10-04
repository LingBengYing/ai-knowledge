# 同资料版本更新合同

编辑者可上传同类型的新原文件，服务端保留原 corpus/sound/video_av 处理通道。候选版本单独保存完整字节、元数据和 SHA；当前资料行、原件与发布索引在成功前继续使用旧版本。新解析及完整索引成功后，在同一 authority 事务切换当前原件、解析版本、publication 与候选终态；不修改整理字段或 ACL。失败、取消不切换。

新 v26 保存不可变原件版本和候选记录。解析任务按 document+revision 绑定，候选任务不混入原资料行 latest_job。原 SQL typed evidence 资格通过连接内、短事务候选作用域复用：外部模型、解码和远端写入在作用域外；未发布时完整恢复旧当前别名。历史字节纳入配额和清理。

GET `/v1/documents/{id}/replacement` 无 body/query；POST 同路径仅 `filename`、`base_revision_id` 两 query，原 File 内容，沿原 20MiB/30秒接收（图片10MiB）。POST `/replacement/index` 仅 JSON `{candidate_revision_id,base_revision_id}`，明确构建，180秒独立预算；文本沿既有后台索引，sound/video_av 完整独立索引后发布。不接受浏览器选择处理通道或地址。

统一响应16字段：document_id,base_revision_id,base_publication_id,candidate_revision_id,pipeline,state,filename,document_type,media_type,source_sha256,size_bytes,ingestion_task,index_task,can_upload,can_index,publication_id。pipeline 为 corpus/sound/video_av；state 为 none/stored/queued/processing/parsed/indexing/published/failed/cancelled；嵌套任务沿既有安全字段。不返回原件正文、私有路径或凭据。

新原件 SHA 不能继承旧独立图片/音频向量回执。新文本发布后，相应独立向量为未建立，用户可沿既有入口明确重建；sound/video_av 新版本须完成自身完整回执。成功后旧问题和范围保留，来源在授权列表确认新 publication 后失效并提示重新查询。页面由用户验收。
