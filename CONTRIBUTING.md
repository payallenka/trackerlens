# Contributing to TrackerLens

Thanks for helping. A few ground rules keep the app trustworthy:

1. **No network access, ever.** Don't add the `INTERNET` permission or any library that needs it.
   CI fails if a debug or release APK declares the `INTERNET` permission.
2. **No proprietary dependencies, analytics, ads or crash reporting.** The app must stay
   buildable by F-Droid from source.
3. **Treat other apps' files as hostile input.** Anything that parses APK or DEX data must be
   bounded in memory and time and must not throw out of `ApkScanner`. Add a test with malformed input.
4. **Keep decision logic in `core/`** with unit tests. `app/` should only do Android I/O and UI.

## Workflow

```sh
./gradlew :core:test :app:assembleDebug
```

### End-to-end tests (run before a release, on an emulator or test device)

CI runs the unit tests and compiles the device tests, but doesn't start an emulator. Before a
release, run both end-to-end suites against an emulator:

```sh
export ANDROID_SERIAL=emulator-5554          # see `adb devices`
./gradlew :app:connectedDebugAndroidTest     # the five-step journey, dashboard, details, search, alerts
./gradlew :app:installDebug && scripts/e2e/update-alert.sh   # app update -> background check -> notification
```

Both change the device's state (the debug app's settings and history, a test app installed and
removed), so don't point them at your everyday phone.

- Use [Conventional Commits](https://www.conventionalcommits.org/) (`fix:`, `feat:`, ...).
- If you change how scans are read or matched, bump `SCANNER_VERSION` in `ScanRepository` so
  stored results are recomputed.
- Tracker definitions come from Exodus Privacy. To add or fix a signature, contribute it to
  [Exodus](https://github.com/Exodus-Privacy/exodus) so everyone benefits, then regenerate
  with `node scripts/update-trackers.mjs`.

By contributing you agree that your code is licensed under GPL-3.0-or-later.
