# Releasing

TrackerLens aims for a release about once a month. Releases must stay **reproducible**: F-Droid
rebuilds each tagged version and only ships it if its build is identical to the APK on GitHub.

## Checklist

1. Merge what's ready (feature work, Dependabot updates, the monthly catalog pull request).
2. Add the release notes:
   - `CHANGELOG.md`: move items from *Unreleased* to the new version.
   - `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`: 2–4 short lines for users (max 500 characters).
3. Run, from a clean `main`:

   ```sh
   scripts/release.sh 0.2.0 2      # versionName versionCode
   ```

   It bumps the version, runs the unit tests, builds and signs the release APK, checks it has no
   INTERNET permission, commits, tags `v0.2.0`, pushes, and publishes the GitHub release with
   `trackerlens-0.2.0.apk` (this exact file name is what F-Droid's `Binaries:` pattern expects).
4. Before publishing, run the end-to-end tests on an emulator (see CONTRIBUTING.md).

## Rules that keep F-Droid working

- Always sign with the same release key (`keystore.properties` → `release.jks`, kept outside the repo and backed up).
- Never change the APK name pattern `trackerlens-<version>.apk`.
- `versionCode` must always increase.
- Don't re-tag or replace an APK after publishing; make a new patch release instead.
