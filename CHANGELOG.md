# Changelog

All notable changes to TrackerLens. Versions follow `MAJOR.MINOR.PATCH`; each release's short
user-facing notes also go in `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

## [Unreleased]

## [0.1.0] - 2026-10-25

First public release.

- On-device scan of installed apps for 428 trackers (Exodus Privacy catalog), with the matching class as proof
- Risk level and privacy score per app, weighted by tracker type; sensitive permissions per app
- Background check about every 12 hours with alerts when an update adds trackers or permissions
- Five-step first-run journey; Home, Apps, Trackers and Alerts tabs; "Your next step"
- Light and dark themes
- No internet permission; hardened parsing of untrusted APK/DEX files; crash-safe storage
