# Agent Tavern

Agent Tavern starts with a modular Java 21 backend for a reproducible Texas Hold'em engine foundation.

## Phase 1

The currently implemented vertical slice is the deterministic, in-memory poker
engine: cards and evaluation, legal betting, complete hands and events, side
pots, and invariant/architecture verification. This does not claim that a
complete product or frontend is implemented. See the [backend poker engine guide](backend/README.md)
for public semantics and deterministic test setup.

The engine's invariant suite uses deterministic JUnit Jupiter property-style
checks, with fixed reproducible scenario seeds rather than jqwik shrinking.

## Prerequisite

Install Java 21.

## Verify

Windows:

```powershell
backend\mvnw.cmd -f backend\pom.xml verify
```

Focused engine invariants and architecture guards:

```powershell
backend\mvnw.cmd -f backend\pom.xml -Dtest="HandInvariantProperties,GameEngineDependencyTest" test
```

Unix or CI:

```sh
./backend/mvnw -f backend/pom.xml verify
```

## Secrets

No real DeepSeek key belongs in Git. Keep credentials in local, untracked configuration only.
