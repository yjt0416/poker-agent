# Agent Tavern 锦标赛、持久化与恢复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有纯 Java 德州扑克引擎之上，实现可运行的六人单桌锦标赛、不可变事件序列、原子快照持久化，以及进程重启后的无损恢复。

**Architecture:** 新增纯 Java `tournament` Spring Modulith 模块，负责座位、按钮、盲注升级、淘汰、排名和下一手牌生命周期；新增 `persistence` 模块，以 Spring JDBC/Flyway 实现 `tournament` 声明的存储端口。系统采用“当前快照 + 不可变事件日志”，不是完整 Event Sourcing：命令在内存中生成新状态和事件，数据库在一个事务中完成乐观锁更新、座位/手牌快照写入、事件追加和幂等回执记录。

**Tech Stack:** Java 21、Spring Boot 4.1.1、Spring Modulith 2.1.0、Spring JDBC、PostgreSQL、Flyway、Jackson 3（由 Spring Boot 管理）、Testcontainers 2.0.5、JUnit Jupiter、AssertJ、ArchUnit。

**Spec:** `docs/superpowers/specs/2026-08-25-multi-agent-texas-holdem-design.md`

## Global Constraints

- 六个座位、每名参赛者初始筹码固定为 `10_000L`，首版不使用 Ante。
- 标准盲注级别严格为：50/100、75/150、100/200、150/300、200/400、300/600、400/800、600/1200、800/1600、1000/2000、1500/3000、2000/4000、3000/6000、4000/8000、6000/12000。
- 每完成八手牌升级一级；最后一级保持不变。
- 首手按钮位由调用方提供；随后在仍有筹码的座位中顺时针移动。
- 筹码归零者在该手结算后淘汰；只剩一名有筹码玩家时锦标赛结束。
- 同手多人淘汰时，较小的“本手开始筹码”获得较差名次；仍相同时，从按钮左侧开始的顺时针顺序先获得较差名次。该规则必须可重复。
- 所有筹码、版本号和事件序号使用 `long`，溢出必须通过 `Math.addExact`/`Math.incrementExact` 暴露，禁止静默回绕。
- `com.agenttavern.game..` 与 `com.agenttavern.tournament..` 不得依赖 Spring Web、JDBC、Flyway、JSON、JPA、LLM 或数据库类型。
- 进行中手牌检查点是服务端私有数据，包含余牌、底牌和烧牌；不得作为公开事件或视图返回。
- 每个已接受命令使用 UUID 幂等键；同一锦标赛中重复键不得再次改变筹码或追加事件。
- 持久化必须在单个数据库事务中完成快照、座位、进行中手牌、事件和命令回执的写入；乐观锁失败必须整体回滚。
- 事件 `sequence` 在单个锦标赛内从 1 开始严格连续递增；事件信封不可变。
- 不加入 Web/REST/WebSocket、DeepSeek、Agent、聊天、前端或公开脱敏投影；这些属于后续阶段。
- 不加入真实 API Key、`.env`、生成目录或本地数据库凭据。
- 禁止 `net.jqwik:*`；属性检查使用固定种子的 JUnit Jupiter 循环测试。
- 每个任务严格执行 red-green-refactor、聚焦测试、全量测试和独立提交。
- 本机没有 Docker 时 PostgreSQL Testcontainers 测试必须明确显示为跳过；纯领域、内存恢复和架构测试仍必须全部通过。具备 Docker 的 CI/开发机运行 `mvnw.cmd -Ppostgres-it verify` 作为 PostgreSQL 验收门。

## File Map

```text
backend/
  pom.xml
  src/main/java/com/agenttavern/
    game/
      card/Deck.java
      card/DeckCheckpoint.java
      betting/BettingRound.java
      betting/BettingRoundCheckpoint.java
      hand/Hand.java
      hand/HandCheckpoint.java
    tournament/
      package-info.java
      Tournament.java
      TournamentId.java
      TournamentMode.java
      TournamentStatus.java
      TournamentSeat.java
      TournamentSeatStatus.java
      TournamentEntrant.java
      TournamentCheckpoint.java
      TournamentTransition.java
      BlindSchedule.java
      TournamentEvent.java
      TournamentEventEnvelope.java
      application/
        CreateTournamentCommand.java
        ActInTournamentCommand.java
        StartNextHandCommand.java
        TournamentCommandService.java
        TournamentExecution.java
        ConcurrentTournamentUpdateException.java
        TournamentNotFoundException.java
      port/
        StoredTournament.java
        TournamentCommit.java
        TournamentStore.java
        TournamentWriteResult.java
    persistence/
      package-info.java
      PersistenceConfiguration.java
      JdbcTournamentStore.java
      TournamentCheckpointJdbcMapper.java
      TournamentEventJsonCodec.java
  src/main/resources/
    application.yml
    db/migration/V1__create_tournament_store.sql
  src/test/java/com/agenttavern/ArchitectureTest.java
  src/test/java/com/agenttavern/game/hand/HandCheckpointTest.java
  src/test/java/com/agenttavern/tournament/BlindScheduleTest.java
  src/test/java/com/agenttavern/tournament/TournamentTest.java
  src/test/java/com/agenttavern/tournament/TournamentCheckpointTest.java
  src/test/java/com/agenttavern/tournament/TournamentInvariantProperties.java
  src/test/java/com/agenttavern/tournament/application/TournamentCommandServiceTest.java
  src/test/java/com/agenttavern/persistence/TournamentEventJsonCodecTest.java
  src/test/java/com/agenttavern/persistence/MigrationContractTest.java
  src/test/java/com/agenttavern/persistence/PostgresTournamentStoreIT.java
  src/test/resources/application-test.yml
README.md
backend/README.md
```

