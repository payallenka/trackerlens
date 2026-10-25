# Review log

TrackerLens is reviewed in iterations. Each iteration covers three areas, records what was
found, what was changed, and how the change was verified. The loop ends when an iteration finds
no open High or Medium issue in any area.

## Exit criteria

| Area | Done when |
|---|---|
| UI/UX | No High/Medium finding against Nielsen's 10 usability heuristics and Material accessibility basics (labels for icons and controls, 48 dp touch targets, meaning never conveyed by colour alone, readable at 200% font and in dark mode). Every issue reported by a user is re-tested on a device. |
| Software engineering | No High/Medium finding from an independent code review. Build has no compiler warnings, unit tests pass, core logic has tests. |
| Security | No High/Medium finding from an independent security review covering OWASP MASVS storage, platform, code and privacy areas, untrusted-input parsing, build and supply chain. No INTERNET permission in the release APK; no secrets in the repository. |

Severity: **High** = wrong results, data loss, security exposure or a blocked core task.
**Medium** = misleading output, a confusing core flow or a reliability risk. **Low** = polish.

---

## Iteration 1 (2026-10-25)

### UI/UX

Method: heuristic review of every screen on an Android 16 emulator (light, dark, 200% font) and
on a real phone; QA test pass; issues reported by the owner while using the app.

| # | Sev | Finding | Fix | Verified |
|---|---|---|---|---|
| U1 | High | Score was dominated by permissions: Telegram had 0 trackers but scored 12/100 | Score now weighs trackers by category (ads/profiling/location 15, identification 10, analytics 8, crash reporting 3); permissions shown but not scored. Risk levels None/Low/Medium/High | Unit test `scoreWeighsTrackersByCategoryAndIgnoresPermissions`; emulator: Telegram now "No trackers" |
| U2 | High | First scan showed "Privacy score 100/100, 0 of 0 apps" before anything was scanned | "Scanning your apps… N of M checked" until results exist | Emulator, fresh install |
| U3 | Medium | Search box sat mid-page; the keyboard covered the results (owner report) | Search bar is a sticky header; while searching or filtering, the dashboard hides so results sit directly under it; clear button; keyboard Search action closes it | Emulator |
| U4 | Medium | No in-app place for alerts; opening the app left the status-bar notification (owner report) | Bell icon with unread badge opens an Alerts screen; opening the app clears the notification; tapping the notification opens Alerts | Emulator + phone |
| U5 | Medium | Notification text was cut off (owner report) | Expandable BigText notification listing every added tracker and permission | Phone: `template=BigTextStyle` |
| U6 | Medium | "Include system apps" seemed to do nothing (owner report). Log showed it worked (170 → 458 apps) but gave no feedback | Confirmation snackbar ("Now showing 253 apps, including 248 system apps"), "System" tag on rows, explanation under the switch | Emulator |
| U7 | Medium | Red text alone marked added trackers; leftover history for an uninstalled test app (owner report) | Added/removed lines use a warning or check icon plus words; history is pruned to installed apps | Emulator |
| U8 | Medium | Phone score averaged over system apps, so enabling them diluted it (99 "Mostly private" with 8 tracker apps) | Phone score covers apps the user installed and is labelled so | Emulator: "Privacy score of apps you installed" |
| U9 | Medium | Switch and sort chips had no accessible label; a screen reader said "switch, off" | Whole row is one labelled toggle (`Role.Switch`); icons have content descriptions; section titles are headings | uiautomator dump |
| U10 | Low | Bare number on each row had no label | Replaced by a risk badge with icon and text | Emulator |
| U11 | Low | Search with no matches showed an empty list | "No apps match …" message (also for tracker filter) | Emulator |
| U12 | Low | "1 trackers", "1 sensitive permissions" | Pluralisation helper | Emulator |
| U13 | Low | Long permission lists buried the content | First 6 shown, "Show all N" | Emulator |
| U14 | Low | No sign the background checks run | "Checked just now / 5 minutes ago" | Emulator |
| U15 | Low | Most striking insight (which tracker is in most apps) was not shown | "Most common trackers on your phone"; tapping one lists the apps containing it | Emulator |
| U16 | Low | Selected app lost if Android killed the process | Selection kept in `SavedStateHandle` | Code |

