# Agent Tavern

Agent Tavern starts with a modular Java 21 backend for a reproducible Texas Hold'em engine foundation.

## Prerequisite

Install Java 21.

## Verify

Windows:

```powershell
backend\mvnw.cmd -f backend\pom.xml verify
```

Unix or CI:

```sh
./backend/mvnw -f backend/pom.xml verify
```

## Secrets

No real DeepSeek key belongs in Git. Keep credentials in local, untracked configuration only.
