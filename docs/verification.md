# 验证记录

验证日期：2026-10-30。环境为 Windows / Docker Desktop，本机接口监听 127.0.0.1。生产字节码目标为 Java 17；CI 使用 Temurin 17。

## 自动测试

执行 `mvn -B -ntp verify`，结果 **BUILD SUCCESS：33 项通过，0 失败、0 错误、0 跳过**，并成功生成可执行 JAR。

| 测试类 | 数量 | 覆盖 |
|---|---:|---|
| RuntimeIntegrationTest | 14 | Spring API 认证、真实 Harness 工具循环、真实 MCP SDK Server、租户和文档 ACL、知识撤销、幂等、A2A 任务、CAS |
| AdapterContractTest | 10 | RAGFlow 文档过滤与业务错误、Embedding 顺序/维度、Token Exchange、出站地址限制、沙箱限制/清理、飞书分页与权限失败关闭、Unicode |
| JwtPolicyTest | 6 | 真实签名 JWT；合法 token、issuer、audience、过期、tenant_id、签名校验 |
| ProcessRunnerTest | 3 | 独立 JVM 子进程的中文/引号标准输入、双流输出截断、超时终止 |

自动测试不使用付费模型，不依赖 Docker 或外部飞书账号。

## 本机端到端

在真实服务上执行：

```powershell
.\scripts\verify-local.ps1 -Docker -Model
```

全部 13 个检查通过：

| 链路 | 实测结果 |
|---|---|
| 自建知识库 | 中文查询经 BGE 512 维 Embedding、OceanBase 原生 cosine_distance 返回年假制度 |
| MCP | 真实 HTTP 发现 get_order；模型安装 orders 后成功查询 TEST-42；模型卸载后服务端拒绝再次调用 |
| 知识问答 | 真实模型检索知识库，回答十天年假并携带来源证据 |
| A2A | 经配置的认证凭证委托 specialist，异步任务完成并返回 A2A_OK |
| RAGFlow | 真实上传 Markdown、异步解析、向量化并检索回“八十元”差旅餐补 |
| 文档 ACL | Alice 可读私有文档、Bob 不召回；撤销 Alice 后立即不召回 |
| 沙箱 | 带引号 Python 脚本输出 42、UID 65534；超时返回 124；大输出被截断；执行后无遗留容器 |

实际模型服务使用用户配置的火山方舟 coding/v3 endpoint 和 ark-code-latest；Embedding 独立使用本机 BAAI/bge-small-zh-v1.5。测试数据均为示例制度、合成订单和临时验证文档。

另行启动独立 SandboxWorkerApplication，实测未携带凭证的 /execute 请求返回 401；携带 Worker Bearer 凭证的带引号 Python 脚本成功返回 42。

## 联调选择与边界

- 本机 RAGFlow 固定为 v1.0.0-rc2，使用独立 Elasticsearch 8.19.23。该 RAGFlow 版本的 OceanBase 搜索适配实测返回 unknown chunk field: compile_kwd，因此本机栈采用 Elasticsearch。自建引擎的 OceanBase VECTOR 链路单独验证通过。
- 首次创建 RAGFlow 搜索索引时可能短暂返回不可用；验证脚本在解析等待期重试，服务端不将上游错误伪装为空成功结果。
- Windows 沙箱命令不再经过命令行引号转义，脚本通过标准输入传入容器。新增回归测试和真实 Docker 执行均已通过。
- 飞书使用接口契约测试验证分页、用户凭证和拒绝路径。尚无企业飞书应用/空间授权，未声称读取过真实企业文档。
- 企业 IdP 的真实 issuer / client_credentials / Token Exchange 需部署方配置；本次验证包括签名 JWT 和授权接口契约、本机 A2A 真实认证互调。
- 当前不含管理前端、分布式任务队列、完整用户 OAuth 授权页面或历史文件清理后台任务。生产落地边界和扩展点见 architecture.md。

密钥、随机本机密码、模型文件、数据库、日志和 Windows DPAPI 文件位于被 Git 忽略的路径，不随项目提交。
