# Agent Tavern · 百兽茶馆

Agent Tavern 是一款以多 Agent 为核心的趣味德州扑克游戏。玩家不仅能下注，也能在牌桌上发言、施压和诈唬；性格不同的 Agent 会把这些话作为不可信线索纳入决策。项目采用 Java 21 + Spring Boot 4 的服务端权威牌局、React 19 前端，以及可选的 DeepSeek 决策提供器。

## 当前可玩版本

截至 2026-10-01：大厅、选角、实时同步、结果页和基础回放已实现；真实 PostgreSQL 会话恢复和事务回滚已验收，并补齐整栈启动配置、健康检查和 CI 冒烟流程。同场锦标赛内的 Agent 记忆、跨手情绪和发言去重已接通，已有可关闭的牌桌短音效、轻量动态反馈和匿名本地战绩。DeepSeek 真实联调已通过；断开 SSE 后的通知异常不会影响已提交牌局，过期会话及关联私有数据会定时清理。公开仓库的首次 GitHub Actions 已通过容器整栈和游戏测试；**实际公网主机、域名与 HTTPS 部署仍待验收**。功能清单见 [P0 进度](docs/reviews/2026-09-12-p0-progress.md)，最新验证见[部署与验收](docs/deployment.md)。

- 精细化中国幻想茶馆界面、8 名统一风格 Q 版角色资产与本土化人物背景；
- 玩家对 5 个 Agent 的真实牌局，服务端校验弃牌、过牌、跟注、加注和全下；
- 大厅支持玩家/观战模式，8 位角色中选择 5/6 位牌友；当前规则固定为标准六人淘汰赛；
- 六名 Agent 互战观战模式，支持单步、连续开局、0.5×/1×/2×/4×播放及角色日志筛选，不会向观众泄露底牌；
- Agent 自动连续行动、四条街推进、摊牌、主池与边池结算；
- HttpOnly 匿名会话和按玩家脱敏的公开投影，浏览器只能收到自己的底牌；
- 可影响 Agent 输入的牌桌发言，含长度、控制字符、冷却和提示词注入防护；
- 无密钥时使用会说自然中文的确定性离线决策器，配置后切换到 DeepSeek Flash；
- 离线 Agent 按自身底牌、公共牌、跟注价格与整手投入预算决策，避免无视牌力的连续加注及大额跟注；策略边界见[离线策略验收](docs/reviews/2026-09-22-local-strategy.md)；
- 每位 Agent 独立保存公开行动统计、有限私有笔记和近期发言，传入后续决策；胜负情绪跨手延续并衰减，牌桌展示公开情绪标签。PostgreSQL 模式支持进程重启恢复，另开一桌重新记忆；
- SSE 按序号推送与断线补发，刷新恢复当前会话；动作带命令 ID 与期望版本，重复提交不重复扣筹码；
- 冠军、最终排名、玩家淘汰后继续观战；当前会话的脱敏回放支持时间轴、逐步、播放、调速和跳转手牌；
- PostgreSQL 检查点、事件流、Web 会话/聊天/界面日志/回放归档；真实数据库重启恢复及事务失败回滚测试通过。
- Docker Compose、Windows/Unix 一键脚本、健康检查、SSE 反向代理、基础网关限流和独立整栈 CI 冒烟；GitHub Actions 已在 Linux Docker 环境通过。
- 默认静音的牌桌合成提示音、音量设置、发牌/下注/弃牌轻量反馈，支持系统及应用内减少动态效果设置。
- 当前浏览器内的匿名战绩页，统计完赛、胜率、累计盈亏、最大底池、行动习惯和各角色交手结果；观战不计入，刷新与断线补发按事件序号去重。

发布主线仍缺：实际公网主机/HTTPS 验收、异地备份与正式故障恢复演练、生产负载验收。本机 PostgreSQL 归档恢复、真实 DeepSeek 适配器及 GitHub Actions 的容器版备份恢复校验已通过。本次联调密钥曾出现在聊天中，正式部署前必须撤销并更换。当前模型请求限额仅在单个后端进程内生效，尚非精确费用上限。自定义规则、关键手牌收藏、成就/难度/牌桌主题和完整角色动画暂缓；跨桌长期记忆与扩展回放视角也不作为本轮发布阻断项。

当前角色包括茶馆掌柜阿绯、矿场工头豪哥、票号账房沈听澜、退隐镖师杜叔、药铺学徒小满、说书人墨羽、商队主熊镇山和码头跑堂阿拾。角色保留独立的激进度、诈唬倾向、耐心和受挑衅敏感度；牌桌发言只作为受控心理线索，不会覆盖合法动作与服务端规则。

## 立即运行

已安装 Docker 时，Windows 运行 `.\scripts\start.ps1`，Unix 运行 `sh scripts/start.sh`，默认访问 `http://localhost:8088`。首次自动生成本地数据库密码。详细配置和验收边界见 [部署说明](docs/deployment.md)。

