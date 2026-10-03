# Verification：复现与验证进度

状态：LOCAL_VERIFIED，交接冻结以实际manifest/VALIDATION为准。0033 frozen manifest `05c91e70e8968622bcbb323a86182569e8a6eeb31a1a779f1712ec1e5c55010e`保持历史事实。当前证据保留在工作区 `.tools/media-role-switch-preparation`。

后端来源边界的首次实际运行：15项、9项断言失败、0错误、0跳过；全部1032执行输入前后相同，原1029输入字节保持，仅增加三份测试。实际复现managed角色切换后的三类视频来源不可读及错误能力声明。日志为 `backend-boundary-red.log`，SHA256 `b2e94b146840454d4ea0dccc17e11a25821d0492d4f4704c5de97ac564bd34cf`。

HTTP首两次分别因新夹具配置优先级、视频未显式发送video/mp4而失败，均为2项ERROR、0项FAIL，不计业务RED。日志、XML、前后输入及修正前源码独立保留，未修改产品或旧断言。第三次为2项、1FAIL、0ERROR、0跳过：切换成功后的旧视频metadata GET实际503；独立旧媒体POST拒绝及零额外调用用例已PASS。日志 `backend-http-red-third.log` SHA256 `d0765fabbba7ceb6c719cd038566144fe1d3aac0e0108002f156e0796a159674`；不存在新POST执行门禁缺陷，原静态误报已经撤回。

实施仅后端三个产品文件及前端app.js来源能力判断；无新构造、Bean、provider、schema或route。A实际独立限定审查22源码及6当时合同，无阻断；旧profile、操作门禁、权威ACL/来源/字节路径及旧测试保持。

root相关回归实际66项全部通过，1032输入前后相同；原严格legacy构造、现有过滤器、Config、文字角色切换一并执行。同一新HTTP类完整运行了普通视频/OCR/字幕/图片来源、生成及重排连续切换、新文字角色、原metadata/Range/SHA、旧行和upsert保持、重启零调用与撤权404。合成编译资料和手动Module索引是明确夹具，不认证原生解码或模型质量。日志 `backend-related-first.log` SHA256 `0e111e342a25af8fceb67e7ffc0f287b9ccf84986b215ce82b203e9e65f06cc9`。

root实际 `clean verify`：3075项、0失败/错误/跳过，1016文件格式及原架构、LINE/BRANCH双80%门禁通过；1032完整输入前后相同。LINE30620/2384=92.776633%，BRANCH16156/3918=80.482216%。日志 `backend-clean-verify-first.log` SHA256 `ac10ae17e0ff2afa18aa80988372d93bd16cced7180806c773e0c10de4be41c2`；完整404个suite XML/txt与JaCoCo另留。

前端同一条新DOM用例实际RED→GREEN，完整431项及语法检查通过，64执行输入前后相同，原430用例身份及次数保持。本轮为实际前端重跑；后端18个Node/static输入已重新核对原字节，73项沿用旧0032实际执行证据，明确NOT_RERUN。

六Native第三轮各1实际通过，0失败/错误/跳过：SoundLibrary、AudioVectorRetrieval、VideoAvLibrary、VoiceQuestion、ImageVectorRetrieval、QueryAttachmentLibrary。真实FFmpeg/FFprobe9.0.2与Tesseract5.5.3处理本机合成媒体，全部远程端仍是loopback替身；1032输入及761完整生产class前后相同。日志 `backend-native-third.log` SHA256 `0f709dbb4d6a765ab88cea49bdc261b61893b31c864d211fde85e071bc805da4`。root首次Native命令漏显式启用参数，第二次五类被非规范工具路径拒绝、VideoAv一类通过；两次原始结果均保留为命令错误，不计业务RED，也未改或跳过旧测试。

最终JAR SHA256 `a5a6f00b3197a199e6470a39d6a3fea7e741ed7040bcea4ba44be240c8fbb457`，39619998字节。核验器经独立静态复核后由root首次实际执行，`independent-release-audit-first.json`为PASS：完整761 class、7 resources及JAR字节一致，旧3058身份次数精确保留；旧0033/0032/0030全部2893/10358/4475绑定保持。工具准备或语法检查本身未当作审计PASS。新前端累计补丁在0030完整public/scripts副本上实际dry-run和apply后，全部27文件与当前一致；这不是现网fork适配或部署通过。

真实供应商、Milvus、中文ASR质量、浏览器及本增量部署均NOT_RUN。部署任务最新记录仍为voice-tags入口复验；页面由用户验收。完整目标保持active，retrieval-only范围入口、通用重建/嵌入迁移及同资料内容版本更新仍依原范围继续。
