# 0012：原图证据进入知识库主链

状态：IMPLEMENTATION；承接负责人“多模态作为主分支开展落地”的实现授权，不是新 Git 分支或发布授权。

输入：生成的无文字 PNG/JPEG、一个包含全部事实要求的问题、完整所选文档范围。
正常路径：上传→原图描述（只供召回）→独立图片证据→嵌入与 Milvus 完整发布→授权检索/重排→原图 draft/verify→整图引用及原字节回读。
可观察输出：持久任务 parsed/indexed、无假页码的 image_region 引用、相同 SHA 的原图；证据不足则整体拒答。

沿用 Spring Controller/Service/Repository/Model/Security/Client/Config 职责及既有完整 scope/ACL/active/trace。不实现新的权限体系，不更改前端、Python、旧服务或数据，不推送/部署。真实云调用仍须新增明确预算，不使用文本余额。
完整视觉 embedding、扫描 PDF、音频、视频、联合证据、摘要与生产目标保留为随后主线。
