# 本地整栈与发布验收

## 一键启动

要求 Docker Engine/Desktop（Linux 容器）及支持 `up --wait` 的 Compose。无需在宿主机另装 Java、Node 或 PostgreSQL。

Windows，在项目根目录运行：

```powershell
.\scripts\start.ps1
```

Linux/macOS：

```sh
sh scripts/start.sh
```

脚本首次创建被 Git 忽略的 `.env`，生成随机数据库密码；已有 `.env` 不会被覆盖。默认使用离线 Agent，无模型费用。服务健康后打开 `http://localhost:8088`。自定义端口用 `.env` 的 `WEB_PORT`，同时调整 `AGENT_TAVERN_CORS_ORIGINS`。

三个服务分别是 PostgreSQL 17、Java 21 后端、Nginx 静态前端/API 代理。数据库和后端没有宿主机发布端口，前端仅绑定 `127.0.0.1`。后端、前端以非 root 用户运行，文件系统只读，仅 `/tmp` 可写。数据库数据保存在 `postgres_data` 命名卷。

```sh
docker compose ps
docker compose logs --tail 100 backend
docker compose down
```

`down` 保留数据卷。不要给停止命令增加 `--volumes`，除非确实要清空全部牌局。更改 `.env` 密码不会自动更改已有 PostgreSQL 数据卷里的角色密码。

## 数据备份与恢复演练

在整栈健康时运行 `python scripts/backup-stack.py --verify`。脚本使用 PostgreSQL 自带的 `pg_dump` 生成一致性自定义格式归档，默认写入 Git 忽略的 `backups/`；`--verify` 会把归档恢复到随机命名的隔离数据库，检查会话表后删除该临时库。`--output` 可以指定全新归档文件，已有文件不会被覆盖。CI 的 `compose-smoke` 在重启恢复验收后也执行此演练。

归档含未公开手牌、牌堆及匿名会话资料。正式部署应每日备份到受控且加密的外部存储，限制读取权限，设置明确保留期，并定期从归档恢复演练。当前脚本只负责生成和本机校验；异地存储、自动轮换和正式故障恢复仍需随部署环境配置。不要把 `backups/` 发布到静态站点或提交到 Git。

在线数据的会话有效期为 8 小时。服务每 15 分钟清除过期牌桌的内存缓存与实时连接，并在到期 24 小时后分批删除会话、回放帧和对应锦标赛的私有检查点、事件及回执（每批最多 500 桌）。服务启动约 1 分钟后进行第一次清理。备份归档不受在线清理影响，仍须单独设定轮换和删除策略。

## 健康检查与实时代理

- `GET /health`：静态前端存活。
- `GET /api/health`：代理到后端 readiness；数据库不可用时不应就绪。
- 后端内部 `/actuator/health/liveness` 与 `/actuator/health/readiness` 可供容器探测，`/actuator/metrics` 提供业务与 JVM 指标；外部 `/actuator` 被 Nginx 拦截，不暴露配置、环境变量和组件详情。
- SSE 代理关闭缓冲和缓存，使用 65 秒读取超时，后端每 15 秒心跳。
- 网关开桌限制为每 IP 每分钟 6 次，突发 3 次；普通 API 每秒 15 次，突发 30 次；SSE 每 IP 最多 12 条、每会话最多 8 条。生产多用户/NAT 场景需要实测调整。
- Compose 下启用 ECS JSON 结构化控制台日志。`agent.tavern.tables.created`（按模式）、`agent.tavern.sse.subscriptions`、`agent.tavern.sse.resumes` 统计开桌与连接；DeepSeek 模式增加 `agent.tavern.llm.requests`、`failures`、`fallbacks`、`budget.exhausted`、`illegal.actions`、`tokens`（按输入/输出）和 `latency`。这些指标只保存在进程内，尚无持久化监控或费用折算。
- DeepSeek 默认 `deepseek-flash`、关闭思考模式、每次最多 512 输出 Token。`DEEPSEEK_MAX_REQUESTS_PER_HOUR` 默认 120，滚动一小时内每次调用和修复重试都计入；达到上限改用安全动作。限额只约束单实例当前进程，重启会重置，且输入 Token 的价格依模型计费而变，因此不能把它当作严格的账户消费上限。

