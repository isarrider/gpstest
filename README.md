# <img src="/icons/ic_launcher-playstore.png" width="50"/> GPSTest 
[![Build Status](https://github.com/barbeau/gpstest/actions/workflows/android.yml/badge.svg)](https://github.com/barbeau/gpstest/actions/workflows/android.yml) 
[![GitHub issues](https://img.shields.io/github/issues/barbeau/gpstest?color=red)](https://github.com/barbeau/gpstest/issues)
[![Twitter Follow](https://img.shields.io/twitter/follow/sjbarbeau.svg?style=social&label=Follow)](https://twitter.com/sjbarbeau)
[![GitHub](https://img.shields.io/github/license/barbeau/gpstest)](/LICENSE)

## About this fork

This is [isarrider/gpstest](https://github.com/isarrider/gpstest), a fork of [barbeau/gpstest](https://github.com/barbeau/gpstest). It follows upstream `master` and adds the changes below, which are not in upstream yet. The branch [`all-features`](https://github.com/isarrider/gpstest/tree/all-features) contains all of them and upstream `master`; the fork's `master` follows upstream, with only a short version of this section in its README. Each change also lives on its own branch, from which it is (or could be) proposed upstream. Once upstream merges a change, its row is removed here.

**Getting the app:** every push to `all-features` is built by the [Android CI Build](https://github.com/isarrider/gpstest/actions/workflows/android.yml?query=branch%3Aall-features) workflow; download the APKs (`google` and `osmdroid` flavors) from the artifacts of the latest successful run. They need Android 15 or newer, and use the same app ID as GPSTest from Google Play or F-Droid but a different signing key, so uninstall that one first (its settings and data are lost).

| Change | Main files | Upstream |
| --- | --- | --- |
| **Galileo OSNMA.** Checks on the phone that the Galileo navigation data is genuine (Open Service Navigation Message Authentication): public key against the Merkle tree, TESLA root key signature, TESLA key chain and MAC tags. A card on the Status screen shows the state (in progress, authenticated, time not verified, don't use, alert) and warnings (failed tags, poor reception, slow MAC only); authenticated satellites get a green seal, failing ones a red one, in the Status list and the sky view. The current public key and TESLA root key are built in and the last TESLA key is kept, so authentication starts within minutes. The Galileo time is checked against Google Play TrustedTime (google flavor) or network time. Works around the quirks of the Pixel 8 Pro, 10 Pro XL and 11 Pro, reported to Google as [580675649](https://issuetracker.google.com/issues/580675649), [580885286](https://issuetracker.google.com/issues/580885286), [583431466](https://issuetracker.google.com/issues/583431466), [583449761](https://issuetracker.google.com/issues/583449761), [583449762](https://issuetracker.google.com/issues/583449762). Optional CSV logging. 49 unit tests, cross-checked against the galileo-osnma library and replaying logs from the three phones. See [OSNMA.md](OSNMA.md). | `library/…/osnma/`, `library/…/data/OsnmaRepository.kt`, `GPSTest/…/ui/status/OsnmaCard.kt`, `OSNMA.md` | [#864](https://github.com/barbeau/gpstest/pull/864), in review, branch [`feature/galileo-osnma`](https://github.com/isarrider/gpstest/tree/feature/galileo-osnma) |
| **Status screen redesign.** Satellites grouped by constellation, one row per satellite (one line on wide screens), group headers that stay visible while scrolling, with satellites used, the average C/N0 of the strongest signals and "8 of 12 possible in view of 31 total". Tap a satellite for its signals, which are used in the fix, and a C/N0 history chart of up to an hour. Signals not used in the fix are dimmed. The classic table is still available as a setting. | `GPSTest/…/ui/status/GroupedStatusList.kt`, `SatelliteGrouping.kt` | fork only, [`feature/status-redesign`](https://github.com/isarrider/gpstest/tree/feature/status-redesign), [`feature/sats-in-view`](https://github.com/isarrider/gpstest/tree/feature/sats-in-view) |
| **Time since first fix** next to the TTFF in the fix card. | `GPSTest/…/ui/status/StatusScreen.kt` | fork only, [`feature/tsff`](https://github.com/isarrider/gpstest/tree/feature/tsff) |
| **EGNOS.** Names and positions of the EGNOS satellites, an EGNOS card on the Status screen, and EGNOS corrections decoded from an internet source (EDAS/SISNeT, set up in Settings). See [EGNOS.md](EGNOS.md). | `library/…/egnos/`, `library/…/data/EgnosRepository.kt`, `GPSTest/…/ui/status/EgnosCard.kt`, `EGNOS.md` | fork only, [`feature/egnos`](https://github.com/isarrider/gpstest/tree/feature/egnos) |
| **Constellation order.** The GNSS filter dialog gets drag handles; the Status list and the Signals screen follow that order. | `GPSTest/…/ui/GnssFilterDialog.kt` | fork only, [`feature/gnss-order`](https://github.com/isarrider/gpstest/tree/feature/gnss-order) |
| **Signals screen.** A drawer entry with the C/N0 of every received signal, as a bar chart or a table by band, and the C/N0 history of all signals in one chart. | `GPSTest/…/ui/signals/` | fork only, [`feature/signal-overview`](https://github.com/isarrider/gpstest/tree/feature/signal-overview), [`feature/signal-history`](https://github.com/isarrider/gpstest/tree/feature/signal-history) |
| **GPX track recording.** A record button in the toolbar writes a GPX track to `Download/GPSTest`: standard fields plus GNSS details per point (satellites, HDOP, dual-frequency, SBAS, optionally every satellite), battery level, the OSNMA state, and the air pressure on phones with a barometer. Written every few minutes to spare the flash, with a configurable segment gap. See [LOGGING.md](LOGGING.md). | `GPSTest/…/io/Gpx*.kt`, `ForegroundOnlyLocationService.kt`, `LOGGING.md` | fork only, [`feature/gpx-track`](https://github.com/isarrider/gpstest/tree/feature/gpx-track), [`feature/gpx-battery`](https://github.com/isarrider/gpstest/tree/feature/gpx-battery), [`feature/gpx-osnma`](https://github.com/isarrider/gpstest/tree/feature/gpx-osnma), [`feature/track-barometer`](https://github.com/isarrider/gpstest/tree/feature/track-barometer) |
| **Track screen.** Cards for the summary (duration, moving and stopped time, distance), speed (current, averages, max, direction, optional pace), elevation (current, range, ascent and descent, gradient; from the barometer calibrated with GNSS where there is one), GNSS (satellites used, accuracy, HDOP, dual-frequency, SBAS, OSNMA), battery and the file (points, logging settings). Charts of distance, splits per km/mi/nmi, elevation, speed, speed distribution, satellites and battery, with a values window when you touch them. Track distance units (km, mi, nautical miles) are separate from elevation units. "Arrange cards" in the toolbar sets the order of the cards and hides the ones you don't need. | `GPSTest/…/ui/track/`, `GPSTest/…/io/GpxTrackStats.kt` | fork only, [`feature/track-units`](https://github.com/isarrider/gpstest/tree/feature/track-units), [`feature/track-chart-marker`](https://github.com/isarrider/gpstest/tree/feature/track-chart-marker), [`feature/track-speed-card`](https://github.com/isarrider/gpstest/tree/feature/track-speed-card), [`feature/track-file-settings`](https://github.com/isarrider/gpstest/tree/feature/track-file-settings), [`feature/track-current-values`](https://github.com/isarrider/gpstest/tree/feature/track-current-values), [`feature/track-stopped-time`](https://github.com/isarrider/gpstest/tree/feature/track-stopped-time), [`feature/track-pace`](https://github.com/isarrider/gpstest/tree/feature/track-pace), [`feature/track-splits`](https://github.com/isarrider/gpstest/tree/feature/track-splits), [`feature/track-gradient`](https://github.com/isarrider/gpstest/tree/feature/track-gradient), [`feature/track-card-order`](https://github.com/isarrider/gpstest/tree/feature/track-card-order), [`feature/track-speed-distribution`](https://github.com/isarrider/gpstest/tree/feature/track-speed-distribution) |
| **Background switch.** GNSS always runs while the app is open; the toolbar switch decides whether it keeps running after the app is closed. It stays on while a track records. | `GPSTest/…/ui/MainActivity.kt`, `ForegroundOnlyLocationService.kt` | fork only, [`feature/background-switch`](https://github.com/isarrider/gpstest/tree/feature/background-switch) |
| **Black top bar** in the dark theme, for AMOLED screens. | `library/…/res/values/styles.xml`, `attrs.xml` | fork only, [`feature/amoled-black-bar`](https://github.com/isarrider/gpstest/tree/feature/amoled-black-bar) |
| **Opening the app from the notification** keeps its state instead of restarting it. | `GPSTest/…/ForegroundOnlyLocationService.kt` | fork only, [`fix/notification-reopen`](https://github.com/isarrider/gpstest/tree/fix/notification-reopen) |
| **Android 15 or newer** (API 35), for the phone app, library and watch app; upstream supports Android 7 and newer. | `build.gradle` files | fork only, [`raise-min-version`](https://github.com/isarrider/gpstest/tree/raise-min-version) |
| **CI builds `all-features`** on every push, with Android 15 and 16 emulators. | `.github/workflows/android.yml` | fork only |

Merged upstream so far: [#871](https://github.com/barbeau/gpstest/pull/871) (GPS times more than 5 days in the future are invalid).

The branches build on each other where they need to: the Status, TSFF, EGNOS, constellation order and Signals branches started from the OSNMA branch of that time, as they show OSNMA marks; `feature/sats-in-view` builds on `feature/status-redesign`, `feature/signal-history` on `feature/signal-overview`, the GPX branches and `feature/background-switch` on `feature/gpx-track`, and the Track screen branches on `feature/track-units`. `feature/track-card-order` and `feature/track-speed-distribution` start from `all-features`, as they arrange cards from several track branches. The other branches start from upstream `master`. `feature/galileo-osnma` is the branch of [#864](https://github.com/barbeau/gpstest/pull/864) and stays as it is while the PR is open.

To see the exact difference against upstream: [compare barbeau:master...isarrider:all-features](https://github.com/barbeau/gpstest/compare/master...isarrider:gpstest:all-features).

The #1 open-source global navigation satellite system (GNSS) testing app.

## Install

[<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png" alt="Get it on Google Play" height="80">](https://play.google.com/store/apps/details?id=com.android.gpstest)
[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="80">](https://f-droid.org/packages/com.android.gpstest.osmdroid/)

## Features

It supports dual-frequency¹ GNSS for:

* GPS (USA Navstar)
* GLONASS (Russia)
* QZSS (Japan)
* BeiDou/COMPASS (China)
* Galileo (European Union)
* IRNSS/NavIC (India)
* Various satellite-based augmentation systems (SBAS):
    * Wide Area Augmentation System (WAAS) (USA)
    * European Geostationary Navigation Overlay Service (EGNOS) (European Union)
    * GPS-aided GEO augmented navigation (GAGAN) (India)
    * Multi-functional Satellite Augmentation System (MSAS) (Japan)
    * System for Differential Corrections and Monitoring (SDCM) (Russia)
    * Satellite Navigation Augmentation System (SNAS) (China)
    * Soluciόn de Aumentaciόn para Caribe, Centro y Sudamérica (SACCSA) (ICAO)
    * Southern Positioning Augmentation Network (SouthPAN) (Australia / New Zealand)
    
¹*Dual-frequency GNSS requires device hardware support and Android 8.0 Oreo or higher. See [Dual-frequency GNSS on Android](https://medium.com/@sjbarbeau/dual-frequency-gnss-on-android-devices-152b8826e1c) for more details.*

</details>

## Screenshots
<img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" height="500"> <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" height="500"> <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" height="500">

<details>
  <summary>More screenshots</summary>
  
<img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/4.jpg" height="500"> <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" height="500"> <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/6.jpg" height="500"> <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/7.png" height="500"> <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/8.jpg" height="500">
  
</details>

## Contributing

I welcome contributions to the project!  Please see the [Contributing Guide](.github/CONTRIBUTING.md) for details, including Code Style Guidelines and Template.

- Don't know where to start?  Take a look at the issues marked with the [your-first-pr](https://github.com/barbeau/gpstest/labels/your-first-pr) label and comment to let me know if you're interested in working on it.

- Beta testing: Get early access to new GPSTest versions, and help us squash bugs! See the [Testing Guide](BETA_TESTING.md) for details.

- Translating: Want to improve existing translations, or add a new translation?  Translate on [Transifex](https://app.transifex.com/sean-barbeau/gpstest-android/dashboard/) or see the [Translations](/TRANSLATIONS.md) documentation.

## FAQ

Questions?  Check out the [FAQ](FAQ.md), the [Slack group](https://gpstest-android.herokuapp.com/), and [Google Group](https://groups.google.com/forum/#!forum/gpstest_android).

- Crowdsourcing GNSS data: Interested in better understanding the state of GNSS feature support on Android devices? See the article [*Crowdsourcing GNSS features of Android devices*](https://barbeau.medium.com/crowdsourcing-gnss-capabilities-of-android-devices-d4228645cf25).

- Accuracy: Measuring your device GNSS accuracy? Check out the [Measuring Accuracy using GPSTest](ACCURACY.md) page, as well as the corresponding article [*Measuring GNSS accuracy on Android devices*](https://medium.com/@sjbarbeau/measuring-gnss-accuracy-on-android-devices-6824492a1389).

- Galileo OSNMA: Want to check whether Galileo navigation data is authentic? See the [Galileo OSNMA](OSNMA.md) page.
- EGNOS: Want to see which EGNOS satellites your phone uses and what corrections EGNOS currently sends? See the [EGNOS](EGNOS.md) page.

- Data Output and Logging: Want to know how to output GNSS data to the system log for further analysis?  Check out the [Data Output and Logging](LOGGING.md) page.

- Building the project: You can open and build this project using [Android Studio](https://developer.android.com/studio).  For more details, see the [Build documentation](BUILD.MD).

## Trusted by industry experts

Notable appearances of GPSTest:

* Xiaomi - [*Xiaomi Redmi Note 9 Pro Max launch*](https://youtu.be/Y_5cfCZBOV4?t=3035), March 12, 2020.
* European Union Global Navigation Satellite Systems Agency (GSA) - [*Test your Android device’s satellite navigation performance*](https://www.euspa.europa.eu/newsroom-events/news-archive/test-your-android-devices-satellite-navigation-performance), August 21, 2018.
