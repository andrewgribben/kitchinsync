# KitchinSync

Sync iCloud **calendars** and **contacts** with Android's built-in calendar and contacts storage.

KitchinSync only sets up the sync. It has no calendar or contacts views of its own. Your iCloud data shows up in the apps you already use (Google/Samsung Calendar, Contacts, WhatsApp, Signal, and so on), the same way a Google or Samsung account does.

> **Status:** pre-alpha. Stage 1: you can add iCloud accounts and choose calendars, but nothing is synced yet. See [`docs/PLAN.md`](docs/PLAN.md) and [`docs/wireframes/`](docs/wireframes/).

## Goals

- Behaves like any other Android sync account. It's listed under **Settings → Accounts** as *iCloud* and has per-account *Calendar* and *Contacts* sync switches.
- Supports multiple iCloud accounts. You sign in with your Apple ID and an [app-specific password](https://support.apple.com/en-us/102654).
- You choose which iCloud calendars to sync.
- Contacts are written through a real `ContactsContract` sync adapter, so third-party apps can see them (WhatsApp, for example).
- Almost nothing to configure, and it follows Material 3 and native Android patterns.
- Builds on proven open-source CalDAV/CardDAV code instead of writing a new implementation.

## Non-goals

- Viewing or editing events and contacts inside KitchinSync.
- iCloud Reminders. Apple moved Reminders off CalDAV in iOS 13.
- iCloud Drive, Photos, Mail.

## License

GPL-3.0-or-later. KitchinSync reuses the GPL-3.0 `synctools` module from [DAVx⁵](https://github.com/bitfireAT/davx5-ose).