QA pass (same iteration, emulator): first-run permission prompt, Back navigation, rotation,
Manage permissions and Share intents, same-version reinstall (no false alert), uninstall,
sort orders, double-tap Rescan (one scan), system toggle on/off (no false alerts), background job
constraints, granted permissions (no INTERNET), dark mode at 200% font: all passed. No crashes.

### Software engineering

Method: independent read-only review of every Kotlin file by a separate reviewer, then fixes,
unit tests, and on-device checks. 3 High, 7 Medium, 8 Low found.

| # | Sev | Finding | Fix | Verified |
|---|---|---|---|---|
| E1 | High | An incomplete scan was reused until the app updated, hiding trackers and disabling alerts for it | Incomplete reads are retried when the failure was temporary (I/O, memory); deterministic failures are not re-read every scan | `ReconcilerTest.needsScanRules`; emulator: broken APK read once, then 0 re-reads |
| E2 | High | Turning off "Include system apps" deleted system apps' snapshots and history | All installed apps' data is kept; only the view is filtered; data is dropped only on uninstall | `ReconcilerTest.mergeKeepsHiddenAppsAndDropsUninstalledOnes` |
| E3 | High | Any storage or parse exception crashed the app | Repository throws typed errors; ViewModel turns them into a message; worker retries on I/O errors | Code review |
| E4 | Medium | DEX read used 2-3x the file size in memory; size cap documented wrongly | Exact-size bounded read, 48 MB cap with correct rationale | `ApkScannerTest.readBoundedDoesNotTrustDeclaredSize` (found and fixed a cap bug) |
| E5 | Medium | A catalog update arriving with an app update produced false "tracker added" alerts | Tracker differences only when catalog/scanner key unchanged and both scans complete | `ReconcilerTest.trackerChangesNeedSameCatalogAndCompleteScans` |
| E6 | Medium | Lists re-filtered and re-sorted on every recomposition and progress tick; progress could go backwards | Derived values computed once when inputs change; progress throttled and monotonic | Code review |
| E7 | Medium | Icons and permission labels reloaded from PackageManager on scroll and recomposition | LRU icon cache; permission-label cache | Code review |
| E8 | Medium | Every resume re-decoded the JSON and rescanned | State held in memory under the mutex; full rescan on resume only if older than 5 minutes | Emulator log |
| E9 | Medium | Layering: worker depended on UI (`Prefs`); ViewModel built its own dependencies | `data/Settings`, `work/AlertNotifier`; ViewModel gets repository, settings and notifier through a factory | Build |
| E10 | Medium | Only 5 unit tests; no tests for change detection, storage or hostile input | 24 tests across reader, matcher, scanner, reconciler, store, markers, score | `./gradlew :core:test` 24/24 |
| E11 | Low | Notification permission re-requested on rotation; screen lost after process death | Requested only on fresh start; screen kept in `SavedStateHandle` | Code review |
| E12 | Low | A new notification replaced older unseen alerts | Notification lists every unseen alert | Emulator |
| E13 | Low | Alerts marked seen while hidden behind an app detail | Marked seen only while the Alerts list is visible | Code review |
| E14 | Low | "Most trackers" sort actually sorted by score | Renamed "Highest risk" | Emulator |
| E15 | Low | Reused snapshots kept a stale system flag | Refreshed on reuse | Code review |
| E16 | Low | Hard-coded English strings; duplicated plural helpers | **Accepted for now**: English-only first release; move to string resources before adding translations | n/a |
| E17 | Low | Matcher allocated a substring per segment | Character trie, one walk per class, no allocation | `TrackerMatcherTest.deepNamesAreLinear` |
| E18 | Low | A long first scan interrupted by the 10-minute worker limit restarted from zero | Results checkpointed every 25 apps | Code review |

### Security

