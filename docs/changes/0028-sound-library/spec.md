# Spec · 0028 独立声音知识库

2026-10-03实现前冻结。独立默认关闭rag.sound.enabled，仅字面loopback development/test且rag.ingestion.enabled；不依赖旧ASR、AudioCompilation、OCR、视觉或文字answer模型。实际SoundLibrary/SoundAnswer完整装配才暴露sound_upload/sound_index/sound_answers/sound_sources/sound_query_attachments五项能力，启动0网络。不猜模型默认、不配置或调用真实provider。

## S01 管理与完整发布

精确POST /v1/sound-documents：原始audio File、octet-stream、唯一合法X-Filename、无query，原六类audio MIME/扩展名、1..20MiB、30秒接收、2在途。X-Filename为encodeURIComponent(filename)的UTF8 percent编码，服务器只strict解码一次、不把加号当空格，再校验完整filename；只有该精确路径转发此头。只保存原文件/资料/owner ACL，不调用ASR或模型，不造旧ASR任务。返回document_id/source_revision_id/source_sha256/size_bytes四字段。资料进入原文件夹/标签/权限/删除列表；未建索引为ready/not_indexed且synthetic_fixture=false，原文件可读；sound发布后parsed/indexed/active source和独立publication，无旧ingestion/indexTask、can_index=false。原文件详情兼容新source；旧真实audio也可显式建sound，原speech链和任务保持。

认证精确GET/POST /v1/documents/{id}/sound-index，均无query/body。reader只GET，当前editor显式POST。GET0解码/模型，完整同profile幂等0解码/模型；不要求旧speech publication。冻结source SHA、decoder、全部samples/window manifest与sound模型/embedding target/chunk profile。实际16k mono S16LE按样本连续切段，含静音、不截尾、不造转录；单段≤30s、原音频≤600s，空描述仍封存和原声embedding。每次未合格构建新UUID generation/physicalID、独立java_sound集合。默认120秒、2在途、同doc一项，无自动建/重试/迁移。真实解码后独立子进程完成全部describe/PCM embedding/完整投影verify，短提交事务复验source/SHA/profile/ACL/整组映射才封存。

## S02 模型和完整范围检索

SoundModels.describe/draft/verify实际完整canonical WAV；describe仅召回，draft听库内原声回答完整原问题，verify独立请求原声/完整问题/全部claims。非完成、截断、重复或未知语义字段、非有限向量、含指令事实、支持数不一致/不完整整体失败，不执行工具。时间仅服务器samples/windows；模型不得创造逐词或事件精确时间。Google Interactions当前steps协议、store=false/stateless/无previous_interaction_id/session/URI；A先冻结wire-contract.md后实现。Google同空间embedContent，文本原完整question、音频每个完整查询波形，分别请求、不加taskType/前缀，autoTruncate=false。模型/版本/decoder/dimensions显式；实际模型范围128..3072，低维2..3072仅显式loopback合成。

SOUND模式专用POST /v1/sound-answers：精确question/document_ids；POST /v1/sound-query-answers：精确mode=SOUND/question/document_ids/attachments。原非空问题4096 UTF8字节、selected/all/显式空语义不改；附件1..3个audio、总20MiB/JSON28MiB，一次真实解码保留全部PCM含静音，不调用ASR/voice/describe，不入库/引用。全部scope/receipts/profile在query解码及embedding前合格；任何sound缺失则前置HTTP409 sound_index_required、0解码/模型，不构造伪publication/scope/trace，不能删资料或回退ASR。all为完整当前授权真实audio，selected逐项真实audio/current ACL，max128。每路全文与每query span DENSE_ONLY，workspace+document-generation过滤在topK前；整路所有candidate先完整authority映射再融合≤64。资格合格后的无证或证明失败才产生完整scope的abstained trace。向量/附件均不证明事实。

## S03 独立原声证明与typed来源

所有证明窗口重新实际解码原文件，按已封存samples取完整PCM并匹配PCM SHA，描述不作事实上下文。完整问题draft后独立verify全部facts，complete=true且全supported才回答。同一窗口必须支持整个问题，不拼几个半问题；合格答案相互冲突拒答，不先取一个就跳过其余candidate。provider前后和最终短trace事务复验完整scope（含未引用资料）；取消/profile/source/ACL变化不提交过时回答。

新sound_span citation携facts/facts SHA、source SHA/revision、sound publication/profile/decoder/model/policy、actual sample范围/rate16000及服务器start_ms=floor(start/16)、end_ms=ceil(end/16)、time_precision=server_window。无伪machine_asr/quote。GET /v1/sound-sources/{answer_id}/{1..32}及/content校验same actor/workspace/current ACL/current publication/profile/source/完整proof；content为同SHA完整原音频和原单byte Range。trace只存问题/回答hash与事实proof绑定；facts标明声音模型判断。重启来源0解码/模型，拒答无citation，policy java-sound-answer-v1。

## S04 追加数据及兼容

v19只加独立sound_originals/publications/spans/traces/trace_documents/trace_evidence，不改旧corpus/speech/v18语义。source FK documents，metadata immutable，blob仅当前tombstone后可擦除。publication完整窗口child先写header后seal，连续samples/ordinal/计数/身份/manifest不可变；trace完整scope/evidence同样。迁移先备份，删除覆盖独立blob；关闭能力仍保留表。描述不自动进入摘要/标签/语音提问。

## S05 真实本机正常链

无语音合成声音含静音gap/尾部差异，实际Spring/SQLite/FFmpeg：上传0ASR/模型→整理→完整sound index→文字/纯声音附件召回→独立draft/verify→typed时间来源/原文件Range→重启0模型。对照描述相同PCM不同、尾段命中、private排除、scope缺一项0providers、无证/半问题/冲突拒答，保留旧speech/image/voice/附件正常链。root串行Maven/Spotless/完整gate，旧2042默认身份多重性及299前端断言保持，原80%门槛不改；输入/class/JAR绑定、新handoff和独立审计。替身不认证真实声音/ASR/网页/生产，总目标active。
