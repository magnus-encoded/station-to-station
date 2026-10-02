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

## Agent skills

### Issue tracker

Issues live in GitHub Issues (`magnus-encoded/station-to-station`, via `gh`). See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` + `docs/adr/` at the root. See `docs/agents/domain.md`.
