# Privacy Policy — Station to Station

Last updated: 26 September 2026 (first published 12 August 2026; see [What has changed](#what-has-changed))

Station to Station is a personal record of concerts you have attended. This policy
describes what the app does with your data.

## The short version

**There is no Station to Station server.** Your record lives on your phone. Nothing is
collected, analysed, or stored by us, because there is nowhere for it to go. The app has
no analytics, no crash reporting, no advertising, and no tracking of any kind.

Data leaves your device in five circumstances:

- when the app looks something up from a music or festival service,
- when you send your contact card to someone standing next to you,
- when you choose to publish a playlist to Spotify,
- when you check in at a gig, and your phone passes small facts about that night to
  other phones nearby ([Gossip](#gossip-at-a-gig)), and
- when your phone makes its own backup, if you have backups turned on.

Each of these starts with something you did. Gossip is the one that carries on after:
once you have checked in, it keeps running in the background until the night is over or
you stop it.

## What is stored on your device

- Your concert history, notes and verdicts
- References to photos you have attached to a night
- Your contacts — people whose cards you have exchanged with — and what they have shared with you
- Your setlist.fm username, and a setlist.fm API key if you enter your own
- Your clashfinder username and private key, if you enter them
- Your Spotify login token, if you have connected Spotify
- Tickets you share with the app, and the barcodes read from them
- Festival timetables you have looked up
- What your phone has heard and is passing on at a gig, until that night expires

All of this is held in the app's private storage. Uninstalling the app deletes it.

**Backups.** Your phone's own backup (Google backup on Android, iCloud on iPhone) can
include the app's storage, so your concert history, contacts and setlist.fm username
come back if you move to a new phone. The backup belongs to your Google or Apple account
and is governed by their terms, not ours; we cannot see it. On Android, the app leaves
two things out of the backup on purpose: your Spotify login, and what your phone has
been carrying at gigs, which is about other people. You can turn app backups off in your
phone's settings.

## Services the app contacts

The app talks to these services on your behalf. Each has its own privacy policy, which
governs what it does with the requests it receives.

| Service | What is sent | When |
|---|---|---|
| [setlist.fm](https://www.setlist.fm/help/privacy) | A username or artist name you are looking up | When you import or search for concerts |
| [MusicBrainz](https://metabrainz.org/privacy) | An artist name or identifier | When enriching an artist's details |
| [Spotify](https://www.spotify.com/legal/privacy-policy/) | Your Spotify login, playlist contents, and a cover photo if you choose one | When you connect Spotify or create a playlist |
| [clashfinder](https://clashfinder.com) | Your clashfinder username, a key derived from your private key (never the key itself), and the festival you are looking up | When you look up a festival's timetable |

Your concert record itself is never sent to any of them. The same goes for your tickets.

## Location

The app can read your device's location to work out whether you are at the venue of a
concert on your record, so that it can offer to check you in. This comparison happens on
your device.

**Your location is never transmitted.** It is not sent to any server, and it is not
included in what you exchange with other people. Location access is optional and the app
works without it.

Checking in is different from your location. A check-in says you were at a particular
gig, and at a gig it is passed to phones nearby (see [Gossip](#gossip-at-a-gig)). Your
contacts can therefore learn which gig you were at and roughly when, even though your
coordinates never leave your phone.

On older versions of Android, the system also requires location permission to search for
nearby phones when you exchange cards. The app does not read your location for that.

## Photos

With your permission, the app reads your photo gallery to find pictures taken on the
date of a concert, so it can offer to attach them to that night. Photos you attach stay
on your device.

The one exception: if you create a Spotify playlist for a night and choose one of your
photos as its cover, that photo is uploaded to Spotify and becomes as visible as the
playlist is. That only happens when you pick a cover.

## Tickets

You can share a ticket PDF with the app. It reads the ticket on your phone, including its
barcodes, and keeps them so it can show them at the door. The reading happens entirely on
your device; the ticket is not uploaded anywhere, not sent to your contacts, and not part
of Gossip.

## Exchanging with other people

When you exchange cards with someone nearby, the app sends your display name, your
public key, and your setlist.fm and Spotify usernames if you have set them, directly to
their device over Bluetooth. There is no server in between.

After that, you may share parts of your record — nights, photos, notes — with contacts
you have exchanged with. You choose what is shared. Material a contact shares with you
this way is not passed to your other contacts.

## Gossip at a gig

When you check in at a gig, your phone starts passing small facts about that night to
other phones in Bluetooth range — contacts and strangers alike — and those phones pass
them on to the next phones they meet. There is still no server in between.

What travels:

- the lines of your log for that night (the songs you have ticked off), and
- a request that a contact standing near you confirm you are there, and their
  confirmation.

Each fact is signed with a temporary key made for that one night. A contact can tell a
fact is yours; anyone else carries it without being able to tell who wrote it. What a
stranger's phone learns is a gig, a time and some text, not whose it is. Your name, your
card, your photos, your notes and your location are not part of it.

Your phone does the same for other people: it holds facts it has heard and hands them on
to the next phone it meets, for a short while. What it carries for strangers never
appears in your record, and is not included in your phone's backup.

Gossip runs in the background while you are checked in. On Android a notification says
so for as long as it runs, and has a Stop button. It ends on its own 30 minutes after you
mark the set complete, and never later than 06:00 the morning after. Every fact expires
at the same 06:00. You can block a person's facts from entering your record; blocking is
not visible to anyone else.

Delivery is best effort: nothing guarantees a fact reaches anyone.

## Children

The app is not directed at children and does not knowingly collect anything from them.

## Permissions the app asks for

- **Location** — to offer a check-in when you are at a venue. Optional.
- **Photos** — to find pictures from the night of a concert. Optional.
- **Bluetooth and nearby devices** — to exchange cards with people you meet, and for
  Gossip at a gig you have checked in to. Optional.
- **Nearby Wi-Fi devices** (Android) — to find the phone you are exchanging cards with.
  Optional.
- **Running in the background** (Android: foreground service, connected device) — so
  Gossip can keep running while the app is closed, only while you are checked in, and
  always with a notification. Optional: without a check-in it never starts.
- **Notifications** — for the Gossip notification, and to keep a night's facts handy
  while you fill in setlist.fm's form. Optional.
- **Calendar** — to add a planned gig to your calendar and link back to it. Optional.
- **Camera** — to take a photo for a night. Optional.

Each is requested when the feature that needs it is first used, and refusing one only
disables that feature.

## Deleting your data

Uninstall the app. Everything it stored is removed with it. Material you have already
shared with a contact is on their device and is theirs to delete. Facts passed on at a gig
expire on their own at 06:00 the morning after. A copy in your phone's backup is deleted
through your Google or Apple account.

To disconnect Spotify without uninstalling, use "Log out of Spotify" in Settings, and
revoke the app at [spotify.com/account/apps](https://www.spotify.com/account/apps/).

## What has changed

Version numbers are the app's; the dates are when each version was released.

**26 September 2026** — this policy caught up with the app. Nothing new was built for it;
it now describes what the versions below already did:

- **1.9.0, 20 September 2026: Gossip.** Checking in at a gig starts passing facts about
  that night to nearby phones, in the background, and carrying other people's facts
  onward. On Google Play, 1.10.0 is the first version with Gossip.
- **1.7.0, 5 September 2026: tickets.** The app reads ticket PDFs you share with it and
  keeps their barcodes, on your phone only.
- **1.5.0, 1 September 2026: clashfinder.** Festival timetables are looked up from
  clashfinder with your own account.
- **Before 12 August 2026, and missing from the first version of this policy:** phone
  backups include the app's storage; the calendar, notification and nearby Wi-Fi
  permissions; and location being required by Android for finding nearby phones on
  older versions.

**12 August 2026** — first published.

## Changes

This policy will be updated as the app changes. The date at the top reflects the most
recent revision, the list above says what changed and when, and the full history is
public in the app's repository.

## Contact

Questions about this policy: [dizzi90@gmail.com](mailto:dizzi90@gmail.com)
