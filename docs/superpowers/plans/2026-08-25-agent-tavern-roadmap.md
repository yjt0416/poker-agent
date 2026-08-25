# Agent Tavern Implementation Roadmap

**Spec:** `docs/superpowers/specs/2026-08-25-multi-agent-texas-holdem-design.md`

The approved product spans several independently reviewable subsystems. It is therefore implemented as six plans instead of one oversized plan. Each phase must leave the repository buildable and independently testable.

| Phase | Plan | Independently testable result |
| --- | --- | --- |
| 1 | `2026-08-25-agent-tavern-foundation-poker-engine.md` | A pure Java No-Limit Hold'em hand engine with deterministic tests, complete betting semantics, side pots, showdown, and domain invariants |
| 2 | Tournament, persistence, and recovery | Six-player blind progression, elimination/ranking, immutable events, PostgreSQL snapshots, Flyway migrations, and crash/reconnect recovery |
| 3 | DeepSeek agents and table chat | OpenAI-compatible DeepSeek adapter, structured decisions, eight personas, private memory, untrusted chat isolation, retry/fallback, and fake-provider integration tests |
| 4 | Realtime API and playable React client | Anonymous sessions, REST commands, WebSocket projections, a functional player-mode table, browser refresh recovery, and Playwright happy path |
| 5 | Approved visual system and game feel | The approved tavern composition, 2D chibi assets, responsive layout, card/chip/turn animations, sound controls, reduced motion, and screenshot regression |
| 6 | Spectator, replay, statistics, and release | AI-vs-AI controls, director views, event replay, anonymous history, observability, Docker Compose, CI, security checks, and release acceptance suite |

Detailed plans for phases 2–6 are written immediately before their execution. That keeps exact file paths and interfaces aligned with the code that actually exists after the preceding phase, while the approved design spec remains the fixed scope contract.

## Spec coverage map

| Approved spec sections | Implementing phases |
| --- | --- |
| Architecture and module boundaries | 1, 2, 3, 4 |
| Hold'em rules and hand state | 1 |
| Tournament rules and blind progression | 2 |
| Agent model, DeepSeek, chat, prompt isolation, and fallback | 3 |
| Player mode, anonymous session, REST, WebSocket, and recovery | 2, 4 |
| Approved frontend flow and visual quality | 4, 5 |
| Persistence objects, replay events, and API contracts | 2, 4, 6 |
| Security, cost controls, and observability | 3, 4, 6 |
| Spectator/director mode, history, and replay | 6 |
| Docker, CI, accessibility, and complete acceptance suite | 5, 6 |

## Version baseline

- Java 21.
- Spring Boot 4.1.1.
- Spring AI 2.0.0 when phase 3 begins.
- Spring Modulith 2.1.0.
- Maven Wrapper 3.3.4 using Maven 3.9.16.
- Node.js 24 LTS when phase 4 begins.
- No snapshot or milestone dependencies.

Official references:

- https://docs.spring.io/spring-boot/system-requirements.html
- https://docs.spring.io/spring-ai/reference/getting-started.html
- https://docs.spring.io/spring-modulith/reference/index.html
- https://maven.apache.org/docs/history.html
- https://nodejs.org/en/about/previous-releases