Method: independent read-only review against OWASP MASVS (storage, platform, code, privacy),
untrusted-input parsing, build and supply chain; then fixes and an on-device attack test.
2 High, 5 Medium, 3 Low found.

| # | Sev | Finding | Fix | Verified |
|---|---|---|---|---|
| S1 | High | A hostile installed app could crash or stall every scan (huge class names, a million duplicate class entries, quadratic matching) | Descriptors capped at 1 KB and ASCII-only; each type decoded once; linear trie matcher; per-APK containment including out-of-memory; crash markers quarantine an APK version that killed the scanner | Hostile APK on emulator (37 MB DEX, 5 MB class name, 1,000,000 duplicate entries, real tracker hidden inside): no crash, tracker found, 0 re-reads afterwards. Tests: `skipsHostileDescriptorsWithoutFailing`, `decodesRepeatedTypeOnlyOnce`, `InFlightMarkersTest` |
| S2 | High | The size cap trusted the zip's declared size; zip bombs could exhaust memory | Hard limit on bytes actually read, independent of declared size | `readBoundedDoesNotTrustDeclaredSize`; hostile APK (45 KB compressed, 37 MB DEX) |
| S3 | Medium | Notifications showed installed apps and permissions on the lock screen and on paired watches | `VISIBILITY_PRIVATE`, generic public version, `setLocalOnly` | Emulator `dumpsys`: `vis=PRIVATE`, `publicVersion`, `LOCAL_ONLY` |
| S4 | Medium | Unbounded attacker-controlled text (evidence, labels) stored and shown | Evidence ≤ 200 chars, labels ≤ 100, version names ≤ 64 | `evidenceIsBounded` |
| S5 | Medium | Reads outside the lock could race a write (AtomicFile on Android 9/10) | All state access under one mutex; own atomic store (fsync + atomic rename) | `SnapshotStoreTest` |
| S6 | Medium | A corrupt or newer/older file silently wiped history | Schema version; every field has a default; unparseable file moved to `.corrupt`, never overwritten; read errors abort instead of saving over good data | `corruptFileIsSetAsideNotOverwritten`, `loadsFilesWrittenWithFewerFields` |
| S7 | Medium | CI: no token permissions, actions pinned by tag, wrapper unused, release APK not checked | `permissions: contents: read`; actions pinned to commit SHAs; `./gradlew` with wrapper validation; INTERNET check on debug and release | Workflow file; wrapper jar SHA-256 matches Gradle's published value |
| S8 | Low | `validateDistributionUrl=false` | Removed; distribution checksum kept | File |
| S9 | Low | Debug and release shared one application ID | Debug is `app.trackerlens.debug` | Emulator |
| S10 | Low | Privacy policy marked draft without contact; `.p12/.pfx/.pem` not ignored | Policy finalised (GitHub issues contact, notification behaviour); ignore patterns added | Files |

Accepted: Gradle dependency verification metadata is not committed, because AGP downloads
platform-specific artifacts and a Linux-generated file would break builds on macOS and Windows.
Versions are pinned exactly and the wrapper is checksum-verified.

### Result of iteration 1

All High and Medium findings fixed and verified. Iteration 2 re-reviews the changed code.

---

## Iteration 2 (2026-10-25)

Three new independent reviewers (UI/UX with screenshots, engineering, security) re-read the
changed app. Totals: 0 High, 12 Medium, 22 Low.

### UI/UX (0 High, 6 Medium)

