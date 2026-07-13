# drivetime

### Every drive, logged automatically. Every mile, yours to keep.

drivetime turns your phone into an effortless driving journal — and then turns that journal
into money back at tax time. Get in the car and it starts. Arrive and it stops. No buttons.
No *"did I remember to track that trip?"* Just a complete history of everywhere you drove,
with the miles already added up and ready to claim.

And it runs **entirely on your phone.** No account. No login. No cloud you're renting space
in. Your driving history lives on your device and answers only to you.

<!-- SCREENSHOT · hero — the Drives tab mid-scroll, live HUD visible. Uncomment when added:
<p align="center">
  <img src="docs/screenshots/hero.png" alt="drivetime — the Drives log" width="300">
</p>
-->

---

## Set it once. Forget it forever.

Most trip trackers have one job — to actually be running when you drive — and that's exactly
where they quietly fail. drivetime is built around a single promise:

> **It never forgets a drive.**

It survives reboots, updates, dead zones, and the aggressive battery-killers on Samsung and
Xiaomi phones. If the system tries to shut it down, it comes right back — and if it ever
*was* shut down, it tells you which setting to fix. You will never open the app to find a
drive missing.

---

## Get paid for your miles

Every drive lands in a ledger. Tag it **Business**, **Charity**, **Personal** — or make up
your own tags, with your own rate per mile — and drivetime keeps a running total of what
you're owed, at the current IRS rate. At tax time, export an **IRS-ready CSV** and you're
done.

It does most of the tagging for you:

- **It learns your routes.** Tag the drive to your job site once, and every future drive
  between those two points suggests the same tag — one tap, or apply them all at once.
- **Auto-tagging rules.** "Weekdays before 9am in the work truck → Business." Write the rule
  once and drives classify themselves as they land.
- **Tag from the road.** Hit a tag chip on the live drive display and it's filed before you
  park.
- **Nothing slips through.** The unclassified pile is a to-do list that counts down to zero,
  and a weekly nudge tells you when you've left drives untagged.

<!-- SCREENSHOT · mileage — the ledger with the reimbursable-$ hero + tagged rows. Uncomment when added:
<p align="center">
  <img src="docs/screenshots/mileage.png" alt="The mileage ledger" width="300">
</p>
-->

---

## What it does for you

🚗 **Starts itself — in any car.**
No "start trip" button. The moment you begin moving, drivetime wakes and starts logging
within seconds. Tell it your car stereo's Bluetooth and it *knows* you're driving. Borrow a
car or rent one? A motion sensor catches the start anyway.

🔋 **Sips battery when you're parked.**
All day it runs a whisper-quiet background trace — enough to catch your next drive, gentle
enough you'll never spot it in your battery stats. It only ramps up to precise, second-by-
second tracking once you're actually on the road. It'll even tell you how much battery each
drive cost.

🗺️ **Every drive, mapped.**
Tap any drive for the route drawn on a map and **coloured by your speed** — red where you
crawled, green where you flew — plus a speed-over-time chart, your time on each road, and
the trip's miles, moving time, average, and top speed.

📍 **Mark a stop without unlocking your phone.**
Pull up to a job site, a client, a delivery — tap **Mark** right on the lock-screen
notification and name it later. Marks split a drive into legs you can compare, and a mark
you name once is recognised the next time you stop within 50 yards of it. Your whole drive
lives on that card: speed, miles, and time, glanceable at a red light.

🛣️ **Knows which way is actually faster.**
Drive the same trip a few different ways and drivetime works out where your routes split and
where they rejoin — then tells you which branch actually wins, by how many seconds, and how
your departure time changes the answer. Not an estimate: *your* drives, on *your* roads.

🔧 **Reads your engine, too.**
Plug in a cheap OBD-II dongle and every drive picks up RPM, coolant temp, throttle, fuel
use, MPG, and battery voltage — merged right onto your route. When the check-engine light
comes on, drivetime pulls the trouble code off the dongle and tells you the moment it
appears — no trip to the parts store to find out what your car is complaining about. A
maintenance log that writes itself.

