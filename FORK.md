# isarrider/gpstest: what's different from GPSTest

This fork of [barbeau/gpstest](https://github.com/barbeau/gpstest) adds features on top of the
original app. Each feature has its own branch, and the branch
[`all-features`](https://github.com/isarrider/gpstest/tree/all-features) combines all of them with
upstream `master` into one app.

## Getting the app

- **Download:** every push to `all-features` is built by the
  [Android CI Build](https://github.com/isarrider/gpstest/actions/workflows/android.yml?query=branch%3Aall-features)
  workflow. Open the latest successful run and download the debug APKs (`google` and `osmdroid`
  flavors) from its artifacts.
- **Android 15 or newer only:** the fork's minimum is Android 15 (API 35); upstream supports
  Android 7 (API 24) and newer.
- **Same app ID as the store version:** the builds are signed with a different key, so Android
  won't install them over GPSTest from Google Play or F-Droid. Uninstall that one first. Its
  settings and data are lost.

## Features

| Feature | What it adds | Branch | Upstream |
| --- | --- | --- | --- |
| **Galileo OSNMA** | Checks on the phone that Galileo navigation data is genuine (Open Service Navigation Message Authentication). An OSNMA card on the Status screen, seals next to authenticated satellites in the Status list and sky view, optional CSV logging. See [OSNMA.md](OSNMA.md). | `feature/galileo-osnma` | [PR #864](https://github.com/barbeau/gpstest/pull/864), in review |
| **Status screen redesign** | Satellites grouped by constellation, one row per satellite (a single line on wide screens). Each group header shows satellites used, the average C/N0 of the strongest signals, and "X of Y possible in view of Z total". Tap a satellite for its signals, whether each is used in the fix, and a C/N0 history chart (up to one hour). Signals not used in the fix are dimmed, and group headers stay visible while scrolling. The classic table is still available as a setting. | `feature/status-redesign`, `feature/sats-in-view` | Fork only |
| **Time since first fix** | TSFF shown next to the TTFF in the fix card. | `feature/tsff` | Fork only |
| **EGNOS** | Names and positions of the EGNOS satellites, an EGNOS card on the Status screen, and EGNOS correction messages decoded from an internet source (EDAS/SISNeT, set up in Settings). See [EGNOS.md](EGNOS.md). | `feature/egnos` | Fork only |
| **Constellation order** | Reorder the constellations in the GNSS filter with drag handles. The Status list follows that order. | `feature/gnss-order` | Fork only |
| **Signals screen** | A new screen in the drawer with the C/N0 of every received signal, as a bar chart or a band table, plus a C/N0 history of all signals in one chart. | `feature/signal-overview`, `feature/signal-history` | Fork only |
| **GPX track recording** | Record button in the toolbar. Tracks are written to `Download/GPSTest`, with GNSS details per point, battery level and OSNMA status. A Track screen shows stats and charts (distance, elevation, speed, satellites), and you can share the track. Distance units (km, mi, nautical miles) can be set separately from elevation units. See [LOGGING.md](LOGGING.md). | `feature/gpx-track`, `feature/gpx-battery`, `feature/gpx-osnma`, `feature/track-units` | Fork only |
| **Background switch** | GNSS always runs while the app is open. The toolbar switch decides whether it keeps running in the background after the app is closed. It stays on while a track records. | `feature/background-switch` | Fork only |
| **Black top bar** | The top bar is black in the dark theme, for AMOLED screens. | `feature/amoled-black-bar` | Fork only |

## Fixes

| Fix | Branch | Upstream |
| --- | --- | --- |
| GPS times more than 5 days in the future are flagged as invalid | `fix/future-gps-time` | [PR #871](https://github.com/barbeau/gpstest/pull/871), in review |
| Opening the app from the notification keeps its state instead of restarting it | `fix/notification-reopen` | Fork only |
| Minimum Android version raised to 15 (API 35) | `raise-min-version` | Fork only |

## How the branches fit together

- `master` follows upstream and is not changed in the fork.
- Each feature or fix is developed on its own branch. Branches that build on each other (for
  example the GPX branches) are merged into one another.
- After every change, the branch is merged into `all-features`, which CI builds automatically.
- Upstream `master` is merged into `all-features` from time to time, so upstream fixes and
  features are included too.
- Features meant for upstream (OSNMA, the future GPS time fix) are offered as pull requests from
  their own branch. Everything else stays in the fork.
