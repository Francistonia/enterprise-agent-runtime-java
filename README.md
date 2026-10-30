# Enterprise Agent Runtime

基于 **Java 17 / Spring Boot 3.5.7 / AgentScope Java 2.0.4** 的企业 Agent Runtime。
默认模型接入火山方舟兼容接口，可配置 Base URL 和模型名称；密钥从进程环境读取。

## 能力

| 能力 | 实现 |
|---|---|
| Agent 执行 | HarnessAgent 的推理/工具循环，异步任务、状态查询、取消、SSE 状态事件 |
| 自然语言管理 MCP | 模型调用 install_mcp / uninstall_mcp；服务端发现工具、保存绑定、执行前检查撤销状态 |
| MCP 工具开发示例 | 独立订单 MCP Server，官方 MCP Java SDK + Streamable HTTP；示例数据为合成数据 |
| A2A | 0.3.0 JSON-RPC 的 message/send、tasks/get、tasks/cancel；远程委托及结果查询 |
| 自建知识库 | 切块、批量 Embedding、OceanBase VECTOR 检索、来源引用、文档 ACL |
| RAGFlow | 文档上传、解析提交、受限 document_ids 检索、远端删除 |
| 沙箱 | Docker 命令执行、无网络、非 root、资源限制、输出截断、超时清理；支持独立 Worker |
| 飞书 Wiki | 空间/子树遍历、Docx 正文、文本附件、增量内容比较、删除对账、逐用户实时权限复核 |
| 企业基础能力 | 租户隔离、OIDC/JWT、资源权限、数据库持久化、乐观锁、会话执行租约、幂等请求、审计 |

模型不会获得凭证值。工具实例绑定本次认证身份；MCP/知识库/远程 Agent 的授权均在服务端检查。
SDK 内置的宿主机文件、shell、网页、动态技能和子 Agent 工具关闭，只注册本项目控制的工具。

## 本机启动

已有服务占用 8080 时，Runtime 使用 **18080**，不会停止已有容器。

### Docker 依赖栈

先启动 Docker Desktop，然后在 PowerShell 运行：

    .\scripts\start-docker-stack.ps1

Compose 项目名为 enterprise-agent-runtime，数据保存在独立命名卷。启动脚本在 .runtime/stack.env
生成本机数据库、对象存储及 RAGFlow 管理密码。第一次拉取镜像、下载模型和初始化 OceanBase 需要时间。
脚本按固定 revision 下载中文 BGE 模型，并校验 SHA-256；模型文件保存在被 Git 忽略的 .runtime/models。
本机 Docker 建议至少分配 8 GB 内存。RAGFlow 使用独立 Elasticsearch；自建检索链路使用 OceanBase。

服务启动后初始化 RAGFlow 的账号、Embedding provider、API Key 和 dataset：

    .\scripts\bootstrap-ragflow.ps1

API Key 使用当前 Windows 用户的 DPAPI 加密；dataset 信息写入 .runtime/ragflow-bootstrap.json。
本机 RAGFlow 账号为 runtime@runtime.local；密码为 .runtime/stack.env 中 LOCAL_ADMIN_PASSWORD 加前缀 rt-。

| 服务 | 本机地址 |
|---|---|
| Runtime | http://127.0.0.1:18080 |
| 示例 MCP | http://127.0.0.1:8081/mcp |
| RAGFlow | http://127.0.0.1:18082 |
| Embedding | http://127.0.0.1:18083/v1/embeddings |
| OceanBase | 127.0.0.1:12881 / agent_runtime，用户 root@runtime |

本机栈使用 BAAI/bge-small-zh-v1.5 的 **512 维真实向量**。
生成的 OceanBase 初始化 SQL 会匹配 512 维；db/oceanbase.sql 是生产环境的 1024 维模板。
两个配置不能混用；已有向量列的维度变更需要新建索引并重建数据。

### 模型密钥

Windows 下可用当前用户的 DPAPI 加密保存：

    .\scripts\set-ark-key.ps1

或者在进程环境设置 ARK_API_KEY。默认配置：

    AGENT_MODEL_PROVIDER=ark
    AGENT_MODEL=ark-code-latest
    ARK_BASE_URL=https://ark.cn-beijing.volces.com/api/coding/v3

### 启动应用

在两个终端分别运行：

    .\scripts\run-mcp-demo.ps1

    .\scripts\run-local.ps1 -Docker

已构建 JAR 时可用 .\scripts\run-local.ps1 -Docker -Jar。Windows 下重新打包前先停止使用该 JAR 的应用进程。

再初始化样例资源：

    .\scripts\bootstrap-local.ps1 -Docker

Docker 模式下 admin 密码读取 .runtime/stack.env 的 LOCAL_ADMIN_PASSWORD。
alice、bob 的本地密码为 local-user，可通过 LOCAL_USER_PASSWORD 覆盖。
纯本地模式（不传 -Docker）使用 H2 和用于离线验证的文本指纹向量；admin 密码为 local-admin。
文本指纹不提供语义向量能力，生产模式禁止使用。

