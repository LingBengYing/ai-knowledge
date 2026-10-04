# 更换嵌入模型后重建并应用

用户最新要求停止自动化测试，完成正常主线。本轮补已有知识后更换嵌入模型、修订和维度的正常操作，不运行测试、浏览器、真实模型或部署。

保存草稿保持旧配置。独立 `GET /v1/model-configuration/rebuild` 返回资格，`POST` 同路径仅接受 `{version}`，明确创建后台批次；完成后自动应用，无需第二次隐藏操作。GET/POST均返回 `target_version,active_version,required,can_start,reason,total_documents,job`。job字段为 `id,base_active_version,target_version,state,total_documents,completed_documents,error_code,created_at,updated_at`，state为queued/running/applying/completed/failed。独立能力为 `model_index_rebuild`。只允许配置管理员发起，并须能编辑完整受影响资料；不会静默跳过无权限资料。

v27批次冻结当前完整有效文字corpus的当前解析材料和base publication；包括原文件更新后资料及已解析但尚未发布资料，独立sound/video_av保持自身处理图。不重新解析或ASR。已有未完解析、索引、原件更新或清理先结束才能开始。单库至多一个未完批次，期间配置保存/普通应用及导入、版本更新、删除、索引写入暂停；读取、问答和整理继续使用旧版本。

每批从可信服务端连接派生独立Java collection，维度变化不写旧集合。新anchor持久实际collection，旧格式仍可恢复。批次索引任务由独立worker领取，普通worker不领取；每份沿完整ProcessTextIndexer验证，完成仅封存candidate publication，不逐资料激活。全部完成后短maintenance窗复验完整base集合，同SQLite事务切换全部publication和text_runtime_selection，再安装runtime。失败或重启中断保留旧配置和全部旧publication，用户可明确新建重建批次，无自动重试。

私有配置按版本不可变封存，SQLite只保存版本与SHA，不保存密钥。selector初次从合法旧配置初始化，none也明确记录；读配置保留最新草稿，active由权威selector所选私有版本恢复。普通应用也沿该selector。旧jobs/publications/来源历史保留，兼容判定以当前有效publication和未完任务为准。

已有独立图片/音频向量保持相同完整原件和证据，不重新调用其模型；批次明确授权跨文字target的binding映射，仍须完整校验其原始receipt和远端向量。不得将普通同target重建条件全局放宽。旧来源在批次处理中可读，发布后需重新查询；页面保留问题、范围及整理草稿。

完成证明本轮仅为实际源代码及跳过测试的运行包编译；不借用旧测试证明本轮正常页面或真实服务可用。
