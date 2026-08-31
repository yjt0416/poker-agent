# Poker Engine Guide

This module is the implemented Phase 1 vertical slice: a deterministic,
in-memory Texas Hold'em engine with betting, showdown, events, and quality
guards. It is not a complete product and does not provide a browser frontend,
database, HTTP API, or agent/LLM integration.

## Verification

Run these commands from the repository root with Java 21 selected.

Focused engine invariants and architecture guards on Windows:

```powershell
backend\mvnw.cmd -f backend\pom.xml -Dtest="HandInvariantProperties,GameEngineDependencyTest" test
```

The complete clean Phase 1 verification on Windows:

```powershell
backend\mvnw.cmd -f backend\pom.xml clean verify
```

Unix or CI equivalent:

```sh
./backend/mvnw -f backend/pom.xml clean verify
```

The invariant suite uses deterministic JUnit Jupiter property-style checks.
Each of its six checks executes 500 fixed, distinct scenario seeds; any failure
reports the reproducing seed. These checks do not perform shrinking.

## Action amounts and betting rights

`PlayerAction.amount` is always a **target total for the current street** for
both `RAISE` and `ALL_IN`. For example, a player who has already committed 40
and chooses `raiseTo(120)` contributes another 80. An all-in action must use
the actor's full target (`streetCommitted + stack`), including a short all-in.
`FOLD`, `CHECK`, and `CALL` always use amount `0`.

A full raise is an increase at least as large as the prior full-raise size; it
reopens raise rights for the other active players. A short all-in can increase
the current bet, so other active players still respond, but it does not reopen
raise rights for players who already used them. `LegalActions` is the engine's
authority for call amounts, minimum raise-to, maximum target, all-in, and
whether rights are open.

## Button, blinds, and streets

With three to six players, the small blind is the next occupied seat clockwise
from the button and the big blind is the next occupied seat. Preflop action
starts with the first active seat left of the big blind. In heads-up play the
button posts the small blind, receives the first preflop action, and the other
player posts the big blind. On flop, turn, and river, action starts with the
first active seat left of the button.

After every completed betting street, the engine resets only the street
commitments. It burns one private card before dealing three flop cards, one
turn card, and one river card. Burned cards are deliberately not exposed by
the public hand or event API; observable hole and community cards are immutable
and deterministic from the injected deck.

## Pots and deterministic decks

Every committed chip funds pots, including chips from folded players. Pots are
ordered main pot through progressively deeper side pots, while only non-folded
contributors are eligible to win each layer. Tied winners divide each pot
equally; any odd chips are allocated clockwise, beginning with the first seat
left of the button.

Use `Deck.ordered(...)` for deterministic tests. Its list is the exact draw
order: the hand deals hole cards first, then burns and community cards as the
hand advances. The deck rejects duplicate physical cards and each `draw()`
returns an immutable remainder, so a test can inject a known deck without a
hidden shuffle.