| # | Sev | Finding | Fix | Verified |
|---|---|---|---|---|
| U17 | Medium | Back left the app while a search or tracker filter was active | Back clears search/filter first, then an empty focused search box, then the list scrolls back to the dashboard | Emulator: search → Back ×3 → dashboard at top; tracker filter → Back → dashboard |
| U18 | Medium | Green check "No trackers" beside "24 sensitive permissions" read as "safe" | Neutral grey "No known trackers" with an info icon; warning icon only for Medium/High | Emulator screenshot |
| U19 | Medium | Nothing explained the score | Info button opens "About the privacy score" (scale, weights, permissions not scored, how to improve) | Emulator: dialog text dumped |
| U20 | Medium | Detail page showed the raw package name and code path; "Manage permissions" opened App info; no next step | Plain header, "Open app settings", a "What you can do" card; package name and evidence behind "Show technical details" | Emulator |
| U21 | Medium | Most-common-tracker rows didn't look or announce tappable | Chevron and `onClickLabel` "Show apps with …" | Emulator |
| U22 | Medium | Search matched package names, so results looked random | Matches the visible app name only | Emulator: "tele" → Telegram, "sig" → Signal |
| U23 | Low | Missing headings; score ring and headline read as separate stops | Title and score card are headings; score, meaning and freshness read as one stop | uiautomator: "Your apps' privacy score 97 out of 100, Mostly private, Checked just now" |
| U24 | Low | Stat tiles squeezed at large font | Stack vertically above 130% font | Emulator, dark mode at 160% |
| U25 | Low | "Low risk" used the same warning icon as High | Info icon for None/Low | Emulator |
| U26 | Low | Long label "Privacy score of apps you installed" | "Your apps' privacy score" | Emulator |

Review-process note: three of the five screenshots given to the UX reviewer showed the home
screen because the capture script's Back key left the app. Captures now verify the app is in the
foreground before each screenshot.

### Software engineering (0 High, 4 Medium)

| # | Sev | Finding | Fix | Verified |
|---|---|---|---|---|
| E19 | Medium | Crash quarantine lost if the process died before the first checkpoint (two bad APKs could crash-loop) | Unfinished reads are saved before the markers are cleared | Code; `InFlightMarkersTest` |
| E20 | Medium | Any process death (swipe away, low memory) quarantined innocent apps permanently | Strike count: quarantine only after two unfinished reads of the same version; cleared after a clean read or an update | `quarantineNeedsTwoUnfinishedReadsOfTheSameVersion`, `mergeClearsOneStrikeAfterACleanReadButKeepsQuarantine` |
| E21 | Medium | System/scope flags went stale for apps outside the current view (e.g. Chrome after uninstalling its updates) | `merge` refreshes flags of every kept snapshot from the platform | `mergeKeepsHiddenAppsDropsUninstalledOnesAndRefreshesFlags` |
| E22 | Medium | A scan that read nothing rewrote the whole file about n/25 times | Checkpoint only after a chunk that read something | Code |
| E23 | Low | "Notified" mark advanced even if the notification wasn't posted | `show()` returns whether it posted | Code |
| E24 | Low | Dangling selection after uninstall blocked "alerts seen" | Selection dropped when the app is gone | Code |
| E25 | Low | Turning "Include system apps" off rescanned | Uses stored results (no reads) | Code |
| E26 | Low | Unseen-alert rule duplicated in UI and worker; unused helper | `core.Alerts` used by both; tested | `unseenAlertsAndNotificationRules` |
| E27 | Low | Open screen didn't show results from a background check | Repository publishes a revision; the ViewModel reloads from memory | Code |
| E28 | Low | Catalog could be parsed on the main thread for the footer | Catalog version comes with scan results | Code |
| E29 | Low | `schemaVersion` written but never checked | Newer schema is set aside like a corrupt file | `newerSchemaIsSetAsideAndBackupsAreBounded` |
| E30 | Low | Persistent I/O errors retried every scan | **Accepted**: rare, bounded by the 5-minute/12-hour scan cadence |  |
| E31 | Low | Scan orchestration loop has no unit test | **Accepted for now**: rules it uses are tested in core; covered by on-device runs |  |

### Security (0 High, 2 Medium)