---

### Task 1: 锦标赛与持久化模块边界、依赖和测试门

**Files:**

- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/com/agenttavern/tournament/package-info.java`
- Create: `backend/src/main/java/com/agenttavern/persistence/package-info.java`
- Modify: `backend/src/test/java/com/agenttavern/ArchitectureTest.java`
- Create: `backend/src/test/java/com/agenttavern/tournament/TournamentDependencyTest.java`

**Interfaces:**

- Consumes: 现有 Spring Boot 4.1.1 / Spring Modulith 2.1.0 单模块工程。
- Produces: `tournament` 只允许依赖 `game`；`persistence` 只允许依赖 `tournament` 与 `game`；PostgreSQL 集成测试 profile `postgres-it`。

- [ ] **Step 1: 写失败的模块边界测试**

在 `TournamentDependencyTest` 中加入以下核心规则，并在 `ArchitectureTest` 中断言三个 Modulith 模块名称与依赖均可验证：

```java
@Test
void tournamentHasNoInfrastructureDependencies() {
    noClasses().that().resideInAPackage("com.agenttavern.tournament..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.jdbc..", "java.sql..", "javax.sql..",
                    "org.flywaydb..", "tools.jackson..", "com.fasterxml.jackson..",
                    "jakarta.persistence..", "org.springframework.web..")
            .check(new ClassFileImporter().importPackages("com.agenttavern.tournament"));
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `backend\mvnw.cmd -f backend\pom.xml -Dtest=ArchitectureTest,TournamentDependencyTest test`

Expected: FAIL，因为新模块和测试类尚不存在。

- [ ] **Step 3: 声明模块并锁定依赖**

`tournament/package-info.java`：

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "Tournament",
        allowedDependencies = "game")
package com.agenttavern.tournament;
```

`persistence/package-info.java`：

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "Persistence",
        allowedDependencies = {"tournament", "game"})
package com.agenttavern.persistence;
```

向 `pom.xml` 添加 `spring-boot-starter-jdbc`、`flyway-core`、`flyway-database-postgresql`、运行时 `org.postgresql:postgresql`、测试依赖 `spring-boot-testcontainers`、`org.testcontainers:testcontainers-postgresql:2.0.5` 和 `org.testcontainers:testcontainers-junit-jupiter:2.0.5`。添加 Failsafe profile：

```xml
<profile>
  <id>postgres-it</id>
  <build><plugins><plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-failsafe-plugin</artifactId>
    <executions><execution><goals><goal>integration-test</goal><goal>verify</goal></goals></execution></executions>
    <configuration><includes><include>**/*IT.java</include></includes></configuration>
  </plugin></plugins></build>
</profile>
```

- [ ] **Step 4: 运行架构与依赖解析测试**

Run: `backend\mvnw.cmd -f backend\pom.xml -Dtest=ArchitectureTest,TournamentDependencyTest test`

Expected: PASS；Maven 解析 Testcontainers 2.0.5 的新 artifact 名称。

- [ ] **Step 5: 运行全量测试并提交**

Run: `backend\mvnw.cmd -f backend\pom.xml clean verify`

Expected: 现有 209 项测试与新增架构测试全部 PASS。

```bash
git add backend/pom.xml backend/src/main/java/com/agenttavern/tournament/package-info.java backend/src/main/java/com/agenttavern/persistence/package-info.java backend/src/test/java/com/agenttavern
git commit -m "build: add tournament persistence module boundaries"
```

---

### Task 2: 可验证的牌局私有检查点

**Files:**

- Create: `backend/src/main/java/com/agenttavern/game/card/DeckCheckpoint.java`
- Modify: `backend/src/main/java/com/agenttavern/game/card/Deck.java`
- Create: `backend/src/main/java/com/agenttavern/game/betting/BettingRoundCheckpoint.java`
- Modify: `backend/src/main/java/com/agenttavern/game/betting/BettingRound.java`
- Create: `backend/src/main/java/com/agenttavern/game/hand/HandCheckpoint.java`
- Modify: `backend/src/main/java/com/agenttavern/game/hand/Hand.java`
- Create: `backend/src/test/java/com/agenttavern/game/hand/HandCheckpointTest.java`
- Modify: `backend/src/test/java/com/agenttavern/game/GameEngineDependencyTest.java`

**Interfaces:**

