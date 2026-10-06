# z-llm

> LLM 网关 —— 对外一张 **OpenAI Chat Completions / Anthropic Messages** 双协议面，对内一张多 vendor 凭据池。

它把"各家模型 API 形状不一样、密钥散落、用量没人记账"这三件事收在一层：调用方只会说 OpenAI 或
Anthropic 的话，网关负责鉴权（自己签发的 ApiKey）、模型路由、采样参数按 vendor 下沉、同 vendor
多凭据 failover 与冷却、RPM/TPM/并发三口径限流，以及按 `z.llm.pricing` 的 token 记账。
上游真正打 HTTP 的那段代码不在本仓 —— 由 `z-agent-kernel-llm` 的 provider 承担，本仓只做编排。

---

## 📋 基本信息

| 字段 | 值 |
|------|-----|
| **仓库** | `z-llm`（LLM Gateway，中心化协议面） |
| **Maven 坐标** | `io.github.yuku123:z-llm`（聚合 pom）+ `z-llm-api` / `z-llm-core` / `z-llm-starter` / `z-llm-admin` |
| **当前版本** | `0.1.7`（根 POM `<revision>`，CI-friendly versions + 常开 `flatten-maven-plugin`（`oss` 模式，剥 parent）） |
| **父项目** | `io.github.yuku123:z-boot-parent:1.0.21`（`<relativePath/>` 留空，parent 在 repo1 不在磁盘；2026-09-29 从"无 parent 自包含"迁过来，版本键由 12 格收到 9 格） |
| **Maven Central** | 已发布并实测（2026-09-30 逐件 ranged GET）：`z-llm-api` / `z-llm-core` / `z-llm-starter` / `z-llm-admin` 的 `0.1.7` pom+jar 全 200；聚合 `z-llm:0.1.7` 只有 pom（`packaging=pom`，jar 404 属正常）。`maven-metadata.xml` 的 `latest`/`release` 均为 `0.1.7`，历史版本 `0.1.0`–`0.1.7` 全在架 |
| **版本对齐** | `z-boot-fleet:1.0.1`（repo1 实测那份）的 `<z-llm.version>` 已钉 `0.1.7`，与本仓 `<revision>` 同格 |
| **模块数** | 4（`z-llm-api` / `z-llm-core` / `z-llm-starter` / `z-llm-admin`，全部在 reactor 里，无 `maven.deploy.skip`） |
| **默认端口** | 不适用 —— 本仓是库 + starter，**没有** bootable 主类、`application*.yml` 或 `server.port`（实测全仓 0 个 `application*` 文件）。端点挂在宿主应用的端口与 context-path 之下 |
| **运行口径** | Java 8 · Spring Boot 2.7.18 · `javax.servlet` |
| **配置前缀** | `z.llm`（`GatewayProperties`），总开关 `z.llm.enabled` |
| **最近更新** | 2026-09-30 |

---

## 🎯 能力清单

每一条都能对应到 `z-llm-core/src/main/java/com/zifang/z/llm/core/` 下的一个类（最后两行落在
`z-llm-starter` 与 `z-llm-admin`）：

| 能力 | 实现 | 说明 |
|------|------|------|
| OpenAI 协议面 | `controller/OpenAIController` | `POST /v1/chat/completions`（非流式 + SSE）、`POST /v1/embeddings`、`GET /v1/models`、`GET /v1/models/**` |
| Anthropic 协议面 | `controller/AnthropicController` | `POST /v1/messages`（含原生事件帧序流式）、`POST /v1/messages/count_tokens` |
| 调用方鉴权 | `credential/AuthorizationExtractor` + `credential/ApiKeyService` | `Authorization: Bearer <key>`，也认 Anthropic 客户端习惯的 `x-api-key`；过期由 `expiresAt` 判定 |
| 请求映射 | `mapper/OpenAIRequestMapper`、`mapper/AnthropicRequestMapper` | 线格式 ↔ `UnifiedRequest`；Anthropic 的 `system` 上提/回落、`tool_result` 归位 |
| 采样参数下沉 | `params/ParamTranslator` | 按 vendor 过滤 + 改名后经 `providerParams` 透传（Anthropic 对多余 top-level 字段报 400，不能无脑全量透传） |
| 模型路由 | `router/ModelRouter` | 别名 → vendor 前缀 → provider 能力判定，四步全空才 404 |
| 凭据保管与 provider 装配 | `registry/LlmCredentialStore`、`registry/LlmProviderRegistry` | 按 `(vendor, alias)` 缓存 kernel provider 实例，凭据变更即重建（复用旧实例会把轮换掉的 key 留在服务路径上） |
| 跨凭据 failover / 冷却 | `resilience/ProviderInvoker` | 同一 vendor 凭据池内换 key，429/5xx 不直接抛给调用方 |
| 限流 | `service/RateLimiter` | 按 ApiKey 的 RPM / TPM / 并发三口径，被拒带 `Retry-After` |
| 用量记账 | `usage/UsageLedger` | 按 `(key, model)` 累计请求数、token、成本；未配单价的模型计入 `unpricedRequests` |
| 授权边界 | `auth/AccessControl` | 强制 ApiKey 的 `allowed-models` / `allowed-vendors` 白名单与 `max-tokens-limit` |
| 多模态直连 | `service/MultimodalChatRelay` | 带图请求绕过 kernel provider 直连上游（provider 传不了图） |
| 向量端点 | `service/EmbeddingService` | 各家 embeddings 形状分形 |
| 状态码信封 | `controller/GlobalExceptionHandler` | `{"error":{"message","type","code","param"}}` |
| 自动装配 | `z-llm-starter` → `ZLlmAutoConfiguration`（13 个 `@Bean`，全部 `zLlm` 前缀）+ `ZLlmCoreScanConfig` | `META-INF/spring.factories` 注册，`z.llm.enabled=true` 才装配 |
| 控制面 | `z-llm-admin`：`AdminController` / `AdminUiController` / `AdminQueryService` / `ZLlmAdminAutoConfiguration` | `z.llm.expose-admin=true` 才注册 |

