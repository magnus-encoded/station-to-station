# Tour character

The **Virtual friend**, as data. Both platforms load `character.json` at run time: Android
bundles this folder as assets, iOS bundles the JSON as a resource. No code names the
character. To swap it, edit `character.json` and replace the two images it names:

- `android/app/src/main/res/drawable/<id>.xml` (vector drawable)
- `ios/StationToStation/Assets.xcassets/<id>.imageset/<id>.svg`

The SVGs here are the source of both. `lines` needs a key for every step S1–S20; both
platforms' tests fail on a missing one.

The current images are original vector drawings after Joseph Karl Stieler's 1820 portrait
of Beethoven. The painting is in the public domain (Stieler died in 1858); no pixels of any
photograph of it are used.