- Consumes: `Hand.start(...)`、`Hand.act(...)`、不可变 `Deck` 与 `BettingRound`。
- Produces: `Deck.checkpoint()` / `Deck.restore(DeckCheckpoint)`、`BettingRound.checkpoint()` / `BettingRound.restore(BettingRoundCheckpoint)`、`Hand.checkpoint()` / `Hand.restore(HandCheckpoint)`。

- [ ] **Step 1: 写失败的中途恢复等价测试**

```java
@Test
void restoredHandProducesTheSameNextTransition() {
    Hand original = handAfterPreflopRaiseAndCall();
    Hand restored = Hand.restore(original.checkpoint());

    assertThat(restored.checkpoint()).isEqualTo(original.checkpoint());
    PlayerId actor = original.actor().playerId();
    PlayerAction action = PlayerAction.check();
    assertThat(restored.act(actor, action).events())
            .isEqualTo(original.act(actor, action).events());
    assertThat(restored.act(actor, action).hand().checkpoint())
            .isEqualTo(original.act(actor, action).hand().checkpoint());
}
```

同一测试类添加具名用例：

```java
@Test void checkpointPreservesRemainingDeckOrder() { assertDeckOrderAfterRestore(); }
@Test void publicEventsNeverContainDeckOrBurnedCards() { assertNoSensitiveComponents(events()); }
@Test void completedHandCanBeRestored() { assertCompletedRoundTrip(); }
@Test void checkpointDefensivelyCopiesNestedCollections() { assertDeeplyImmutable(); }
@Test void restoreRejectsDuplicatePhysicalCards() { assertDuplicateCardRejected(); }
@Test void restoreRejectsActorOutsidePendingActiveSeats() { assertInvalidActorRejected(); }
@Test void restoreRejectsChipConservationViolation() { assertChipMismatchRejected(); }
```

这些 helper 在测试类内用真实 `HandCheckpoint` 构造、AssertJ `assertThatThrownBy` 和固定牌组实现；每个 helper 只验证方法名描述的一项约束。

- [ ] **Step 2: 运行测试并确认失败**

Run: `backend\mvnw.cmd -f backend\pom.xml -Dtest=HandCheckpointTest test`

Expected: FAIL，`HandCheckpoint` 和 `checkpoint/restore` 尚不存在。

- [ ] **Step 3: 实现不可变检查点记录**

核心签名：

```java
public record DeckCheckpoint(List<Card> remainingCards) { }

public record BettingRoundCheckpoint(
        List<SeatState> seats,
        Street street,
        long currentBet,
        long lastFullRaiseSize,
        Set<PlayerId> pendingAction,
        Set<PlayerId> raiseRights,
        PlayerId actorId) { }

public record HandCheckpoint(
        HandId id,
        int buttonSeat,
        BlindLevel blinds,
        DeckCheckpoint deck,
        List<SeatState> seats,
        Map<PlayerId, List<Card>> holeCards,
        List<Card> board,
        List<Card> burnedCards,
        Optional<BettingRoundCheckpoint> bettingRound,
        Street street,
        boolean complete,
        long initialTotalChips) { }
```

所有集合在 compact constructor 中深复制；`restore` 复用或新增领域校验，确保卡牌在底牌、公共牌、烧牌与余牌之间物理唯一，actor 属于 pending active seat，完成牌局没有 betting round，且 `stack + handCommitted` 总和等于 `initialTotalChips`。

- [ ] **Step 4: 运行检查点与引擎测试**

Run: `backend\mvnw.cmd -f backend\pom.xml -Dtest=HandCheckpointTest,HandTest,GameEngineDependencyTest test`

Expected: PASS。

- [ ] **Step 5: 运行全量测试并提交**

Run: `backend\mvnw.cmd -f backend\pom.xml clean verify`

```bash
git add backend/src/main/java/com/agenttavern/game backend/src/test/java/com/agenttavern/game
git commit -m "feat: add validated hand checkpoints"
```

---

### Task 3: 标准盲注表与锦标赛聚合

**Files:**

- Create: `backend/src/main/java/com/agenttavern/tournament/TournamentId.java`
- Create: `backend/src/main/java/com/agenttavern/tournament/TournamentMode.java`
- Create: `backend/src/main/java/com/agenttavern/tournament/TournamentStatus.java`
- Create: `backend/src/main/java/com/agenttavern/tournament/TournamentSeatStatus.java`
- Create: `backend/src/main/java/com/agenttavern/tournament/TournamentEntrant.java`
- Create: `backend/src/main/java/com/agenttavern/tournament/TournamentSeat.java`
- Create: `backend/src/main/java/com/agenttavern/tournament/BlindSchedule.java`
- Create: `backend/src/main/java/com/agenttavern/tournament/Tournament.java`
- Create: `backend/src/main/java/com/agenttavern/tournament/TournamentTransition.java`
- Create: `backend/src/main/java/com/agenttavern/tournament/TournamentEvent.java`
- Create: `backend/src/test/java/com/agenttavern/tournament/BlindScheduleTest.java`
- Create: `backend/src/test/java/com/agenttavern/tournament/TournamentTest.java`

**Interfaces:**

