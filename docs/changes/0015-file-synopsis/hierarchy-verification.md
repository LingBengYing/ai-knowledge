# 0015 完整长文件摘要：本机后端验证

2026-09-20 14:33:25 +08:00，完整长文件→分批/分层候选→逐条原始引用证明→全部原批次复查→持久任务/结果→重启与末尾原始来源回读通过。不是仅内部Module，也不是云模型语义质量、前端或生产验收。旧有界模式及其历史验证保留，不覆盖上步工件。

## 用户正常路径与可观察结果

通过原 `POST /v1/documents/{id}/synopsis` 提交超过短模式上限的已索引资料，后台生成后，原GET/任务/来源接口直接可用；无需第二套任务或来源系统。

- 实际Spring HTTP、SQLite、已有摄取/索引Service封存的长文字（超过64条）、17段合计超过64000CP音频转录、9帧加转录/OCR视频均轮询至available。每次leaf与review输入逐项比较，顺序、数量与完整真实publication材料一致。
- 摘要来源仍指向真实原文/原音频/原帧：最终材料引用、音频末段16–17秒、视频第9帧8–8.2秒、原文件SHA和单Range206均实测；重启后结果/来源一致，模型调用数不增加。
- 合成模型故意从中间候选遗漏文件末尾的撤销条件；最终原批review仍见到该原文并否决整份结果，返回unavailable/unsupported_claims，已发布文件索引不变，重启不重试。
- 2049条原始材料的多层合并测试核对完整叶顺序及尾节点；最终只把条目实际引用的原证据交给verify，不把派生摘要或caption当成事实证据。
- 每批64条/64000CP/8图/10MiB原像素，实际大于8MiB的合法PNG通过独立模型协议原字节发送校验；完整文件上限4096条/250万CP/128图/32MiB，不以截尾方式“支持任意长度”。
- v14真实迁移与备份重开保留旧available结果及processing任务身份/时间/状态/指纹/关系；旧v1–v13迁移体未改。短模式模型/策略/原v1指纹保持，长模型版本变化不使旧短结果失效。

模型为真实loopback HTTP固定协议替身；媒体编译输入是合成原帧/转录，不是本轮重验native上传或真实ASR/VLM。全文件遍历和复查调用结构已经验证，模型自然语言判断正确性与实际长文件质量尚未验证。云请求0。

## RED → GREEN 与最终门禁

| 阶段 | 时间（+08:00） | 实际结果 |
| --- | --- | --- |
| Domain/材料/存储/模型/Service RED | 14:16:09 | 45项，28failure/11error/0skip：显式桩、旧版本13与旧完整输入上限；无编译或环境错误冒充产品RED |
| 核心GREEN + HTTP接线RED | 14:23:43 | 50项，48通过；两项真实HTTP因旧消费者input_capacity_exceeded失败 |
| Library接线RED | 14:25:55 | 5项，4个长材料领取失败、旧短模式1项通过；报告只取本次执行的具名suite，未计陈旧XML |
| 全部新旧摘要定向GREEN | 14:30:20 | 133/133，0failure/error/skip，包括两个新长HTTP用例和旧短HTTP完整回归 |
| 最终clean verify | 14:33:25 | 1485 Java、193 XML，0failure/error/skip；旧1429 case身份与多重性全部保留 |
| 格式/架构 | 同最终verify | 512 Java文件格式及现有架构检查通过，无新增白名单 |
| 覆盖率 | 同最终verify | LINE14029/15017=93.420790%；BRANCH7430/9265=80.194280%；双80%门禁未改 |
| Node | 本批 | 73/73，0failure/skip；前端源码未修改 |

真实JBR21.0.8+9-b1038.68、Maven3.9.9，离线缓存、新临时隔离构建与数据；根代理唯一Maven执行者。构建目录 `/private/tmp/java-synopsis-hierarchy.nZT6VK` 保存三阶段RED、定向GREEN、最终日志/193报告/覆盖率/JAR。没有更换运行中JAR，没有触及旧服务或真实数据库。

## 源码绑定与审查

- [hierarchy-source-manifest.json](hierarchy-source-manifest.json) 绑定539输入、193份默认报告以及日志/覆盖率/JAR摘要。
- [hierarchy-test-cases.json](hierarchy-test-cases.json) 保存1485精确case identity与多重性，原字节SHA为 `70679d4e7ed8f929f33d7b5071048d1be7c72d9d703d55fa4c48c4b1e7bac5e0`。
- 上步521输入中496未改、25具名修改：9个生产文件用于完整材料/旧指纹委托/完整claim/存储迁移及消费者接线；15个旧存储测试仅当前版本与v13恢复链必要适配，1个Config测试增加独立长模式装配断言。旧case没有删除、跳过、放宽。新增18个Java输入，包含全部新测试。
- [REVIEW](REVIEW.md) 分别记录独立限定Standards/Spec审查；当前未发现本步未关闭的主线阻断。独立制品审计结果完成后追加，不能用源码审查代替运行证据。
- 已有鉴权/完整scope/来源校验、默认关闭/本地环境合同、密钥保护及无自动重试不变；本轮不改前端、Python、Git分支/提交/推送/部署，不动云调用额度。
- 跟踪内容/历史及最终359个未跟踪文件密钥扫描均无finding，`git diff --check`通过；生成工件后重核539输入，冻结后变化0。扫描只输出路径/类型，不输出实际凭据。

## 偏离、边界与后续

按codebase-design的小Interface和fullstack-dev的集成验证方法，保留项目已有layer-first Spring/SQLite/REST风格；没有引入通用任务框架、ORM、权限系统或自动重试。为完成正常HTTP闭环，采用现有上限内完整不可变输入与有界模型batch；分页/流式内存优化留待测量，不以它阻挡本次交付。

本步完整长文件本机后端已交付。模型布尔自评不是自然语言正确性证明，完整多模态与生产目标仍IMPLEMENTATION。后续业务主线为独立字幕轨与typed时间来源；查询附件、网页交互、真实模型代表性质量/费用/延迟、生产部署仍需后续完成，不反复重做本步协议/迁移/HTTP诊断。新增云请求继续单独授权，不挪用历史文本余额。

## 独立制品复核 PASS

非实现代理只读使用Ruby/REXML/Digest/unzip直接复算，未执行生成helper、Maven、网络或Git写操作，全部无差异：539输入在仓库/build/manifest一致，旧build的521输入也匹配历史manifest；496未改/25修改/18新增与声明一致。193XML为1485/0/0/0，旧1429身份/多重性精确保留，三组RED与133定向GREEN准确，Library RED仅采用具名一份XML。两JSON单LF、case原字节SHA、193报告与8项日志/覆盖率/JAR摘要、覆盖率/512格式/Node73/14:33:25 SUCCESS全部一致。额外403个生产class的JAR与target/classes集合及逐字节SHA一致；旧测试必要适配与v1–v13/旧短Interface/Adapter/transport保留均获确认。此PASS不扩展为真实模型语义、前端或生产验收。
