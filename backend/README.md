# 德州扑克引擎与锦标赛指南

本模块包含 Agent Tavern 已完成的两个后端阶段：确定性的德州扑克规则引擎，以及六人单桌锦标赛的持久化与宕机恢复能力。

第一阶段提供下注、摊牌、领域事件和质量守卫。第二阶段提供 6 人各 10,000 筹码、盲注升级、按钮轮转、淘汰排名、不可变检查点、连续事件流、幂等命令回执及 `TournamentStore` 的 PostgreSQL/JDBC 实现；结构由 Flyway 管理。

它还不是完整游戏产品：浏览器前端、HTTP/WebSocket API、Agent 编排和 DeepSeek/其他 LLM 集成尚未实现。

## 锦标赛恢复与隐私边界

命令服务在每次创建锦标赛、行动或开始下一手牌时，原子提交检查点、事件批次和命令回执。服务重建后从持久化检查点恢复，再执行下一条命令；事件序号在同一锦标赛内严格连续。

检查点是服务端私有状态：其中包含底牌、余牌堆和烧牌，因而不能作为公开事件或客户端状态传输。原始 `TournamentExecution.events` 与持久化的 `TournamentEventEnvelope` 负载也只可在服务端使用，因为 `HoleCardsDealt` 含有所有玩家的底牌。当前不存在 WebSocket 广播端点；未来接入时必须在上述原子提交**成功之后**，为每类受众生成相应的脱敏投影后才可广播，绝不能直接发送原始事件。失败、冲突或幂等重放不得产生未提交事件的广播。

## 验证方式

要求：Java 21 与 Maven Wrapper。请先选择 Java 21，然后在仓库根目录执行以下命令。

Windows 下只运行引擎性质校验与架构守卫：

```powershell
backend\mvnw.cmd -f backend\pom.xml -Dtest="HandInvariantProperties,GameEngineDependencyTest" test
```

Windows 下执行第一阶段完整干净验证：

```powershell
backend\mvnw.cmd -f backend\pom.xml clean verify
```

Unix 或 CI 的等价命令：

```sh
./backend/mvnw -f backend/pom.xml clean verify
```

默认验证不需要 Docker 或可连接的 PostgreSQL；它会覆盖领域、恢复、序列化和迁移契约。

PostgreSQL 集成验收额外需要 Docker，使用 Testcontainers：

```powershell
backend\mvnw.cmd -f backend\pom.xml -Ppostgres-it verify
```

无 Docker 时，该命令仍会完成默认测试；`PostgresTournamentStoreIT` 会明确显示为 `SKIPPED`，表示 Docker 验收尚未执行，而不是 PostgreSQL 测试通过。也可在专门的回环、测试专用 PostgreSQL 上显式设置 `POKER_TEST_DB_URL`、`POKER_TEST_DB_USERNAME` 和 `POKER_TEST_DB_PASSWORD` 来启用本地 runner；这些变量和凭据只能保存在本地环境，不能提交。

六人恢复场景可单独运行：

```powershell
backend\mvnw.cmd -f backend\pom.xml -Dtest=TournamentRecoveryEndToEndTest test
```

该测试对每条命令重建 `TournamentCommandService`，使用按手牌编号固定的牌堆序列和优先 CHECK/CALL、否则 ALL_IN 的服务端合法动作策略。它在每一步检查事件序号无缺口，并按领域约定将当前手牌已投入筹码与座位筹码相加，验证总额始终为 60,000。

性质校验由 JUnit Jupiter 确定性执行。六项校验分别运行 500 个固定且互不相同的场景种子；一旦失败，错误信息会包含可复现该问题的种子。当前测试框架不提供自动收缩。

## 动作金额与加注权

对于 `RAISE` 和 `ALL_IN`，`PlayerAction.amount` 始终表示**玩家在当前街的目标总投入**。

例如，玩家本街已经投入 40，随后执行 `raiseTo(120)`，则本次实际再投入 80。all-in 动作必须使用该玩家的完整目标投入，即 `streetCommitted + stack`；短筹码 all-in 也遵循相同规则。`FOLD`、`CHECK` 和 `CALL` 的 amount 始终为 `0`。

完整加注要求本次加注增量不小于上一次完整加注的增量，并会为其他仍在牌局中的玩家重新开放加注权。短筹码 all-in 可以提高当前最高投入，因此投入不足的玩家仍需响应，但它不会为已经使用过加注权的玩家重新开放加注权。

`LegalActions` 是服务端权威的合法动作结果，统一给出跟注金额、最小加注目标、最大投入目标、all-in 可用性以及当前是否拥有加注权。调用方只能提交该结果允许的动作。

## 按钮位、盲注与行动顺序

三至六人牌局中，小盲位是按钮位顺时针方向的下一个有人座位，大盲位是再下一个有人座位。翻牌前由大盲左侧第一个仍可行动的座位开始。

单挑牌局中，按钮位同时是小盲位，并且翻牌前先行动；另一名玩家是大盲位。翻牌、转牌和河牌阶段，都由按钮左侧第一个仍可行动的座位开始。

每个下注街结束后，引擎只清空玩家的本街投入，整手牌累计投入仍被保留以供边池计算。进入公共牌阶段时，引擎先烧掉一张私有牌，再依次发出三张翻牌、一张转牌和一张河牌。

烧牌不会通过公开 `Hand` 或领域事件 API 暴露；通过注入牌堆得到的底牌和公共牌则是不可变且确定的。

## 底池、边池与奇数筹码

所有已经投入的筹码都会进入底池，包括后来弃牌玩家的投入。底池按照主池到更深层边池的顺序生成；弃牌玩家可以为底池提供筹码，但没有资格赢得任何一层底池。

多名赢家牌力相同时，每一层底池独立平分。无法整除的奇数筹码从按钮左侧开始，按照座位顺时针顺序分配给该层底池的并列赢家。

## 确定性牌堆

测试中可使用 `Deck.ordered(...)` 注入固定牌序。传入列表就是精确抽牌顺序：引擎先按顺序发两轮底牌，随后在街道推进时抽取烧牌和公共牌。

牌堆会拒绝重复的物理牌；每次 `draw()` 都返回新的不可变剩余牌堆，因此测试可以注入已知牌序，而不会触发隐藏洗牌或原地修改。