- Consumes: `Hand.start`、`Hand.act`、`HandEvent.HandCompleted`、`BlindLevel`、`Deck`。
- Produces: `Tournament.start(...)`、`Tournament.act(...)`、`Tournament.startNextHand(...)` 和不可变 `TournamentTransition`。

- [ ] **Step 1: 写失败的盲注与完整生命周期测试**

```java
@ParameterizedTest
@CsvSource({"0,50,100", "7,50,100", "8,75,150", "112,6000,12000", "999,6000,12000"})
void standardScheduleAdvancesEveryEightCompletedHands(
        int completedHands, long smallBlind, long bigBlind) {
    assertThat(BlindSchedule.standard().forCompletedHands(completedHands))
            .isEqualTo(new BlindLevel(smallBlind, bigBlind));
}

@Test
void completedHandEliminatesZeroStacksMovesButtonAndStartsNextHand() {
    TournamentTransition started = Tournament.start(id, TournamentMode.PLAYER,
            sixEntrants(), 2, firstHandId, stackedDeck());
    TournamentTransition completed = playCurrentHandToCompletion(started.tournament());
    assertThat(completed.tournament().status()).isEqualTo(TournamentStatus.BETWEEN_HANDS);
    assertThat(completed.tournament().completedHands()).isEqualTo(1);
    TournamentTransition next = completed.tournament().startNextHand(nextHandId, nextDeck());
    assertThat(next.tournament().buttonSeat()).isEqualTo(nextFundedSeatClockwiseFrom(2));
}
```

同一测试类添加以下具名用例，每个用例使用固定牌组和 AssertJ 的精确异常/状态断言：

```java
@Test void startRequiresTwoToSixUniqueFundedEntrants() { assertInvalidEntrantsRejected(); }
@Test void everyEntrantStartsWithTenThousandChips() { assertInitialStacks(10_000L); }
@Test void onlyCurrentActorCanAct() { assertOutOfTurnActionRejected(); }
@Test void nextHandCannotStartBeforeCurrentHandCompletes() { assertEarlyNextHandRejected(); }
@Test void lastFundedPlayerBecomesWinner() { assertSingleWinnerAndPositions(); }
@Test void ninthHandUsesSecondBlindLevel() { assertBlindAtHandNumber(9, 75, 150); }
@Test void blindScheduleStaysAtFinalLevel() { assertBlindAtHandNumber(500, 6_000, 12_000); }
@Test void eliminatedSeatIsExcludedFromFollowingHand() { assertEliminatedSeatNotDealt(); }
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `backend\mvnw.cmd -f backend\pom.xml -Dtest=BlindScheduleTest,TournamentTest test`

Expected: FAIL，新类型尚不存在。

- [ ] **Step 3: 实现值对象、事件和聚合**

核心 API：

```java
public static TournamentTransition start(
        TournamentId id, TournamentMode mode, List<TournamentEntrant> entrants,
        int firstButtonSeat, HandId firstHandId, Deck firstDeck);

public TournamentTransition act(PlayerId actorId, PlayerAction action);

public TournamentTransition startNextHand(HandId handId, Deck deck);
```

`TournamentEvent` 为 sealed interface，至少包含：

```java
record TournamentStarted(TournamentId tournamentId, TournamentMode mode,
        List<TournamentSeat> seats, int buttonSeat) implements TournamentEvent {}
record HandEventRecorded(TournamentId tournamentId, HandEvent handEvent)
        implements TournamentEvent {}
record PlayerEliminated(TournamentId tournamentId, PlayerId playerId,
        int finishPosition) implements TournamentEvent {}
record BlindLevelAdvanced(TournamentId tournamentId, int levelIndex,
        BlindLevel blinds) implements TournamentEvent {}
record TournamentCompleted(TournamentId tournamentId, PlayerId winnerId,
        List<TournamentSeat> finalSeats) implements TournamentEvent {}
