# Milvus鉴权：零模型真实集成验证

2026-09-08 10:32:04 +08:00，实际Temurin21.0.12.1+1执行新增[MilvusAuthenticationLiveIT](../../../src/test/java/com/evidence/rag/client/vector/MilvusAuthenticationLiveIT.java)：1项通过，0失败/错误/跳过，suite 2.717秒，Maven总计4.054秒。没有构造模型client或调用模型；不代表真实PDF问答、生产RBAC/TLS或总体RAG验收。

## 环境与边界

新建可丢弃standalone实例使用Milvus2.6.22/Linux arm64，固定镜像摘要 `sha256:defbbd212727ff06507acc8b4a21531d269be85d467ea304fb1c1eff514d00e9`。只使用任务专用Docker引擎、新命名卷和非默认loopback端口，不共享旧测试卷、不重启或修改旧实例。新容器限制2CPU/2GiB，使用默认seccomp；这是小样本测试配置，不是容量结论。

根据[固定版本官方研究](../../research/milvus-2.6-authentication.md)，首次启动即开启鉴权，并使用外部生成的随机root密码。配置和凭据保存在仓库外的0700目录/0600文件中；不提交值、认证头、容器配置或原始运行日志。root仅用于这个隔离测试，不作为生产最小权限设计。

## 验证行为

- 同一受保护请求对照：缺凭据、未知用户名、有效用户名但错误密码都必须返回HTTP401/code1800；生产Milvus Adapter同时必须抛出 `projection_remote_failed` 且无异常cause。连接失败、403或超时不能算鉴权负例通过。
- 正确凭据通过真实 `TextAdapterSettings.load` 加载；先验证专用库为空、随机集合不存在，生产writer初始化成功后才记录清理所有权。
- 写入一条含中文/emoji的合成正文和固定4维向量，完整manifest与精确digest验证通过；独立reader只读准备并检索，唯一physical ID和dense/BM25双路RRF贡献 `2/61` 均符合断言。
- 退出删除本次owned随机集合并验证不存在；之后再次回读鉴权测试库及为未来PDF链路预建的专用库，两者均为0集合。未删除数据库、卷或其他实例的数据。
- 10:23:54缺显式ENABLED的配置保护运行按预期1 failure/0 error/0 skip，未执行HTTP；它不是产品故障或有凭据测试的替代。

实际密码精确扫描本次工作文件、Surefire报告与执行日志共453文件，0匹配；此前容器最近10000行日志的有界扫描也未匹配。该结果只覆盖明确扫描范围，不表示聊天、全机或所有日志历史已清除凭据。

## 重放入口与证据绑定

默认 `clean verify` 编译但不运行该 `*IT`。只有准备新的鉴权隔离实例、专用空库，并通过安全进程环境提供以下变量后，才显式运行；不要把真实密码写在命令参数、文档或shell历史里。

| 前缀 | 必填后缀 |
| --- | --- |
| `RAG_MILVUS_AUTH_IT_` | `ENABLED`（true）、`ENDPOINT`（非默认127.0.0.1 HTTP端口）、`DATABASE`（java_it_auth_前缀）、`TOKEN`（有效用户名:密码） |

```bash
mvn -s .mvn/settings.xml -gs .mvn/settings.xml \
  -Dtest=MilvusAuthenticationLiveIT test
```

| 工件 | SHA256 |
| --- | --- |
| 最终测试源码 | `7859606217a48061c78585bae01d91a3c32247314be1aa4dde2e5579acbbb24a` |
| 独立冻结JUnit XML | `99a03e2a9f295102d63143101cebfcb2d3dd8e41cefd2eda7b6fcddded67c2df` |
| 独立执行日志 | `c9229a9c0a85e5291077d4d6976e487b283407c6a4c38e37b6c652f620b21f5b` |
| 首次HTTP鉴权/专用库准备安全摘要 | `5fd46c15461f65d2ac914ea51e16d6dca89e612a0fcb950e65d2fbff5dbe8578` |

原始执行工件留在受控本地，不随代码发布。独立限定复核见[REVIEW](REVIEW.md)。两次获准生成诊断已用完；本测试不使用该额度，也不授予新的模型调用权限。[固定PDF完整链路](text-answers-live.md)仍未执行。