| # | Sev | Finding | Fix | Verified |
|---|---|---|---|---|
| S11 | Medium | Many distinct types aliasing one 1 KB name bypassed per-type dedup: a 48 MB DEX could produce ~1.4 GB of strings | Names are streamed into the matcher through one reused buffer (nothing retained); total decoding, including failed decodes, is capped at the file's size | Tests `manyTypesAliasingOneLongStringAreBoundedByFileSize`, `failedDecodesAlsoCountAgainstTheBudget`; emulator: 44.6 MB DEX with 1,300,000 aliasing types scanned in 1.3 s, no OOM, the hidden tracker still found, app ~141 MB PSS |
| S12 | Medium | Ordinary process death quarantined innocent apps, which then scored 100 | Strike count (E20); apps not fully read and showing no trackers are "Not fully scanned" and excluded from the score | Unit tests; emulator |
| S13 | Low | Requested permissions stored without limit | At most 512, each ≤ 255 chars | Code |
| S14 | Low | No total decompression budget per app; scan not cancellable | 512 MB of code per app; cancellation checked between DEX files | `totalCodePerAppIsBudgeted`, `stopsWhenAsked` |
| S15 | Low | Second corruption overwrote the first backup; user not told | Timestamped backups, newest 3 kept; user sees a message | `newerSchemaIsSetAsideAndBackupsAreBounded` |
| S16 | Low | App labels could inject fake lines (newlines, bidi controls) into notifications | Control, format and separator characters removed; truncation on code-point boundaries | Code |
| S17 | Low | CI: credentials persisted after checkout; no job timeout | `persist-credentials: false`, `timeout-minutes: 30` | Workflow file |
| S18 | Low | No fsync of the directory after rename | **Accepted**: a power loss can only bring back the previous complete file, never a corrupt one |  |

### Result of iteration 2

All 12 Medium findings fixed and verified; 32 unit tests pass; both builds have no warnings.
Iteration 3 re-reviews all three areas.

---

## Iteration 3 (2026-10-25)

Fresh reviewers again; screenshots captured with a foreground check. Totals: 0 High, 4 Medium
(one shared by two reviewers), 18 Low. Security: 0 High, 0 Medium for the first time.

### UI/UX (0 High, 1 Medium)

| # | Sev | Finding | Fix | Verified |
|---|---|---|---|---|
| U27 | Medium | A partly read app that found a tracker showed a confident "Low risk" and counted toward the score | Badge reads "Low risk · partial"; all partial reads are left out of the phone score; the score dialog explains "partial" | Emulator: "Low risk · partial"; score 94 → 96 |
| U28 | Low | Detail score ring was a bare number | "Privacy score" caption | Emulator |
| U29 | Low | Alert cards didn't look tappable | "See app details ›" | Emulator: opens the app |
| U30 | Low | Every added tracker looked equally alarming | Category shown: "Tracker added: Google Firebase Analytics (Analytics)" | Emulator |
| U31 | Low | Category labels looked like links | Neutral colour | Emulator |
| U32 | Low | Stacked stat tiles filled the screen at large font | One line per figure above 130% | Code |
| U33 | Low | Updated system apps shown with the toggle off, unexplained | Switch text says updated system apps are always shown | Emulator |
| U34 | Low | No sign which alerts are new | "New" badge for alerts unseen when the screen opened | Code |

### Software engineering (0 High, 3 Medium)

| # | Sev | Finding | Fix | Verified |
|---|---|---|---|---|
| E32 | Medium | A checkpoint during a catalog/scanner change discarded stored results of apps not yet re-read, and hidden system apps entirely | Each snapshot records its own scan key; snapshots are never discarded for an old key, only re-read; tracker diffs require equal keys | `mergeKeepsSnapshotsFromAnOldCatalogAndBoundsHistory`; emulator: scanner upgrade re-read 9/9 apps, history kept |
| E33 | Medium | Scroll position lost after rotation and after Back from details (scroll-to-top also ran on first composition) | List state hoisted; scroll to top only on leaving search/filter | Emulator: scroll, open app, Back → same position |
| E34 | Medium | An innocent app read in parallel with a crashing APK could be quarantined | Apps with one strike are read alone before the parallel pass (same as S19) | `oneStrikeMakesASuspectAndReinstallResetsIt` |
| E35 | Low | The "history couldn't be read" notice could be consumed by the worker | Kept until the UI acknowledges it | Code |
| E36 | Low | Quarantine survived a scanner change | Crash records carry the scan key and install time | Test |
| E37 | Low | Notification posted while the Alerts screen was open | Cleared whenever alerts are marked seen | Code |
| E38 | Low | Reports rebuilt on every resume | Result cached per revision and scope | Code |
| E39 | Low | Duplicate/misplaced KDoc, misleading comment | Fixed | Code |
| E40 | Low | No tests for the scan loop or ViewModel | **Accepted for now**: all decision rules are in core and tested (36 tests); loop verified on devices |  |

