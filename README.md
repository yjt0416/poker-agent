# Agent Tavern

Agent Tavern 是一款以多 Agent 为核心的趣味德州扑克游戏。玩家不仅能下注，也能在牌桌上发言、施压和诈唬；性格不同的 Agent 会把这些话作为不可信线索纳入决策。项目采用 Java 21 + Spring Boot 4 的服务端权威牌局、React 19 前端，以及可选的 DeepSeek 决策提供器。

## 当前可玩版本

- 精细化奇幻酒馆界面、8 名统一风格 Q 版角色资产；
- 玩家对 5 个 Agent 的真实牌局，服务端校验弃牌、过牌、跟注、加注和全下；
- Agent 自动连续行动、四条街推进、摊牌、主池与边池结算；
- HttpOnly 匿名会话和按玩家脱敏的公开投影，浏览器只能收到自己的底牌；
- 可影响 Agent 输入的牌桌发言，含长度、控制字符、冷却和提示词注入防护；
- 无密钥时使用稳定的离线决策器，配置后切换到 DeepSeek V4 Flash；
- PostgreSQL 检查点、事件流、幂等命令和进程恢复能力。

仍在建设：完整观战 UI、WebSocket 推送、牌局回放、音效动效、Agent 长期记忆与生产部署封装。

## 立即运行

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

打开 <http://localhost:5173>。默认 `local` profile 使用进程内存储，不要求 PostgreSQL 或 LLM 密钥；Java 后端未启动时，前端会明确进入界面演示模式。

## 启用 DeepSeek

启动后端前，只在当前终端设置环境变量：

```powershell
$env:AGENT_TAVERN_AGENT_PROVIDER="deepseek"
$env:DEEPSEEK_API_KEY="<你的新密钥>"
.\mvnw.cmd spring-boot:run
```

默认模型为 `deepseek-v4-flash`，可通过 `DEEPSEEK_MODEL` 覆盖。此前粘贴到聊天中的密钥应视为已暴露，请在 DeepSeek 控制台撤销并生成新密钥。

## 状态边界与隐私

锦标赛的私有检查点会保存恢复所必需的底牌、余牌堆和烧牌，必须只保留在服务端。HTTP API 只返回 `TableView` 脱敏投影，不会序列化检查点或原始领域事件。后续接入 WebSocket 时同样必须在提交成功后再生成受众专属投影。

引擎使用 JUnit Jupiter 执行确定性的性质校验。每项校验使用固定且可复现的场景种子，不依赖 jqwik 或自动收缩。

## 环境要求

安装 Java 21；前端开发还需要 Node.js 22+。

## 验证项目

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
