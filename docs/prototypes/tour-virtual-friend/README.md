# Tour Virtual friend: prototype (throwaway)

Question answered: what does the Virtual friend's Tour card look like, say, and do, step by step (S1-S20), on both platforms, including where the keyboard goes?

`ludwig-tour.html` is the spec page: one self-contained file, no network except fonts. Open it, pick iOS or Android, and perform each step's event. The `a`/`b` chips on S3, S4, S14 and S15 are the keyboard-shown and keyboard-hidden states.

Rebuild after changing `fixtures/tour/character/character.json` or its assets:

    python3 docs/prototypes/tour-virtual-friend/build.py

`ludwig-tour.src.html` is the template (placeholders `__CHARACTER__`, `__AVATAR__`, `__BUST__`, `__SELFIE__`). The phone is a schematic of the app, not a screenshot. Nothing here ships; the validated decisions live in the character file, its README and the implementation tickets.
