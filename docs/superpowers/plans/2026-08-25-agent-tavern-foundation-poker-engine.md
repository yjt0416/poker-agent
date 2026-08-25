# Agent Tavern Foundation and Poker Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a framework-independent, fully tested Java engine that can execute one complete two-to-six-player No-Limit Texas Hold'em hand, including legal actions, short all-ins, side pots, showdown, and replayable domain events.

**Architecture:** A Spring Boot modular-monolith shell hosts a `game` application module, but every poker rule remains in immutable or carefully encapsulated plain Java domain types. `Hand` is the aggregate root; it accepts validated player actions and returns a new state plus domain events. External concerns such as PostgreSQL, WebSocket, LLMs, sessions, and UI are deliberately excluded from this phase.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring Modulith 2.1.0, Maven Wrapper 3.3.4/Maven 3.9.16, JUnit 5, AssertJ, jqwik 1.10.1.

**Spec:** `docs/superpowers/specs/2026-08-25-multi-agent-texas-holdem-design.md`

## Global Constraints

- Use Java 21 language features; do not enable preview features.
- All monetary/chip values use `long`; negative stacks, bets, pots, and payouts are rejected.
- `PlayerAction.amount` means the player's target total contribution for the current street when the action is `RAISE` or `ALL_IN`; it is zero for `FOLD`, `CHECK`, and `CALL`.
- The engine is server-authoritative and accepts only actions present in its own `LegalActions` result.
- Production shuffling accepts a caller-provided `RandomGenerator`; tests use deterministic or stacked decks.
- No Spring Web, Spring Data, JPA, JSON, database, LLM, or frontend type may appear under `com.agenttavern.game..`.
- The engine supports two to six funded seats and both heads-up and multi-way blind/button rules.
- A short all-in may increase the current bet without reopening raise rights for players who have already acted.
- Odd chips go to tied winners in clockwise seat order starting immediately left of the button.
- Every task follows red-green-refactor, runs its focused tests, runs all engine tests, and ends with a commit.
- Do not add real API keys, `.env` files, generated build output, or visual brainstorming files to Git.

## File Map

```text
backend/
  pom.xml                                      Maven dependency and plugin lock
  mvnw, mvnw.cmd, .mvn/wrapper/*               Reproducible Maven 3.9.16 build
  src/main/java/com/agenttavern/
    AgentTavernApplication.java                Spring Boot entry point
    game/package-info.java                     Spring Modulith module declaration
    game/card/
      Suit.java, Rank.java, Card.java           Card value objects
      Deck.java                                Immutable ordered/shuffled deck
    game/handvalue/
      HandCategory.java                        Poker category ordering
      HandValue.java                           Comparable category/tiebreak value
      HandEvaluator.java                       Best-five evaluation from 5–7 cards
    game/betting/
      PlayerId.java, PlayerStatus.java          Player identity and lifecycle
      SeatState.java                           Stack and street/hand contributions
      Street.java, ActionType.java              Betting vocabulary
      PlayerAction.java                        Validated action request
      LegalActions.java                        Exact options exposed to a caller
      IllegalActionException.java              Stable domain error
      BettingRound.java                        Turn order, raise rights, completion
    game/showdown/
      Pot.java, SidePotCalculator.java          Main/side-pot construction
      ShowdownResolver.java                    Hand comparison and odd-chip payout
    game/hand/
      HandId.java, BlindLevel.java              Hand identity and blinds
      PlayerStack.java                         Funded seat input
      HandEvent.java                           Sealed replayable event contract
      Hand.java                                Aggregate state and transitions
      HandTransition.java                      New state plus emitted events
  src/test/java/com/agenttavern/
    AgentTavernApplicationTest.java             Boot smoke test
    ArchitectureTest.java                      Modulith boundary verification
    game/...                                   Focused unit and property tests
```

---

### Task 1: Reproducible Backend Skeleton and Module Boundary

**Files:**

- Create: `backend/pom.xml`
- Create: `backend/.mvn/wrapper/maven-wrapper.properties`
- Create: `backend/mvnw`
- Create: `backend/mvnw.cmd`
- Create: `backend/src/main/java/com/agenttavern/AgentTavernApplication.java`
- Create: `backend/src/main/java/com/agenttavern/game/package-info.java`
- Create: `backend/src/test/java/com/agenttavern/AgentTavernApplicationTest.java`
- Create: `backend/src/test/java/com/agenttavern/ArchitectureTest.java`
- Modify: `.gitignore`
- Modify: `README.md`

**Interfaces:**

- Produces: base package `com.agenttavern`, Spring Boot entry point `AgentTavernApplication`, and Modulith module `com.agenttavern.game`.
- Produces: build command `backend\mvnw.cmd verify` on Windows and `./backend/mvnw verify` on Unix/CI.

- [ ] **Step 1: Create the Maven build descriptor**