分开运行开发服务：

要求：Java 21、Node.js 22 或更新版本。

第一个 PowerShell 窗口：

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

第二个 PowerShell 窗口：

```powershell
cd frontend
npm install
npm run dev
```

打开 <http://localhost:5173>。默认 `local` profile 使用进程内存储，不要求 PostgreSQL 或 LLM 密钥；**后端进程退出会丢失 local 牌局**。后端未启动时，大厅显示连接错误，不会自动创建模拟牌局。

需要数据库持久化时，设置 `SPRING_PROFILES_ACTIVE=postgres`、`DATABASE_URL`（JDBC URL）、`DATABASE_USERNAME`、`DATABASE_PASSWORD` 后启动；Flyway 自动执行迁移。当前仅支持单后端实例，会话有效期 8 小时；过期数据再保留 24 小时，随后每 15 分钟分批清理会话、回放与对应锦标赛私有数据。另开一桌会替换当前浏览器会话，暂未提供旧桌历史列表。

## 启用 DeepSeek

启动后端前，只在当前终端设置环境变量：

```powershell
$env:AGENT_TAVERN_AGENT_PROVIDER="deepseek"
$env:DEEPSEEK_API_KEY="<你的新密钥>"
.\mvnw.cmd spring-boot:run
```

默认模型为 `deepseek-flash`，可通过 `DEEPSEEK_MODEL` 覆盖。默认关闭思考模式、每次最多生成 512 Token，每个后端进程滚动一小时最多发起 120 次请求（包括修复重试）；可用 `DEEPSEEK_MAX_OUTPUT_TOKENS` 与 `DEEPSEEK_MAX_REQUESTS_PER_HOUR` 调整。达到限额后自动执行安全动作。此前粘贴到聊天中的密钥应视为已暴露，请在 DeepSeek 控制台撤销并生成新密钥。

## 状态边界与隐私

锦标赛的私有检查点会保存恢复所必需的底牌、余牌堆和烧牌，必须只保留在服务端。REST、SSE 与回放 API 只返回 `TableView` 脱敏投影，不会序列化检查点或原始领域事件。SSE 仅广播成功提交的受众专属投影；回放权限保持原会话范围。数据库只保存 Cookie 的 SHA-256 摘要。

引擎使用 JUnit Jupiter 执行确定性的性质校验。每项校验使用固定且可复现的场景种子，不依赖 jqwik 或自动收缩。

## 环境要求

安装 Java 21；前端开发还需要 Node.js 22+。

## 验证项目

当前本机验收：后端 330 项、前端 22 项、Chromium E2E 7 项通过，前端生产构建与 `npm audit --audit-level=high` 通过；真实 PostgreSQL 的 LocalIT 契约 13 项通过（9 项领域持久化、4 项 Web 恢复/回滚/私有记忆恢复/过期数据清理）。本机真实 PostgreSQL 后端通过健康、失效 SSE、开桌、聊天幂等、SSE 即时帧与会话回放冒烟；修复断开 SSE 后，完成五轮 40 桌/16 并发的直接后端验收，未再出现 Agent 任务中断。无 Docker 的对应容器用例跳过，容器整栈未验收。E2E 含自动 WCAG A/AA 检查及截图产物，尚无截图差异回归基线。

前端与浏览器测试（先启动 local 后端；Playwright 会按需启动 Vite）：

```powershell
cd frontend
npm ci
npm test
npm run build
npx playwright install chromium
npm run test:e2e
```

Windows：

```powershell
backend\mvnw.cmd -f backend\pom.xml verify
```

只运行引擎性质校验与架构守卫：

```powershell
backend\mvnw.cmd -f backend\pom.xml -Dtest="HandInvariantProperties,GameEngineDependencyTest" test
```

Unix 或 CI：

```sh
./backend/mvnw -f backend/pom.xml verify
```

PostgreSQL 持久化验收（Docker/Testcontainers 可用时）在 Java 21 下运行：

```powershell
backend\mvnw.cmd -f backend\pom.xml -Ppostgres-it verify
```

该 profile 在无 Docker 的机器上仍会运行领域、恢复、序列化和迁移契约；Docker 驱动的 `PostgresTournamentStoreIT` 会明确显示为 `SKIPPED`，这不等同于 Docker PostgreSQL 验收通过。可选的、显式配置的回环测试数据库 runner 使用 `POKER_TEST_DB_URL`、`POKER_TEST_DB_USERNAME` 和 `POKER_TEST_DB_PASSWORD` 环境变量；凭据不可写入仓库。

## 密钥安全

禁止将真实的 DeepSeek API Key 提交到 Git。凭据只能保存在本地且不受 Git 跟踪的配置中。
