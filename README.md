<img src="docs/img/mark-512.png" alt="" width="88" align="left" hspace="16" vspace="4">

# Station to Station

Your concert-going life as a single continuous line — the gigs you attended, the
festivals they collapse into, and the nights your line crossed someone else's.

<br clear="left">

The mark is the app's own drawing: the **Spine**, with two **Nodes** on it. The line stops at each
node's rim and resumes past it, exactly as the timeline draws it. The filled node is a night that
happened; the open one is still ahead. Up is later.

Two native apps, one shared design:

| | |
| --- | --- |
| [**android/**](android/README.md) | Jetpack Compose app |
| [**ios/**](ios/README.md) | SwiftUI port — same features, same peer-to-peer model |

Each app README covers its own build, logins, and CI. Start there.

## What the apps do

- **Timeline** — your attended gigs on one line, zoomable in place from your whole
  concert life down to a single night. Festivals collapse into one node.
- **Followed lines** — pull a friend's public setlist.fm attendance and draw it beside
  yours. Nights you were both at merge into a single crossing.
- **Playlists** — every song in a setlist matched on Spotify, confirmed by you, created
  as a playlist named `year – artist – venue`, with a photo you took that night as its
  cover.
- **Media** — photos attached to a gig, exchanged directly between contacts you met in
  person. No server holds them.

There is no backend. Friends are exchanged phone-to-phone; everything else is on-device
or comes from the setlist.fm and Spotify APIs.

## Reading the code

- [`CONTEXT.md`](CONTEXT.md) — the glossary. **Read this first.** The timeline's
  vocabulary is precise (Line, Spine, Lane, Crossing, Followed line vs. Contact), and the
  words carry design decisions.
- [`docs/adr/`](docs/adr/) — architectural decisions.
- [`docs/personas.md`](docs/personas.md) — who this is for.
- [`fixtures/weave/`](fixtures/weave/README.md) — the corpus both platforms assert against.

## The CLI

This repo started as `setlist-fm-cli`, a Python tool that turned a setlist.fm page into a
Spotify playlist. The apps have long since outgrown it and it is no longer maintained. It
lives on the [`cli`](../../tree/cli) branch and is gone from `main`.
