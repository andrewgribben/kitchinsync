# Vendored: davx5-ose `synctools/`

- Source: https://github.com/bitfireAT/davx5-ose
- Path: `synctools/` (plus `synctools/opentasks-contract/`, partially)
- Commit: `e002a1e34b5fa718b3555ec5c6997d2aa4ecc97c` (branch `main`, fetched 2026-11-06)
- License: GPL-3.0-or-later (same as this app; see upstream `LICENSE`)

Pulled in for the CalDAV-event ⇄ `CalendarContract` and CardDAV-contact ⇄
`ContactsContract` mapping/storage layers so Synccloud doesn't reimplement
iCal/vCard parsing or provider mapping.

## Modifications from upstream

1. **Build file replaced.** Upstream's `build.gradle.kts` uses DAVx⁵'s
   `davx5.common-buildconfig` convention plugin and its own version catalog.
   This module's `build.gradle.kts` is a plain `com.android.library` module
   with the equivalent compileSdk/minSdk/Java toolchain/desugaring settings
   inlined, using entries added to this project's `gradle/libs.versions.toml`
   (`ical4j`, `ezvcard`, `guava`, `slf4j-jdk`, `commons-codec`, `commons-lang`,
   `androidx-annotation`, `android-desugaring`), matching the versions pinned
   in DAVx⁵'s own catalog at the same commit.
2. **Tasks (OpenTasks/`DmfsTaskList`) and jtx (`at.techbee.jtx`, JTX Board)
   support dropped entirely**, along with `synctools/opentasks-contract/`.
   Synccloud only syncs CalDAV events (VEVENT) and CardDAV contacts, never
   VTODO/VJOURNAL, so none of `mapping/tasks`, `mapping/jtx`, `storage/tasks`,
   `storage/jtx`, `storage/TaskProvider.kt`, or `JtxContract.kt` are reachable
   code. Verified with `grep` that nothing outside those packages imports
   them before deleting.
3. **Upstream's own tests, test fixtures and instrumented tests were not
   vendored** (`src/test`, `src/testFixtures`, `src/androidTest`, the
   `apk/` third-party APK installer, and the Gradle managed-device test
   config). They depend on DAVx⁵'s `testFixtures` module (mock
   `ContentProvider`/`ContentResolver` helpers), Robolectric with Android
   resources, `mockk-android`, and a managed virtual device — heavy,
   davx5-ose-specific test infrastructure that isn't worth re-wiring here.
   Only the production (`src/main`) code is vendored. Synccloud's own unit
   tests (in `app/src/test`) cover the sync logic Synccloud adds on top
   (sync-token/CTag decisions, 412 handling, dirty-upload, group mapping),
   not synctools' own builders/handlers — those are upstream's
   responsibility and DAVx⁵ already exercises them at scale.
4. `src/main/res/values/about_strings.xml` dropped (DAVx⁵'s in-app "About"
   licenses screen; Synccloud has no such screen).

## Updating

Re-run the copy from a newer davx5-ose commit, re-apply the same four
modifications, and update the commit SHA above.
