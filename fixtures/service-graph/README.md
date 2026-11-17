# Settings Field fixtures

What the Settings **Field** (#563) says about each service: `serviceGraph` in
`ServiceGraph.kt` and `ServiceGraph.swift`, word for word. `ServiceGraphTest` (Android)
and `ServiceGraphTests` (iOS) assert `cases.json` case for case. Fixtures are required,
never skipped if missing. Synthetic only.

Each case gives `known`, a `ServicesAsKnown`. Any field left out is its fresh-install
default: `false`, `""`, `0`, `null`, and `photos` `"none"` (the others are `"partial"`
and `"full"`).

`expect` holds:

- `timelineLit`: whether **My timeline** is lit.
- `alcoveLines`: for each **Alcove**, whether the line out to it is lit.
- `lit` (optional): the *exact* set of lit node ids, order not significant.
- `nodes`: the named nodes only, each with `lit`, `status` and `nextStep` (`null` for
  none). Nodes a case does not name are not checked by it.

Node ids, in drawing order: `setlistfm`, `musicbrainz`, `clashfinder`, `photos`,
`tickets`, `location`, `contacts`, `gossip` (inputs), then `spotify`, `calendar`
(**Alcoves**).

## What these cannot show

An **Alcove** that is lit while its line is not. The line is lit when the **Alcove** is
and **My timeline** is, and **My timeline** is lit when any input is — but MusicBrainz and
Ticket PDFs are always lit, so no `known` here can darken it. The rule is still the rule,
so both suites check it on a hand-built `ServiceGraph` whose inputs are all unlit
(`an alcove lit on a dark timeline keeps its line dark`), rather than through this file.