### Security (0 High, 0 Medium)

Reviewer verified S11 (decode budget bounded for every hostile layout), streaming, S13–S17.

| # | Sev | Finding | Fix | Verified |
|---|---|---|---|---|
| S19 | Low | A crash gave strikes to both parallel reads, letting a hostile APK get a neighbour quarantined | Suspects are read alone (E34) | Test |
| S20 | Low | Permission labels (declarable by any app) were not cleaned like app labels | Shared `TextSafety.clean` in core for both | `TextSafetyTest` |
| S21 | Low | Failure list unbounded for crafted archives | First 20 + count; names truncated | Code |
| S22 | Low | Newer-schema file could be rotated away; same-version reinstall stayed quarantined | Newer schema kept as `.schema-N`; crash records include install time | `newerSchemaIsKeptOutsideTheRotation`, test |
| S23 | Low | Store screenshots showed the old UI | Retaken before publishing | Release step |

### Result of iteration 3

All Medium findings fixed and verified; 36 unit tests pass; both builds have no warnings.
Iteration 4 confirms.

---

## Iteration 4 (2026-10-25)

Totals: 0 High, 3 Medium, 15 Low. **UI/UX: 0 High, 0 Medium.**

### UI/UX (0 High, 0 Medium)

All round-3 fixes confirmed. Lows fixed: footer read "version ." during the first scan; partial-scan
note no longer error-red; "added" lines red only for ad/profiling/location trackers (amber
otherwise); sort chips wrap at large font; score explanation also on the detail screen; history
cards don't repeat the app name; app rows announce "Open details".

### Software engineering (0 High, 2 Medium)

| # | Sev | Finding | Fix | Verified |
|---|---|---|---|---|
| E41 | Medium | E36/S22 didn't work end to end: the "does this crash record apply" rule was written three times and had drifted, so an app stayed "Not fully scanned" after a reinstall or scanner change | One rule, `CrashRecord.appliesTo`, used by `merge`, `isSuspect` and `isQuarantined`; a quarantined snapshot stays retryable so it's read once the quarantine lifts | `quarantineLiftsEndToEndAfterReinstallOrScannerChange` (sequence test) |
| E42 | Medium | Alerts could be marked seen, and their notification cancelled, while the app was in the background on the Alerts screen | Marked seen only while the activity is visible (`onResume`/`onStop`) | Code |
| E43 | Low | Corruption notice held only in memory | Persisted in the stored state until acknowledged | Code |
| E44 | Low | `StoredState.scanKey` no longer read | Documented as informational | Code |

### Security (0 High, 1 Medium)

| # | Sev | Finding | Fix | Verified |
|---|---|---|---|---|
| S24 | Medium | S23 was logged before it was done: store screenshots and description showed the old UI; README referenced a missing image | Retaken from the current build with **real apps only** (test fixtures uninstalled); description updated; README updated | Screenshots reviewed |
| S25 | Low | `versionName` (set by other apps) not cleaned | `TextSafety.clean` | Code |
| S26 | Low | Newer-schema protection only worked if the newer file still decoded; a second downgrade overwrote it | Schema read before decoding; preserved with a timestamped name | `newerSchemaThatNoLongerDecodesIsStillPreserved` |
| S27 | Low | History entries unbounded per change | At most 64 items per set | Code |
| S28 | Low | Two inaccurate doc comments | Fixed | Files |