```

每次引擎 transition 的 `HandEvent` 按原顺序包装；手牌完成后同步 seat stack、按确定性规则赋予淘汰名次，并在仍有至少两名 funded seat 时进入 `BETWEEN_HANDS`。只有 `startNextHand` 接受新 `Deck`，避免随机源进入聚合状态。

- [ ] **Step 4: 运行锦标赛与引擎回归测试**

Run: `backend\mvnw.cmd -f backend\pom.xml -Dtest=BlindScheduleTest,TournamentTest,HandTest test`

Expected: PASS。

- [ ] **Step 5: 运行全量测试并提交**

Run: `backend\mvnw.cmd -f backend\pom.xml clean verify`

```bash
git add backend/src/main/java/com/agenttavern/tournament backend/src/test/java/com/agenttavern/tournament
git commit -m "feat: add single-table tournament lifecycle"
```

---

### Task 4: 锦标赛检查点、恢复与确定性属性

**Files:**

- Create: `backend/src/main/java/com/agenttavern/tournament/TournamentCheckpoint.java`
- Modify: `backend/src/main/java/com/agenttavern/tournament/Tournament.java`
- Create: `backend/src/test/java/com/agenttavern/tournament/TournamentCheckpointTest.java`
- Create: `backend/src/test/java/com/agenttavern/tournament/TournamentInvariantProperties.java`

**Interfaces:**

- Consumes: Task 2 的 `HandCheckpoint` 与 Task 3 的 `Tournament` 状态。
- Produces: `Tournament.checkpoint()` 和 `Tournament.restore(TournamentCheckpoint)`；恢复后下一命令与未重启流程字节级领域等价。

- [ ] **Step 1: 写失败的恢复与属性测试**

```java
@Test
void restoreDuringTurnPreservesNextEventsAndState() {
    Tournament original = tournamentAfterSeveralActions();
    Tournament restored = Tournament.restore(original.checkpoint());
    PlayerAction legal = deterministicLegalAction(original.currentHand());
    PlayerId actor = original.currentHand().actor().playerId();
    assertThat(restored.act(actor, legal)).isEqualTo(original.act(actor, legal));
}
```

`TournamentInvariantProperties` 使用六个固定 seed，每个 seed 执行 500 次合法随机动作/下一手，逐步断言总筹码恒等于 60,000、版本外状态不依赖时钟、按钮只落在 funded seat、事件顺序稳定、恢复后继续执行结果一致。

- [ ] **Step 2: 运行测试并确认失败**

Run: `backend\mvnw.cmd -f backend\pom.xml -Dtest=TournamentCheckpointTest,TournamentInvariantProperties test`

Expected: FAIL，锦标赛检查点尚不存在。

- [ ] **Step 3: 实现检查点与严格恢复校验**

```java
public record TournamentCheckpoint(
        TournamentId id,
        TournamentMode mode,
        TournamentStatus status,
        List<TournamentSeat> seats,
        int buttonSeat,
        int completedHands,
        int blindLevelIndex,
        Optional<HandCheckpoint> currentHand,
        Map<PlayerId, Long> currentHandStartingStacks) { }
```

校验 `completedHands / 8` 与封顶后的 blind index 一致；`IN_HAND` 必须包含 hand 和本手起始筹码，`BETWEEN_HANDS/COMPLETE` 不得包含；锦标赛 seat 与 hand seat 必须一一对应；所有当前 stack 与 hand committed 合计必须等于参赛总筹码；名次唯一且连续；`COMPLETE` 恰有一个 WINNER。

- [ ] **Step 4: 运行恢复、属性和架构测试**

Run: `backend\mvnw.cmd -f backend\pom.xml -Dtest=TournamentCheckpointTest,TournamentInvariantProperties,TournamentDependencyTest test`

Expected: PASS。

- [ ] **Step 5: 运行全量测试并提交**

Run: `backend\mvnw.cmd -f backend\pom.xml clean verify`

```bash
git add backend/src/main/java/com/agenttavern/tournament backend/src/test/java/com/agenttavern/tournament
git commit -m "feat: add deterministic tournament recovery"
```

---

### Task 5: 事件信封、幂等命令服务与内存事务端口

**Files:**

- Create: `backend/src/main/java/com/agenttavern/tournament/port/StoredTournament.java`
- Create: `backend/src/main/java/com/agenttavern/tournament/port/TournamentCommit.java`
- Create: `backend/src/main/java/com/agenttavern/tournament/port/TournamentStore.java`
- Create: `backend/src/main/java/com/agenttavern/tournament/port/TournamentWriteResult.java`
- Create: `backend/src/main/java/com/agenttavern/tournament/application/CreateTournamentCommand.java`
- Create: `backend/src/main/java/com/agenttavern/tournament/application/ActInTournamentCommand.java`
- Create: `backend/src/main/java/com/agenttavern/tournament/application/StartNextHandCommand.java`
- Create: `backend/src/main/java/com/agenttavern/tournament/application/TournamentCommandService.java`
- Create: `backend/src/main/java/com/agenttavern/tournament/application/TournamentExecution.java`
- Create: `backend/src/main/java/com/agenttavern/tournament/application/ConcurrentTournamentUpdateException.java`
- Create: `backend/src/main/java/com/agenttavern/tournament/application/TournamentNotFoundException.java`
- Create: `backend/src/main/java/com/agenttavern/tournament/TournamentEventEnvelope.java`
- Create: `backend/src/test/java/com/agenttavern/tournament/application/InMemoryTournamentStore.java`
- Create: `backend/src/test/java/com/agenttavern/tournament/application/TournamentCommandServiceTest.java`

**Interfaces:**

- Consumes: `TournamentTransition` 与 `TournamentCheckpoint`。
- Produces: 带版本、序号和时间戳的 `TournamentEventEnvelope`，以及数据库实现必须满足的原子 `TournamentStore.commit` 契约。

- [ ] **Step 1: 写失败的命令、序号、冲突与幂等测试**

```java
@Test
void acceptedCommandAssignsContinuousSequenceAndOneVersion() {
    TournamentExecution result = service.act(new ActInTournamentCommand(
            commandId, tournamentId, 1L, actorId, PlayerAction.call()));
    assertThat(result.version()).isEqualTo(2L);
    assertThat(result.events()).extracting(TournamentEventEnvelope::sequence)
            .containsExactly(5L);
    assertThat(result.events()).allSatisfy(e -> assertThat(e.aggregateVersion()).isEqualTo(2L));
}

