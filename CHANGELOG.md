# What's new in Station to Station

Every release, newest first, written for the people who use the app rather than
the people who build it. Android and iOS share one version number; where a
release only reached one of them, it says so.

Each version opens with a short summary between `<!-- play -->` markers. That
paragraph is what Google Play shows under "What's new" for the release, so it is
plain text and at most 500 characters; `tools/changelog.py` extracts it and the
release workflow refuses a tag whose version has no entry here. The rest of the
section is the fuller account, and becomes the notes on the GitHub release.

**A change that affects privacy comes first.** If a version changes what leaves
the phone, or who it reaches, its Play summary opens with "Privacy:" and says
so, before any feature, and the fuller account has a **Privacy.** paragraph.
Every version listed under "What has changed" in the
[privacy policy](docs/privacy-policy.md#what-has-changed) is checked for this.

## 1.12.0 — 2026-10-05

<!-- play -->
Delete a gig you added by hand and it leaves your contacts' phones at the next sync. One "Add a gig" form, "I was there too" on a contact's gig, and long-press to delete or open on setlist.fm. Syncing on the same WiFi works again, with the Exchange screen open on both phones.
<!-- /play -->

**Taking a gig back.** A hand-added gig you delete leaves your line on a
Contact's phone at the next sync, with any photos they had not yet accepted.

**Adding and joining.** One "Add a gig" form; the date decides whether you were
there or are going. A Contact's gig offers "I was there too".

**Long-press.** Delete a gig, or open it on setlist.fm.

**Fixes.** Syncing with a Contact works again on Android. Their whole setlist.fm
history shows. Hand-added gigs can be checked into. No more doubled rows beside
festivals, and no black screen at launch on iOS.

## 1.11.0 — 2026-09-28

<!-- play -->
Privacy: syncing with a contact on the same WiFi now sends them the list of nights you have been at (date, act, venue), and the artist and date of tickets and hand-added gigs are looked up on setlist.fm. So contacts without a setlist.fm account get a line beside yours, and nights that may be the same gig are marked "maybe" for you to decide. Every control works with TalkBack, plus security fixes.
<!-- /play -->

**Privacy.** Two things now leave your phone that did not before. When you sync
with a Contact on the same WiFi, your phone sends them the list of nights you
have been at — date, act and venue, not what was played — where before it sent
only the photos and notes you had shared. And the artist and date of a ticket,
or of a gig you typed in, are sent to setlist.fm to find that night, including
automatically while the app is open. See the
[privacy policy](https://magnus-encoded.github.io/station-to-station/privacy-policy.html#what-has-changed).

**Contacts without accounts.** Until now, someone you met in person only got a
line beside yours if they kept their concert history on setlist.fm. A Contact's
own nights — the ones they logged by hand as well as the ones they imported —
now travel with the photo sync the next time your phones meet, and draw their
line offline. Their setlist.fm history is fetched only when there is one and it
is actually needed. Photos a Contact shares are offered to you, never filed into
your nights without asking.

**Maybe the same night.** When you and a Contact both logged a gig by hand on the
same date, the app no longer guesses whether it was the same show. The two nights
are marked *maybe*, and from the timelines view you can open them side by side
and answer "Same night" (your lines cross there) or "Not the same".

**setlist.fm, looked up for you.** A ticket, or a gig you typed in yourself, is
now matched against setlist.fm, and every match names the act so a support band
cannot be mistaken for the headliner. Ticket import asks rather than guesses: a
tour name never passes for an artist, and a barcode the app cannot redraw shows
you the original ticket at the door instead.

**Settings becomes the Field.** Settings is drawn as a picture of your timeline:
the services that feed it on the left, the places it goes on the right. Tap one
to see what it unlocks and how to turn it on.

**Accessibility.** TalkBack and VoiceOver now reach every control. Contrast was
audited throughout, status changes are spoken, and screens have proper headings.

**Also:** fixes from a security review, and a launch that holds its splash
screen until your timeline is ready instead of flashing half-loaded screens.

## 1.10.0 — 2026-09-26

*Android only. iOS stayed on 1.9.1 for this release.*

<!-- play -->
Privacy: the first version on Google Play where checking in at a gig passes small signed facts about that night to phones nearby over Bluetooth, in the background, until the night is over (from 1.9.0). Tickets are now read twice and cross-checked, and every barcode on a ticket is kept and redrawn at the door exactly as printed.
<!-- /play -->

**Privacy.** Gossip, added in 1.9.0, reaches Google Play for the first time with
this version, because Play did not accept 1.9.0 or 1.9.1. See 1.9.0 below.

**Tickets you can trust at the door.** A shared ticket PDF is now read twice —
once from the text inside the PDF, once by recognising the printed page — and
the two readings are compared field by field. Only a complete read that both
agree on is added without asking.

**Every barcode, as printed.** A ticket for two people yields two admissions,
and each one is redrawn in the format it was printed in (QR, Aztec, PDF417,
Data Matrix, Code 128, EAN) and checked when it is imported, so a code that
would not scan is found at home rather than at the gate. At the door you step
between them.

**Fixes.** A ticket for a different act on a date you already have asks
instead of creating a second night; sharing a second ticket before confirming
the first queues it instead of replacing it.

## 1.9.1 — 2026-09-25

<!-- play -->
Ticket PDFs shared from another app now arrive on Android. The barcode is read more carefully and kept as the code itself, so the one you show at the door is the one that was printed. A complete ticket for tonight is added straight away. Your own notes on what was played survive a gig being matched to setlist.fm.
<!-- /play -->

- Sharing a ticket PDF into the app from another app now works on Android.
- Ticket barcodes are read more thoroughly and stored as the code they carry,
  so the one the app shows at the door matches the printed one. A barcode type
  the app cannot yet redraw says so, and asks you to bring the PDF.
- A complete ticket for tonight is added on the spot, on both platforms.
- Matching a gig to its setlist.fm entry keeps your own log of the night.
- iOS: the refresh view always has a way out. Sideloaded installs find shared
  tickets again.

## 1.9.0 — 2026-09-20

<!-- play -->
Privacy: checking in at a gig now passes small signed facts about that night, such as your song log, to phones nearby over Bluetooth, in the background, until 06:00 the next morning at the latest. A friend nearby can witness your check-in, and the gig shows who else is there (experimental). Photos from a whole festival or run of nights can be seen together.
<!-- /play -->

**Check-ins that reach your friends without the internet (experimental).**
Checking in at a gig now passes quietly from phone to phone over Bluetooth
between Contacts — people you have swapped cards with in person — even in a
venue with no signal. A Contact standing nearby can witness your check-in, the
gig shows who else is here, and afterwards it remembers who you were seen with.
Everything is signed, you can see who said what, and you can block anyone.

**Privacy.** Phones in range pass these facts on, contacts and strangers alike.
A stranger's phone learns a gig, a time and some text, never whose it is; your
name, photos, notes and location are never part of it. It runs only once you
have checked in, and on Android a notification with a Stop button says so. This
replaces the earlier version of the feature; phones on older versions will not
hear from this one. Not yet field-tested at a real show.

- A festival or a run of nights shows all of its photos and clips together.
- iOS: moving to a new phone now brings your Spotify and setlist.fm accounts
  across, as Android already did.
- When the app's shared setlist.fm key has used up its daily allowance, you are
  told so, and offered how to add your own key, rather than left waiting.
- The Android download on GitHub is smaller: one APK per processor type.

## 1.8.0 — 2026-09-07

<!-- play -->
A gig you logged yourself now joins a friend's night at the same venue on the same date, so your lines cross there, whichever of you gave the night its name first.
<!-- /play -->

A night you added by hand and a friend's night at the same place on the same
date are now recognised as one, and your two lines cross there — no matter which
of you logged it first or which name it went by.

## 1.7.2 — 2026-09-06

<!-- play -->
Ticket reading copes with plain, unstyled tickets and Norwegian month names. You can open a ticket PDF directly with Station to Station, or follow a direct link to one.
<!-- /play -->

- Plain tickets without styled text are understood, as are Norwegian month names.
- A ticket can be opened with the app from a file manager or mail attachment,
  or from a direct link.

## 1.7.1 — 2026-09-06

<!-- play -->
Tickets are named after the event, not the banner printed above it. On the day, the gig screen holds your ticket's QR code up until you are inside.
<!-- /play -->

- A ticket takes its name from the event rather than a promoter's banner above it.
- On the day of the gig, the gig screen keeps your ticket's code ready until
  you are inside.

## 1.7.0 — 2026-09-05

<!-- play -->
Privacy: the app now reads ticket PDFs you share with it and keeps their barcodes, on your phone only. Share a ticket PDF and it becomes a night on your timeline, after you confirm it. On the day, your ticket's QR code is on the gig screen. Moving to a new phone tells you whether your accounts arrived.
<!-- /play -->

**Tickets become nights.** Share a ticket PDF to Station to Station on either
platform and it reads the artist, venue and date and offers the night for you
to confirm. On the day, the ticket's code is on the gig's own screen. The
ticket is read and kept on your phone only; it is never uploaded or sent to
your Contacts.

- Moving to a new phone reports whether your accounts came across.
- The hand-typed festival poster is gone; festival timetables (1.5.0) do the
  job properly.
- The setlist button tells TalkBack what it opens.

## 1.6.0 — 2026-09-03

<!-- play -->
Swapping cards in person no longer needs a setlist.fm account. Lines you hide stay hidden between launches, and the list of names shows your most recent people first. Pulling down for the planning options now works the same with TalkBack.
<!-- /play -->

- **Swap cards without setlist.fm.** The Exchange screen, where two people
  standing together become Contacts, now works for someone who has never
  used setlist.fm.
- Lines you hide from the timelines view stay hidden after a restart, and the
  names beneath it are ordered by who you added most recently, with the rest
  behind "+ N more".
- Pulling down at the top of the timeline and using TalkBack now open the same
  planning options.

## 1.5.1 — 2026-09-02

<!-- play -->
The festival planner arrives on iOS. Back and forward now move through the planner as you would expect. Green is kept for Spotify alone; the rest of the app is drawn in its own amber. iPhone and Android now report the same version.
<!-- /play -->

- The festival planner from 1.5.0 arrives on iOS.
- Back and forward work through the planner.
- Green now means Spotify and only Spotify; the rest of the app wears amber.
- iOS reports the same version number as Android again.

## 1.5.0 — 2026-09-01

<!-- play -->
Privacy: festival timetables are fetched from Clashfinder using your own Clashfinder account, if you add one. Plan a festival: pick the acts you are going to see, and each becomes a gig on your timeline, with clashes between stages shown.
<!-- /play -->

**Departures: the festival planner.** Fetch a festival's published timetable
from Clashfinder with your own account, pick the acts you mean to see, and
each one becomes a gig on your timeline straight away. Clashes between stages
are shown as you choose. Your Clashfinder private key stays on your phone; only
a key derived from it is sent.

## 1.4.2 — 2026-08-29

<!-- play -->
Type in a night you were at, or one still ahead, by hand, with spelling suggestions for the artist. Your card's QR code is always visible on the Exchange screen. Photos appear at timeline zoom, and a recording opens with the setlist as its index. iOS catches up on logging nights to setlist.fm, a welcome screen and hiding lines.
<!-- /play -->

- **Nights typed in by hand**, past or future, with spelling suggestions for
  the artist's name.
- Your own card's QR code is always on the Exchange screen, no toggle needed.
- A night's photos show at timeline zoom. A clip gets a cover frame, and a
  night's recording opens with its setlist as an index into it.
- You can no longer "add" a Contact's night as if it were yours.
- **iOS catches up:** a first-run welcome, filing a night with setlist.fm,
  deleting a night from its own screen, tapping a name to hide a line, and a
  setlist that reads in the same order as on Android.

## 1.4.1 — 2026-08-26

<!-- play -->
Photos now really do move between friends' phones on the same WiFi; a fault had stopped every transfer. Moving to a new phone is fixed the same way. Turning the phone to walk through a night now walks a whole run of gigs. Many TalkBack fixes. A deleted gig no longer lingers in the setlist list.
<!-- /play -->

- **Photo sharing between Contacts works.** A fault in how the two phones
  secured their connection meant every WiFi transfer failed silently. Fixed, and
  checked between Android and iPhone. Moving to a new phone had the same fault
  and the same fix.
- The landscape walk takes in a run of gigs, not just one night.
- TalkBack: labels, states and touch targets fixed across the app.
- A deleted gig no longer stays behind in the setlist list.
- iOS: pinching is smoother, and the back swipe works on the gig and
  programme screens.

## 1.4.0 — 2026-08-20

*Includes 1.3.3, which was not released separately.*

<!-- play -->
Turn the phone sideways on a gig and walk down the night: the setlist runs ahead, photos stand either side, and whatever you walk up to is what a tap opens. Move everything to a new phone from a screen on both platforms. A link can no longer make someone your contact; only meeting in person does. Tap a name to hide that line.
<!-- /play -->

**Turn the phone and walk down the night.** Landscape on a gig is a walk: the
setlist runs ahead of you, photos stand to either side, each Contact's floor
line has its own colour, and your notes wait at the end. Dragging is walking,
and whatever you have walked up to is what a tap opens.

**Move to a new phone.** A screen on both platforms: choose what to bring, scan
the code on the old phone, watch it arrive. What arrived is kept even if you
stop halfway.

- **A link cannot make a Contact.** A crafted link could previously grant its
  sender the same access as someone you had met. Links now only let you follow
  a line; becoming Contacts takes two people in the same place.
- A card asks before it changes a Contact you already have.
- Tap a name under the timelines view to take that line out of the picture.
- Fixed a crash when two Android phones looked for each other on WiFi at once.

## 1.3.2 — 2026-08-19

<!-- play -->
Two Android phones swapping cards over the fast nearby connection now actually become contacts, so photo sharing between them can begin.
<!-- /play -->

Two Android phones swapping cards over Nearby now become Contacts as they
should, so the WiFi photo sharing from 1.2.7 has someone to work with.

## 1.3.1 — 2026-08-19

<!-- play -->
iPhones can now share a night's photos with contacts on the same WiFi, with each other and with Android phones. No server is involved.
<!-- /play -->

Photo sharing between Contacts arrives on iOS, between iPhones and between an
iPhone and an Android phone. Nothing passes through a server.

## 1.2.7 — 2026-08-18

<!-- play -->
Photos from a night you shared with a contact now travel directly between your phones when you are on the same WiFi. No server ever holds them.
<!-- /play -->

**Photos, phone to phone.** When two Contacts are on the same WiFi, the photos
and clips each of you took on a night you were both at move straight across,
and only those. The phones prove to each other who they are first; no server is
involved at any point.

## 1.2.6 — 2026-08-18

<!-- play -->
"Are you here?": arrive at a gig you're going to and the app offers to check you in. See your night the way a contact would, with what you keep private made visible. Log a night song by song, gaps included. Notes on a night are kept alongside its photos. Pulling down on a gig refreshes the right thing for when it is.
<!-- /play -->

- **Are you here?** At a gig you are going to, the app checks your location
  once and offers to check you in, or lets you say "I'm here" yourself.
- **The light switch.** See a night as your Contacts would, with what you keep
  back shown as a count, never the photo.
- **Song by song.** Log what was played as it happens, with gaps for the songs
  you could not name, and close the log when you are sure.
- A written note on a night is kept with its photos, private or shared like them.
- Pulling down on a gig asks the right source for the right time: a night
  weeks away, mid-set, and one from decades ago no longer make the same request.
- iOS catches up on the festival noticeboard, careful Spotify song matching,
  planned gigs with calendar and Maps, and a night's photo at the top of its
  screen. The iOS build is now downloadable from the GitHub release page.

## 1.2.5 — 2026-08-17

<!-- play -->
Pulling down for the planning options now lets go properly, and the options are no longer shown twice. Updated for the latest Android version Google Play requires.
<!-- /play -->

- Pulling down at the top of the timeline now closes again when released, and
  its options are shown once.
- Built for the Android version Google Play now requires.

## 1.2.4 — 2026-08-16

<!-- play -->
Importing from setlist.fm moves into the pull-down at the top of the timeline. A brand-new setlist.fm account with no shows yet is no longer reported as a wrong username. A gig you add now appears straight away, and the contact view no longer dims every night the same.
<!-- /play -->

- Bulk import from setlist.fm is the deepest option when you pull down at the
  top of the timeline.
- A new setlist.fm account with no shows marked yet imports fine instead of
  being called a typo.
- Adding a gig you are going to shows it at once, not after a restart.
- Seeing your line as a Contact would no longer dims every night the same,
  whatever it holds back.

## 1.2.3, 1.2.2, 1.2.1 — 2026-08-15

<!-- play -->
Release packaging fixes. Nothing changes in the app.
<!-- /play -->

Release packaging only: signing checks, and an installable APK attached to
each GitHub release. Nothing changes in the app.

## 1.2 — 2026-08-15

*The first release on Google Play.*

<!-- play -->
Your concert life as one continuous line. Import the gigs you have been to from setlist.fm, see festivals fold into single stops, and zoom from your whole history down to one night. Follow a friend's line and see where you crossed. Swap cards in person to share photos. Turn a setlist into a Spotify playlist, for the few accounts Spotify allows during the test.
<!-- /play -->

**Station to Station** draws the concerts you have been to as a single line,
newest at the top. Festivals fold into a single stop and open when you zoom in;
zoom out and friends' lines run beside yours, becoming one wherever you were at
the same show.

- **Your timeline** from setlist.fm, kept on your phone, plus gigs still ahead.
- **Followed lines**: anyone's public setlist.fm history, drawn beside yours.
- **Contacts**: people you swap cards with in person, over Bluetooth, Nearby or
  a QR code. The only people your photos can ever reach.
- **A night's own screen**: its setlist, your photos and clips, a log of what
  was played, notes, and the recording's song-by-song timestamps.
- **Playlists**: every song of a setlist found on Spotify, checked by you, and
  saved as *year – artist – venue* with a photo from the night as its cover.
  Spotify lets an app like this one serve only five accounts, so this is
  limited to a handful of testers; everything else works for everyone.

There is no server. Everything stays on your phone, or moves directly between
phones.