Create `backend/pom.xml` with the exact dependency baseline below. Configure Maven Enforcer to require Java 21 and Maven 3.9.0+, and configure Surefire to run both JUnit and jqwik on the JUnit Platform.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version>
    <relativePath/>
  </parent>
  <groupId>com.agenttavern</groupId>
  <artifactId>agent-tavern-backend</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <name>Agent Tavern Backend</name>
  <properties>
    <java.version>21</java.version>
    <spring-modulith.version>2.1.0</spring-modulith.version>
    <jqwik.version>1.10.1</jqwik.version>
  </properties>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.modulith</groupId>
        <artifactId>spring-modulith-bom</artifactId>
        <version>${spring-modulith.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.modulith</groupId>
      <artifactId>spring-modulith-starter-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.modulith</groupId>
      <artifactId>spring-modulith-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>net.jqwik</groupId>
      <artifactId>jqwik</artifactId>
      <version>${jqwik.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-enforcer-plugin</artifactId>
        <version>3.6.3</version>
        <executions>
          <execution>
            <id>enforce-toolchain</id>
            <goals><goal>enforce</goal></goals>
            <configuration>
              <rules>
                <requireJavaVersion><version>[21,22)</version></requireJavaVersion>
                <requireMavenVersion><version>[3.9.0,4.0.0)</version></requireMavenVersion>
              </rules>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Generate and pin Maven Wrapper files**

Run from `backend/` using an installed Maven once:

```powershell
mvn org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper -Dmaven=3.9.16 -Dtype=only-script
```

Verify `.mvn/wrapper/maven-wrapper.properties` contains the Maven 3.9.16 distribution URL, then run:

```powershell
.\mvnw.cmd --version
```

Expected: Maven `3.9.16` and Java `21.x`.

- [ ] **Step 3: Write the failing boot and architecture tests**

```java
// backend/src/test/java/com/agenttavern/AgentTavernApplicationTest.java
package com.agenttavern;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AgentTavernApplicationTest {
    @Test
    void contextLoads() {}
}
```

```java
// backend/src/test/java/com/agenttavern/ArchitectureTest.java
package com.agenttavern;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ArchitectureTest {
    @Test
    void applicationModulesAreValid() {
        ApplicationModules.of(AgentTavernApplication.class).verify();
    }
}
```

- [ ] **Step 4: Run the tests and confirm the red state**

Run:

```powershell
backend\mvnw.cmd -f backend\pom.xml test
```

Expected: compilation fails because `AgentTavernApplication` does not exist.

- [ ] **Step 5: Add the minimal application and module declaration**

```java
// backend/src/main/java/com/agenttavern/AgentTavernApplication.java
package com.agenttavern;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AgentTavernApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentTavernApplication.class, args);
    }
}
```

```java
// backend/src/main/java/com/agenttavern/game/package-info.java
@org.springframework.modulith.ApplicationModule(displayName = "Game Engine")
package com.agenttavern.game;
```

- [ ] **Step 6: Add repository hygiene and startup documentation**

Append `backend/.mvn/wrapper/maven-wrapper.jar` only if the wrapper type generates it; keep `backend/target/` ignored. Create `README.md` with project purpose, Java 21 prerequisite, Windows/Unix verify commands, and a prominent statement that no real DeepSeek key belongs in Git.

- [ ] **Step 7: Run the focused verification**

Run:

```powershell
backend\mvnw.cmd -f backend\pom.xml verify
```

Expected: `BUILD SUCCESS`, two tests pass, zero failures.

- [ ] **Step 8: Commit the backend skeleton**

```powershell
git add .gitignore README.md backend
git commit -m "build: scaffold modular Spring backend"
```

---

### Task 2: Card Values and Immutable Deck

**Files:**

- Create: `backend/src/main/java/com/agenttavern/game/card/Suit.java`
- Create: `backend/src/main/java/com/agenttavern/game/card/Rank.java`
- Create: `backend/src/main/java/com/agenttavern/game/card/Card.java`
- Create: `backend/src/main/java/com/agenttavern/game/card/Deck.java`
- Test: `backend/src/test/java/com/agenttavern/game/card/CardTest.java`
- Test: `backend/src/test/java/com/agenttavern/game/card/DeckTest.java`

**Interfaces:**

- Produces: `Card(Suit suit, Rank rank)` with non-null fields.
- Produces: `Rank.strength(): int`, from `TWO=2` through `ACE=14`.
- Produces: `Deck.standard()`, `Deck.ordered(List<Card>)`, `Deck.shuffled(RandomGenerator)`, `Deck.draw()`, and `Deck.remaining()`.
- Produces: `Deck.Draw(Card card, Deck deck)`; drawing never mutates the original deck.

- [ ] **Step 1: Write failing card and deck tests**

```java
@Test
void standardDeckContainsExactlyFiftyTwoUniqueCards() {
    Deck deck = Deck.standard();
    Set<Card> drawn = new HashSet<>();
    while (deck.remaining() > 0) {
        Deck.Draw draw = deck.draw();
        assertThat(drawn.add(draw.card())).isTrue();
        deck = draw.deck();
    }
    assertThat(drawn).hasSize(52);
}

@Test
void stackedDeckDrawsInDeclaredOrderWithoutMutatingOriginal() {
    Card ace = new Card(Suit.SPADES, Rank.ACE);
    Card king = new Card(Suit.HEARTS, Rank.KING);
    Deck deck = Deck.ordered(List.of(ace, king));
    Deck.Draw draw = deck.draw();
    assertThat(draw.card()).isEqualTo(ace);
    assertThat(draw.deck().draw().card()).isEqualTo(king);
    assertThat(deck.remaining()).isEqualTo(2);
}

@Test
void orderedDeckRejectsDuplicateCards() {
    Card ace = new Card(Suit.SPADES, Rank.ACE);
    assertThatThrownBy(() -> Deck.ordered(List.of(ace, ace)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate");
}
```

- [ ] **Step 2: Run tests and confirm the red state**

Run:

```powershell
backend\mvnw.cmd -f backend\pom.xml -Dtest="CardTest,DeckTest" test
```

Expected: test compilation fails because the card types do not exist.

- [ ] **Step 3: Implement card value objects**

Use enums with the exact constants below and a defensive record constructor:

```java
public enum Suit { CLUBS, DIAMONDS, HEARTS, SPADES }

public enum Rank {
    TWO(2), THREE(3), FOUR(4), FIVE(5), SIX(6), SEVEN(7), EIGHT(8),
    NINE(9), TEN(10), JACK(11), QUEEN(12), KING(13), ACE(14);
    private final int strength;
    Rank(int strength) { this.strength = strength; }
    public int strength() { return strength; }
}

public record Card(Suit suit, Rank rank) {
    public Card {
        Objects.requireNonNull(suit, "suit");
        Objects.requireNonNull(rank, "rank");
    }
}
```

- [ ] **Step 4: Implement an immutable deck**

`Deck` stores an unmodifiable copy of the remaining cards. `standard()` creates every suit/rank Cartesian pair in enum order. `ordered()` rejects null, null cards, and duplicates. `shuffled(RandomGenerator)` copies and shuffles with an explicit Fisher–Yates loop driven only by the supplied generator. `draw()` rejects an empty deck and returns the first card plus a deck containing the tail.

```java
public final class Deck {
    private final List<Card> cards;

    private Deck(List<Card> cards) {
        this.cards = List.copyOf(cards);
    }

    public static Deck ordered(List<Card> cards) { /* validate then copy */ }
    public static Deck standard() { /* 4 x 13 Cartesian product */ }
    public Deck shuffled(RandomGenerator random) { /* Fisher–Yates */ }
    public Draw draw() { /* first card plus immutable tail */ }
    public int remaining() { return cards.size(); }

    public record Draw(Card card, Deck deck) {}
}
```

- [ ] **Step 5: Add deterministic shuffle coverage**

Add a test that shuffling two standard decks with `new Random(8128)` produces identical draw sequences and that the sequence differs from `Deck.standard()`.

- [ ] **Step 6: Run tests and commit**

```powershell
backend\mvnw.cmd -f backend\pom.xml -Dtest="CardTest,DeckTest" test
backend\mvnw.cmd -f backend\pom.xml test
git add backend/src/main/java/com/agenttavern/game/card backend/src/test/java/com/agenttavern/game/card
git commit -m "feat: add immutable cards and deck"
```

Expected: all commands succeed and deck tests report zero failures.

---

### Task 3: Five-to-Seven-Card Hand Evaluation

**Files:**

- Create: `backend/src/main/java/com/agenttavern/game/handvalue/HandCategory.java`
- Create: `backend/src/main/java/com/agenttavern/game/handvalue/HandValue.java`
- Create: `backend/src/main/java/com/agenttavern/game/handvalue/HandEvaluator.java`
- Test: `backend/src/test/java/com/agenttavern/game/handvalue/HandEvaluatorTest.java`

**Interfaces:**

- Produces: `HandCategory` in ascending enum order: `HIGH_CARD`, `ONE_PAIR`, `TWO_PAIR`, `THREE_OF_A_KIND`, `STRAIGHT`, `FLUSH`, `FULL_HOUSE`, `FOUR_OF_A_KIND`, `STRAIGHT_FLUSH`.
- Produces: `HandValue(HandCategory category, List<Integer> tieBreakers)` implementing lexicographic `Comparable<HandValue>`.
- Produces: `HandEvaluator.evaluate(List<Card> cards)` accepting 5–7 unique cards and returning the strongest five-card value.

- [ ] **Step 1: Write a parameterized failing category test**

Create a local test helper `cards(String notation)` that parses test-only notation such as `"As Ks Qs Js Ts"`. Do not add notation parsing to production code.

```java
@ParameterizedTest
@MethodSource("categoryExamples")
void classifiesEveryCategory(String notation, HandCategory expected) {
    assertThat(HandEvaluator.evaluate(cards(notation)).category()).isEqualTo(expected);
}

static Stream<Arguments> categoryExamples() {
    return Stream.of(
        arguments("As Kd 9c 6h 3s", HIGH_CARD),
        arguments("As Ad 9c 6h 3s", ONE_PAIR),
        arguments("As Ad 9c 9h 3s", TWO_PAIR),
        arguments("As Ad Ac 6h 3s", THREE_OF_A_KIND),
        arguments("9s 8d 7c 6h 5s", STRAIGHT),
        arguments("As Js 9s 6s 3s", FLUSH),
        arguments("As Ad Ac 9h 9s", FULL_HOUSE),
        arguments("As Ad Ac Ah 3s", FOUR_OF_A_KIND),
        arguments("9s 8s 7s 6s 5s", STRAIGHT_FLUSH)
    );
}
```

- [ ] **Step 2: Run the focused test and confirm failure**

```powershell
backend\mvnw.cmd -f backend\pom.xml -Dtest=HandEvaluatorTest test
```

Expected: compilation fails because evaluator types do not exist.

- [ ] **Step 3: Implement comparable hand values**

`HandValue` rejects null category, null/empty tie breakers, and values outside 2–14. `compareTo` compares category ordinal first, then corresponding tie-breaker integers; both values for the same category must have equal tie-breaker list lengths.

Tie-breaker formats are exact:

- High card/flush: five ranks descending.
- Pair: pair rank, then three kickers descending.
- Two pair: higher pair, lower pair, kicker.
- Trips: trip rank, then two kickers descending.
- Straight/straight flush: high-card rank only; wheel is `5`.
- Full house: trip rank, pair rank.
- Quads: quad rank, kicker.

- [ ] **Step 4: Implement five-card classification and 5–7-card selection**

`evaluate` validates 5–7 unique cards. For every 5-card combination, calculate rank frequencies, flush, and straight high. Evaluate categories from strongest to weakest, construct the exact tie-break list, then return `max(HandValue::compareTo)`.

Wheel detection is explicit:

```java
private static OptionalInt straightHigh(Set<Integer> ranks) {
    List<Integer> descending = ranks.stream().sorted(Comparator.reverseOrder()).toList();
    for (int high : descending) {
        if (IntStream.rangeClosed(high - 4, high).allMatch(ranks::contains)) {
            return OptionalInt.of(high);
        }
    }
    return ranks.containsAll(Set.of(14, 2, 3, 4, 5))
        ? OptionalInt.of(5)
        : OptionalInt.empty();
}
```

- [ ] **Step 5: Add tie-break, wheel, and seven-card tests**

Add assertions that:

- `As Ad Kc 7h 3s` beats `Ah Ac Qc 7d 3c` by kicker.
- `As 2d 3c 4h 5s` is a five-high straight and loses to six-high.
- From `As Ks Qs Js Ts 2d 2c`, the selected result is ace-high straight flush.
- Duplicate input cards and lists of size 4 or 8 throw `IllegalArgumentException`.

- [ ] **Step 6: Run tests and commit**

```powershell
backend\mvnw.cmd -f backend\pom.xml -Dtest=HandEvaluatorTest test
backend\mvnw.cmd -f backend\pom.xml test
git add backend/src/main/java/com/agenttavern/game/handvalue backend/src/test/java/com/agenttavern/game/handvalue
git commit -m "feat: evaluate Texas Holdem hands"
```

Expected: all evaluator and existing tests pass.

---

### Task 4: Betting Vocabulary and Legal Action Calculation

**Files:**

- Create: `backend/src/main/java/com/agenttavern/game/betting/PlayerId.java`
- Create: `backend/src/main/java/com/agenttavern/game/betting/PlayerStatus.java`
- Create: `backend/src/main/java/com/agenttavern/game/betting/SeatState.java`
- Create: `backend/src/main/java/com/agenttavern/game/betting/Street.java`
- Create: `backend/src/main/java/com/agenttavern/game/betting/ActionType.java`
- Create: `backend/src/main/java/com/agenttavern/game/betting/PlayerAction.java`
- Create: `backend/src/main/java/com/agenttavern/game/betting/LegalActions.java`
- Create: `backend/src/main/java/com/agenttavern/game/betting/IllegalActionException.java`
- Test: `backend/src/test/java/com/agenttavern/game/betting/LegalActionsTest.java`

**Interfaces:**

- Produces: `PlayerId(UUID value)` and `PlayerId.random()`.
- Produces: immutable `SeatState(PlayerId playerId, int seatIndex, long stack, long streetCommitted, long handCommitted, PlayerStatus status)`.
- Produces: `PlayerAction.fold()`, `check()`, `call()`, `raiseTo(long)`, and `allIn()`.
- Produces: `LegalActions(Set<ActionType> types, long callAmount, OptionalLong minRaiseTo, long maxRaiseTo)`.
- Consumes in later tasks: `LegalActions.calculate(SeatState actor, long currentBet, long lastFullRaiseSize, boolean raiseRightsOpen)`.

- [ ] **Step 1: Write failing legal-action tests**

```java
@Test
void facingNoBetAllowsCheckAndARaiseAtLeastOneBigBlind() {
    SeatState actor = seat(0, 1_000, 0, ACTIVE);
    LegalActions legal = LegalActions.calculate(actor, 0, 100, true);
    assertThat(legal.types()).containsExactlyInAnyOrder(CHECK, RAISE, ALL_IN);
    assertThat(legal.callAmount()).isZero();
    assertThat(legal.minRaiseTo()).hasValue(100);
    assertThat(legal.maxRaiseTo()).isEqualTo(1_000);
}

@Test
void facingBetCapsCallAtRemainingStackAndOffersShortAllIn() {
    SeatState actor = seat(0, 75, 25, ACTIVE);
    LegalActions legal = LegalActions.calculate(actor, 200, 100, true);
    assertThat(legal.types()).containsExactlyInAnyOrder(FOLD, CALL, ALL_IN);
    assertThat(legal.callAmount()).isEqualTo(75);
    assertThat(legal.minRaiseTo()).isEmpty();
    assertThat(legal.maxRaiseTo()).isEqualTo(100);
}
```

- [ ] **Step 2: Run tests and confirm failure**

```powershell
backend\mvnw.cmd -f backend\pom.xml -Dtest=LegalActionsTest test
```

Expected: compilation fails because betting types do not exist.

- [ ] **Step 3: Implement defensive betting value objects**

`SeatState` enforces seat index `0..5`, non-negative chip fields, `streetCommitted <= handCommitted`, and zero stack for `ALL_IN` or `OUT`. Provide methods `withContribution(long chips)`, `fold()`, `resetStreet()`, and `settled(long payout)` that preserve invariants and return new values. `settled` clears both contribution fields, adds the payout to the remaining stack, and changes status to `ACTIVE` when the resulting stack is positive or `OUT` when it is zero.

`PlayerAction` enforces target amount greater than zero only for `RAISE`; `ALL_IN` has amount zero because its target is derived from the actor's entire remaining stack.

- [ ] **Step 4: Implement exact legal-action calculation**

Use these rules in order:

1. `OUT`, `FOLDED`, and `ALL_IN` actors receive an empty action set.
2. `needed = max(0, currentBet - actor.streetCommitted())`.
3. If `needed == 0`, allow `CHECK`; otherwise allow `FOLD` and `CALL` with `callAmount=min(needed, stack)`.
4. Allow `ALL_IN` whenever stack is positive.
5. `maxRaiseTo = streetCommitted + stack`.
6. `minRaiseTo = currentBet + lastFullRaiseSize` when facing a bet, otherwise `lastFullRaiseSize`.
7. Allow `RAISE` only when raise rights are open and `maxRaiseTo >= minRaiseTo`.
8. Do not expose `FOLD` when checking is free.

- [ ] **Step 5: Add validation cases**

Cover no raise rights after a short all-in, an exact-stack call, a full all-in raise, folded/all-in actors, invalid negative values, and `minRaiseTo` empty when the stack cannot reach it.

- [ ] **Step 6: Run tests and commit**

```powershell
backend\mvnw.cmd -f backend\pom.xml -Dtest=LegalActionsTest test
backend\mvnw.cmd -f backend\pom.xml test
git add backend/src/main/java/com/agenttavern/game/betting backend/src/test/java/com/agenttavern/game/betting
git commit -m "feat: calculate legal poker actions"
```

---

### Task 5: Betting Round State Machine

**Files:**

- Create: `backend/src/main/java/com/agenttavern/game/betting/BettingRound.java`
- Test: `backend/src/test/java/com/agenttavern/game/betting/BettingRoundTest.java`

**Interfaces:**

- Consumes: `SeatState`, `PlayerAction`, `LegalActions`, and `IllegalActionException` from Task 4.
- Produces: `BettingRound.start(List<SeatState> seats, Street street, int firstToActSeat, long lastFullRaiseSize)`.
- Produces: `actor()`, `legalActions()`, `apply(PlayerAction)`, `isComplete()`, `seats()`, `currentBet()`, `lastFullRaiseSize()`, and `street()`.
- Maintains internally: `pendingAction` and `raiseRights` player sets.

- [ ] **Step 1: Write failing turn-order and completion tests**

```java
@Test
void callCallCheckCompletesThreePlayerRound() {
    BettingRound round = preflopRoundWithBlinds(50, 100);
    round = round.apply(PlayerAction.call());
    round = round.apply(PlayerAction.call());
    round = round.apply(PlayerAction.check());
    assertThat(round.isComplete()).isTrue();
    assertThat(round.seats()).extracting(SeatState::streetCommitted)
        .containsExactly(100L, 100L, 100L);
}

@Test
void fullRaiseReopensActionForEveryOtherActivePlayer() {
    BettingRound round = preflopRoundWithBlinds(50, 100);
    PlayerId firstActor = round.actor().playerId();
    round = round.apply(PlayerAction.raiseTo(300));
    round = round.apply(PlayerAction.call());
    round = round.apply(PlayerAction.call());
    assertThat(round.actor().playerId()).isEqualTo(firstActor);
    assertThat(round.legalActions().types()).contains(CALL, RAISE);
}
```

- [ ] **Step 2: Run the focused test and confirm failure**

```powershell
backend\mvnw.cmd -f backend\pom.xml -Dtest=BettingRoundTest test
```

Expected: compilation fails because `BettingRound` does not exist.

- [ ] **Step 3: Implement round initialization and actor advancement**

Initialization validates unique seats/player IDs, two to six seats, one active first actor, and positive `lastFullRaiseSize`. Set `currentBet` to the maximum street contribution. Populate `pendingAction` and `raiseRights` with every `ACTIVE` player. Actor selection walks clockwise by seat index and skips `FOLDED`, `ALL_IN`, `OUT`, and players absent from `pendingAction`.

- [ ] **Step 4: Implement action reduction**

For each action:

- Reject action types absent from `legalActions().types()`.
- `FOLD`: mark folded; remove actor from pending and raise-right sets.
- `CHECK`: remove actor from pending and raise-right sets.
- `CALL`: contribute `callAmount`; mark all-in when stack reaches zero; remove actor from pending and raise-right sets.
- `RAISE`: contribute `target - streetCommitted`; require `target >= minRaiseTo`; update `currentBet`; set `lastFullRaiseSize = target - oldCurrentBet`; reset pending and raise rights to every other active player.
- `ALL_IN`: contribute the full stack. If target does not exceed `currentBet`, treat it as an all-in call and remove the actor from pending/raise-right sets. If target is a full raise, apply the `RAISE` reset behavior. If target increases the bet by less than `lastFullRaiseSize`, add every other active undercalled player to pending without restoring raise rights for a player that already acted.

The round completes when only one non-folded/non-out player remains or `pendingAction` is empty. A returned complete round has no actor.

- [ ] **Step 5: Add short-all-in and skip-state tests**

Prove all of the following:

- A short all-in increases `currentBet` but does not reopen a previous caller's raise rights.
- A full all-in raise does reopen raise rights.
- Folded and all-in seats are skipped in clockwise actor selection.
- Folding down to one player completes immediately.
- Attempting an out-of-turn, under-minimum, or stale action throws `IllegalActionException` without changing the original round.

- [ ] **Step 6: Run tests and commit**

```powershell
backend\mvnw.cmd -f backend\pom.xml -Dtest=BettingRoundTest test
backend\mvnw.cmd -f backend\pom.xml test
git add backend/src/main/java/com/agenttavern/game/betting/BettingRound.java backend/src/test/java/com/agenttavern/game/betting/BettingRoundTest.java
git commit -m "feat: execute betting rounds"
```

---

### Task 6: Side Pots and Showdown Resolution

**Files:**

- Create: `backend/src/main/java/com/agenttavern/game/showdown/Pot.java`
- Create: `backend/src/main/java/com/agenttavern/game/showdown/SidePotCalculator.java`
- Create: `backend/src/main/java/com/agenttavern/game/showdown/ShowdownResolver.java`
- Test: `backend/src/test/java/com/agenttavern/game/showdown/SidePotCalculatorTest.java`
- Test: `backend/src/test/java/com/agenttavern/game/showdown/ShowdownResolverTest.java`

**Interfaces:**

- Consumes: final `SeatState.handCommitted`, player status, hole cards, community cards, and `HandEvaluator`.
- Produces: `Pot(long amount, Set<PlayerId> eligiblePlayers)`.
- Produces: `SidePotCalculator.calculate(List<SeatState>): List<Pot>` ordered main pot to deepest side pot.
- Produces: `ShowdownResolver.resolve(List<Pot> pots, Map<PlayerId,List<Card>> holeCards, List<Card> board, Map<PlayerId,Integer> seats, int buttonSeat): Map<PlayerId,Long>`.

- [ ] **Step 1: Write failing multi-side-pot tests**

```java
@Test
void buildsMainAndTwoSidePotsFromThreeContributionLevels() {
    List<SeatState> seats = List.of(
        committed(0, 100, ALL_IN),
        committed(1, 300, ALL_IN),
        committed(2, 500, ACTIVE),
        committed(3, 500, FOLDED));

    assertThat(SidePotCalculator.calculate(seats)).containsExactly(
        pot(400, players(0, 1, 2)),
        pot(600, players(1, 2)),
        pot(400, players(2)));
}
```

The folded player contributes money to pot amounts but is absent from every eligible-player set.

- [ ] **Step 2: Run focused tests and confirm failure**

```powershell
backend\mvnw.cmd -f backend\pom.xml -Dtest="SidePotCalculatorTest,ShowdownResolverTest" test
```

Expected: compilation fails because showdown types do not exist.

- [ ] **Step 3: Implement deterministic side-pot construction**

Sort distinct positive contribution levels. For each level `L`, subtract the previous level, multiply by the number of seats whose contribution is at least `L`, and set eligible players to those contributors whose status is neither `FOLDED` nor `OUT`. Reject a non-zero pot with no eligible player. Verify the sum of calculated pots equals the sum of all hand contributions.

- [ ] **Step 4: Implement payout resolution**

For each pot, evaluate every eligible player's two hole cards plus the five-card board, retain all players tied for the maximum `HandValue`, and distribute `amount / winnerCount`. Distribute the remainder one chip at a time in clockwise seat order starting at `(buttonSeat + 1) % tableSize`, skipping non-winners.

Return an immutable map containing every paid player and assert internally that total payouts equal total pots.

- [ ] **Step 5: Add showdown and odd-chip tests**

Cover:

- Different winners for the main and side pot.
- A two-way tie in an odd-sized pot where the first winner left of the button receives the odd chip.
- A folded player holding the strongest cards receives nothing.
- Missing hole cards for an eligible player, a board not containing five cards, duplicate cards, and contribution/pot mismatches are rejected.

- [ ] **Step 6: Run tests and commit**

```powershell
backend\mvnw.cmd -f backend\pom.xml -Dtest="SidePotCalculatorTest,ShowdownResolverTest" test
backend\mvnw.cmd -f backend\pom.xml test
git add backend/src/main/java/com/agenttavern/game/showdown backend/src/test/java/com/agenttavern/game/showdown
git commit -m "feat: settle side pots and showdowns"
```

---

### Task 7: Complete Hand Aggregate and Domain Events

**Files:**

- Create: `backend/src/main/java/com/agenttavern/game/hand/HandId.java`
- Create: `backend/src/main/java/com/agenttavern/game/hand/BlindLevel.java`
- Create: `backend/src/main/java/com/agenttavern/game/hand/PlayerStack.java`
- Create: `backend/src/main/java/com/agenttavern/game/hand/HandEvent.java`
- Create: `backend/src/main/java/com/agenttavern/game/hand/Hand.java`
- Create: `backend/src/main/java/com/agenttavern/game/hand/HandTransition.java`
- Test: `backend/src/test/java/com/agenttavern/game/hand/HandTest.java`
- Test: `backend/src/test/java/com/agenttavern/game/hand/HandEventTest.java`

**Interfaces:**

- Produces: `Hand.start(HandId id, List<PlayerStack> players, int buttonSeat, BlindLevel blinds, Deck deck)`.
- Produces: `Hand.actor()`, `legalActions()`, `act(PlayerId, PlayerAction)`, `street()`, `board()`, `seats()`, `holeCards(PlayerId)`, `isComplete()`, and `totalChipsInSystem()`.
- Produces: `HandTransition(Hand hand, List<HandEvent> events)`.
- Produces sealed events: `HandStarted`, `HoleCardsDealt`, `BlindsPosted`, `PlayerActed`, `CommunityCardsDealt`, `PotsAwarded`, and `HandCompleted`.
- The `HoleCardsDealt` event remains private infrastructure input in later phases; public projection is not part of this plan.

- [ ] **Step 1: Write a failing fixed-deck start test**

Stack a deck so the expected two hole cards for every player are known. Assert that `Hand.start`:

- Uses the next active seat left of the button as small blind for three or more players.
- Uses the button as small blind in heads-up play.
- Posts at most each player's remaining stack.
- Deals one card clockwise to each funded seat, then a second round in the same order.
- Starts Pre-Flop with the first active seat left of the big blind, or the button in heads-up play.
- Emits `HandStarted`, private `HoleCardsDealt`, and `BlindsPosted` events in deterministic order.

- [ ] **Step 2: Run the hand test and confirm failure**

```powershell
backend\mvnw.cmd -f backend\pom.xml -Dtest="HandTest,HandEventTest" test
```

Expected: compilation fails because hand aggregate types do not exist.

- [ ] **Step 3: Implement hand initialization**

`BlindLevel` requires positive small blind and `bigBlind >= 2 * smallBlind`. `PlayerStack` requires a unique seat index `0..5` and positive chips. `Hand.start` requires 2–6 players, unique IDs/seats, a deck large enough for all hole cards plus eight burn/community cards, and a button seat occupied by a funded player.

Create `SeatState` values, deal hole cards, post blinds through contribution methods, and construct the Pre-Flop `BettingRound` with `lastFullRaiseSize = bigBlind`.

- [ ] **Step 4: Implement action and street advancement**

`act` first rejects completed hands and out-of-turn player IDs, applies the betting action, and emits `PlayerActed`. Then:

1. If one non-folded player remains, calculate all contributions as pots, award everything uncontested, and complete.
2. If the betting round remains open, return the updated hand.
3. If every remaining player is all-in, burn/deal every missing community street and resolve showdown immediately.
4. If Pre-Flop completes normally, reset street commitments, burn one card, deal three cards, and start Flop action at the first active seat left of the button.
5. If Flop or Turn completes normally, reset street commitments, burn one, deal one, and start the next street in the same post-flop order.
6. If River completes, calculate pots and resolve showdown.

Every new betting street uses `currentBet=0` and `lastFullRaiseSize=bigBlind`. Burned cards stay in private hand state but are not exposed as community cards.

- [ ] **Step 5: Write end-to-end hand tests**

Add fixed-deck scenarios for:

- Everyone folds to the big blind; awarded chips equal all posted/contributed chips.
- Check/call through River; the known best hand wins and the aggregate completes.
- Three-player all-in at different stack sizes; remaining board runs out and main/side pots go to expected players.
- Heads-up button/small-blind action order before and after the flop.
- A player action absent from `legalActions` leaves the original `Hand` unchanged.

- [ ] **Step 6: Validate event completeness**

For each end-to-end scenario, concatenate all transition events and assert:

- Exactly one `HandStarted` and one `HandCompleted` exist.
- Every accepted action has one `PlayerActed` event.
- Community-card events reveal exactly 3, then 1, then 1 cards.
- Sum of `PotsAwarded` payouts equals all chips committed to pots.
- No event carries an API key, prompt, framework type, or mutable collection.

- [ ] **Step 7: Run tests and commit**

```powershell
backend\mvnw.cmd -f backend\pom.xml -Dtest="HandTest,HandEventTest" test
backend\mvnw.cmd -f backend\pom.xml test
git add backend/src/main/java/com/agenttavern/game/hand backend/src/test/java/com/agenttavern/game/hand
git commit -m "feat: execute complete Holdem hands"
```

---

### Task 8: Property Tests, Dependency Guardrails, and Engine Documentation

**Files:**

- Create: `backend/src/test/java/com/agenttavern/game/HandInvariantProperties.java`
- Create: `backend/src/test/java/com/agenttavern/game/GameEngineDependencyTest.java`
- Create: `backend/src/test/java/com/agenttavern/game/TestPolicies.java`
- Create: `backend/src/test/resources/junit-platform.properties`
- Create: `backend/README.md`
- Modify: `README.md`

**Interfaces:**

- Consumes: all public engine interfaces produced by Tasks 2–7.
- Produces: reusable test-only policies that always choose a legal check, call, or fold and terminate a hand deterministically.
- Produces: a phase quality gate `backend\mvnw.cmd -f backend\pom.xml verify`.

- [ ] **Step 1: Write a failing chip-conservation property**

Use jqwik to generate 2–6 player stacks between 100 and 20,000, a valid button seat, and blind levels that do not exceed the largest stack. Drive each hand with `TestPolicies.passiveLegalAction(hand.legalActions())` until completion or a hard cap of 500 actions.

```java
@Property(tries = 500)
void chipsAreConservedAcrossEveryTransition(@ForAll("handScenarios") HandScenario scenario) {
    Hand hand = scenario.start();
    long initial = hand.totalChipsInSystem();
    int actions = 0;
    while (!hand.isComplete()) {
        PlayerAction action = TestPolicies.passiveLegalAction(hand.legalActions());
        hand = hand.act(hand.actor().playerId(), action).hand();
        assertThat(hand.totalChipsInSystem()).isEqualTo(initial);
        assertThat(++actions).isLessThan(500);
    }
}
```

- [ ] **Step 2: Run the property and confirm it exposes missing generators/helpers**

```powershell
backend\mvnw.cmd -f backend\pom.xml -Dtest=HandInvariantProperties test
```

Expected: compilation fails because `handScenarios` and `TestPolicies` are not implemented.

- [ ] **Step 3: Implement scenario generators and legal policies**

Generate unique `PlayerId`/seat pairs, positive stacks, an occupied button, a valid blind level, and a deck shuffled by a scenario seed. The passive policy chooses `CHECK`, then `CALL`, then `ALL_IN`, then `FOLD` in that priority order. A second aggressive policy chooses a legal minimum `RAISE`, then `CALL`, then `CHECK`, then `ALL_IN`, then `FOLD`.

- [ ] **Step 4: Add remaining invariant properties**

Run at least 500 tries each and assert:

- No duplicate physical card exists across deck remainder, burned cards, board, and all hole cards.
- The actor is always active and has at least one legal action.
- Every `HandTransition` contains an immutable event list.
- Reapplying a rejected action does not change the hand value object.
- Passive and aggressive legal policies always terminate within 500 actions.

- [ ] **Step 5: Add architectural dependency rules**

Use ArchUnit classes already present through Spring Modulith test dependencies:

```java
@Test
void gameEngineDoesNotDependOnInfrastructureFrameworks() {
    JavaClasses classes = new ClassFileImporter().importPackages("com.agenttavern.game");
    noClasses().that().resideInAPackage("com.agenttavern.game..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.web..",
            "org.springframework.data..",
            "org.springframework.ai..",
            "jakarta.persistence..")
        .check(classes);
}
```

Also assert public domain records expose immutable collections and no class under `game..` ends with `Controller`, `RepositoryImpl`, or `Entity`.

- [ ] **Step 6: Document engine semantics**

Create `backend/README.md` documenting:

- `PlayerAction.amount` as raise-to amount.
- Short-all-in and raise-right behavior.
- Blind/button rules for heads-up and multi-way play.
- Burn-card behavior.
- Side-pot ordering and odd-chip rule.
- How to inject `Deck.ordered` for deterministic tests.
- Exact focused and full verification commands.

Update the root `README.md` with a link to this engine guide and mark phase 1 as the currently implemented vertical slice only after all tests pass.

- [ ] **Step 7: Run the complete phase verification**

```powershell
backend\mvnw.cmd -f backend\pom.xml clean verify
git diff --check
git status --short
```

Expected: Maven reports `BUILD SUCCESS`, all unit/property/architecture tests pass with zero failures, `git diff --check` prints nothing, and only the intended Task 8 files are modified/untracked.

- [ ] **Step 8: Commit the quality gate**

```powershell
git add README.md backend/README.md backend/src/test backend/src/test/resources
git commit -m "test: enforce poker engine invariants"
```

- [ ] **Step 9: Verify the committed phase**

```powershell
backend\mvnw.cmd -f backend\pom.xml clean verify
git status --short --branch
git log -8 --oneline
```

Expected: build succeeds, the worktree is clean, and Tasks 1–8 appear as focused commits.

## Phase 1 Acceptance Checklist

- [ ] A clean clone builds with the Maven Wrapper and Java 21.
- [ ] Spring Modulith verifies the `game` module.
- [ ] A deterministic deck can drive complete two-to-six-player hands.
- [ ] All nine hand categories and tie breakers are correct.
- [ ] Legal actions reflect stack, call amount, minimum raise, all-in, and raise rights.
- [ ] Betting rounds handle full raises and short all-ins correctly.
- [ ] Main pots, side pots, ties, folded contributors, and odd chips settle correctly.
- [ ] A complete hand emits immutable replayable domain events.
- [ ] Property tests prove chip conservation, card uniqueness, legal actor selection, and termination.
- [ ] No infrastructure or LLM dependency leaks into the game engine.
- [ ] No real secret appears in tracked files or Git diff.

## Official Version References

- Spring Boot 4.1.1 system requirements: https://docs.spring.io/spring-boot/system-requirements.html
- Spring Modulith 2.1.0: https://docs.spring.io/spring-modulith/reference/index.html
- Maven 3.9.16 release history: https://maven.apache.org/docs/history.html
- Maven Wrapper 3.3.4: https://maven.apache.org/tools/wrapper/download.cgi
- jqwik metadata: https://repo.maven.apache.org/maven2/net/jqwik/jqwik/maven-metadata.xml