@Test
void duplicateCommandReturnsOriginalReceiptWithoutNewEvents() {
    TournamentExecution first = service.act(command);
    TournamentExecution duplicate = service.act(command);
    assertThat(duplicate).isEqualTo(first);
    assertThat(store.eventCount(tournamentId)).isEqualTo(first.events().size());
}
```

添加四个独立用例：

```java
@Test void staleExpectedVersionThrowsConcurrentUpdate() { assertStaleVersionRejected(); }
@Test void failedCommitChangesNeitherSnapshotEventsNorReceipt() { assertAtomicFailure(); }
@Test void envelopeUsesInjectedClock() { assertOccurredAtEquals(FIXED_INSTANT); }
@Test void unknownTournamentThrowsStableNotFoundError() { assertUnknownTournamentRejected(); }
```

`assertAtomicFailure` 在调用前后分别捕获 `StoredTournament`、事件列表和 receipt 数量并逐一比较相等；异常断言包含 `tournamentId` 和 expected version。

- [ ] **Step 2: 运行测试并确认失败**

Run: `backend\mvnw.cmd -f backend\pom.xml -Dtest=TournamentCommandServiceTest test`

Expected: FAIL，应用服务与存储端口尚不存在。

- [ ] **Step 3: 实现事件信封和端口**

```java
public record TournamentEventEnvelope(
        TournamentId tournamentId,
        Optional<HandId> handId,
        long sequence,
        long aggregateVersion,
        Instant occurredAt,
        TournamentEvent payload) { }

public interface TournamentStore {
    Optional<StoredTournament> load(TournamentId tournamentId);
    TournamentWriteResult commit(TournamentCommit commit);
    List<TournamentEventEnvelope> eventsAfter(TournamentId tournamentId, long sequenceExclusive);
}

public record TournamentCommit(
        UUID commandId,
        long expectedVersion,
        TournamentCheckpoint checkpoint,
        List<TournamentEventEnvelope> events) { }
```

`StoredTournament` 包含 checkpoint、version、lastSequence；`TournamentWriteResult` 包含 `WriteStatus`（`APPLIED` 或 `ALREADY_APPLIED`）、写入后的 `StoredTournament` 与该命令原始事件列表。应用服务据此构建 `TournamentExecution`，避免 port 包反向依赖 application 包。端口禁止引用 JDBC/JSON/Spring 类型。

- [ ] **Step 4: 实现应用服务与内存原子存储**

`TournamentCommandService` 接受 `TournamentStore`、`Clock` 和生产牌组工厂接口 `Supplier<Deck>`；create/act/start-next-hand 都先加载期望版本，再调用聚合，再一次性编号并 commit。内存测试实现先复制全部 map/list，在校验和故障注入成功后才替换引用，以模拟事务原子性。

- [ ] **Step 5: 运行应用、架构与全量测试后提交**

Run: `backend\mvnw.cmd -f backend\pom.xml -Dtest=TournamentCommandServiceTest,TournamentDependencyTest,ArchitectureTest test`

Run: `backend\mvnw.cmd -f backend\pom.xml clean verify`

```bash
git add backend/src/main/java/com/agenttavern/tournament backend/src/test/java/com/agenttavern/tournament/application
git commit -m "feat: add idempotent tournament command service"
```

---

### Task 6: Flyway PostgreSQL 结构与 JDBC 映射

**Files:**

- Create: `backend/src/main/resources/db/migration/V1__create_tournament_store.sql`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/java/com/agenttavern/persistence/PersistenceConfiguration.java`
- Create: `backend/src/main/java/com/agenttavern/persistence/TournamentCheckpointJdbcMapper.java`
- Create: `backend/src/main/java/com/agenttavern/persistence/TournamentEventJsonCodec.java`
- Create: `backend/src/main/java/com/agenttavern/persistence/JdbcTournamentStore.java`
- Create: `backend/src/test/java/com/agenttavern/persistence/TournamentEventJsonCodecTest.java`
- Create: `backend/src/test/java/com/agenttavern/persistence/MigrationContractTest.java`

**Interfaces:**

- Consumes: Task 5 的 `TournamentStore`、`TournamentCommit` 和事件信封。
- Produces: PostgreSQL schema 与 `JdbcTournamentStore`；JSON 仅存在于 persistence 模块。

- [ ] **Step 1: 写失败的 JSON 往返与迁移契约测试**

```java
@ParameterizedTest
@MethodSource("allEventVariants")
void everyEventVariantRoundTrips(TournamentEvent event) {
    EncodedEvent encoded = codec.encode(event);
    assertThat(codec.decode(encoded.eventType(), encoded.payloadJson())).isEqualTo(event);
}
```

`MigrationContractTest` 读取 SQL 文本并断言五张表、主键/唯一键、外键级联、`jsonb`、非负 check、事件 `(tournament_id, sequence)` 唯一约束和 command receipt 唯一约束全部存在。它不是 PostgreSQL 行为测试，而是无 Docker 环境的快速防退化门。