本地 Basic 登录只在 local profile 开启，默认监听 127.0.0.1。生产运行使用 prod profile 和企业 OIDC。

## 使用示例

请求均需要认证。以下使用 curl 的交互式密码提示，避免把密码写进请求示例。

    curl.exe -u admin http://127.0.0.1:18080/api/me

提交 Agent 任务：

    POST /api/agents/assistant/runs
    Idempotency-Key: a-unique-request-key
    Content-Type: application/json

    {"message":"请接入 orders MCP"}

响应为 202，并包含 id、sessionId、state。查询 GET /api/runs/{id}。
安装完成后下一轮可以说“查询订单 TEST-42”；卸载可以说“卸载 orders MCP”。

知识问答：

    {"sessionId":"上一轮返回的 sessionId","message":"公司年假有几天？请给出来源"}

A2A 委托：

    {"message":"请委托 helper 把以下内容整理成三条行动项：完成接口、补充测试、部署演示"}

沙箱：

    POST /api/sandbox/execute
    {"command":"python3 -c 'print(1 + 2)'","timeoutSeconds":10}

## API

| 路由 | 作用 |
|---|---|
| GET /api/me | 当前身份 |
| GET /api/agents；POST /api/agents | 列表、创建 Agent |
| POST /api/agents/{id}/runs | 异步执行；支持 Idempotency-Key |
| GET /api/runs/{id} | 查询任务，检查 owner |
| POST /api/runs/{id}/cancel | 取消自己的任务 |
| GET /api/runs/{id}/events | SSE 状态快照；断开不取消任务，30 秒后可重连 |
| GET /api/sessions/{id} | 会话历史；重新校验历史知识引用 |
| GET /api/mcp；POST /api/mcp | MCP 目录、登记服务 |
| POST /api/mcp/{id}/discover | 发现批准的工具 |
| PUT /api/agents/{agent}/mcp/{connector} | 安装绑定 |
| DELETE /api/agents/{agent}/mcp/{connector} | 撤销绑定 |
| POST /api/agents/{agent}/mcp/{connector}/tools/{tool} | 直接调用已绑定工具 |
| GET /api/knowledge-bases；POST /api/knowledge-bases | 知识库管理 |
| POST /api/knowledge-bases/{id}/documents | 写入或替换文本 |
| GET /api/knowledge-bases/{id}/documents | 有权访问的文档 |
| PUT /api/knowledge-bases/{id}/documents/{doc}/readers | 修改 ACL |
| DELETE /api/knowledge-bases/{id}/documents/{doc} | 先撤销本地访问，再清理索引 |
| POST /api/knowledge-bases/{id}/search | 统一检索 |
| POST /api/a2a/peers | 登记远程 Agent |
| POST /api/a2a/peers/{id}/invoke | A2A 调用；短时等待结果，未完成时返回 task |
| GET /api/a2a/peers/{id}/tasks/{task} | 查询远端任务 |
| GET /agents/{agent}/.well-known/agent-card.json | Agent Card |
| POST /agents/{agent}/a2a | A2A JSON-RPC |
| POST /api/feishu/bindings | 关联飞书子树 |
| PUT /api/feishu/users | 管理员登记用户 access token 的凭证引用 |
| POST /api/feishu/bindings/{id}/sync | 同步及删除对账 |
| POST /api/sandbox/execute | 沙箱执行 |
| GET /api/audit | 当前租户最近 200 条审计 |

## 认证和授权

生产 JWT 要求签名、issuer、有效期、audience 校验通过，并包含 tenant_id、sub、scope。
groups 用于组 ACL。业务身份从认证结果取得，JSON 中传入身份不会改变执行权限。

| scope | 作用 |
|---|---|
| agent:manage / agent:run | 管理 / 执行 Agent |
| mcp:manage / mcp:invoke | 管理 MCP / 调用 MCP |
| kb:manage / kb:read | 管理知识库 / 读取知识库 |
| a2a:manage / a2a:invoke | 管理远程 Agent / 调用 A2A |
| sandbox:execute | 执行沙箱命令 |
| audit:read | 查询审计 |

资源 readers 支持 "*"（当前租户内）、"user:主体ID"、"group:组ID"。
文档 ACL 在检索前及返回前复核；历史会话和任务结果也会检查来源授权。
新索引代际发布后才替换旧版本，搜索只读取当前代际。

对外凭证使用引用，如 RUNTIME_SECRET_ORDERS，不接受把明文凭证放进配置 DTO。
支持 NONE、BEARER、BASIC、CLIENT_CREDENTIALS、TOKEN_EXCHANGE。
BASIC 用于本地 A2A 演示；生产优先使用企业 OAuth。
TOKEN_EXCHANGE 使用当前用户 token 换取目标 audience/scope 的 token，不把入站 token直接转发给远程服务。
凭证引用、目录可见性和执行工具范围由管理员配置。

