# Milvus 2.6.22：隔离 standalone 的最小鉴权配置与验证

研究日期：2026-09-08。范围限 Milvus 官方固定 `v2.6.22` 源码及官方 REST 2.6 文档，为新建、可丢弃、仅 loopback 暴露的独立测试实例提供配置依据；不修改已有实例或数据，不涉及模型请求。本文记录的是源码与文档核对，作者未执行 Docker、Milvus 请求或模型调用；实际验证结果须另记，不能从本文推定通过。

## 结论

可以从首次启动就使用外部生成的随机 root 密码。准确配置名是 `common.security.defaultRootPassword`，不是 `rootPassword`；它仅在 root 凭据尚不存在时初始化凭据，不是已有实例的密码重置机制。最小鉴权验证应对同一个受保护 REST 请求比较“无凭据、错误凭据、正确凭据”，分别要求前两项 HTTP 401 / code 1800，后一项 HTTP 200 / code 0。[配置定义](https://github.com/milvus-io/milvus/blob/v2.6.22/pkg/util/paramtable/component_param.go)、[MetaTable.InitCredential](https://github.com/milvus-io/milvus/blob/v2.6.22/internal/rootcoord/meta_table.go)、[HTTP authenticate](https://github.com/milvus-io/milvus/blob/v2.6.22/internal/distributed/proxy/service.go)

## 1. 启动配置与初始化边界

| 用途 | 准确配置键 | 可采用的环境变量名 |
| --- | --- | --- |
| 开启鉴权 | `common.security.authorizationEnabled` | `COMMON_SECURITY_AUTHORIZATIONENABLED` |
| 首次创建 root 的密码 | `common.security.defaultRootPassword` | `COMMON_SECURITY_DEFAULTROOTPASSWORD` |

第一项须设置为 `true`；第二项的值由受控运行环境外部注入，本文不记录实际值或默认密码。上述环境变量映射是源码推导：`BaseTable.init` 加载环境来源并规范化键名；`EnvSource.NewEnvSource` 收集环境值，规范化过程转小写并去除点、下划线及斜线。因此环境键可对应上表的配置键。两项在参数结构中均标记为不可动态刷新，应在新进程启动前设置。不要同时配置多个冲突的别名并依赖未经验证的覆盖顺序。[BaseTable.init](https://github.com/milvus-io/milvus/blob/v2.6.22/pkg/util/paramtable/base_table.go)、[EnvSource.NewEnvSource](https://github.com/milvus-io/milvus/blob/v2.6.22/pkg/config/env_source.go)、[commonConfig 配置定义](https://github.com/milvus-io/milvus/blob/v2.6.22/pkg/util/paramtable/component_param.go)

`MetaTable.InitCredential` 的顺序是：读取 root 凭据；已存在便返回；不存在时读取 `DefaultRootPassword.GetValue()`、加密，再保存 root 凭据。`Core.initCredentials` 在启动流程调用此初始化。因此必须使用新的元数据存储及新卷，不能通过改配置轮换旧实例的密码。此次读取的固定 tag `internal/rootcoord/meta_table.go` 原始文件 SHA-256 为 `b428df092ffd64455e624c61d54f65c1f4cebddb4ea4046f43005c27b5feaca6`；链接按函数名定位，不依赖浏览器转换后的行号。[MetaTable.InitCredential](https://github.com/milvus-io/milvus/blob/v2.6.22/internal/rootcoord/meta_table.go)、[Core.initCredentials](https://github.com/milvus-io/milvus/blob/v2.6.22/internal/rootcoord/root_coord.go)

鉴权与 TLS 是不同配置。loopback HTTP 仅用于本次隔离验证，不证明网络传输已加密，也不构成生产部署建议。环境变量可能被容器管理工具读取，运行记录应采用字段白名单，不能保存完整环境、认证头或未脱敏的 inspect 输出。[官方 2.6 鉴权指南](https://milvus.io/docs/v2.6.x/authenticate.md)

## 2. REST 身份格式与最小管理入口

REST 请求使用 `Authorization: Bearer <username>:<password>`，即用户名与密码通过冒号连接，不要将整个凭据 Base64 编码，也不要使用模型提供商密钥。固定源码的 `ParseUsernamePassword` 对 Bearer 值按第一个冒号分割；Basic Auth 另有独立处理分支。本次采用 Bearer 用户凭据，不使用插件 API-key 扩展路径。[ParseUsernamePassword / GetAuthorization](https://github.com/milvus-io/milvus/blob/v2.6.22/internal/distributed/proxy/httpserver/utils.go)、[authenticate](https://github.com/milvus-io/milvus/blob/v2.6.22/internal/distributed/proxy/service.go)

下列接口均为 `POST`。表中只列字段名，密码值只能来自外部受控凭据。

| 操作 | 路径 | 本次应提供的 JSON 字段 | 对应权限 |
| --- | --- | --- | --- |
| 创建专用用户 | `/v2/vectordb/users/create` | `userName`, `password` | Global / `CreateOwnership` |
| 修改密码 | `/v2/vectordb/users/update_password` | `userName`, `password`（旧密码）, `newPassword` | User / `UpdateUser`，本人用户对象另有放行规则 |
| 为用户授予已有角色 | `/v2/vectordb/users/grant_role` | `userName`, `roleName` | Global / `ManageOwnership` |
| 创建专用数据库 | `/v2/vectordb/databases/create` | `dbName` | Global / `CreateDatabase` |
| 最小受保护读请求 | `/v2/vectordb/collections/list` | `dbName` | 使用本次管理身份验证，不据此认证应用最小权限 |

路由和字段以固定 tag 的 `RegisterRoutesToV2`、`createUser`、`updateUser`、`operateRoleToUser`、`createDatabase` 及请求结构为依据；官方 REST 2.6 页面提供相应使用说明。修改密码时 REST JSON 提供上述原始字段，handler 内部才将新旧密码转换为下游 RPC 所需的编码，调用者不应预先编码。[固定路由与 handler](https://github.com/milvus-io/milvus/blob/v2.6.22/internal/distributed/proxy/httpserver/handler_v2.go)、[固定请求结构](https://github.com/milvus-io/milvus/blob/v2.6.22/internal/distributed/proxy/httpserver/request_v2.go)、[更新密码](https://milvus.io/api-reference/restful/v2.6.x/v2/User%20%28v2%29/Update%20Password.md)、[授予角色](https://milvus.io/api-reference/restful/v2.6.x/v2/User%20%28v2%29/Grant%20Role.md)、[创建数据库](https://milvus.io/api-reference/restful/v2.6.x/v2/Database%20%28v2%29/Create.md)

权限名称来自 `CreateCredentialRequest`、`UpdateCredentialRequest`、`OperateUserRoleRequest`、`CreateDatabaseRequest` 的 `privilege_ext_obj`；Milvus 的固定 `go.mod` 将该协议依赖绑定到 `v2.6.22`。默认 `rootShouldBindRole=false` 时 root 在 `PrivilegeInterceptor` 中直接通过权限检查；当前用户操作自己的 User 对象也有独立放行分支，但不免除身份验证及修改密码所需的旧密码检查。不要为本次验证额外配置 `superUsers` 来绕过旧密码验证。[固定协议权限注解](https://github.com/milvus-io/milvus-proto/blob/v2.6.22/proto/milvus.proto)、[固定依赖版本](https://github.com/milvus-io/milvus/blob/v2.6.22/go.mod)、[PrivilegeInterceptor](https://github.com/milvus-io/milvus/blob/v2.6.22/internal/proxy/privilege_interceptor.go)、[Proxy.UpdateCredential](https://github.com/milvus-io/milvus/blob/v2.6.22/internal/proxy/impl.go)

如后续完整链路需要独立应用用户，可由本次实例的 root 创建用户和专用数据库，再授予明确选定的角色。内建 `admin` 具有广泛管理权限，只能作为这个新建、可丢弃实例的显式测试选择，不能被写成生产最小权限方案；本研究不扩展为完整 RBAC 设计。[官方用户与角色说明](https://milvus.io/docs/v2.6.x/users_and_roles.md)

## 3. 缺失或无效凭据的准确拒绝契约

启用鉴权后，HTTP 服务器安装 `authenticate` 中间件。对于正常的受保护 REST 请求，缺少认证头和错误的 Bearer 用户密码均在此中间件被拒绝，预期响应为：

```json
{"code":1800,"message":"user hasn't authenticated"}
```

HTTP 状态是 **401**，不是 HTTP 200 的业务错误；`ErrNeedAuthenticate` 定义了上述 code 和安全消息。这里不包含 `data` 字段。[HTTP authenticate / startHTTPServer](https://github.com/milvus-io/milvus/blob/v2.6.22/internal/distributed/proxy/service.go)、[ErrNeedAuthenticate](https://github.com/milvus-io/milvus/blob/v2.6.22/pkg/util/merr/errors.go)

已经认证但权限不足属于另一条路径：`checkAuthorizationV2` 返回 HTTP 403，响应 code/message 来源于具体授权错误。不要把它与 401 混为一类，也不要未经实际断言便固定所有 403 的业务 code。成功管理操作通常返回 HTTP 200 / code 0，但其他业务失败也可能使用 HTTP 200，所以每次验证都必须同时检查 HTTP 状态和 JSON code。[checkAuthorizationV2 与管理 handler](https://github.com/milvus-io/milvus/blob/v2.6.22/internal/distributed/proxy/httpserver/handler_v2.go)

## 4. 本轮最小验证建议与停止条件

1. 仅创建新的独立容器、卷和非默认 loopback 端口；固定已核验的 Milvus 2.6.22 镜像。已有实例、卷、数据库和集合不在操作范围内。
2. 首启同时设置鉴权开关和外部随机 root 密码，不先以已知默认凭据暴露服务。记录镜像身份、选定的安全配置和健康状态，不记录任何密码值。
3. 用该新实例的正确 root 身份创建专用测试数据库；对相同 `collections/list` 请求做三组对照：无认证头、错误密码、正确密码。前两项必须精确为 HTTP 401 / code 1800，正确身份必须 HTTP 200 / code 0，且新库集合为空。超时、连接错误、任意非 200 或单纯健康检查均不能替代这三项证明。
4. 只有后续应用入口确需专用用户时，才在这个实例中创建独立用户并授予显式选定的角色；只保存成功/失败及安全结构化指标。密码轮换也仅能面向本次新建身份，须使用更新密码接口，不能依靠修改首启配置。
5. 三组身份对照完成即可关闭本次研究范围。是否实际通过、所用运行时及工件摘要另记；本文不认证 Linux 同生产镜像、生产 RBAC/TLS、模型调用或真实 PDF 问答闭环。

以上步骤是根据前述官方契约提出的隔离验证方案，不是已执行记录。任何资源清理都只能定位本轮确认拥有的新资源，不能使用广泛匹配删除已有数据。
