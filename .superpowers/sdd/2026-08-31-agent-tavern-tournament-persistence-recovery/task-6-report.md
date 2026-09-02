# Task 6 Report: PostgreSQL tournament store

## Status

Completed.

## Delivered

- Added Flyway V1 with the five required PostgreSQL tables, cascading child foreign keys, UUID keys, integrity checks, unique stream/command constraints, JSONB fields, and query indexes. Events retain epoch-second and nanosecond columns in addition to `timestamptz`, so PostgreSQL microsecond precision cannot alter published envelopes.
- Added environment-only datasource configuration; no credentials are stored in the repository.
- Added Jackson 3 checkpoint and event codecs. Event and nested hand-event decoding uses explicit switches, not default typing. The mapper includes explicit `PlayerId` map-key handling for current-hand stacks, hole cards, and payouts.
- Added `JdbcTournamentStore` with snapshot-consistent joined reads; transactional create/update optimistic locking; normalized seat and hand-snapshot replacement; batch event insertion; and immutable command receipts that retain the original private checkpoint JSON, version, sequence, and event bounds.
- Declared the tournament persistence port as a named Modulith interface and narrowed persistence's game dependencies to its actual interfaces.

## TDD evidence

The initial focused RED run failed at test compilation because `TournamentCheckpointJdbcMapper` and `TournamentEventJsonCodec` did not yet exist.

After implementation:

```text
backend\mvnw.cmd -f backend\pom.xml -Dtest=TournamentEventJsonCodecTest,MigrationContractTest test
15 tests, 0 failures, 0 errors

backend\mvnw.cmd -f backend\pom.xml -Dtest=TournamentEventJsonCodecTest,MigrationContractTest,TournamentDependencyTest,ArchitectureTest test
20 tests, 0 failures, 0 errors

backend\mvnw.cmd -f backend\pom.xml clean verify
271 tests, 0 failures, 0 errors
```

The codec tests cover all five `TournamentEvent` variants, all seven nested `HandEvent` variants, UUID-keyed JSON maps, and IN_HAND, BETWEEN_HANDS, and COMPLETE checkpoint JSON round trips.

## PostgreSQL smoke check

Before the final lossless-timestamp schema extension, the packaged application started against the supplied temporary loopback PostgreSQL 17.11 database and Flyway successfully applied the initial V1 table structure. The temporary process was stopped and no credentials were persisted. The final V1 will be applied to the fresh Task 7 database, avoiding a Flyway checksum rewrite of that prior disposable smoke database.

## Remaining scope

No database behavior integration tests were added here, per the Task 6 no-Docker constraint. Task 7 can exercise the committed store against PostgreSQL for transaction, receipt, and concurrent-writer behavior.
