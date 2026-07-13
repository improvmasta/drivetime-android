# Getting drivetime onto Google Play

The plan to put the app in testers' hands through Play instead of by emailing them an APK.
**This is a live plan, not a reference** — when the app is on Play and the last box is
ticked, delete this file and keep only what CLAUDE.md → Distribution already carries.

Two things make this more than "upload an AAB": the app self-updates (which Play forbids),
and its data lives on the phone (so a signing mistake destroys it).

---

## 1 · Decisions

### Account type — **decided: personal now, organization before monetizing**

Register a **personal** account, on the `jupiterns.org` Workspace email (a Workspace account
is a Google Account; the domain does not determine account type, and the developer email is
shown publicly, so `lindsay@jupiterns.org` beats a Gmail). **No D-U-N-S needed.** The
*developer name* on the listing may still differ from your legal name, so it can read
"Jupiter Networking Systems".

The thing that makes this easy: **the 12-testers/14-continuous-days rule only gates
production — it does not gate testing.** A personal account can push to internal testing
immediately and hand the app to testers through Play, with auto-updates, today. Chasing a
DBA cert and a D-U-N-S to dodge a rule that doesn't apply to the current goal is weeks of
paperwork for nothing.

|  | Personal | Organization |
|---|---|---|
| D-U-N-S | not required | **required** (needs a business cert / DBA) |
| Testing tracks | unrestricted | unrestricted |
| To reach **production** | 12 testers opted in for 14 *continuous* days, actually using the app | exempt |
| Shown publicly | legal name + country | business name + address |
| Shown publicly **if you monetize** | **your full legal (home) address** | business address |

That last row is what eventually pays for the paperwork: `drivetime/GO_TO_MARKET.md` makes
this a paid product, and monetizing on a personal account publishes your home address. It is
**not a one-way door** — Google supports transferring an app between developer accounts. So:
personal now, and before you ever charge money, re-register the sole-prop DBA → free D-U-N-S
from D&B (allow ~30 days) → organization account → transfer the app.

> Recruiting the 12: **do not use paid "tester exchange" services.** Google tracks whether
> testers actually engage across the 14 days; bought opt-ins get the release rejected or the
> account banned. Twelve real people who open the app.
>
> Workspace caveat: if that Workspace subscription ever lapses, the Google Account — and the
> Play Console with it — goes away. Keep the domain.

### Signing — decide once, get it wrong once
`app/signing/` is committed to a public repo, password and all. Treat it as burned.

- **Let Play generate its own app signing key.** Google holds it; it is never exposed. The
  committed key stays the sideload key, and doubles as Play's *upload* key (which only proves
  the AAB came from us — Play re-signs before serving it).
- **The consequence you must plan for:** a Play install and a sideloaded install then have
  **different signatures**, so Android will not upgrade one into the other. Switching a phone
  from the sideload build to the Play build is **uninstall → reinstall**, and uninstalling
  **wipes every drive on that device**.
- So: **everyone backs up before switching** (Settings → Sync & Backup → Back up now), then
  restores after. Do this while it's you and one or two others — not after 12 people are on it.

> Making the repo private does not undo this: the key is already in public git history. It
> would also break the sideload updater, which reads GitHub Releases unauthenticated.

---

## 2 · Code — done, CI green

