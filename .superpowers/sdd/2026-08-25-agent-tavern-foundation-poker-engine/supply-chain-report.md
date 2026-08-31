# Supply-chain report — jqwik removal

**Scope:** `e9f137b` (pre-replacement baseline) to the deterministic JUnit
Jupiter replacement.  This report's baseline SHA is
`e9f137b66d7d3112c49a6ff1b6e52c86e4923c11`.

## Root cause

The official jqwik `1.10.1` `JqwikExecutor.execute()` writes an
ANSI-concealed prompt-injection message to standard output.  Suppressing that
output, selecting an upstream hide flag, or changing jqwik versions would
retain the dependency and evade the governing supply-chain decision.  jqwik is
therefore removed entirely and Maven now prohibits `net.jqwik:*`, including
transitive coordinates.

## RED evidence

With the dependency still declared, `backend\\mvnw.cmd -f backend\\pom.xml
validate` failed its new Enforcer rule.  The failure listed the direct
`net.jqwik:jqwik:1.10.1` dependency and its `jqwik-api`, `jqwik-web`,
`jqwik-time`, and `jqwik-engine` transitive coordinates as banned.  The host's
then-selected JDK 23 also correctly failed the independent Java-21 Enforcer
rule; the banned-dependency failure was nevertheless observed in that same
RED run.

## GREEN replacement and 6 x 500 evidence

`HandInvariantProperties` is now a pure JUnit Jupiter class.  It has six
deterministic property-style `@Test` methods plus three directed assertion
mutation examples.  Each property invokes `check500`, which asserts exactly
500 executed assertion calls, 500 distinct seeds, and the first and last seed.
It also asserts coverage of every two-to-six player count, sparse seats, a
relative short stack, occupied buttons, varying blind levels, and varying full
deck orders.  Any scenario exception is rethrown with the property name and
its reproducing seed.

| Property-style test | Base seed | Exact seed range | Executions |
| --- | ---: | ---: | ---: |
| chipsAreConservedAcrossEveryTransition | 2026082808 | 2026082808–2026083307 | 500 |
| everyObservableDealtCardIsPhysicallyUnique | 2026082809 | 2026082809–2026083308 | 500 |
| everyIncompleteHandHasAnActiveActorWithLegalActions | 2026082810 | 2026082810–2026083309 | 500 |
| everyTransitionExposesAnImmutableEventList | 2026082811 | 2026082811–2026083310 | 500 |
| rejectedActionCanBeReappliedWithoutChangingAnyPublicHandState | 2026082812 | 2026082812–2026083311 | 500 |
| passiveAndAggressiveLegalPoliciesAlwaysTerminateWithinTheHardActionLimit | 2026082813 | 2026082813–2026083312 | 500 |

The three directed examples still prove the card-population, strict
500-action, and hole-card snapshot assertions reject deliberately mutated
inputs.  No production source changed.

## Dependency and build evidence

All GREEN commands use Microsoft OpenJDK `21.0.9+10` from the untracked temp
runtime `C:\\Users\\yjt\\AppData\\Local\\Temp\\agent-tavern-jdk21\\jdk-21.0.9+10`.

- Focused invariants: `-Dtest=HandInvariantProperties test` — 9 tests, 0
  failures/errors.
- Architecture guard: `-Dtest=GameEngineDependencyTest test` — 3 tests, 0
  failures/errors.
- Full gate: `clean verify` — 209 tests, 0 failures, 0 errors, 0 skipped,
  `BUILD SUCCESS`.
- `dependency:tree` contains no resolved `net.jqwik` artifact.
- The effective POM and source POM contain no `net.jqwik` dependency node. The
  sole textual coordinate is the intentional Enforcer exclusion that prevents
  future direct and transitive additions.
- `rg` across `backend/src` found no jqwik imports or configuration keys.
- The full clean-build output scan found no `net.jqwik`, `jqwik`, or
  prompt-injection text.

The final replacement commit SHA is reported with the invoking task's completion
evidence. A commit cannot embed its own final object SHA without changing that
SHA; this report records the audited baseline SHA above.
