# Spec：受控清理及可验证完成

状态：IMPLEMENTATION，合同冻结于2026-10-03，测试结果尚待实际执行。基线0030 manifest为f599ccfa63f0e9c3240393e742caf8e93e7df599beec6888e418c451750df728。

## 1. 默认关闭控制与回执

- 新`RAG_DOCUMENT_CLEANUP_ENABLED`/`rag.document-cleanup.enabled`默认false，要求removal开启、development/test和字面127.0.0.1/::1。关闭时新路由404，不创建执行器或外部调用，不改变旧能力、readiness/production拒绝。不需要模型配置。
- 保留旧`DELETE /v1/documents/{id}`的四字段202撤下回执和原取消语义。旧A墓碑不会因读取状态或启动新执行器自动变成B请求。
- 新`POST /v1/documents/{id}/cleanup`无query/非空body，用于单项受控清理；新`POST /v1/management/document-cleanups`严格`{document_ids:[...]}`用于批量，1..100个完整、唯一、合法ID，非法shape/重复整批422。一次请求捕获完整列表，不截断或扩成全库。
- 新管理清理要求当前writer和idle：ingestion/index/synopsis queued或processing及实际操作尚未结束时，该项busy、不撤下；旧A已撤下的资料也可显式接管B但仍等待真正操作退出。批量逐项独立，保持输入顺序和数量，返回202 `{items:[{document_id,status,cleanup,error_code}],total}`，status仅accepted/busy/not_found，cleanup为完整回执或null，error_code仅null/document_busy/not_found。不能沿旧updated文案表示完成。单项busy为409 document_busy，越权/reader/未知统一404。
- `GET /v1/documents/{id}/cleanup`严格无query/body，返回200；`GET /v1/management/document-cleanups?page=1&page_size=20`只允许这两个正整数参数，page_size最大100，返回当前writer授权过滤后分页的`{items,total,page,page_size}`。GET均只读取。普通资料列表继续排墓碑；记录不得通过分页计数泄露不可访问资料。
- 每项状态固定九字段：document_id、cleanup_id、status、cleanup_status、requested_at、updated_at、completed_at、error_code、resources。status仅deleting/deleted；cleanup_status仅not_requested/pending/running/blocked/failed/completed。旧A未接管时cleanup_id/completed_at/error_code为null，时间沿旧撤下请求，resources空；无墓碑未请求资料统一404。completed_at只在真实完成提交后非null。重复POST复验当前同组织owner/editor raw ACL、返回原任务身份，不追加重复请求或自动重试失败。
- resources是固定kind/status安全枚举列表，不返回路径、正文、target地址、claim、操作者或内部错误。kind包括database_payload/database_file/managed_backups/managed_temporaries/remote_inventory/remote_logical_rows/remote_write_terminal/remote_physical_storage/restore_barrier；status仅pending/running/completed/not_applicable/blocked/failed。unknown必须映为blocked，不能跳过。
- 新cap只声明`document_cleanup`，且必须实际装配持久账本、执行器、全操作栅栏和status/control。`document_removal`只表示A。旧document_delete/reindex及旧管理action=delete/reindex合同保持501/不可用；新批量清理是本切明确的新入口，不把未证实的全局硬删除冒充已有can_delete能力。
- 所有控制沿既有可信Actor、Origin、safe error、no-store、request ID。raw当前writer ACL用于墓碑记录重放；降权/撤权统一404，不借旧receipt放行。没有恢复正文或撤销墓碑接口。

## 2. 全操作准入与实际退出

- 使用一套具体的整库operation gate，而非另建任务总线。普通HTTP、实际后台parser/index/synopsis执行、image/audio/sound/video-av build及所有answer/voice/query/native线程根操作进入；在真正body、后代进程、输入writer及私有临时资源释放后finally退出。异步HTTP返回、等待超时、Future cancel、claim取消不释放仍执行的body准入。
- 清理控制及安全状态读取不占普通body lease；执行器以非阻塞方式取得整库维护窗口，只在全部实际操作退出后执行。封新入场与确认零active必须同锁；维护中普通入口明确503 migration_incomplete，不能继续读取已在清除的正文。等待不持Store事务。短窗口结束恢复无关资料操作。
- 单项准入必须先当前ACL再idle复验，登记与墓碑仍是权威短事务；并发准入/清理不得留下新claim或晚publication。源码没有完整实际root/临时证明时，对应资源blocked，不能用旧两个job表为空当完成证据。
- 首个具体维护实现保守要求整库根操作idle；即使活跃操作尚未精确归属该资料，也返回document_busy且零变更，不猜测可并行清理。该限制是维护准入边界，不把无关操作改成已撤下。
- 新受控工作目录由本库owned root及可信无正文owner/进程身份记录管理；只清本库登记路径且确认后代退出，NOFOLLOW/路径边界/有界枚举。旧无所有权目录不得按/tmp前缀扫删。全局collection lease inode、共享二进制/配置和仓库/交接工件从不删除。

## 3. v22真实正文清除