所有业务出站地址都必须在 RUNTIME_ALLOWED_ORIGINS 的精确 origin 列表中，重定向关闭。
生产环境还应通过网关/网络策略落实出站约束。模型服务地址属于部署配置。

## 双知识引擎

创建自建知识库：

    {"id":"company","engine":"CUSTOM","readers":["*"]}

创建 RAGFlow 知识库（先在 RAGFlow 创建 dataset 并配置 Embedding）：

    {
      "id":"company-ragflow","engine":"RAGFLOW","readers":["*"],
      "ragflowUrl":"http://127.0.0.1:18082","datasetId":"真实dataset ID",
      "credential":{"mode":"BEARER","tokenRef":"RUNTIME_SECRET_RAGFLOW"}
    }

统一文本写入：

    {"id":"leave","title":"年假制度","text":"正文","sourceUrl":"https://source.example/doc","readers":["user:alice"]}

检索：

    {"query":"年假","topK":5}

RAGFlow 的上传和解析是异步的，文档返回 INDEXING 表示已经提交解析；检索需等待上游解析完成。
检索只传入当前用户可读文档的远端 ID；空 ACL 直接返回空结果。
自建引擎使用 OceanBase 原生 cosine_distance 精确搜索；可在验证过滤条件和召回后再扩展 ANN。

## 飞书

需要企业自建应用开通 Wiki、Docx 和相关文件读取权限，并获授目标空间访问权限。
在进程环境/凭证库配置应用 Secret 和各用户授权 token，再使用 API 登记引用：

    POST /api/feishu/bindings
    {"id":"wiki","kbId":"company","spaceId":"数字空间ID","rootNode":"可选根节点token",
     "appId":"cli_xxx","appSecretRef":"RUNTIME_SECRET_FEISHU_APP","readers":["user:alice"]}

    PUT /api/feishu/users
    {"subject":"alice","tokenRef":"RUNTIME_SECRET_FEISHU_ALICE"}

应用身份负责同步；提问者自己的用户 token 用于实时访问复核。缺少用户授权、token 过期、
飞书拒绝访问或访问检查不可用时，该飞书文档不进入召回结果。
当前实现同步在线 Docx 正文及 TXT/MD/CSV/JSON 文件，其他类型出现在 skipped 中。
同步只有完整遍历成功后才对账删除。未接入企业授权时可运行接口测试，不能读取真实飞书内容。

## 独立沙箱 Worker

生产 API 进程不需要 Docker socket。单独在 Worker 主机安装 Docker，并设置共享的
RUNTIME_SECRET_SANDBOX_WORKER，运行：

    .\scripts\run-sandbox-worker.ps1

API 配置 SANDBOX_ENABLED=true、SANDBOX_WORKER_URL，并在出站 origin 中加入 Worker 地址。
生产模式不允许 API 进程直接执行 Docker；本机 local 模式支持直接执行。
沙箱不挂载宿主机路径，不传入企业凭证；工作目录临时存在于容器内。

## 构建和生产配置

    mvn -B -ntp verify
    mvn -B -ntp package

CI 执行相同验证；单元和集成测试不调用付费模型。测试使用真实 Harness 循环和真实示例 MCP Server。
外部服务适配使用接口契约测试，本机 Docker 联调结果见 docs/verification.md。

依赖栈、示例 MCP、Runtime 和样例资源就绪后，可重跑本机端到端验证：

    .\scripts\verify-local.ps1 -Docker

加 -Model 验证真实模型的 MCP 安装/卸载、知识问答和 A2A，调用配置的模型服务。
架构和授权边界见 [docs/architecture.md](docs/architecture.md)。

生产配置参考 .env.example：使用 prod profile、企业 OIDC、OceanBase 初始化 SQL、真实 Embedding、
服务凭证及独立沙箱 Worker。Dockerfile 默认以非 root 用户启动 API。
当前实现不带管理前端。SSE 提供任务状态，不提供逐 token 输出；进程崩溃后旧任务租约过期会标为失败，
不会自动重放有副作用的工具操作。RAGFlow 替换文档后旧远端文档不再被召回，物理历史清理需要额外的保留策略。

## 参考

- AgentScope Java 2.0.4：https://github.com/agentscope-ai/agentscope-java/releases/tag/v2.0.4
- MCP Java SDK 0.17.2：https://github.com/modelcontextprotocol/java-sdk/tree/v0.17.2
- A2A 0.3.0：https://a2a-protocol.org/v0.3.0/specification/
- RAGFlow HTTP API：https://ragflow.io/docs/http_api_reference
- 火山方舟兼容接口：https://docs.volcengine.com/docs/ark/coding-plan-personal-ai-other-tools