### vendor 支持口径（只讲代码，不背书连通性）

`Vendor` 枚举共 6 家：`openai` / `anthropic` / `deepseek` / `qwen` / `dashscope` / `gemini`。
`LlmProviderRegistry#buildProvider` 的 switch 为这 6 家分别构造 `z-agent-kernel-llm` 的
`OpenAIProvider` / `AnthropicProvider` / `DeepSeekProvider` / `QwenProvider` / `DashScopeProvider` /
`GeminiProvider`，默认 base URL 一律引用 kernel provider 的 `DEFAULT_BASE_URL` 常量（网关不另抄一份）。

**这只说明"代码能为这 6 家构造请求"，不构成"某一家现在一定能调通"的证据**：本仓不做任何 vendor
连通性验证，历史上部分网关的凭据在真实环境里已失效。唯一的真实上游用例是
`RealUpstreamE2ETest`，只覆盖 OpenAI 与 Anthropic 两家、由环境变量门控、缺 key 时是 `skipped`。
其余四家的"支持"停在协议层（端点拼装 + 鉴权头 + 单测用替身）。凭据一律由部署方注入，
本 README 只写配置键与环境变量名，**不写任何 key 值，也不写带凭据的租户端点**。

直连面（`UpstreamEndpoints` 实测）分三层，与"注册了 6 家"不是一回事：

- 带图 chat 可转发：`openai` / `deepseek` / `qwen`（OpenAI 兼容 `{base}/chat/completions`）+ `anthropic`
  （原生 `{base}/v1/messages`，`x-api-key` + `anthropic-version`）；`dashscope` / `gemini` 明确拒绝
- embeddings 可转发：除 `anthropic`（Messages API 无向量端点）外都可拼 URL；`gemini` 用
  `x-goog-api-key` 且模型名在路径里
- 其余路径一律走 kernel provider 主链路

---

## 🏗️ 项目结构

```
z-llm/
├── pom.xml                # 聚合 POM：继承 z-boot-parent:1.0.21，<revision>=0.1.7 单一真源，常开 oss flatten
├── LICENSE                # MIT
├── z-llm-api/             # DTO + Vendor 枚举 + GatewayException（只依赖 jackson-annotations，无 Spring）
├── z-llm-core/            # 网关核心：controller / mapper / router / registry / resilience / service / usage / upstream
├── z-llm-starter/         # ZLlmAutoConfiguration + ZLlmCoreScanConfig + META-INF/spring.factories
└── z-llm-admin/           # 控制面 REST + Thymeleaf 概览页（templates/z-llm-admin/index.html）
```

代码体量实测：`src/main/java` 共 50 支（api 17 / core 25 / starter 3 / admin 5），`src/test/java` 共 24 支。

下面几条差异都是 POM 与发布层面的实测事实（与 z-ctc 那类仓对照着看最容易踩）：

