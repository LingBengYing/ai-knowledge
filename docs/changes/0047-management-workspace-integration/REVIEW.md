# 当前记录

已实现后端适配并启动本机工作台。前端0034/0035使用原HTTP合同；原18084后端未启动是本机连接失败的直接原因。源码证据：TextRetrievalTestService.execute原先对textPublications和完整scope做无条件集合相等判断，混合媒体全库在远程请求前拒绝；本轮仅放开all范围的文字候选筛选，完整scope与显式selected规则保持。

## 实现与构建

- Service只修改全库文字候选判定及无文字候选的原协议empty/no_matches结果；Controller、DTO、Repository、索引和证据校验没有改动。
- 显式run-workspace.sh使用workspace Spring profile，并继续通过run-dev.sh复制不变JAR。默认独立`.data/workspace`、本机loopback、开发身份、managed模型设置、真实文本摄取；普通启动/JWT默认不变。
- 2026-10-04 14:09:01 +08，JDK21运行`mvn -ntp -s .mvn/settings.xml -gs .mvn/settings.xml -Dmaven.test.skip=true package`成功，4.951秒；未复制测试资源、未编译测试源码、未执行测试。
- 首次启动被执行沙箱拒绝绑定端口（Operation not permitted），进程已退出。获执行工具许可后在同一本轮新建目录重启，14:10:59 +08正常监听127.0.0.1:18084，新前端18085同源代理已连通。该环境故障不计产品或测试失败。

## 实际HTTP操作

以下均经新版前端现有18085代理发送到真实Java/Spring/SQLite及解析子进程，没有替身、模型调用或旧数据复用：

1. `/v1/config`返回management、text_upload、ingestions、document_originals、model_configuration等真实能力；migration_stage为text_configuration_required，索引/召回/回答尚不可用。
2. owner读取模型设置成功：unconfigured、version0、active_version=null、三个has_key=false、projection.configured=false。
3. 14:12:00 +08上传本轮自写合成TXT，HTTP202；任务`138ea9a7-115a-47fb-b389-9d348452fd45`于14:12:01进入parsed，attempt1、无error、segment_count1。文档`298defc6-48cd-4dda-ae23-0a1398f7432b`的index_status仍not_indexed。
4. 管理列表、原任务、原文件metadata及原字节回读成功；版本`dc93d54d-6a81-4035-ab26-d2a829b08198`，原文件220字节，SHA为`204fa19240de277c895089985aa0ebab338d376f1d3658ea185adcfae66b9bea`。
5. PATCH整理显示名和两个标签返回HTTP200，原文件名、版本、SHA和解析任务未变。合成资料保留供用户打开新版列表/详情/任务。

## 未验证与偏离

Chrome实际返回“此页面已被Chrome屏蔽 / ERR_BLOCKED_BY_CLIENT”，未绕过浏览器限制；因此本轮没有认证新版详情分区、聊天布局和召回页面的实际DOM交互，按计划改用真实同源HTTP操作核对后端链。不能把HTTP成功写成浏览器通过。

全部自动化测试、语法/格式检查、审计仍未运行。全库混合媒体召回适配已编译，但本机模型未配置且没有本轮新增调用额度，未执行真实检索/生成或混合媒体运行态回归，RAG acceptance记未运行。无新schema或既有数据迁移，无前端源码修改、Git提交/推送或远端部署。0046及前端0034/0035既有未提交修改保留。