Also found while retaking screenshots (UX, Medium): the headline "No known trackers" sat beside
"8 with trackers" (the score covers user-installed apps; the 8 were system apps). Headline now reads
"No trackers in your apps".

### Result of iteration 4

All findings fixed; 38 unit tests pass; both builds have no warnings. Iteration 5 confirms.

---

## Iteration 5 (2026-10-25)

**Engineering: 0 High, 0 Medium. Security: 0 High, 0 Medium** (second clean round for security).
UI/UX: 0 High, 2 Medium, addressed together with the owner's request for a guided journey.

### UI/UX (0 High, 2 Medium)

| # | Sev | Finding | Fix | Verified |
|---|---|---|---|---|
| U35 | Medium | Score scope still unclear: "No trackers in your apps" beside "8 with trackers"; with nothing fully read the card showed 100 | Scope stated with counts ("Across the 5 apps you installed"; "8 preinstalled apps also have trackers (not part of the score)"); "Not enough data yet" when nothing could be scored | Pending (iteration 6) |
| U36 | Medium | Notifications that are denied or off were never surfaced, while the Alerts screen promised notifications | "Update alerts are off" card with a direct "Turn on" (dashboard and Alerts screen); state refreshed on resume | Pending |
| U37 | Low | "System" vs "Preinstalled" | "Preinstalled" everywhere | Code |
| U38 | Low | "Uninstall" advice for preinstalled apps | "Disable" wording for preinstalled apps | Code |

### Owner request: a guided journey ("five-course meal")

The first launch is now a five-step journey, and the journey continues on the dashboard:

1. **Welcome**: what TrackerLens does, three promises (on-device, no internet, proof).
2. **How it works + consent**: what is and isn't read; optional update alerts, asked here with the reason.
3. **Live scan**: progress, trackers found so far, the latest discovery ("Found Google AdMob in YouTube").
4. **Results**: score with its scope, most-tracked apps, the most common tracker.
5. **Plan**: review the riskiest apps, alerts status (with a fix), "we'll keep watching".

Afterwards the dashboard shows **"Your next step"** (the riskiest app not yet reviewed, with
"Reviewed X of Y risky apps" progress). "Replay introduction" is in the footer. Progress through
the journey survives process death.

### Engineering (0 High, 0 Medium) and security (0 High, 0 Medium): Lows handled

- CI network check could pass silently (no `pipefail`, no APK existence check): now `set -euo pipefail`, APK must exist, aapt2 output must be non-empty.
- Catalog script now fails on HTTP errors, fewer than 300 trackers, or unexpected signature formats.
- `*.apk`/`*.aab` git-ignored; preserved newer-schema files pruned to 3; README alt text accurate.
- Corruption notice acknowledged only after the snackbar actually showed it (guarded against I/O errors).
- "New" alert badges survive process death.

### End-to-end tests added

- `app/src/androidTest/.../JourneyTest.kt` (instrumented, real device or emulator): full journey welcome → dashboard;
  score explanation; search with no match; open app details and back; Alerts screen. Run with
  `ANDROID_SERIAL=<device> ./gradlew :app:connectedDebugAndroidTest`.
- `scripts/e2e/update-alert.sh`: builds a v1/v2 test app, installs and updates it, runs the background
  check, asserts the notification text, privacy flags (`vis=PRIVATE`, `LOCAL_ONLY`) and cleans up.

---

## Iteration 6 (2026-10-25): the journey and end-to-end tests

Totals: 1 High, 9 Medium, 15 Low. Security: 0 High, 0 Medium.

### UI/UX (1 High, 4 Medium)

