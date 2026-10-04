# Agents

## Reading the code

- [`CONTEXT.md`](CONTEXT.md) — the glossary. **Read this first.** The timeline's
  vocabulary is precise (Line, Spine, Lane, Crossing, Followed line vs. Contact), and the
  words carry design decisions.
- [`docs/adr/`](docs/adr/) — architectural decisions.
- [`docs/personas.md`](docs/personas.md) — who this is for.
- [`fixtures/weave/`](fixtures/weave/README.md) — the corpus both platforms assert against.

## Driving the app

[`docs/agents/device.md`](docs/agents/device.md) — every `station-to-station://` link, and
the order to try them in. Use it before swiping over `adb` or `simctl`.

## Writing comments

Follow [`docs/agents/comments.md`](docs/agents/comments.md): separate rules for logic, plumbing and tests. The periodic comment review checks against them.

## Feature state

Feature state lives in `features/<name>/` (Android): a controller taking `state`, `update`, its `data/` dependencies and a `CoroutineScope`, wired in `AppViewModel`.