- v1..v21迁移方法原字节保留；v22新迁移对受旧CHECK限制的正文表做受控重建，列/ID/hash/外键/历史指针保留，只增加显式正文缺失状态和必要约束。FK关闭只在独占Store启动迁移外层，事务前后恢复ON并做foreign_key_check；失败不覆盖旧库。
- 普通连接/普通SQL不能开启purge context。Store内部专用维护session需要真实cleanup claim、封存plan、目标墓碑及维护准入；窄connection-local函数或等价私有能力仅在该session有效，不接受SQL表/HTTP/env直接toggle。只允许原body→规范空值且全部非正文列不变，禁止回填、删除身份、REPLACE或清live资料。旧no_delete/no_replace保持，非目标guard不变。空正文INSERT仍按原有效性拒绝；本来合法的空转录照原规则。
- 真正清除：corpus_documents.original_blob；sound_originals/video_av_originals.original_blob；corpus_pages/segments.text；image_evidence.recall_text；audio_spans.text；video_frames.frame_blob及recall_text；video_transcript_spans.text；video_frame_ocr/video_ocr_segments.text；video_subtitle_tracks/cues.text；synopsis_entries.text；sound_spans.recall_text；sound_trace_evidence/video_av_trace_evidence.facts_json。JSON规范空值[]，其他正文空字符串/零BLOB。
- 对没有单列正文SHA的摘要/recall，清前封存完整正文hash、row identity和真实字节计数；已有SHA代表原材料，不能重算为空SHA。全部ID、页/CP/时间/样本/tick/框、hash、模型policy及共享trace完整scope骨架保留。不能删除包含其他资料的整个trace。
- v22启动逐行验证已purged行仅属墓碑+封存计划且body规范为空、非live、FK完整；guard需验证实际结构而非只数名字。来源先当前scope/墓碑判不可读，不能把合法purged历史当有效空证据或复活资料。
- 保留documents.size_bytes审计身份；原件容量按实际resident原件计量，包含legacy/sound/video-av三路并统一现有256MiB工作区原件上限。墓碑本身不减账，已实际清空原件才释放。派生、远端与备份不伪装成原件容量；不开展usage/计费。

## 4. 投影全部代次与外部证明

- 新资料/各投影attempt在第一次可能写远端前持久登记document/workspace/source/generation/route/非秘密qualified target，write-issued intent先于调用；成功publication绑定attempt。覆盖legacy文字及四新build、VideoAV两路部分成功。不能只枚举active或成功publication。
- 旧text attempts保留；旧新媒体失败代次或旧target地址不能反解时按legacy inventory未知标blocked。当前配置只可满足identity精确匹配的目标，不能猜旧集合。确认目标上的删除以workspace+document严限定全代次，发现跨组织/资料冲突则阻断，不drop共享collection、不create/改schema、不调模型。
- REST v2 delete实际使用dbName/collectionName/filter，code0/data={}是合法回执；删除+强一致零行只标remote_logical_rows。本机child退出或远端请求超时不能证明remote_write_terminal，compaction触发/完成也不能代替GC及备份完成。缺真实可验证fence、GC/备份合同则相应resource blocked，整体保持未完成。
- 已登记且能证明从无投影尝试的新纯本地资料，远端项not_applicable，必须有实际清理完成正例。无publication不能推导从未写远端。任何production/provider/旧远端操作需原会话对应授权，本机本切仅合成loopback协议验收。

## 5. 文件、副本和恢复

- 清前封存resource plan、原行/字节/hash及目标清单，先fsync本库独立hash-only删除intent/high-water，再执行不可逆动作。journal绑定library identity；正常启动发现旧库/高水位缺失或不匹配须拒绝或显式维护恢复，不自动跑旧queued任务。它只防恢复复活，不能冒充备份内容已清。
- 正文事务后，在排他writer lock与全操作维护窗口内实际secure_delete/数据库压实、核format/FK/正文缺失/非目标不变并fsync。Store事务不覆盖网络或文件等待。未整理当前DB/free页/journal/暂存副本时database_file不能completed。
- 只处理本库明确生成并登记的迁移/业务副本。可将旧受控快照净化并原子替换，或按明确策略使旧快照失效并生成当前净化替代；保留其他资料及审计。不得对未登记路径或共享OS快照猜测删除。未知副本/legacy tmp保持对应未完成，不能默认为不存在。
- 完成承诺的scope固定`application-managed-v1`：本程序登记的库、临时路径、备份、投影及恢复屏障。用户已下载文件、浏览器已交付副本、第三方provider留存、未知外部备份/设备快照和取证安全擦除均不可推定完成。若某在范围资源的inventory或策略未知，blocked；不能偷偷排除已知managed副本。
- 崩溃按封存plan、intent和实际阶段幂等继续；不自动恢复旧正文求可用。任何清除/compact/文件/fsync/audit失败不得final completed；异常只存safe code。终态回执一次封存。

## 6. 真实完成与页面

全部九资源必须completed或经过真实证明的not_applicable，且墓碑/plan/source身份一致、无active本机body/child、无未终结旧写、所有受控原/派生body和副本已清、远端全部代次可见及物理资源完成、恢复屏障一致、最后audit成功，才可写status=deleted/cleanup_status=completed。完成未证时继续公开真实pending/blocked/failed。

页面准确确认单项或完整批量，dirty draft沿原放弃确认；取消零请求。独立CleanupSession/处理记录入口保留accepted后回执，刷新普通列表并释放已撤下媒体。accepted只显示“已撤下，清理待完成”；failed/blocked仍是已撤下未完成；unknown只提示重新核对，不自动POST。404不等于删除完成。身份epoch、迟到回执、选择与草稿隔离；停止只停本地等待。短mutation锁在回执后释放，GET轮询不阻塞工作台。

代理新控制仍128KiB/10s短请求，精确route/方法/query/body/身份/Origin/Host/no-store/request ID及零自动重试。页面由用户验收，本机DOM替身验证不能冒称实际浏览器验收。