- [ ] **Step 2: 运行测试并确认失败**

Run: `backend\mvnw.cmd -f backend\pom.xml -Dtest=TournamentEventJsonCodecTest,MigrationContractTest test`

Expected: FAIL，codec 与迁移尚不存在。

- [ ] **Step 3: 创建 PostgreSQL 迁移**

迁移必须创建：

```sql
create table tournaments (
  tournament_id uuid primary key,
  mode varchar(24) not null,
  status varchar(24) not null,
  version bigint not null check (version >= 1),
  last_sequence bigint not null check (last_sequence >= 0),
  button_seat smallint not null check (button_seat between 0 and 5),
  completed_hands integer not null check (completed_hands >= 0),
  blind_level_index smallint not null check (blind_level_index between 0 and 14),
  updated_at timestamptz not null
);
```

以及 `tournament_seats`（玩家、座位、stack、状态、finish_position、本手起始筹码）、`hand_snapshots`（hand_id + 私有 `checkpoint_json jsonb`）、`tournament_events`（序号/版本/时间/类型/私有 payload jsonb）、`tournament_command_receipts`（command_id + 返回版本与序号区间）。所有子表以 `tournament_id` 外键 `on delete cascade`。

- [ ] **Step 4: 实现 JSON codec、行映射与事务写入**

`TournamentEventJsonCodec` 使用 Spring Boot 管理的 Jackson 3 `JsonMapper`，显式 `switch(eventType)` 解码每一种 sealed event，禁止启用全局 default typing。`JdbcTournamentStore.commit` 用 `@Transactional`：先查询 command receipt；重复则读取原始结果；新命令通过 `update tournaments ... where version = ?` 实施乐观锁（create 用 insert）；随后 upsert seats、替换/删除 hand snapshot、批量 insert events、insert receipt。任何更新数不符都抛冲突异常并回滚。

- [ ] **Step 5: 运行 codec、迁移、架构和全量测试后提交**

Run: `backend\mvnw.cmd -f backend\pom.xml -Dtest=TournamentEventJsonCodecTest,MigrationContractTest,TournamentDependencyTest,ArchitectureTest test`

Run: `backend\mvnw.cmd -f backend\pom.xml clean verify`

```bash
git add backend/src/main/java/com/agenttavern/persistence backend/src/main/resources backend/src/test/java/com/agenttavern/persistence
git commit -m "feat: add PostgreSQL tournament store"
```

---

### Task 7: PostgreSQL 原子性、迁移与宕机恢复集成测试

**Files:**

- Create: `backend/src/test/java/com/agenttavern/persistence/PostgresTournamentStoreIT.java`
- Create: `backend/src/test/resources/application-postgres-it.yml`
- Modify: `backend/pom.xml`

**Interfaces:**

- Consumes: `JdbcTournamentStore`、Flyway V1、Spring Boot Testcontainers service connection。
- Produces: 对真实 PostgreSQL 的 Docker 可选验收门。

- [ ] **Step 1: 写 Testcontainers 集成测试**

```java
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("postgres-it")
class PostgresTournamentStoreIT {
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void flywayMigratesAndRestartRecoversExactNextTransition() {
        TournamentExecution beforeRestart = executeFirstAction(commandService);
        TournamentCommandService restarted = newServiceUsingSameDataSource();
        assertThat(executeNextAction(restarted, beforeRestart)).isEqualTo(expectedWithoutRestart());
    }

    @Test
    void snapshotEventsSeatsAndReceiptCommitAtomically() {
        DatabaseCounts before = counts();
        assertThatThrownBy(() -> store.commit(commitWithDuplicateSequence()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(counts()).isEqualTo(before);
    }

    @Test
    void optimisticConflictRollsBackEveryTable() {
        TournamentCommit stale = commandBasedOnVersion(1L);
        applyCompetingVersionTwoCommit();
        assertThatThrownBy(() -> store.commit(stale))
                .isInstanceOf(ConcurrentTournamentUpdateException.class);
        assertThat(loadVersion()).isEqualTo(2L);
    }

    @Test
    void duplicateCommandDoesNotAppendOrMoveChipsTwice() {
        TournamentWriteResult first = store.commit(validCommit());
        TournamentWriteResult second = store.commit(validCommit());
        assertThat(second.status()).isEqualTo(WriteStatus.ALREADY_APPLIED);
        assertThat(second.events()).isEqualTo(first.events());
    }

    @Test
    void eventsAfterReturnsStrictGapFreeOrder() {
        appendThreeCommands();
        assertThat(store.eventsAfter(tournamentId, 2L))
                .extracting(TournamentEventEnvelope::sequence)
                .containsExactly(3L, 4L, 5L);
    }
}
```

测试不得使用 H2，因为 JSONB、约束和 PostgreSQL 事务语义属于验收范围。

- [ ] **Step 2: 运行集成 profile 并记录当前环境行为**

