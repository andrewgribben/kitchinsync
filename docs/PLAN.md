# Synccloud: design plan (draft for review)

## Problem

There's no well-maintained, iCloud-focused Android app that syncs both calendars **and** contacts as a native Android account. Outlook can import iCloud contacts, but other apps such as WhatsApp don't see them reliably. The likely reason is that Outlook stores them under its own account type with visibility and grouping flags that differ from a normal sync adapter's. Contacts written by a proper `ContactsContract` sync adapter that marks its account visible (`Settings.UNGROUPED_VISIBLE = 1`, as DAVx⁵ does) show up in WhatsApp. We'll test this on the target device early on (see the test plan).

## Approach

Don't write a new CalDAV/CardDAV stack. Reuse the code that DAVx⁵ ships to millions of users, and build a small, iCloud-specific shell around it.

| Concern | Library / platform API | License |
|---|---|---|
| WebDAV / CalDAV / CardDAV protocol | [`dav4jvm`](https://github.com/bitfireAT/dav4jvm) (bitfire) | MPL-2.0 |
| Calendar Provider + Contacts Provider mapping (iCal ⇄ `CalendarContract`, vCard ⇄ `ContactsContract`) | [`synctools`](https://github.com/bitfireAT/davx5-ose/tree/main-ose/synctools) (now inside davx5-ose) | GPL-3.0 |
| iCalendar parsing | ical4j (via synctools) | BSD-3 |
| vCard parsing | ez-vcard (via synctools) | BSD |
| HTTP | OkHttp | Apache-2.0 |
| Android account | `AccountManager` + `AbstractAccountAuthenticator` | platform |
| Sync scheduling | `AbstractThreadedSyncAdapter` (calendar + contacts authorities), with periodic sync through the system sync framework | platform |
| UI | Kotlin, Jetpack Compose, Material 3, dynamic colour | Apache-2.0 |
| DI / persistence | Plain constructors; per-account settings in `AccountManager` userdata (see [Stage 1 decisions](#stage-1-decisions)) | platform |

Because synctools is GPL-3.0, **Synccloud is GPL-3.0-or-later**. That suits an open-source app. The MPL/BSD-only alternative would mean rewriting the provider-mapping layer ourselves, which goes against the "don't reinvent the wheel" goal.

### iCloud specifics

- Auth: Apple ID plus an **app-specific password** from appleid.apple.com. iCloud doesn't accept the normal password over CalDAV/CardDAV when 2FA is on. We store the password in `AccountManager`, encrypted with an Android Keystore key.
- Discovery: start from `https://caldav.icloud.com/` and `https://contacts.icloud.com/`, then follow `current-user-principal` → `calendar-home-set` / `addressbook-home-set`. iCloud redirects each account to a partition host (`pNN-caldav.icloud.com`).
- Calendars: each iCloud calendar collection becomes one local `CalendarContract.Calendars` row, owned by the iCloud account. You can switch each one on or off. Colour and name come from the server.
- Contacts: iCloud exposes **one** CardDAV address book per Apple ID. iCloud contact *groups* are vCard `KIND:group` entries. These map to `ContactsContract.Groups`, so they appear as labels/groups in the Contacts app. If you add several iCloud accounts, each one is its own address book in Android.
- Reminders (VTODO): out of scope. Modern iCloud Reminders don't use CalDAV.
- Sync: two-way. Uses `sync-collection` (WebDAV sync tokens) where iCloud supports it, with CTag/ETag fallback.

### Android integration (what makes it feel native)

- Account type `io.github.andrewgribben.synccloud.icloud`, shown as **"iCloud"** with the iCloud-style cloud icon in Settings → Accounts.
- `res/xml/authenticator.xml` declares `accountPreferences`, so **Settings → Accounts → iCloud → (account)** links to Synccloud's account settings screen.
- Two sync adapters (`com.android.calendar`, `com.android.contacts`), so the system **Account sync** screen shows *Calendar* and *Contacts* switches plus *Sync now*.
- `contacts.xml` (`ContactsAccountType`) defines how the account appears in the Contacts app's account picker for new contacts.
- No app-specific notification noise. A notification appears only for actionable errors, such as a revoked password ("Re-enter password").

## Screens (see `docs/wireframes/Synccloud Wireframes.html`)

1. **Accounts** (launcher): list of iCloud accounts with status, and a FAB to add one. Empty state explains app-specific passwords.
2. **Sign in**: Apple ID plus app-specific password, with a link to Apple's instructions.
3. **Choose what to sync**: shown right after sign-in. Calendars as a checklist with server colours, a Contacts switch, and *Done*.
4. **Account detail**: sync status, *Sync now*, collection toggles, sync interval, and *Remove account*. The system Settings → Accounts entry opens this same screen.
5. **System Settings → Accounts → iCloud** (Android-owned): Calendar/Contacts switches and *Sync now*.
6. **Permissions**: a single runtime request for Calendar and Contacts, plus an optional battery-optimisation exemption prompt.

## Project layout (proposed)

```
synccloud/
  app/                 Compose UI, account authenticator, sync adapters
  core/icloud/         iCloud discovery + auth (dav4jvm)
  core/sync/           calendar & contacts syncers (synctools)
  core/data/           Room: accounts → collections → sync state
  synctools/           git submodule → davx5-ose synctools (pinned)
  docs/
```

minSdk 26 (Android 8), targetSdk = latest stable.

Stage 1 ships as a single `:app` module. Split modules out only when stage 2 needs them.

## Stage 1 decisions

- **dav4jvm**: consumed the same way DAVx⁵ does, from JitPack as `com.github.bitfireAT:dav4jvm:5.0.0`. Since 4.x, dav4jvm's API is built on Ktor rather than OkHttp, so discovery uses a Ktor `HttpClient` with the **OkHttp engine** (`ktor-client-okhttp`), with `followRedirects = false` because dav4jvm follows redirects itself. dav4jvm's `xpp3` dependency is excluded from the app because Android has its own `XmlPullParser`; JVM unit tests keep it.
- **Collection choices** are stored as JSON (kotlinx.serialization) in the account's `AccountManager` userdata, not in Room. Android deletes userdata together with the account, whether the account is removed in the app or in Settings, so there is nothing to clean up and no schema to migrate yet. Revisit Room in stage 2 if per-collection sync state (CTags, sync tokens) outgrows this.
- **Sync switches and interval** are not duplicated: the per-account Contacts/Calendar on/off state is `ContentResolver.get/setSyncAutomatically`, and the interval is `ContentResolver.getPeriodicSyncs`, so the app and Settings → Accounts always agree.
- **Password**: AES/GCM with a key from the Android Keystore; the ciphertext (IV + data, Base64) is what's stored as the `AccountManager` password.

## Stage 2: bringing in synctools

synctools now lives as the `synctools/` directory of [davx5-ose](https://github.com/bitfireAT/davx5-ose) (branch `main-ose`). Its build uses DAVx⁵'s `davx5.common-buildconfig` convention plugin (from DAVx⁵'s `build-logic`) and DAVx⁵'s version catalog. The old standalone repo [bitfireAT/synctools](https://github.com/bitfireAT/synctools) is archived, has no tags (last commit `cb4f75f`), and is an older snapshot, so building it from JitPack would pin us to stale code. Stage 1 therefore doesn't depend on synctools at all.

Plan for stage 2:

1. Vendor `synctools/` from davx5-ose into `third_party/synctools/`, as a Gradle module (`:synctools`).
2. Add `third_party/synctools/UPSTREAM.md` recording the davx5-ose commit SHA it was copied from, the upstream path, and its license (GPL-3.0, same as this app). Updating means re-copying from a newer SHA and updating that file.
3. Replace `id("davx5.common-buildconfig")` in its `build.gradle.kts` with the few settings that plugin provides (compileSdk/minSdk, Java/Kotlin toolchain, core library desugaring), and add the libraries it needs (ical4j, ez-vcard, slf4j-jdk, guava, commons-codec/lang pinned as in DAVx⁵'s catalog) to our version catalog. Drop the managed-device and APK-install test setup.

## Test plan

- Unit: discovery parsing against recorded iCloud PROPFIND responses (anonymised fixtures). Calendar/contact mapping is covered by synctools' own tests.
- Instrumented: account creation, collection enable/disable, and provider rows created and removed.
- Manual on the Galaxy Fold: add 2 iCloud accounts, pick calendars, and confirm they appear in Samsung Calendar and Google Calendar, contacts appear in Samsung Contacts, and **contacts appear in WhatsApp's contact picker**. Then edit on the phone, check the change on iCloud.com, and repeat in the other direction.

## Open questions

1. ~~Package name / account type~~: decided, `io.github.andrewgribben.synccloud`.
2. Distribution: GitHub releases + F-Droid, Play Store later?
3. Should iCloud contact groups be synced (default on)?