🚙 **More than one car?**
Register each vehicle by its Bluetooth or its VIN and drivetime files every drive against
the right one automatically — so the work truck's miles and the family car's miles never mix.

🚘 **Rides on your dashboard.**
drivetime speaks **Android Auto** — a glanceable grid dashboard (speed, miles, elapsed,
marks, plus RPM and battery with a dongle) with one-tap **Mark** while you drive, and a
"leave by" card when you're parked so you're never late.

🔔 **Tells you what needs attention.** *(optional — it asks first, and stays quiet if you say no)*
A quiet notification when a drive lands, ready to tag. A heads-up when a gas stop split one
drive into two so you can merge them back. A weekly nudge about untagged miles. Every one of
them is optional, and every one of them is a single tap away from being handled.

🔄 **Updates itself.**
No app-store dance. When a new version lands, drivetime offers a one-tap update and keeps
every setting and every drive. Updates come from this repo's public GitHub releases, so they
reach you whether or not you ever configure a server.

<!-- SCREENSHOT · drive detail — speed-heatmapped map + speed chart. Uncomment when added:
<p align="center">
  <img src="docs/screenshots/drive-detail.png" alt="Drive detail — speed-heatmapped route" width="300">
  <img src="docs/screenshots/commute.png" alt="Route comparison" width="300">
</p>
-->

---

## Your data. Your rules.

drivetime is **local-first.** The entire app — maps, stats, mileage, everything — is bundled
right in the download and works with **no server, no signup, and no internet.** Your drives
are computed on your phone and stored on your phone.

**Back it up so a lost phone isn't a lost year.** Point drivetime at your Google Drive or a
folder of your choosing, pick a schedule — daily, weekly, or after every drive — and it
keeps a rolling set of restorable snapshots. Everything is in there: drives, tags, places,
vehicles, settings. Restoring onto a new phone puts it all back exactly as it was.

**Want your drives on your laptop too?** Pair a server you host yourself by scanning a QR
code, and drivetime will sync both ways — while still working perfectly when there's no
signal. Sync is something you *choose*, never a fee you're forced to pay to reach your own
data.

---

## Get driving in under a minute

1. **Install it** — grab the latest APK from this repo's
   [Releases](https://github.com/improvmasta/drivetime-android/releases/latest) page. Approve
   the install once; every update after that installs itself.
2. **Follow the setup walkthrough.** It opens by itself the first time and takes about a
   minute: grant location, tell it about your car, choose a backup, done.
3. **Drive.** That's it — there is no third step.

> **On a Samsung, Xiaomi, or OnePlus phone?** If drivetime shows a battery banner, tap it and
> allow the app to run in the background. Those phones love to "sleep" apps — the one thing
> that can make *any* tracker miss a drive. drivetime notices when it's been restricted and
> takes you straight to the fix.

<!-- SCREENSHOT · setup wizard — the welcome + permissions steps side by side. Uncomment when added:
<p align="center">
  <img src="docs/screenshots/setup.png" alt="First-run setup" width="300">
</p>
-->

---

## For the tinkerers

drivetime is a **controllable instrument.** Your phone's own automation — Samsung Modes &
Routines, Tasker, MacroDroid, Home Assistant — can start it, stop it, force a tracking mode,
change any setting, drop a mark, or react to what it's doing. No extra apps required.

See **[AUTOMATION.md](AUTOMATION.md)** for the shortcuts, intents, and state broadcasts, with
copy-paste recipes. The same cheat-sheet lives in the app under *Settings → Advanced →
Automation*.

Building on it? [CLAUDE.md](CLAUDE.md) is the architecture orientation — how it's wired, how
it ships, and what's next. The web app and optional server live in the sibling
[drivetime](https://github.com/improvmasta/drivetime) repo.

---

**drivetime** — the driving journal that just runs.