- **本仓没有"不进 Central"的模块**：5 个 POM 里 `maven.deploy.skip` 命中 0 处，`z-llm-admin` 同样发布
  （repo1 上 `z-llm-admin-0.1.7.jar` 实测 200）。它的 `/z-llm/admin/api-keys` 只回显掩码，但**部署方若把
  控制面暴露到公网仍需自行挂鉴权** —— 见 [API 一览](#-api-一览)。
- **`z-llm-admin` 不是可执行应用**：它是 `jar` 打包、没有 `spring-boot-maven-plugin` 的 repackage、
  也没有主类，`target/` 里就是普通库 jar。想跑 HTTP 服务必须挂进宿主应用（如 z-opc 主 starter）。
- **控制面是 compile 线上唯一带 `spring-boot-starter-web` 的一支**（另带 thymeleaf）；
  `z-llm-core` / `z-llm-starter` 只取 `spring-web` / `spring-webmvc` / `spring-boot(-autoconfigure)`，
  容器由宿主决定。
- **本仓不落库**：全仓没有任何 MyBatis/JDBC/连接池依赖，凭据与 ApiKey 只来自配置。
- **没有 `_doc/` 目录**，也没有 `Dockerfile` / `docker-compose*.yml` / `deploy/` / `k8s/` / `Makefile`
  （`find` 实测 0 命中）—— 所以本 README 不设「文档目录」章节，也不编造部署资产。

---

## 🔧 技术栈

版本取自 `z-llm-core:0.1.7` 与 `z-agent-kernel-llm:0.1.0` 在 repo1 上的**已发布 POM**（不是本仓源码里的属性名）：

| 层级 | 技术 |
|------|------|
| 语言 / 运行时 | Java 8（`maven.compiler.source/target=8` 由 `z-boot-parent` 下发） |
| 框架 | Spring Boot 2.7.18（版本由地板 `z-boot-dependencies` 供给，本仓不再 import Boot BOM） |
| Web 层 | `spring-web` / `spring-webmvc` 5.3.39，SSE 用手写 `PrintWriter`；Servlet API 锁 `javax.servlet-api` 4.0.1 |
| JSON | `jackson-databind` / `jackson-core` 2.18.6（`z-llm-api` 只用 `jackson-annotations` 把 DTO 钉成 OpenAI 线格式） |
| 上游调用 | `z-agent-kernel-llm` / `-message` / `-types` / `-tool` **0.1.0**，其 HTTP 走 okhttp `4.12.0`（+ `okhttp-sse`） |
| 日志 | 只依赖 `slf4j-api` 门面 1.7.36；实现由宿主带。地板对 `spring-boot-starter-logging` 挂 `*:*` 排除，本仓刻意不补回 logback/log4j2 |
| 控制面视图 | `spring-boot-starter-thymeleaf`（单页 `z-llm-admin/index.html`） |
| 测试 | JUnit 4.13.2 + `spring-test`（MockMvc）+ `json-path`；starter 冒烟用 `spring-boot-starter-test` + `junit-vintage-engine`；admin 用 `assertj-core` |
| 构建 | Maven（`revision` + flatten `oss`），发布走 `central` profile（sources / javadoc / gpg / central-publishing） |

> ⚠ `z-agent-kernel` 这一格本仓用 4 条**直接** `dependencyManagement` 钉在 `0.1.0`，而 repo1 上的
> `z-boot-fleet:1.0.1` 已把 `<z-agent-kernel.version>` 抬到 `0.2.1`。这是根 POM 注释里登记的刻意分歧
> （import 进来的 BOM 顶不过直接条目）。合并进程里 kernel 的实际字节码由依赖仲裁决定，
> 消费方若靠 fleet 抬 kernel 版本，需要自行跑一遍本仓协议测试再上生产。

---

## 🚀 快速开始

### 编译

```bash
mvn clean install -DskipTests
```

构建要求 parent 可解析：`io.github.yuku123:z-boot-parent:1.0.21`（repo1 实测 pom 200）。
第三方版本一律走父链（地板 + fleet），模块 POM 里不该再出现字面版本钉。

### 消费入口：直引 `z-llm-starter`，还是走聚合 starter

POM 实测到的两条路，本仓不替你选：

| 入口 | POM 里实际写了什么 | 结论 |
|------|--------------------|------|
| `io.github.yuku123:z-llm-starter` | 由本仓发布，版本随 `<revision>` / fleet 表 | 精确控版的直连口。`z-boot-starter-web` 之类容器**不会**被它带进来，宿主自备 |
| `io.github.yuku123:z-boot-llm-starter` | 位于 `z-boot/z-boot-integration-starters/`，`<dependencies>` 只有 `z-llm-starter` 一支且不带 version，版本吃它 import 的 `z-boot-fleet:1.0.1`（那份 `<z-llm.version>` = 0.1.7） | 一行 import 的聚合口，等价于上面，但版本要等 fleet 抬格才跟上 |

真实消费方是怎么选的（读它们的 POM）：`z-opc/bootstraps/z-opc-main-starter/pom.xml` 在 2026-09-24
起**绕开** `z-boot-llm-starter`、直引 `z-llm-starter`，注释写的原因是"聚合那版锁的 z-llm 落后"；
`z-opc/z-agent/z-agent-center/z-agent-center-core` 则直接引 `z-llm-api` + `z-llm-core` + `z-llm-starter`。
⇒ 需要对齐到具体某一版时直引 `z-llm-starter`；只要"有一个能用的 L3 网关"时用聚合 starter 更省事。

### 打开开关 + 注入凭据

引入 starter 后必须显式打开：`z.llm.enabled=true`。默认不开 —— 它要与 z-opc 内遗留的
`z-agent-llm-gateway`（走 `z-agent.llm-gateway.enabled`）共存，两者互不接管。

```yaml
z:
  llm:
    enabled: true
    credentials:                       # 上游 vendor 凭据；同 vendor 可配多条做 failover
      - alias: openai-primary
        vendor: openai
        api-key: ${OPENAI_API_KEY}     # 只写环境变量名；值禁止入库/入 jar/入镜像层
        base-url: https://api.openai.com/v1   # 指向"版本化根"，网关在其后拼资源路径
        priority: 100
      - alias: claude-primary
        vendor: anthropic
        api-key: ${ANTHROPIC_API_KEY}
        # Anthropic 的 base 不带版本段（https://api.anthropic.com），与 kernel provider 一致
        extra-headers: {}              # 会原样并入出站请求头（UpstreamEndpoints#headers）
    api-keys:                          # 网关签发给调用方的凭证
      - id: team-a
        key: ${GATEWAY_KEY_TEAM_A}
        status: active                 # ApiKey 的 status 必须显式写 active
        requests-per-minute: 600
        tokens-per-minute: 200000
        allowed-vendors: [openai, anthropic]
    pricing:
      openai/gpt-4o-mini:
        prompt-per-million: 0.15
        completion-per-million: 0.60
```

两个 `status` 口径不对称，是踩点位（代码实测）：`ApiKeyService#reload` 用
`!"active".equalsIgnoreCase(status)` 直接跳过 —— **ApiKey 留空 status 等于不装载**；
而 `LlmCredentialStore` 是 `status == null || "active".equalsIgnoreCase(status)` ——
**上游凭据留空 status 按 active 处理**。

---

## 🔌 API 一览

数据面（`z-llm-core`，前缀由宿主决定；下表路径即 controller 上的 `@RequestMapping("/v1")`）：

| 方法 | 路径 | Controller | 说明 |
| --- | --- | --- | --- |
| POST | `/v1/chat/completions` | `OpenAIController` | OpenAI 兼容；线格式为蛇形（`max_tokens`、`stop`、`tool_calls`…），非流式与 SSE 流式（`stream` 由 **body** 决定，`?stream=true` 仍兼容） |
| POST | `/v1/embeddings` | `OpenAIController` | 按各家真实协议直连上游，见 [embeddings](#embeddings) |
| GET | `/v1/models` | `OpenAIController` | 可路由模型卡（`id`/`owned_by`/`capabilities`/`context_window`/`max_output_tokens`） |
| GET | `/v1/models/**` | `OpenAIController` | 单个模型查询。用 `**` 而非 `{id}`：OpenAI 的模型 id 本身含斜杠 |
| POST | `/v1/messages` | `AnthropicController` | Anthropic Messages，含原生事件帧序的流式 |
| POST | `/v1/messages/count_tokens` | `AnthropicController` | **估算**：`ceil(字符数 / 4)`，响应带 `estimate: true`（网关这层没有各家 tokenizer） |

`model` 字段两种写法都接受：裸名（`claude-3-5-sonnet-latest`）或带命名空间
（`anthropic/claude-3-5-sonnet-latest`）；`/v1/messages` 上裸名会补 `anthropic/` 前缀。回显同理 ——
上游只给裸名时由 `ModelNames#external` 补回调用方使用的 vendor 前缀，kernel 主链路与多模态直连共用
同一份规则，避免同一模型经两条路得到两种 id。

控制面（`z-llm-admin`）**默认不注册**，打开需要两个条件同时成立：

```yaml
z.llm:
  enabled: true          # 提供 LlmCredentialStore / ApiKeyService / RateLimiter 等 bean
  expose-admin: true     # 注册 /z-llm/admin/*
```

只开 `expose-admin` 会在启动时缺 bean 直接失败（按设计：没有网关就没有网关的控制面）。开关同时写在
`ZLlmAdminAutoConfiguration` 与两个控制器各自的 `@ConditionalOnProperty` 上，防止宿主自己
`@ComponentScan("com.zifang.z.llm")` 绕过自动装配。

| 路径 | 内容 |
| --- | --- |
| `GET /z-llm/admin/overview` | vendor / 凭据数 / 活跃 key 概览 |
| `GET /z-llm/admin/vendors` | 各 vendor 凭据数与模型数 |
| `GET /z-llm/admin/vendors/{vendor}/models` | 列模型 |
| `GET /z-llm/admin/credentials` | 凭据总数与分布（不含 key 原文） |
| `GET /z-llm/admin/api-keys` | key 清单，**只回显掩码**（头尾各 4 位，≤8 位整串打星） |
| `GET /z-llm/admin/rate-limit` | 限流桶快照 |
| `GET /z-llm/admin/health` | `status` / `providers` / `credentials` |
| `GET /z-llm/admin/ui`、`/ui/`、`/ui/index` | Thymeleaf 最小概览页 |

> ⚠ 这些端点自身没有鉴权，`expose-admin` 只保证"默认关"。公网或内网共享环境打开必须自行前置认证。

**没有的端点也如实说**：`GatewayException` 定义了 413 工厂方法、`UsageLedger` 有 `snapshot()`，
但 0.1.7 里两者都**没有任何 controller 调用点** —— 既没有 `/usage` 端点，也不会返回 413。

---

## ⚙️ 行为口径

### 模型路由

`ModelRouter.resolve()` 按四步判定，全部失败才 404：

1. `z.llm.model-aliases` 显式映射（请求 model → 内部 `vendor/model`）
2. `vendor/` 前缀命中 vendor
3. 前缀不是任何 vendor（`meta-llama/Llama-3.1-70B` 这类）→ 按 provider 声明的 `supportsModel` 能力判定
4. 裸名同样走能力判定

### 流式

- OpenAI 面：`data:` 帧 + `[DONE]` 结束哨兵；`stream_options.include_usage` 由 `z.llm.inject-stream-usage`
  决定是否替客户端补上（默认 `false`：部分自建兼容端点会对未知的 `stream_options` 直接报 400）
- Anthropic 面：`message_start` → `content_block_start` → `content_block_delta` → `content_block_stop` →
  `message_delta`(stop_reason, usage) → `message_stop`，`content_block` 只开一次
- 转发的 Anthropic 流里出现 `error` 帧按失败处理，不会静默当成普通增量
- **响应一旦提交就无法改状态码**：流式中途出错时异常抛给容器，客户端看到的是已完成的 SSE 连接里插入错误帧

### 多模态（图片）转发

kernel 的 provider 只把 `Msg.content` 当纯文本下发，**经主链路转发的图片会被静默丢掉** —— 上游"看不见图"
却给出一个像样的回答。因此带 image part 的请求由 `MultimodalChatRelay` 绕过 provider 直连上游，
凭据选择 / 冷却 / failover 仍复用 `ProviderInvoker`，resilience 只有一份实现。

| vendor | 端点 | 鉴权头 | 图片形状 |
| --- | --- | --- | --- |
| `openai` / `deepseek` / `qwen` | `{base}/chat/completions` | `Authorization: Bearer` | `image_url` |
| `anthropic` | `{base}/v1/messages` | `x-api-key` + `anthropic-version` | `{type:"image", source:{type:"base64" 或 "url"}}`，`data:` URL 自动拆出 `media_type`/`data` |
| `dashscope` / `gemini` | — | — | 不转发，请求被拒 |

行为边界（默认即"诚实优先"）：

- 转发面开着但 vendor 不被支持 → **400**，错误信息点明哪几家可转发
- `z.llm.allow-multimodal-downgrade=true` 才允许把多模态摊平成纯文本继续服务
- `z.llm.relay-multimodal=false` 整体关闭直连面，回到"按降级开关决定 400 或摊平"

Anthropic 面另有两条原生约定：`system` 上提为顶层字段，`tool_result` 必须落在 `user` 消息里；
调用方没传 `max_tokens` 时补 `8192`（`MultimodalChatRelay` 与 kernel `AnthropicProvider` 各写了一份字面量，
kernel 没公开常量 —— 是已知重复，不是同一个源）。

### embeddings

`POST /v1/embeddings` 是网关直连面（kernel 的 `LlmProvider` SPI 没有 embedding 方法），各家形状不同：

| vendor | 端点 | 请求 | 响应 |
| --- | --- | --- | --- |
| `openai` / `deepseek` / `qwen` | `{base}/embeddings` | `input: [...]` | `data[].embedding` |
| `dashscope` | `{base}/services/embeddings/text-embedding/text-embedding` | `input.texts` | `output.embeddings[].text_index` |
| `gemini` | `{base}/v1beta/models/{model}:batchEmbedContents` | `requests[].content.parts[].text` | `embedding[].values`（模型名在路径里） |
| `anthropic` | — | — | 无向量端点，400 |

三条约束（与代码逐条对齐）：

- **条数不齐即失败**：某条输入没拿到向量会报 502（消息形如 `… 1 vectors for 2 inputs`），而不是静默返回短列表
- 裸 embedding 模型名（`text-embedding-3-small`）需要显式归属：`z.llm.embedding-vendor`；
  未配置时，只有"支持 embeddings 且当前有凭据的 vendor 恰好剩一家"（判定是 `vendor != anthropic`，
  不限 OpenAI 兼容三家）才自动选中，否则 404 并在提示里指向该配置项
- 单次输入条数受 `z.llm.max-embedding-batch`（默认 2048）约束，**超出报 400 `invalid_request`**，
  且挡在出网之前（`GatewayHttpProtocolTest.embeddingsRejectsEmptyAndOversizedInputWithoutCallingUpstream`
  把"上游 0 次调用"一起钉住了）。此前文档写的 413 是 `GatewayException` 里有但没人调的分支

记账只落 prompt 侧（`usage.{prompt_tokens,total_tokens}`），与 OpenAI 形状一致。

### 弹性与状态码

`ProviderInvoker` 在同一 vendor 的凭据池内做 failover：某个凭据被打爆（429）、上游 5xx 或网络故障时
进入冷却窗口，请求自动改投下一个凭据，而不是把 429 直接抛给调用方。

`retryable()` 实测口径：429 / 401 / 403 / 5xx 换凭据重试；其余 4xx（如 400 参数错）直接透出；
无 HTTP 状态的连接 / 超时 / 解析故障也换凭据。

**换凭据有次数上限，也有总时长预算**。次数是 `min(retry.max-attempts, 凭据池大小)`，
但真正挂住请求线程的是**这个次数乘以单次上游超时**：按本仓默认
（3 次 × `upstream-read-timeout-sec=300` + 连接 10s）最坏约 **930 秒**，
整条链路占着一个 servlet 请求线程，而没有任何配置项能看见这个乘积——
改 `max-attempts` 会把它线性放大。`retry.max-elapsed-ms`（默认 `0` = 不限）就是补这个缺口的：
设成 `>0` 之后它成为硬上限，预算用尽即停止换凭据、**并且不再退避**
（调用方注定要收到池耗尽错误，让它在请求线程上多睡一轮没有意义）。

取值要**大于单次上游超时**，否则第一次尝试结束时预算就已用尽、failover 等于关闭——
流式长回答本来就靠那个读超时兜着。流式场景的换凭据安全性是另一回事，已经单独处理：
**只能在还没吐出任何 chunk 时安全换凭据**，一旦向客户端吐过内容再切就会让客户端收到
两段互相矛盾的回复（`ChatGatewayService#streamChat`）。

状态码语义（`GlobalExceptionHandler`）：

| 情况 | 结果 |
| --- | --- |
| 网关本端问题 | 401 鉴权 / 403 越权 / 404 模型未找到 / 400 请求不合法 / 429 限流 / 500 内部错误 |
| 上游返回 4xx（除 429） | 原样透出该状态码 —— 是调用方或配置问题 |
| 上游 429 | 429 |
| 上游 5xx 或未知 | 502 |
| **body 在读进 controller 之前就坏掉**（截断 / 全空 / 不是 JSON） | 400 `invalid_request`（0.1.6 起；此前是 500） |
| **整个凭据池都被限流** | 429，不是笼统的 502 —— 客户端的正确动作是退避，把它压成 502 会诱导立刻重试 |

错误响应体：`{"error":{"message","type","code","param"}}`。对外 `message` 只放给人看的短句，
异常原文（Spring 会把 controller 的方法签名、Jackson 的内部类名整段带出来）只进 log。

### 与宿主应用同 JVM 时（0.1.5 起的两条契约）

上表那套状态码只在异常真的落到本类头上时成立。合并进程里有两个坑，0.1.5 各钉了一条：

- **bean 名带 `zLlm` 前缀**（`@Component("zLlmGlobalExceptionHandler")` + `ZLlmAutoConfiguration` 里 13 个
  `@Bean` 同名前缀）。Spring 默认按简单类名首字母小写注册，宿主或另一支 L3 库里只要有同名类，扫描期就
  `ConflictingBeanDefinitionException` 直接起不来，且 `allow-bean-definition-overriding` 救不了。
- **advice 限定 `basePackages` + `@Order(HIGHEST_PRECEDENCE)`**。Spring 解析异常时按 advice 顺序逐个问
  "你有没有这个异常的 handler"，**第一个命中的赢，不是全进程挑最具体的那个**。宿主常见的
  `@RestControllerAdvice(basePackages = "com.zifang")` + `Exception` 兑底只要排在前面，就会把网关的
  429/400/502 压成 500 + 宿主自己的信封。限定包名让本类只管 `com.zifang.z.llm.core.controller` /
  `com.zifang.z.llm.admin.controller` 两个包（不反向吃掉宿主语义），抬 order 让网关自己的 controller
  必定先命中本类。

两条都由永久闸盯着：`MergedProcessAdvicePrecedenceTest`（真 `AnnotationConfigWebApplicationContext`，
宿主替身 advice 先注册且不带 `@Order`）。`standaloneSetup` 那层结构上测不到这两个机制。

### 限流与记账

按 ApiKey 维度同时限制三个口径：RPM（`requests-per-minute`）、TPM（`tokens-per-minute`）、
并发数（`z.llm.max-concurrent-requests`）。并发是后加的维度：流式请求长时间占住 servlet 线程，
只限 RPM 挡不住少量慢请求把线程池打满。

- 上游返回多少 token 就扣多少，不做预估扣费
- 被拒时响应带 `Retry-After`
- `UsageLedger` 按 `(key, model)` 在进程内累计 token 与成本，单价取自 `z.llm.pricing`（每百万 token）。
  查不到单价的那笔按 0 成本计并计入 `unpricedRequests`；`Record.estimated()` 为真表示的是
  **"这台模型没配价"**，不是"上游没回 usage"（旧版文档在此处写反了）。上游确实没回 usage 时，
  这条记录按 prompt/completion 各 0 计
- 桶与台账都是**单实例内存态**，重启即清零；分布式部署需替换为 z-cache / redis 原子计数器

---

## 🛠 配置项

前缀 `z.llm`（`GatewayProperties`），下表与代码逐格实测；未列出的键不生效。

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | 未设即不装配 | `@ConditionalOnProperty` 总开关（**不是** `GatewayProperties` 的字段），与遗留网关共存的必要设计 |
| `credentials` | `[]` | 上游凭据列表 |
| `api-keys` | `[]` | 调用方凭证列表 |
| `expose-admin` | `false` | 是否注册控制面 |
| `max-tokens-limit` | `32768` | 调用方传的 `max_tokens` 超限直接拒；未传则不主动限制 |
| `rate-limit-enabled` | `true` | 限流总开关 |
| `max-concurrent-requests` | `0`（不限） | 单 key 并发上限 |
| `inject-stream-usage` | `false` | 是否替客户端补 `stream_options.include_usage=true` |
| `enforce-key-restrictions` | `true` | 是否强制 `allowed-models` / `allowed-vendors` 白名单（`false` 只告警，用于灰度） |
| `retry.enabled` | `true` | 跨凭据 failover |
| `retry.max-attempts` | `3` | 单次请求最多尝试几个凭据（含首个） |
| `retry.max-elapsed-ms` | `0`（不限） | 跨凭据重试的**总时长预算**。`>0` 即硬上限：预算用尽就停止换凭据、不再退避，直接返回失败 |
| `retry.backoff-ms` | `200` | 每次重试前基准退避，按尝试次数线性放大 |
| `retry.cooldown-ms` | `30000` | 凭据冷却时长 |
| `model-aliases` | `{}` | 请求 model → 内部 `vendor/model` |
| `pricing` | `{}` | `"vendor/model"` → 每百万 token 单价；查找顺序为精确 → 裸 model → vendor 前缀 → 后缀匹配 |
| `currency` | `USD` | 只进 `UsageLedger.snapshot()` 的 `currency` 字段。**0.1.7 没有任何 HTTP 端点回显它**（无 `/usage`），改这一格对调用方不可见 |
| `relay-multimodal` | `true` | 带图请求是否绕过 provider 直连上游 |
| `allow-multimodal-downgrade` | `false` | 不允许转发时是否容忍摊平成文本 |
| `upstream-connect-timeout-sec` | `10` | 直连上游连接超时 |
| `upstream-read-timeout-sec` | `300` | 读超时，流式长回答要留足 |
| `upstream-write-timeout-sec` | `60` | 写超时 |
| `max-embedding-batch` | `2048` | 单次 `/v1/embeddings` 输入条数上限，超出 400 |
| `embedding-vendor` | 未设 | 裸 embedding 模型名归属的 vendor |

凭据与 key 的值只允许经环境变量注入（`${OPENAI_API_KEY}` / `${ANTHROPIC_API_KEY}` /
`${GATEWAY_KEY_TEAM_A}` 这类占位），**禁止写进 yml / jar / 镜像层 / 本 README**。

---

## 🧪 测试

```bash
mvn -o -pl z-llm-core -am test       # 必须带 -am
mvn -B test                          # 全 reactor
```

`-am` 不是可省的：不带它时 `z-llm-api` 会从本地 m2 解析到已发布的旧 jar，新增的 DTO 直接
`NoClassDefFoundError`。改过 `z-llm-api` 后要让消费方看到，需要 `mvn install`。

静态计数（2026-10-06 `grep @Test` 实测）：`z-llm-core` 147 个测试方法、`z-llm-starter` 2、`z-llm-admin` 2。
下表是同日全 reactor 跑通的 surefire 读数——两个数一个是静态计数、一个是运行汇总，
口径不同，不要互相校正（`ProviderInvokerBudgetTest` 的 6 个方法与静态计数一致，
差额来自 `@ParameterizedTest` 一类不由 `@Test` 计数的用法）：

| 模块 | Tests | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- |
| `z-llm-core` | 147 | 0 | 0 | 5 |
| `z-llm-starter` | 2 | 0 | 0 | 0 |
| `z-llm-admin` | 2 | 0 | 0 | 0 |

覆盖层面：双协议 HTTP 线格式（MockMvc，含 embeddings 与 Anthropic 转发）、请求映射、参数下沉、
模型路由、凭据池 failover 与状态码、**跨凭据重试的总时长预算**、限流三口径、记账、
starter 装配 smoke、控制面注册开关与 key 掩码。

### 真实上游 E2E

`z-llm-core/src/test/java/com/zifang/z/llm/core/e2e/RealUpstreamE2ETest` 走真网络、真 provider
（不经测试替身），由环境变量门控：

```bash
Z_LLM_E2E_OPENAI_API_KEY='<注入，勿写入文件>' \
Z_LLM_E2E_ANTHROPIC_API_KEY='<注入，勿写入文件>' \
mvn -o -pl z-llm-core -am test -Dtest=RealUpstreamE2ETest
```

模型名可用 `Z_LLM_E2E_OPENAI_MODEL`（默认 `gpt-4o-mini`）、`Z_LLM_E2E_OPENAI_EMBED_MODEL`
（默认 `text-embedding-3-small`）、`Z_LLM_E2E_ANTHROPIC_MODEL`（默认 `claude-3-5-haiku-latest`）覆盖。

**没配 key 时这 5 个用例是 skipped 而不是 passed** —— 上面表格里的 `Skipped: 5` 就是它们，
不能计入"已验证"。测试图片在运行时用 `BufferedImage` 画一张 240×240 PNG，不往仓库塞 base64 字面量；
两个转发用例断言 `prompt_tokens > 40`，作为"图没被静默丢掉"的探针。

---

## 📦 发布与运行形态

```bash
mvn -B -P central clean deploy
```

`central` profile 补上中央仓要求的四件事：`maven-source-plugin` 的 sources jar、`maven-javadoc-plugin`
的 javadoc jar（绑 `verify`）、`maven-gpg-plugin` 的 `.asc` 签名（也绑 `verify`），再由
`org.sonatype.central:central-publishing-maven-plugin` 打成 `target/central-publishing/central-bundle.zip`
上传。凭证取 `~/.m2/settings.xml` 中 id 为 `central` 的 server（token 认证），**不进任何文档或仓库文件**。

⚠ **`BUILD SUCCESS` 不等于已发布**：profile 里 `waitUntil=uploaded`，Maven 只等到"上传成功"就返回，
之后还有服务端校验与同步。判发布只认 repo1 回读，且用 ranged GET（repo1 对 HEAD 不给 200）：

```bash
curl -s -o /dev/null -w '%{http_code}\n' \
  https://repo1.maven.org/maven2/io/github/yuku123/z-llm-core/<版>/z-llm-core-<版>.pom
```

更硬的验收是从干净本地仓拉一次：

```bash
mvn -B -Dmaven.repo.local=/tmp/m2-central-check dependency:get \
  -Dtransitive=false -DremoteRepositories=https://repo1.maven.org/maven2 \
  -Dartifact=io.github.yuku123:z-llm-starter:<版>
```

聚合 pom 是 `packaging=pom`，`dependency:get` 必须显式带类型 `-Dartifact=io.github.yuku123:z-llm:<版>:pom`。
历史发布节奏实测（0.1.4）：`uploaded` → repo1 五个构件**同时** 200 约 21 分钟，中途 404 不能当失败证据。

发布形状（本轮再次实测）：`flatten-maven-plugin` 是常开（不挂 profile）、`flattenMode=oss`，
repo1 上 `z-llm`/`z-llm-starter`/`z-llm-core` 的 `0.1.7` POM 里 `<parent>` 计数为 0、版本落成字面量 ——
消费方**不需要**能解析 `z-boot-parent` 就能拉本仓。

运行形态：本仓无容器/编排资产（无 Dockerfile、compose、k8s、Makefile），产物就是 4 支 jar；
部署方式 = 宿主应用引 starter、带自己的 web 容器与 `z.llm.*` 配置，或把 `z-llm-admin` 挂进同一个应用。

---

## ⚠️ 已知边界

- 真实上游 E2E 只在配了 key 的环境能跑；本仓默认状态是 5 skipped，其余 4 家 vendor 完全没有真实上游证据
- `count_tokens` 是估算值（`ceil(字符数 / 4)`），不是各家 tokenizer 的精确值
- ApiKey 与凭据只来自配置；`LlmCredentialStore#reload()` 只是重读 `GatewayProperties`，
  没有任何调用方在运行时改它 ⇒ 动态刷新（接 z-config）未做
- 限流桶与用量台账是单实例内存态，进程重启即清零
- `dashscope` / `gemini` 的图片不在转发面内（明确报 400，不静默降级）；`anthropic` 无 embeddings 端点
- 未鉴权 + 坏 body 是 400 而不是 401：body 解析发生在 controller 方法体之前，鉴权在方法体里，
  顺序改不动（要挪得先加 filter/interceptor）
- **网关路径上 mapping 阶段的 415/405/404 不归本库管**（结构性，不是漏改）：0.1.5 把 advice 收窄成只管
  `com.zifang.z.llm.core.controller` / `com.zifang.z.llm.admin.controller` 两个包，而这几类异常由
  `HandlerMapping` 抛出、那时 handler 还没定下来 ⇒ 带限定的 advice 一律不参与。0.1.6 把机制量成四腿对照
  （永久闸 `GatewayHttpProtocolTest.mappingPhaseErrorsEvadePackageScopedAdvice`）：裸 advice 接得到
  415/405（阳性腿），限定成本库包名接不到，限定成 `com.zifang` 也接不到。合并进程里能接住 405 的只有
  **裸** advice（z-config-web 那份正是这个形状，给的是 `Result` 信封而不是 `{"error":{...}}`）。
  注意那四腿量的是 MockMvc 层的**可达性**，没有跑合并进程拍过 405 的实际响应体。
  要收 405/415 只有一条路：宿主或 z-config 侧给那份裸 advice 限定包名 / 抬 order，不在本库范围内。

---

## 📄 License

MIT，见仓库根 [`LICENSE`](LICENSE)（版权行 `Copyright (c) 2026 z-opc-foundation`）；根 POM 的
`<licenses>` 同样声明 MIT License。

_Maintained by the z-opc-foundation organization._
