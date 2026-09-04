# Agent Tavern

Agent Tavern 是一款以多 Agent 为核心的趣味德州扑克游戏。本仓库目前从模块化 Java 21 后端起步，优先构建可复现、可验证的德州扑克规则引擎和可恢复的单桌锦标赛。

## 当前进度：第二阶段（锦标赛持久化与恢复）

第一阶段已完成确定性的内存扑克引擎垂直切片，包括：

- 扑克牌值对象、不可变牌堆与完整牌型计算；
- 合法下注、完整下注轮与短筹码 all-in 规则；
- 从发牌到结算的完整手牌流程与不可变领域事件；
- 主池、边池、平分底池与奇数筹码分配；
- 确定性性质校验、模块边界和架构依赖守卫。

第二阶段已在此基础上完成：

- 六人、每人 10,000 筹码的单桌锦标赛，含按钮轮转、盲注升级、淘汰和 1 至 6 名排名；
- 不可变锦标赛检查点、严格连续的事件流与幂等命令回执；
- 通过命令服务在每次合法命令后保存，进程重启后从检查点恢复并继续执行；
- `TournamentStore` 持久化端口及其 PostgreSQL/JDBC 实现，使用 Flyway 管理结构迁移；
- 六人整场恢复验收：每条命令均以新建服务实例执行，并检查 60,000 筹码守恒及事件序号连续。

浏览器前端、HTTP/WebSocket API、Agent 行为编排和 DeepSeek/其他 LLM 集成仍是后续工作，尚未实现。引擎、锦标赛状态和运行验证请参阅[后端指南](backend/README.md)。

## 状态边界与隐私

锦标赛的私有检查点会保存恢复所必需的底牌、余牌堆和烧牌，必须只保留在服务端持久化层，不能作为公开事件或客户端状态下发。原始 `TournamentExecution.events` 与持久化的 `TournamentEventEnvelope` 负载同样是服务端私有数据：`HoleCardsDealt` 含有所有玩家的底牌。当前还没有 WebSocket 端点；未来接入时必须先原子提交检查点、事件和命令回执，**仅在提交成功后**才可为各受众生成脱敏投影并广播，绝不能直接下发原始事件。提交失败、版本冲突或命令幂等重放均不得广播未提交的数据。

引擎使用 JUnit Jupiter 执行确定性的性质校验。每项校验使用固定且可复现的场景种子，不依赖 jqwik 或自动收缩。

## 环境要求

安装 Java 21。

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
