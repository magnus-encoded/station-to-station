# Driving the app on a device

Try a link first, then `android layout`, then a gesture, and take a screenshot only when
the visuals matter. A swipe → screenshot → look loop costs far more context than the
task it is trying to reach.

```
adb shell am start -a android.intent.action.VIEW -d 'station-to-station://timelines'
xcrun simctl openurl booted 'station-to-station://timelines'
```

Quote the link: `&` and `?` belong to the shell otherwise.

## Links

`station-to-station://<screen>[/<id>][/<action>][?params]`. Screen and action names are
case-insensitive. Action links pre-fill; they never save, and never skip what the form
requires. An unknown screen or action does nothing. The grammar and every case are in
[`fixtures/deeplinks/`](../../fixtures/deeplinks/README.md), which both platforms assert.

| Link | Does |
| ---- | ---- |
| `timeline` | My single **Line**, from wherever the app is. |
| `timeline?date=yyyy-MM-dd` | My **Line**, scrolled to the **Gig** nearest that date; a tie goes to the earlier. |
| `timelines` | The weave, zoomed out. Needs a followed Line or a **Contact**. |
| `timelines?date=yyyy-MM-dd` | The weave, scrolled to the nearest **Gig** across all Lanes. |
| `timeline/add-gig?artist=&venue=&date=` | The one add form, pre-filled and unsaved. The date decides what saving it does: before today is a night you were at, today, later or no date is a gig you're going to. |
| `gig/<id>` | The **Gig** view. On my **Line** it adds nothing; an unknown setlist.fm id is fetched and opened without being kept, and the **Room** offers to join it. |
| `gig/<id>/write-to-log?<text>[&<text>…][&N=<text>…]` | Writes to that **Gig**'s **Log** as typed input would. A bare item is the next song; `N=` replaces song N (from 1), appends if N is just past the end, and is ignored if further out. |
| `programme` | The festival programme. |
| `settings` | Settings. |

`log` is reserved and does nothing: the **Log** is reached through `gig/<id>/write-to-log`.
`gig?id=<id>` is the older invite form of `gig/<id>`. The place links `<gigId>`,
`<line>/<gigId>`, `Friends/<gigId>` and `me` still work, as do `friend`, `handover`,
`callback` and `ticket`. A **Line** named like a reserved screen has no place link.

Percent-encode `&`, `=` and `#` in text, and use `%20` for a space; `+` is a plus.

## Example

```
adb shell am start -a android.intent.action.VIEW \
  -d 'station-to-station://timeline/add-gig?artist=gossiptest123&date=2026-09-29'
```

opens a pre-filled, unsaved dialog.
