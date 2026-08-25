# Agent Tavern

Agent Tavern starts with a modular Java 21 backend for a reproducible Texas Hold'em engine foundation.

## Prerequisite

Install Java 21.

## Verify

Windows:

```powershell
backend\mvnw.cmd verify
```

Unix or CI:

```sh
./backend/mvnw verify
```

## Secrets

No real DeepSeek key belongs in Git. Keep credentials in local, untracked configuration only.