这些配置采用 [Compose 健康依赖顺序](https://docs.docker.com/compose/how-tos/startup-order/) 和 [Spring Boot 健康端点](https://docs.spring.io/spring-boot/reference/actuator/monitoring.html) 的机制。

## 自动验收

在专门的本地验收栈上运行；脚本会另建一张牌桌，`--restart` 会重启后端：

```sh
python scripts/smoke-stack.py --restart
```

依次检查网关健康、失效 SSE 会话的 401、玩家牌局、聊天、重复命令、SSE 即时帧、重启后相同 Cookie 的会话/回执恢复，以及回放。脚本不输出 Cookie、底牌或密钥。GitHub Actions 已添加独立 `compose-smoke` job，在临时 CI 数据卷上执行完整流程。没有 Docker 时，可针对本机 PostgreSQL 后端运行 `python scripts/smoke-stack.py --url http://127.0.0.1:8080 --health-path /actuator/health/readiness`，该模式不验证 Nginx 或容器重启。

无 Docker 时，已有 PostgreSQL 专用测试库也能运行持久化验收：

```powershell
$env:POKER_TEST_DB_URL='jdbc:postgresql://127.0.0.1:55432/agent_tavern_it'
$env:POKER_TEST_DB_USERNAME='<测试用户>'
$env:POKER_TEST_DB_PASSWORD='<测试密码>'
backend\mvnw.cmd -f backend\pom.xml -Ppostgres-it verify
```

LocalIT 仅接受回环地址且库名包含独立 `test`/`it` 标记；只删除各用例自己创建的数据。Docker IT 与 LocalIT 执行同一持久化契约，因此选择其中一个环境即可验证契约；不能把跳过项算作通过。

直接启动的本地后端还可运行 `python scripts/load-smoke.py --tables 12 --workers 6`。脚本在独立会话里并发开桌，等待玩家行动，校验聊天幂等、SSE 即时帧、下注动作和跨会话回放隔离。若某手牌因其他人全部弃牌而无需玩家行动，脚本会开始下一手。它仅接受回环地址，适合专用测试库；它不经过 Nginx 限流，也不模拟 DeepSeek 延迟，不能代表公网容量。

## HTTPS 与公开部署边界

目前 Compose 是本地整栈，不会自动申请证书或公开端口。可在宿主机部署 Caddy，参考 `deploy/Caddyfile.example`，把你控制的域名转发到本地 8088；配置 DNS 和证书后再将 `AGENT_TAVERN_SECURE_COOKIE=true`，CORS 改为准确的 HTTPS 来源，并重新创建后端容器。没有 HTTPS 时启用 Secure Cookie 会导致浏览器不能维持 HTTP 会话。

公网入口应有唯一受信任的边缘代理。当前 Nginx 使用连接 IP 做限流；再套代理时会按该代理的 IP 合并计数，需根据实际拓扑配置可信来源，不能直接信任客户端提供的转发头。

正式公开发布仍缺：异地备份保留与正式故障恢复演练、生产负载验收、域名/HTTPS 及目标主机验收。GitHub Actions 已在 Linux Docker 环境通过整栈运行和容器备份恢复校验；真实 DeepSeek 适配器已在独立本机实例联调通过。此次密钥曾出现在聊天中，正式部署前必须撤销并更换。当前只支持单个后端实例，不能直接水平扩容。请求限额只是基础费用保护；若需要严格预算，应结合 DeepSeek 账户侧限额或持久化用量控制。

## 2026-09-21 实际验证范围

- 后端 311 项常规测试通过；真实 PostgreSQL 17.11 上 9 项领域持久化及 2 项 Web 恢复/原子回滚验收通过。
- 5 项 Chromium E2E 在 PostgreSQL 后端下通过。
- 人工关闭并重启 Java 进程后，浏览器刷新恢复同一手牌、筹码、聊天和事件序号。
- Compose 官方 CLI 的配置校验通过；PowerShell 启动脚本与 Python 验收脚本语法检查通过。
- 本机无 Docker daemon，镜像构建、Nginx 容器运行及 Compose 整栈冒烟尚未执行；远端 CI 尚未运行。配置完成不等同于这些验收已经通过。

## 2026-09-22 增量验证

Agent 记忆与情绪接入后，后端 317 项常规测试、真实 PostgreSQL 12 项 LocalIT、前端 13 项测试及生产构建、Chromium 5 项 E2E 均通过。新增数据库契约覆盖私有记忆重启恢复及回放隔离，详见 [记忆验收](reviews/2026-09-22-agent-memory.md)。Docker、HTTPS、远端 CI 和真实 DeepSeek 的未验收边界不变。

## 2026-09-23 增量验证

离线策略、声音/动态反馈及匿名本地战绩接入后，后端最近一次常规套件为 324 项通过；前端 22 项、生产构建及 Chromium 7 项 E2E 通过。真实 PostgreSQL 契约仍为此前 12 项通过，本日服务端未变更后未重复执行。Docker、HTTPS、远端 CI 和真实 DeepSeek 的未验收边界不变。

## 2026-09-25 发布前复验

- DeepSeek 默认模型按当前官方接口改为 `deepseek-flash`；适配器加入输出上限、单进程一小时调用上限、Token/延迟/失败/安全降级指标。真实密钥未使用。
- 修正失效 SSE 会话：接受 `text/event-stream` 的请求现在得到明确的 401 空响应，不再触发 JSON 协商错误。独立验收脚本也检查该状态。
- 完整后端 326 项常规测试通过；真实 PostgreSQL 17.11 的 12 项 LocalIT 通过。第一次在并行前端任务负载下有一项 10 秒等待超时，单项重跑及随后整套顺序重跑均通过，仍需 CI 观察稳定性。
- 前端 22 项测试、生产构建、Chromium 7 项 E2E、`npm audit --audit-level=high` 通过。真实 PostgreSQL 后端上的本机冒烟通过健康、失效 SSE、开桌、聊天幂等、SSE 即时帧及会话回放；内部指标端点可读到开桌和订阅计数。
- Compose 配置校验通过；本机没有 Docker Engine，镜像、Nginx 代理、容器重启恢复仍待 `compose-smoke` 在 Docker/CI 环境运行。仓库当前没有 Git 远端，因此 GitHub Actions 尚未有远端运行结果。
- 本机原生 PostgreSQL 的自定义格式归档已恢复到独立临时库，读回 46 条 `table_sessions` 记录后清理临时库。容器版脚本已加入 CI，但本机尚未执行 Docker 版本。

## 2026-09-25 并发补验

本机 PostgreSQL 后端连续三轮完成每轮 40 张独立牌桌、16 个并发会话的开桌、聊天幂等、SSE、玩家行动和回放隔离；每轮总耗时约 2.9 秒，开桌 p95 为 0.59–0.73 秒，轮到玩家行动 p95 为 0.75–0.94 秒。最初脚本误将大盲位无人跟注、直接结束的合法手牌当作等待超时，已修正为进入下一手。CI 另加入 12 桌/6 并发的轻量回归；容器网关和真实模型负载仍待部署环境验收。

## 2026-09-26 SSE 断线与并发修复

高并发验收发现：断开的 SSE 客户端会让 Tomcat 拒绝再次使用异步上下文，原通知清理操作又可能抛错，使已经提交数据库的牌局被误当作提交失败。现在发送失败仅移除订阅；通知在事务提交后独立处理，不能回滚已持久化的牌局。新增回归测试分别验证断线发送不抛错、通知异常不撤销已提交牌局。

修复后后端 328 项常规测试、真实 PostgreSQL 12 项 LocalIT、Chromium 7 项 E2E 通过。本机 PostgreSQL 后端连续五轮完成每轮 40 桌/16 并发验收，单轮 2.7–3.7 秒，开桌 p95 0.64–1.00 秒，玩家行动到达 p95 0.81–1.52 秒；日志无 Agent 任务中断。CI 本地模式的 12 桌/6 并发用例也已在本机通过。浏览器用例改为选择当前合法动作，并等待页面 DOM 就绪，避免把合法禁用按钮或资源 `load` 事件延迟误判为失败。生产网关、多机器和真实模型负载仍未验证。

## 2026-09-26 过期数据与连接回收

过期牌桌现会在后台清理，不再依赖玩家再次访问；SSE 连接额度检查改为原子操作，断线或过期桌的空连接列表会移除。PostgreSQL 清理使用事务，删除会话与回放帧后，只删除不再被任何会话引用的锦标赛，相关私有数据通过外键级联清理。新增并发连接额度、内存回收和真实数据库级联测试。

本机完整后端 330 项常规测试、真实 PostgreSQL 13 项 LocalIT 通过。PostgreSQL 预览服务重新打包并启动后，独立冒烟通过健康、失效 SSE、开桌、聊天幂等、即时帧与回放。浏览器实际验证了大厅开局、玩家行动、刷新恢复及回放逐步操作。当时尚未完成 Docker/CI、公网 HTTPS 和真实模型联调；模型联调结果见下节。

## 2026-09-27 DeepSeek 实机联调与发布复验

使用独立的本机 PostgreSQL 后端实例接入真实 `deepseek-flash`，将模型请求额度限制为 2 次/小时。测试牌桌自动推进；实例指标记录 2 次请求、883 个 Token，失败、非法动作与安全降级均为 0。该实例随后停止；密钥只通过临时进程环境变量传递，未写入仓库或 `.env`。由于密钥已在聊天中披露，不得继续作为正式部署密钥。

重启后的本地预览后端和前端健康；前端 22 项测试、生产构建、`npm audit --audit-level=high`（0 项漏洞）、Chromium 7 项 E2E 与 PostgreSQL 后端冒烟再次通过。本机 SSH 公钥指纹与用户提供的 WindowsPC 指纹一致，`ssh -T git@github.com` 认证为目标 GitHub 账户。仓库尚无 Git 远端；当前只收到 GitHub 个人主页，需目标仓库 URL 才能推送并触发远端 CI。本机仍无 Docker Engine，容器与 HTTPS 部署尚未完成。

## 2026-10-01 GitHub 公开仓库与首次远端 CI

代码已推送到公开仓库 [`yjt0416/poker-agent`](https://github.com/yjt0416/poker-agent) 的 `main` 分支。首次 [GitHub Actions 运行](https://github.com/yjt0416/poker-agent/actions/runs/36824219477) 的 `game` 与 `compose-smoke` 均通过：前者执行后端及 PostgreSQL 契约、前端测试/构建/安全审计、Chromium E2E 与并发牌桌冒烟；后者在 Linux Docker 上构建整栈，验证 Nginx 代理、SSE、数据库重启恢复及容器备份恢复。该结果验证了临时 CI 环境，不代表实际公网主机、域名、HTTPS、生产流量和异地备份已验收。本机仍未安装 Docker Engine。

## 2026-10-02 本地全流程验收更新

本轮针对当前根目录代码，使用 Java 21、原生 PostgreSQL 17.11 与离线 Agent 验收，不调用真实 DeepSeek。

| 检查 | 本轮结果 |
| --- | --- |
| 后端常规套件 | 331 项通过 |
| 前端单元测试 | 27 项通过 |
| PostgreSQL 持久化契约 | 13 项 LocalIT 通过；无 Docker 的 13 项容器契约明确跳过，不计入通过数 |
| 前端生产构建 | TypeScript 与 Vite 构建通过 |
| npm 依赖审计 | 普通 `npm audit` 通过，0 漏洞 |

本轮浏览器验收改用生产构建预览，九项场景分组通过：七项短流程、一次 319 手完整观战赛程及终局回放、三次实际淘汰后继续观战。首次整组执行暴露淘汰测试命令限额过低，修正后补测通过；修复内容、分组证据与最终结论见 [本地全流程验收](reviews/2026-10-01-local-acceptance.md)。真实大厅、牌桌、观战、冠军、回放及移动界面截图见 [README 游戏界面](../README.md#游戏界面)。

每次推送还会触发 [GitHub Actions](https://github.com/yjt0416/poker-agent/actions/workflows/verify.yml) 的整组 E2E 和临时 Docker 整栈验证，具体结论以相同提交的运行记录为准。目标公网主机、域名 / HTTPS、生产负载、异地备份和正式故障恢复仍待在实际部署环境验收。