Verified on the `play-store` branch ([PR #2](https://github.com/improvmasta/drivetime-android/pull/2)):
tests + lint pass and CI produces **both** artifacts — `drivetime-debug-apk` and
`drivetime-play-aab`. The release step is gated on `main`, so nothing has reached users; the
PR is **not merged**, because merging publishes a GitHub release to sideload users.

Two things the branch caught, neither of them the Play work itself:

- **Robolectric moves with targetSdk.** It resolves its `android-all` jar from *targetSdk*, so
  4.13 (no SDK 35 jar) failed all six Robolectric tests with `initializationError` the moment
  34 → 35 landed. Now on **4.16.1**, which carries 35 *and* 36 — so the August bump won't
  re-break it.
- **Lint rejected the Drive OAuth redirect** (`AppLinkUrlError`: a filter with a path needs a
  host). Google's installed-app redirect is scheme + path with **no** host; adding one changes
  the `redirect_uri` and every Drive sign-in comes back `redirect_uri_mismatch`. Suppressed on
  that one filter — the manifest was right and the check was wrong.

- [x] **`play` build flavor** with the in-app updater **compiled out**
      (`BuildConfig.UPDATER_ENABLED=false`) and `REQUEST_INSTALL_PACKAGES` moved to
      `src/github/AndroidManifest.xml`. Play's *Device and Network Abuse* policy forbids an
      app updating itself outside Play — shipping the updater in a Play build is a takedown,
      not a warning. The SPA hides the update UI when the bridge reports
      `updates_supported=false`.
- [x] **Exact-alarm permissions dropped.** `USE_EXACT_ALARM` is restricted to clock/calendar
      apps. `Control.scheduleResumeAlarm` already asks `canScheduleExactAlarms()` and falls
      back to `setAndAllowWhileIdle`, so "pause tracking for N hours" still auto-resumes — it
      can just drift a few minutes in deep Doze.
- [x] **targetSdk 34 → 35** (34 is below Play's floor and is rejected on upload), compileSdk
      36, AGP 8.10.1, Gradle 8.11.1. Those versions move as a unit or the build fails at
      configuration.
- [x] **CI builds both channels**: `assembleGithubDebug` → APK + GitHub release as before,
      `bundlePlayRelease` → the AAB, as a separate artifact.
- [x] **Push a branch and confirm CI is green.** Green on `play-store`; both artifacts built.

### Deliberately deferred: targetSdk 36 — hard deadline **2026-08-31**
Play requires 36 for new apps *and updates* from that date. Targeting 36 also makes
`windowOptOutEdgeToEdgeEnforcement` (themes.xml) inert, so the WebView would start drawing
under the status bar and the tab bar under the gesture pill. That is a layout change nobody
can verify without a real phone, so it is **not** bundled into the "get to testers" push:
ship on 35 now, then pad the activity roots by the system-bar + IME insets
(`ViewCompat.setOnApplyWindowInsetsListener`) and bump to 36 during the testing window, with
the app on a device. Miss this date and your updates stop being accepted.

---

## 3 · Things Play will not let you skip

- [x] **Privacy policy at a public URL** — live at
      **https://drivetime.jupiterns.org/privacy**. Served from the backend
      (`backend/static/privacy.html` + a route in `spa.py`), *not* from the SPA bundle, so it
      stays out of the APK and — the part that matters — is in `security.PUBLIC_PATHS`, i.e.
      readable by a Play reviewer who has no credential on the box. It covers background
      location as the core function, the three ways data can leave the phone (a server *you*
      run, *your own* Drive, files you export), no ads/analytics/sale, the Google API Services
      Limited Use disclosure, and uninstall-deletes-everything.
- [ ] **Data safety form.** Declare location as collected-but-not-shared (optional, user can
      delete), encrypted in transit. The local-first design makes this genuinely benign.
- [ ] **Background-location permission declaration** + a **prominent-disclosure demo video**.
      This is the most common source of review delay. The justification is core-feature and
      should pass: the app exists to log drives that happen with the screen off.
- [ ] Content rating questionnaire, target audience, ads = none.
- [ ] Store listing: name, short + full description (lift from `README.md`), 512×512 icon,
      1024×500 feature graphic, and ≥2 phone screenshots (see `docs/screenshots/`).
- [ ] Expect scrutiny on **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`** — justify it as core to
      "never miss a drive".

---

## 4 · The track ladder

1. **Internal testing** — up to 100 testers, live in minutes, no full policy review, and
   **available on a personal account with no D-U-N-S**. This is the goal: testers install
   drivetime from the Play Store like any app — no sideloading, no "allow unknown sources",
   and auto-updates. Everything before this section exists to reach this line.
2. **Closed testing** — the only track that runs the **12-testers / 14-continuous-days**
   clock toward production. Start it in parallel; the clock is the long pole, and it does not
   block internal testing.
3. **Production** — apply once 12/14 is met. (Revisit the organization account here, per §1,
   if you're about to monetize.)

---

## 5 · Order of operations

1. Register the **personal** account on the `jupiterns.org` Workspace email (§1). $25, no
   D-U-N-S, no waiting. **← the only thing now blocking internal testing; it's yours to do.**
2. ~~Push a branch; confirm CI builds the AAB (§2).~~ **Done** — green on `play-store`.
3. ~~Write and publish the privacy policy (§3).~~ **Done** — https://drivetime.jupiterns.org/privacy
4. Merge `play-store` to `main` (this also publishes an APK to sideload users), then take the
   AAB from CI. Create the app in Play Console; **let Play generate the app signing key**;
   upload the AAB to **internal testing**.
5. Register Play's app-signing SHA-1 on the Google Drive OAuth client, or Drive backup dies
   silently for every Play install (`drivetime/BACKUP.md`).
6. **Back up your phone, then uninstall the sideload build and install from Play.** Confirm
   restore works, Drive reconnects, and tracking survives a reboot — *before* inviting anyone.
   This is the migration every tester will make; find its sharp edges on your own phone.
7. Invite testers to internal testing. **This is the milestone** — the original goal is met
   here.
8. Open the closed track, get 12 real people opted in, and let the 14-day clock run.
9. Complete the data-safety + background-location declarations; apply for production.