| # | Sev | Finding | Fix | Verified |
|---|---|---|---|---|
| U39 | High | The reveal contradicted itself: "100 · No known trackers" above "Medium risk" preinstalled apps, and the plan said to find an alternative to Google Play services | Risky apps, "most tracked" and the plan cover apps the user installed; preinstalled apps get their own line and "limit permissions / disable" advice; the plan celebrates when there's nothing to do | Code; emulator |
| U40 | Medium | Leaving mid-journey restarted at Welcome | Course persisted in settings; Back on Results returns to the finished scan | Emulator: force-stop on step 2 → relaunch on step 2 |
| U41 | Medium | "Turn on alerts" did nothing below Android 13 or after a permanent denial | Opens the app's notification settings when the system dialog can't appear (fallback to App info) | Code |
| U42 | Medium | TalkBack wasn't told when the course changed or the scan finished | Each course title takes accessibility focus; scan status is a polite live region | Code |
| U43 | Medium | Units and scope changed between screens ("7 apps" vs "5 you installed"; "trackers found" vs "different trackers") | Same scope phrase everywhere ("5 apps you installed and 2 preinstalled apps"); the scan counts apps with trackers, the unit the results use | Emulator |
| U44 | Low | Progress copy shown after the scan finished | Shown only while scanning | Emulator |
| U45 | Low | "No high-risk apps" with a warning icon | "Your apps are in good shape" with a check | Code |
| U46 | Low | Opening an app counted as "reviewed" | Explicit "Mark as reviewed" on the app's page | Code |
| U47 | Low | Replay always rescanned and couldn't be closed | "Close" during a replay; Back on Welcome returns to the dashboard; no rescan if results are fresh | Code |
| U48 | Low | Dashboard polish (lone progress dot at 0, weak heading, squeezed results at large font) | Fixed | Code |

### Software engineering (0 High, 5 Medium)

| # | Sev | Finding | Fix | Verified |
|---|---|---|---|---|
| E45 | Medium | A full scan could start before consent (resume on Welcome/Consent; also the background job on first launch) | No foreground rescan before the scan course; the background job does nothing until the journey is finished | Emulator: 0 scans while on Welcome across background/foreground; scan starts on "Scan my apps" |
| E46 | Medium | A failed scan left the journey stuck on a disabled "Scanning…" | Failure shown on the scan course with "Try again" | Code |
| E47 | Medium | "Alerts are on" ignored a blocked notification channel | Channel importance checked too | Code |
| E48 | Medium | E2E script: `adb | grep -q` under `pipefail` could fail a real match (SIGPIPE) | Process substitution; `logcat -c` failure tolerated | Script run: PASS |
| E49 | Medium | Device tests not compiled in CI; E2E runs undocumented | CI compiles `assembleDebugAndroidTest`; CONTRIBUTING documents the pre-release E2E runs | Build |
| E50 | Low | Live discovery updates recomposed the screen even after onboarding | Only while the scan course is shown | Code |
| E51 | Low | `reviewedApps` never pruned | Pruned when results cover all apps | Code |
| E52 | Low | Stale notification could satisfy the script's check | Script opens the app first (clears it) | Script run |

### Security (0 High, 0 Medium)

Lows handled: the E2E script refuses to run without `ANDROID_SERIAL` and documents what it changes
on a device; reviewed marks pruned; notification-settings intent falls back instead of crashing.

### End-to-end results

`connectedDebugAndroidTest`: 5/5 pass on the Android 16 emulator. `scripts/e2e/update-alert.sh`: PASS.

### Owner testing on a real phone (2026-10-25)

| Report | Cause | Fix | Verified |
|---|---|---|---|
| "Include system apps" doesn't work during a scan | The switch was disabled while scanning, so taps were ignored | Always tappable; a change made during a scan is queued and applied when the scan ends, with a message saying so | Emulator: tapped mid-scan (246 apps being read) → "System apps will be hidden when the current scan finishes" → applied after the scan |
| "Our app shows as medium risk" | Not TrackerLens: the "TrackerLens Test App" fixture had been left installed on the phone after a notification test | Fixture removed from the phone; the E2E script now refuses to run without an explicit `ANDROID_SERIAL` and removes the fixture on exit | Phone: package list |
| First "most common tracker" row opens differently; Back closes the app | The phone had an old build (pre-iteration 2) without the Back handling for filters and the later fixes | Phone updated to the current release build | Emulator, current build: all rows open their "Apps with …" filter; Back returns to the dashboard each time |