Run: `backend\mvnw.cmd -f backend\pom.xml -Ppostgres-it verify`

Expected on this workstation: unit tests PASS，`PostgresTournamentStoreIT` 明确 SKIPPED（Docker unavailable），构建不能伪装成已连接 PostgreSQL。Expected with Docker: all five integration cases PASS。

- [ ] **Step 3: 修复直到 Docker 环境通过**

若可用 Docker，逐项确认 Flyway schema history 为 success、失败命令前后五张业务表行数不变、恢复后 next events/checkpoint 相等、序号无重复无缺口。若当前仍无 Docker，不以 H2 替代；保留测试与 profile，并在 README 标注尚需 Docker 验收。

- [ ] **Step 4: 运行默认全量测试并提交**

Run: `backend\mvnw.cmd -f backend\pom.xml clean verify`

Expected: 所有非 Docker 测试 PASS，默认 Surefire 不误执行 `*IT`。

```bash
git add backend/pom.xml backend/src/test/java/com/agenttavern/persistence/PostgresTournamentStoreIT.java backend/src/test/resources/application-postgres-it.yml
git commit -m "test: verify PostgreSQL tournament recovery"
```

---

### Task 8: 六人锦标赛端到端恢复与中文运行文档

**Files:**

- Create: `backend/src/test/java/com/agenttavern/tournament/TournamentRecoveryEndToEndTest.java`
- Modify: `README.md`
- Modify: `backend/README.md`

**Interfaces:**

- Consumes: 领域锦标赛、命令服务、内存 store、JDBC store 的共同契约。
- Produces: 从创建到冠军的确定性验收场景、Phase 2 中文运行与验收说明。

- [ ] **Step 1: 写失败的六人完整锦标赛恢复测试**

```java
@Test
void sixPlayersCanFinishTournamentAcrossRepeatedServiceRestarts() {
    TournamentExecution state = createSixPlayerTournament();
    while (state.checkpoint().status() != TournamentStatus.COMPLETE) {
        TournamentCommandService restarted = new TournamentCommandService(store, fixedClock, decks);
        state = executeOneDeterministicLegalCommand(restarted, state);
        assertThat(sumAllSeatStacks(state.checkpoint())).isEqualTo(60_000L);
        assertThat(store.eventsAfter(id, 0).stream().map(TournamentEventEnvelope::sequence))
                .containsExactly(LongStream.rangeClosed(1, state.lastSequence()).boxed().toArray(Long[]::new));
    }
    assertThat(state.checkpoint().seats()).filteredOn(s -> s.status() == WINNER).hasSize(1);
    assertThat(finalPositions(state.checkpoint())).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6);
}
```

每个命令后重新构造 service，模拟进程重启；固定牌组和“优先 CHECK/CALL，否则 ALL_IN”的动作策略保证测试有限终止且可复现。

- [ ] **Step 2: 运行端到端测试并确认失败或发现差异**

Run: `backend\mvnw.cmd -f backend\pom.xml -Dtest=TournamentRecoveryEndToEndTest test`

Expected: 若实现尚有恢复、排名或序号差异则 FAIL；修正根因而非放宽断言。

- [ ] **Step 3: 补齐中文 README**

根 README 增加 Phase 2 能力清单与架构说明；后端 README 增加：

```text
默认验证：mvnw.cmd clean verify
PostgreSQL 验收：mvnw.cmd -Ppostgres-it verify
要求：Java 21、Maven Wrapper；PostgreSQL 集成测试额外要求 Docker。
无 Docker 时：领域、恢复、序列化和迁移契约测试照常运行，PostgresTournamentStoreIT 显示 SKIPPED。
```

明确说明私有检查点含底牌/余牌，只能留在服务端；事件提交成功后才允许后续 WebSocket 广播。

- [ ] **Step 4: 最终验证**

Run: `backend\mvnw.cmd -f backend\pom.xml clean verify`

Run: `backend\mvnw.cmd -f backend\pom.xml -Ppostgres-it verify`

Run: `git status --short`

Expected: 默认验证全绿；无 Docker 时仅 PostgreSQL IT 明确跳过；工作树仅包含本任务预期文档/代码变更。

- [ ] **Step 5: 提交**

```bash
git add README.md backend/README.md backend/src/test/java/com/agenttavern/tournament/TournamentRecoveryEndToEndTest.java
git commit -m "docs: document tournament persistence and recovery"
```

---

## 完成定义

- 六人锦标赛可从 10,000 初始筹码运行至唯一冠军，盲注、按钮、淘汰与最终名次符合本计划。
- 任意合法动作后保存并恢复，下一动作产生与未重启流程完全相同的领域状态和事件。
- 事件序号严格连续，命令幂等，旧版本冲突不会产生部分写入。
- 默认 Java 21 测试全部通过；Docker 环境的 PostgreSQL Testcontainers 验收全部通过，或在当前无 Docker 环境中明确记录为尚未执行而不是声称通过。
- Spring Modulith/ArchUnit 证明领域模块不依赖持久化基础设施。
- README 使用中文说明架构、运行方式、隐私边界和 Docker 验收门。
