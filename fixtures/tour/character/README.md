# Tour character

The Tour's Virtual friend lives in this folder and nowhere else. Both platforms load `character.json`; code refers to keys only, so swapping the character means replacing this folder's contents.

## character.json

| Key | Meaning |
|---|---|
| `history` | Fictional demo gigs: artist, date (`dd-MM-yyyy`), venue and city. Ludwig’s varied history is set within Beethoven’s lifetime; these are not claims about real performances or attendance. |
| `name` | Display name on every card and on the Exchange row. |
| `username` | The card's setlist.fm username, shown as `@username` under the name on the Exchange row (S7). |
| `avatar`, `selfie`, `cutout` | Asset base names (see below). |
| `lines` | Keyed by step id `S1`..`S19`. S9, S17 and S20 have no card, so no key. |
| `notes.gapFill` | Text the friend's gossip puts in the user's Gap (S16). |
| `notes.setlistFill` | Note shown where the setlist is filled from setlist.fm (S17). |
| `playlist.title`, `playlist.description` | The Spotify playlist created at S19. |

Each `lines` entry: `do` (required, at most 20 words: the one instruction), `why` (optional, at most 30 words: only where a step is counter-intuitive or leaves the app), and optional `ios` / `android` strings that replace `do` on that platform. A missing key for a step that has a card is an error.

## assets/

| File | Use |
|---|---|
| `ludwig_cutout.webp` | Android card image, transparent, 480px wide. |
| `ludwig_cutout_1x.png`, `_2x.png`, `_3x.png` | iOS imageset (160/320/480px wide), transparent. |
| `ludwig_avatar.png` | Round avatar, 256x256 (Contact avatar, Exchange row). |
| `ludwig_selfie.jpg` | The friend's selfie at S18, 1024px, raster. |
| `source/` | The original generated images, kept as the primary source. |

Provenance: the images were generated with ChatGPT by the project owner, who is happy for others to use them. The character is based on Beethoven's public-domain portrait (Stieler, 1820). The cutout was made by flood-filling the white background of `source/ludwig_card_original.webp`; the avatar is cropped from the cutout.

## Card design

See `docs/prototypes/tour-virtual-friend/ludwig-tour.html` for the full spec (layout numbers, per-step placement, keyboard behaviour).
