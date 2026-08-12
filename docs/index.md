# Station to Station

A personal record of the concerts you were at. Your timeline lives on your
phone, not on a server.

## Spotify export

Spotify limits apps like this one to five signed-in users, and that limit has
no path around it for an individual developer. Everything else in the app works
for everyone: importing your concerts, the timeline, photos, notes, and
comparing timelines with someone else over Bluetooth.

If you want Spotify export anyway, there are two ways:

**Ask me.** [Email your Spotify address](mailto:dizzi90@gmail.com?subject=Station%20to%20Station%20-%20Spotify%20access)
and I will add you when there is a slot free. There usually is not, so this is
first come, first served.

**Or use your own.** This costs nothing and has no waiting:

1. Go to [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard)
   and create an app. Any name will do.
2. Tick **Web API**.
3. Set the redirect URI to exactly `station-to-station://callback`.
4. Copy the app's Client ID into Settings in Station to Station, then Save.
5. Log out of Spotify in the app and back in.

The redirect URI is the step that goes wrong. It has to match character for
character, or login fails without saying why.

## Privacy

[Privacy policy](privacy-policy.html). Short version: there is no server, and
nothing leaves your phone except what you explicitly send to setlist.fm,
MusicBrainz, or Spotify.
