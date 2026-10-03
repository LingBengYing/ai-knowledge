# 原视频音画知识库

0029新增完整连续视频与原音轨的独立索引和问答，前端0018提供「原视频音画」上传及画面、声音、联合三种文字问题模式。原件管理、索引和引用独立于旧视频的抽帧描述、转录、OCR和字幕路径。

## 使用顺序

| 操作 | HTTP接口 | 身份与材料 |
| --- | --- | --- |
| 保存原视频 | `POST /v1/video-av-documents` | octet-stream；`X-Filename`只解码一次UTF-8百分号，字面加号保留；201四字段receipt |
| 读取索引 | `GET /v1/documents/{id}/video-av-index` | reader可读，十四字段missing/available；零解码和模型 |
| 显式建立 | `POST /v1/documents/{id}/video-av-index` | editor明确建立全部实际窗口和两路receipt；当前完整索引幂等回读 |
| 提问 | `POST /v1/video-av-answers` | 精确JSON `question`、显式`mode: VISUAL/AUDIO/JOINT`及可选`document_ids` |
| 来源说明 | `GET /v1/video-av-sources/{answer_id}/{ordinal}` | 原回答者当前授权、完整保存scope和proof复验 |
| 原视频 | 上述来源加`/content` | 完整同SHA原视频200，支持单byte Range |

省略范围表示全部当前授权真实视频，含没有音轨或最终未引用的视频；显式空数组保持空范围。任一资料缺当前完整同profile索引时返回409 `video_av_index_required`，不开始解码或provider请求。此切接文字问题；视频参考附件未接此新入口。

索引和来源接口禁止query/body。raw上传只核对MP4/MOV/WebM/MKV扩展名、对应magic及20MiB大小上限，保存完整原件时不解码。显式建立索引时才由native核验实际封装与完整轨时间，要求源时长≤600秒；失败不会发布部分索引。前端核对上传原件完整SHA，建立索引期间保留整理草稿；停止等待后显示结果未知，须回读状态。

## 时间、媒体与事实

时间身份保存原始整数PTS和约分有理time base，以L=lcm(time-base denominator,16000)构建共同精确tick轴。窗口从首个视频帧开始连续覆盖整个视频和实际音轨尾部；每窗口最多30秒，最多1201个。分割使用真实帧边界，逐帧核对PTS、duration、尺寸和完整RGBA像素SHA。输出视觉MP4移除音轨；声音使用同epoch完整16kHz mono S16LE PCM/WAV，保留前部延迟、静音和实际末尾样本，不填充音轨尾部。

缺画面/声音用明确null和ABSENT route manifest表达。纯视频仍是合法完整publication；一个窗口两种材料不能同时缺失。窗口MP4上限8MiB，全部MP4 payload最多64MiB，全部PCM payload最多19.2MiB。83.2MiB只是两类payload合计，原件、frame metadata、序列化及防御copy另占内存；不是已证JVM峰值或一般容量结论。

索引只嵌入实际MP4/WAV，分别写入两个新的`java_video_av_`集合，在同一个显式多模态embedding空间内召回。索引不生成描述或转录；新UUID publication也是两路共同generation。只有所有条目和双路完整receipt、source/profile/ACL复验合格后才封存。v20追加六个不可变表，派生媒体不存数据库。

召回后先映射每路全部candidate，再融合到最多64个窗口；按父原件逐个重新完整编译并核对封存窗口组。模型draft与独立verify各收到完整原问题、当前模式所需实际媒体及窗口偏移；单窗口必须回答完整问题，不拼接半个答案。

服务器给每个事实固定ID，核验不能增删改ID。事实用精确五字段JSON及SHA绑定到question/publication/epoch/window/媒体/模型/policy。VISUAL事实只贡献画面，AUDIO事实只贡献声音，JOINT事实须两者贡献；联合答案可分别回答独立属性，但因果、同步或发声主体关系须联合关系事实。语义正确性仍依赖真实模型质量，不能由这些结构规则单独认证。

来源时间由服务器产生，精度为`server_window`，不是事件逐词时间。tick/sample/epoch long全部用规范字符串传递，前端通过BigInt复验后使用floor/ceil毫秒展示。完整原视频SHA校验后播放，离页/身份或问题变化释放播放器和Blob。浏览器对非零媒体epoch的实际定位仍由用户页面验收。

## 配置和验收边界

`RAG_VIDEO_AV_ENABLED`默认false，只允许development/test字面loopback及ingestion装配。`rag.video-av.*`独立配置FFmpeg/FFprobe路径和准确decoder revision、Google Interactions模型及固定版本；`rag.video-av.embedding.*`提供同空间text/video/audio embedding；`rag.video-av.milvus.*`提供独立`video-collection`与`audio-collection`。具体键以[application.properties](../src/main/resources/application.properties)为准，凭据由私有环境提供。

原文件/native/model/collection身份和chunk大小绑定profile。模型请求无history、tools或file URI，`store/stream/background=false`，实际序列化UTF-8请求最多14MiB。Google视频处理当前1fps采样，可能漏掉短暂动作，不能宣称每帧语义理解。窗口材料完整性也不等于模型采样充分。

单操作默认120秒，最多2项在途，同资料最多一次构建，无自动建立或重试。实际Library和Answer共同装配后才声明`video_av_upload/index/answers/sources`四能力。启动不访问provider；来源复验完整保存scope及原件SHA，重启回读不调用模型或解码；撤下只在tombstone后擦除raw blob，保留历史metadata/proof。

当前实现和验收记录见[0029验证](changes/0029-video-audiovisual/verification.md)。合成本机Spring/SQLite/FFmpeg和loopback协议替身不认证真实Google/Milvus语义质量、一般容量、浏览器或生产。用户负责页面验收，Git和部署归原责任方；没有新增真实模型调用，完整开发目标保持active。
