# Tour shared cases

`cases.json` is the platform-neutral executable form of the **Shared cases** table in issue #587.
The epic remains the source of truth; this fixture exists only so Android and iOS drive their Tour
state machines with the same cases.

Each object has a stable `id`, the source-table `row`, optional `precondition`, an ordered
`events` array, and an `expect` object. `Skip at Sn` is expanded to nineteen concrete cases so
both platforms must prove skip from every scripted step rather than interpreting a range.

Event objects use `type` plus only the payload needed by the case. `restart` is a fixture harness
event: persist the state, construct a fresh script instance, then continue. Fill cases include their
stubbed source data in `precondition`; no network request belongs in a shared-case test.

When #587 changes its Shared cases table, change this corpus in the same commit.
