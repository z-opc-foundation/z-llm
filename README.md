# z-llm

多 vendor 的 LLM 网关：Spring MVC 实现，对外同时提供 **OpenAI Chat Completions** 与 **Anthropic Messages** 两套协议面，
一条主链路负责鉴权、模型路由、限流、跨凭据 failover 与用量记账。

- Maven 坐标：`io.github.yuku123:z-llm-{api,core,starter,admin}`
- 运行前提：Java 8 + Spring Boot 2.7（`javax.servlet`）
- 上游调用能力来自 `z-agent-kernel-llm` 的 provider；网关不另抄一份 base URL —— 默认值直接引用 kernel provider 常量

## 模块

| 模块 | 内容 |
| --- | --- |
| `z-llm-api` | DTO、`Vendor` 枚举、`GatewayException`；无 Spring 依赖 |
| `z-llm-core` | 双协议控制器、请求映射、模型路由、限流、failover、记账、多模态转发、embeddings |
| `z-llm-starter` | `META-INF/spring.factories` → `ZLlmAutoConfiguration`，注册全部网关 bean |
| `z-llm-admin` | 控制面 REST + Thymeleaf 最小 UI；**默认不注册**，见 [控制面](#控制面) |

## 快速接入

引入 `z-llm-starter` 后必须显式打开开关：`z.llm.enabled=true`。
默认不开 —— 它要与 z-opc 内遗留的 `z-agent-llm-gateway`（走 `z-agent.llm-gateway.enabled`）共存，两者互不接管。

```yaml
z:
  llm:
    enabled: true
    credentials:                       # 上游 vendor 凭据；同 vendor 可配多条做 failover
      - alias: openai-primary
        vendor: openai
        api-key: ${OPENAI_API_KEY}
        base-url: https://api.openai.com/v1   # 指向"版本化根"，网关在其后拼资源路径
        priority: 100
      - alias: claude-primary
        vendor: anthropic
        api-key: ${ANTHROPIC_API_KEY}
        # Anthropic 的 base 不带版本段（https://api.anthropic.com），与 kernel provider 一致
    api-keys:                          # 网关签发给调用方的凭证
      - id: team-a
        key: ${GATEWAY_KEY_TEAM_A}
        status: active                 # 非 active 的 key 不会被装载
        requests-per-minute: 600
        tokens-per-minute: 200000
        allowed-vendors: [openai, anthropic]
    pricing:
      openai/gpt-4o-mini:
        prompt-per-million: 0.15
        completion-per-million: 0.60
```

调用方鉴权支持 `Authorization: Bearer <key>`，也接受 Anthropic 客户端习惯的 `x-api-key`。

## 对外协议面

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/v1/chat/completions` | OpenAI 兼容；线格式为蛇形（`max_tokens`、`stop`、`tool_calls`…），非流式与 SSE 流式 |
| POST | `/v1/embeddings` | 按各家真实协议直连上游，见 [embeddings](#embeddings) |
| GET | `/v1/models` | 当前可路由的模型卡（`id`/`owned_by`/`capabilities`/`context_window`/`max_output_tokens`） |
| GET | `/v1/models/**` | 单个模型查询。用 `**` 而非 `{id}`：OpenAI 的模型 id 本身含斜杠 |
| POST | `/v1/messages` | Anthropic Messages，含原生事件帧序的流式 |
| POST | `/v1/messages/count_tokens` | **估算**：网关这层没有各家 tokenizer，按约 4 字符 = 1 token 计，响应带 `estimate: true` |

`model` 字段两种写法都接受：裸名（`claude-3-5-sonnet-latest`）或带命名空间（`anthropic/claude-3-5-sonnet-latest`）；
`/v1/messages` 上裸名会补 `anthropic/` 前缀。回显时同理 —— 上游只给裸名，网关补回调用方使用的 vendor 前缀，
两条路径（kernel 主链路 / 多模态直连）共用同一份规则，避免同一模型经两条路得到两种 id。

### 流式

- OpenAI 面：`data:` 帧 + `[DONE]` 结束哨兵；`stream_options.include_usage` 由 `z.llm.inject-stream-usage` 决定是否替客户端补上
  （默认 `false`：部分自建兼容端点会对未知的 `stream_options` 直接报 400）
- Anthropic 面：`message_start` → `content_block_start` → `content_block_delta` → `content_block_stop` →
  `message_delta`(stop_reason, usage) → `message_stop`，`content_block` 只开一次
- 转发的 Anthropic 流里出现 `error` 帧时按失败处理，不会静默当成普通增量
- **响应一旦提交就无法改状态码**：流式中途出错时异常抛给容器，客户端看到的是已完成的 SSE 连接里插入 `event: error`

## 模型路由

`ModelRouter.resolve()` 按四步判定，全部失败才 404：

1. `z.llm.model-aliases` 显式映射（请求 model → 内部 `vendor/model`）
2. `vendor/` 前缀命中 vendor
3. 前缀不是任何 vendor（`meta-llama/Llama-3.1-70B` 这类）→ 按 provider 声明的 `supportsModel` 能力判定
4. 裸名同样走能力判定

`Vendor` 共 6 家：`openai` / `anthropic` / `deepseek` / `qwen` / `dashscope` / `gemini`。

## 多模态（图片）转发

kernel 的 provider 只把 `Msg.content` 当纯文本下发，**经主链路转发的图片会被静默丢掉** —— 上游"看不见图"却给出一个像样的回答。
因此带 image part 的请求由 `MultimodalChatRelay` 绕过 provider 直连上游，凭据选择 / 冷却 / failover 仍复用 `ProviderInvoker`，
 resilience 只有一份实现。

| vendor | 端点 | 鉴权头 | 图片形状 |
| --- | --- | --- | --- |
| `openai` / `deepseek` / `qwen` | `{base}/chat/completions` | `Authorization: Bearer` | `image_url` |
| `anthropic` | `{base}/v1/messages` | `x-api-key` + `anthropic-version` | `{type:"image", source:{type:"base64"|"url"}}`，`data:` URL 自动拆出 `media_type`/`data` |
| `dashscope` / `gemini` | — | — | 不转发，请求被拒 |

行为边界（默认即"诚实优先"）：

- 转发面开着但 vendor 不被支持 → **400**，错误信息点明哪几家可转发
- `z.llm.allow-multimodal-downgrade=true` 才允许把多模态摊平成纯文本继续服务
- `z.llm.relay-multimodal=false` 整体关闭直连面，回到"按降级开关决定 400 或摊平"

Anthropic 面另有两条原生约定：`system` 上提为顶层字段，`tool_result` 必须落在 `user` 消息里；
调用方没传 `max_tokens` 时补 `8192` —— 与 kernel `AnthropicProvider` 的默认值同数，但目前是两处字面量（kernel 没有公开常量）。

## embeddings

`POST /v1/embeddings` 是网关直连面（kernel 的 `LlmProvider` SPI 没有 embedding 方法），各家形状不同：

| vendor | 端点 | 请求 | 响应 |
| --- | --- | --- | --- |
| `openai` / `deepseek` / `qwen` | `{base}/embeddings` | `input: [...]` | `data[].embedding` |
| `dashscope` | `{base}/services/embeddings/text-embedding/text-embedding` | `input.texts` | `output.embeddings[].text_index` |
| `gemini` | `{base}/v1beta/models/{model}:batchEmbedContents` | `requests[].content.parts[].text` | `embedding[].values`（模型名在路径里） |
| `anthropic` | — | — | 无向量端点，400 |

三条约束：

- **条数不齐即失败**：某条输入没拿到向量会报 502（消息形如 `1 vectors for 2`），而不是静默返回短列表
- 裸 embedding 模型名（`text-embedding-3-small`）需要显式归属：`z.llm.embedding-vendor`；
  未配置且只有一家 OpenAI 兼容 vendor 时自动选中，否则 404 并在提示里指向该配置项
- 单次输入条数受 `z.llm.max-embedding-batch`（默认 2048）约束，超出报 413

记账只落 prompt 侧（`usage.{prompt_tokens,total_tokens}`），与 OpenAI 形状一致。

## 弹性与状态码

`ProviderInvoker` 在同一 vendor 的凭据池内做 failover：某个 AK 被打爆（429）、上游 5xx 或网络故障时该凭据进入冷却窗口，
请求自动改投下一个凭据，而不是把 429 直接抛给调用方。

`retryable()` 实测口径：429 / 401 / 403 / 5xx 换凭据重试；其余 4xx（如 400 参数错）直接透出；
无 HTTP 状态的连接 / 超时 / 解析故障也换凭据。

状态码语义（`GlobalExceptionHandler`）：

| 情况 | 结果 |
| --- | --- |
| 网关本端问题 | 401 鉴权 / 403 越权 / 404 模型未找到 / 400 请求不合法 / 413 超限 / 429 限流 / 500 内部错误 |
| 上游返回 4xx（除 429） | 原样透出该状态码 —— 是调用方或配置问题 |
| 上游 429 | 429 |
| 上游 5xx 或未知 | 502 |
| **body 在读进 controller 之前就坏掉**（截断 / 全空 / 不是 JSON） | 400 `invalid_request`（0.1.6 起；此前是 500，见"已知边界"） |
| **整个凭据池都被限流** | 429，不是笼统的 502 —— 客户端的正确动作是退避，把它压成 502 会诱导立刻重试 |

错误响应体：`{"error":{"message","type","code","param"}}`。对外 `message` 只放给人看的短句，
异常原文（Spring 会把 controller 的方法签名、Jackson 的内部类名整段带出来）只进 log。

### 与宿主应用同 JVM 时（0.1.5 起的两条契约）

上表那套状态码只在异常真的落到本类头上时成立。合并进程里有两个坑，0.1.5 各钉了一条：

- **bean 名带 `zLlm` 前缀**（`@Component("zLlmGlobalExceptionHandler")` + 13 个 `@Bean` 同名前缀）。
  Spring 默认按简单类名首字母小写注册，宿主或另一支 L3 库里只要有同名类，扫描期就
  `ConflictingBeanDefinitionException` 直接起不来，且 `allow-bean-definition-overriding` 救不了。
- **advice 限定 `basePackages` + `@Order(HIGHEST_PRECEDENCE)`**。Spring 解析异常时是按 advice 顺序
  逐个问"你有没有这个异常的 handler"，**第一个命中的赢，不是全进程挑最具体的那个**。宿主常见的
  `@RestControllerAdvice(basePackages = "com.zifang")` + `Exception` 兑底只要排在前面，就会把
  网关的 429/400/502 压成 500 + 宿主自己的信封（z-opc 上实测过）。限定包名让本类只管
  `com.zifang.z.llm.core.controller` / `com.zifang.z.llm.admin.controller` 两个包（不反向吃掉宿主语义），
  抬 order 让网关自己的 controller 必定先命中本类。

两条都由永久闸盯着：`MergedProcessAdvicePrecedenceTest`（真 `AnnotationConfigWebApplicationContext`，
宿主替身 advice 先注册且不带 `@Order`）。`standaloneSetup` 那层结构上测不到这两个机制。

## 限流与记账

按 ApiKey 维度同时限制三个口径：RPM（`requests-per-minute`）、TPM（`tokens-per-minute`）、并发数（`z.llm.max-concurrent-requests`）。
并发是新加的维度：流式请求长时间占住 servlet 线程，只限 RPM 挡不住少量慢请求把线程池打满。

- 上游返回多少 token 就扣多少，不做预估扣费
- 被拒时响应带 `Retry-After`
- `UsageLedger` 按 key 累计 token 与成本，单价取自 `z.llm.pricing`（每百万 token），`z.llm.currency` 仅作回显标记；
  上游没给 usage 时记账标记 `estimated=true`

桶是单实例内存实现；分布式部署需替换为 z-cache / redis 原子计数器。

## 控制面

`z-llm-admin` 的端点默认**不注册**。打开需要两个条件同时成立：

```yaml
z.llm:
  enabled: true          # 提供 LlmCredentialStore / ApiKeyService / RateLimiter 等 bean
  expose-admin: true     # 注册 /z-llm/admin/*
```

只开 `expose-admin` 会在启动时缺 bean 直接失败（按设计：没有网关就没有网关的控制面）。

| 路径 | 内容 |
| --- | --- |
| `GET /z-llm/admin/overview` | vendor / 凭据数 / 活跃 key 概览 |
| `GET /z-llm/admin/vendors` | 各 vendor 凭据数与模型数 |
| `GET /z-llm/admin/vendors/{vendor}/models` | 列模型 |
| `GET /z-llm/admin/credentials` | 凭据总数与分布（不含 key 原文） |
| `GET /z-llm/admin/api-keys` | key 清单，**只回显掩码**（头尾各 4 位） |
| `GET /z-llm/admin/rate-limit` | 限流桶快照 |
| `GET /z-llm/admin/health` | providers / credentials |
| `GET /z-llm/admin/ui` | Thymeleaf 最小概览页 |

> ⚠️ 这些端点自身没有鉴权。开关只保证"默认关"，要在公网或内网共享环境打开，必须自行在前面挂认证。

## 配置项

前缀 `z.llm`（`GatewayProperties`），下表与代码同批实测，未列出的键不生效。

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | 未设即不装配 | `@ConditionalOnProperty` 总开关，与遗留网关共存的必要设计 |
| `credentials` | `[]` | 上游凭据列表 |
| `api-keys` | `[]` | 调用方凭证列表 |
| `expose-admin` | `false` | 是否注册控制面 |
| `max-tokens-limit` | `32768` | 调用方传的 `max_tokens` 超限直接拒；未传则不主动限制 |
| `rate-limit-enabled` | `true` | 限流总开关 |
| `max-concurrent-requests` | `0`（不限） | 单 key 并发上限 |
| `inject-stream-usage` | `false` | 是否替客户端补 `stream_options.include_usage=true` |
| `enforce-key-restrictions` | `true` | 是否强制 `allowed-models` / `allowed-vendors` 白名单 |
| `retry.enabled` | `true` | 跨凭据 failover |
| `retry.max-attempts` | `3` | 单次请求最多尝试几个凭据（含首个） |
| `retry.backoff-ms` | `200` | 每次重试前基准退避，按尝试次数线性放大 |
| `retry.cooldown-ms` | `30000` | 凭据冷却时长 |
| `model-aliases` | `{}` | 请求 model → 内部 `vendor/model` |
| `pricing` | `{}` | `"vendor/model"` → 每百万 token 单价 |
| `currency` | `USD` | 仅用于用量接口回显 |
| `relay-multimodal` | `true` | 带图请求是否绕过 provider 直连上游 |
| `allow-multimodal-downgrade` | `false` | 不允许转发时是否容忍摊平成文本 |
| `upstream-connect-timeout-sec` | `10` | 直连上游连接超时 |
| `upstream-read-timeout-sec` | `300` | 读超时，流式长回答要留足 |
| `upstream-write-timeout-sec` | `60` | 写超时 |
| `max-embedding-batch` | `2048` | 单次 `/v1/embeddings` 输入条数上限 |
| `embedding-vendor` | 未设 | 裸 embedding 模型名归属的 vendor |

## 构建与测试

```bash
mvn -o -pl z-llm-core -am test       # 必须带 -am
```

`-am` 不是可省的：不带它时 `z-llm-api` 会从本地 m2 解析到已发布的旧 jar，新增的 DTO 直接 `NoClassDefFoundError`。
改过 `z-llm-api` 后要让消费方看到，需要 `mvn install`（否则会与被同步的新上游版本形成 m2 分叉）。

实测（2026-09-25，本机全 reactor `mvn -B test`）：

| 模块 | Tests | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- |
| `z-llm-core` | 135 | 0 | 0 | 5 |
| `z-llm-starter` | 2 | 0 | 0 | 0 |
| `z-llm-admin` | 2 | 0 | 0 | 0 |

覆盖的层面：双协议 HTTP 线格式（MockMvc，含 embeddings 与 Anthropic 转发）、请求映射、参数下沉、模型路由、
凭据池 failover 与状态码、限流三口径、记账、starter 装配 smoke、控制面注册开关与 key 掩码。

### 真实上游 E2E

`z-llm-core/src/test/.../e2e/RealUpstreamE2ETest` 走真网络、真 provider（不经测试替身），由环境变量门控：

```bash
Z_LLM_E2E_OPENAI_API_KEY=sk-... \
Z_LLM_E2E_ANTHROPIC_API_KEY=sk-ant-... \
mvn -o -pl z-llm-core -am test -Dtest=RealUpstreamE2ETest
```

模型名可用 `Z_LLM_E2E_OPENAI_MODEL`（默认 `gpt-4o-mini`）、`Z_LLM_E2E_OPENAI_EMBED_MODEL`
（默认 `text-embedding-3-small`）、`Z_LLM_E2E_ANTHROPIC_MODEL`（默认 `claude-3-5-haiku-latest`）覆盖。

**没配 key 时这 5 个用例是 skipped 而不是 passed** —— 上面表格里的 `Skipped: 5` 就是它们，不能计入"已验证"。
测试图片在运行时用 `BufferedImage` 画一张 240×240 PNG，不往仓库塞 base64 字面量；
两个转发用例断言 `prompt_tokens > 40`，作为"图没被静默丢掉"的探针。

## 发布到 Maven Central

```bash
mvn -B -P central clean deploy
```

`central` profile 会补上中央仓要求的四件事：`maven-source-plugin` 的 sources jar、`maven-javadoc-plugin` 的
javadoc jar（绑在 `verify`）、`maven-gpg-plugin` 的 `.asc` 签名（也绑在 `verify`），再由
`org.sonatype.central:central-publishing-maven-plugin` 把整包打成 `target/central-publishing/central-bundle.zip` 上传。
一次上传覆盖父 pom + 4 个子模块：父 pom 只有 `pom` 一份（加 `.asc` 与 4 种校验和共 6 个条目），
每个子模块是 `jar/pom/sources/javadoc` 四种构件 × 同样 6 个条目 = 24，整包合计 102 个条目。

⚠️ **`BUILD SUCCESS` 不等于已发布**：profile 里 `waitUntil=uploaded`，Maven 只等到"上传成功"就返回，
控制台的 `Deployment will publish automatically` 之后还有服务端校验与同步。
凭据取 `~/.m2/settings.xml` 里 id 为 `central` 的 server（token 认证）。

判断"到底发布了没有"，用 repo1 而不是状态 API：
`curl -s -o /dev/null -w '%{http_code}\n' https://repo1.maven.org/maven2/io/github/yuku123/z-llm-core/<版>/`
（2026-09-25 发 0.1.4 时实测：`central.sonatype.com/api/v1/publisher/status` 带正确 Basic 认证仍恒返回
`{"httpStatus":500,"errorCode":10500}`，鉴权是对的——不带认证返回 401 `Invalid auth`——所以这个接口不能当验收依据）。
更硬的验收是从干净本地仓拉一次，能解析下来才算真能消费：

```bash
mvn -B -Dmaven.repo.local=/tmp/m2-central-check dependency:get \
  -Dtransitive=false -DremoteRepositories=https://repo1.maven.org/maven2 \
  -Dartifact=io.github.yuku123:z-llm-starter:<版>
```

父 pom 是 `packaging=pom`，`dependency:get` 必须显式带类型 `-Dartifact=io.github.yuku123:z-llm:<版>:pom`，
不写类型时它去找 jar 而报失败——那是命令写错了，不是没发布。

实测节奏（0.1.4）：`uploaded 13:12:14Z` → repo1 五个构件**同时** 200 于 `13:33:36Z`，约 21 分钟；
中途的 404 不能当失败证据。发布成功后再核两件事：repo1 的 `<artifact>-<版>.jar.md5`
应与本机 `target/` 里的 jar 逐字节相同；带传递依赖、指向空本地仓能一次拉全，才算真能消费。

## 已知边界

- 真实上游 E2E 只在配了 AK 的环境能跑；本仓默认状态是 5 skipped
- `count_tokens` 是估算值，不是各家 tokenizer 的精确值
- ApiKey 与凭据目前来自配置文件，动态刷新（接 zk-config）未做
- 限流桶是单实例内存态
- `dashscope` / `gemini` 的图片不在转发面内（会明确报 400，不静默降级）
- ~~body 定性不了的输入错误回 500，不是 400~~ —— **0.1.6 已修**：advice 补了
  `@ExceptionHandler(HttpMessageNotReadableException.class)`，五格（`/v1/chat/completions` 的截断 /
  全空 / `hello`、`/v1/embeddings` 的 `{oops`、`/v1/messages` 的全空）从 `500 internal_error`
  变 `400 gateway_error / invalid_request`，message 换成定句、Spring 原文只进 log。
  （算缺陷而不是"反正有信封"：客户端把 4xx 判成"我的请求坏了、不重试"、把 5xx 判成"服务端坏了、
  退避后重试"，回 500 等于让它拿一个永远坏的请求去重试。）
  0.1.5 的 before 读数（14 格，含异常类名）留在 `~/.cache/zllm-40-probe/before-extended.log`。
  永久闸：`GatewayHttpProtocolTest.malformedOrMissingBodyIs400AndDoesNotLeakInternals`
  （三支具名变异各自点名转红：摘 handler → 状态码那批、`message` 换回 `ex.getMessage()` → 泄漏那批 16 条）。
  顺带一条没修的既有行为：**未鉴权 + 坏 body 现在是 400 不是 401** —— body 解析发生在
  controller 方法体之前，鉴权在方法体里，所以顺序改不动（要挪得先加 filter/interceptor），0.1.6 未做。
- **网关路径上 mapping 阶段的 415/405/404 不归本库管**（结构性，不是漏改）：0.1.5 把 advice 收窄成
  只管 `com.zifang.z.llm.core.controller` / `com.zifang.z.llm.admin.controller` 两个包，而这几类异常
  由 `HandlerMapping` 抛出、那时 handler 还没定下来 ⇒ **带限定的 advice 一律不参与**。
  0.1.6 把这个机制量成了四腿对照（永久闸
  `GatewayHttpProtocolTest.mappingPhaseErrorsEvadePackageScopedAdvice`，四份 advice 的 handler 集合
  逐字相同，唯一差别是类级限定）：裸 advice **接得到** 415/405（阳性腿，摘掉标记体它必红），
  限定成本库 controller 所在包 ⇒ 接不到，限定成 `com.zifang`（宿主 z-opc 那份 advice 的写法）⇒ 也接不到。
  所以合并进程里能接住 405 的只有**裸** advice —— z-config-web 那份正是这个形状
  （`javap -v` 读已发布件 `z-config-web-1.0.8.jar` 里的
  `com/zifang/z/config/web/config/GlobalExceptionHandler.class`：类级注解 `#90()` 零元素、
  没有任何 `basePackages` 常量），它给的是 `com.zifang.util.core.meta.Result` 信封，不是
  `{"error":{...}}`。⚠ 上面四腿是 MockMvc 层量到的**可达性**，**没有**跑合并进程拍过 405 的实际响应体。
  未知路径另有一层：这一层根本没抛异常（404、空 body、`getResolvedException()` 为 null，同一条闸里钉着），
  谈不上谁接得住。要收 405/415 只有一条路：宿主或 z-config 侧给那份裸 advice 限定包名 / 抬 order，
  不在本库范围内 —— 给本库 advice 加 `HttpMediaTypeNotSupportedException` 的 handler 是够不着的死代码
  （上面 D 腿量的就是这件事，所以 0.1.6 没顺手收 415）。

