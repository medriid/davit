<div align="center">

<img src="docs/assets/logo.png" width="96" alt="daVIT logo">

# daVIT

**A fast, private VTOP companion for VIT students.**

Attendance, today's classes and your week, printed in two inks.

[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-F65058?style=flat-square)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Android%208%2B-00838A?style=flat-square)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-00585D?style=flat-square)

</div>

---

## Features

- **Attendance.** Every course sorted by risk, with how many classes you can skip or must attend, and its day-by-day history.
- **Today at a glance.** The class that's on now, what's next, what's due, and what changed since you last looked.
- **Academics.** Marks, assignments, exams, grades with your CGPA, credit progress, a semester planner, and your faculty's contact details.
- **Your week.** Each day's classes, the whole week as a grid, and the academic calendar with holidays, exam dates and swapped days.
- **Alerts and a widget.** Syncs in the background and tells you about attendance dips, new marks, deadlines and exams. A home-screen widget shows your next class.
- **Signs in by itself.** VTOP's captcha is solved on the phone by a tiny model; sessions are reused, so most refreshes skip login entirely.
- **Riso look.** Four two-ink palettes on paper or night print, halftone bars, and a mascot you can dress up.

<div align="center">
<img src="docs/assets/mascots.png" width="560" alt="Mascot styles">
</div>

## Download

Get the latest APK from [Releases](https://github.com/medriid/davit/releases). Android 8 or newer.

## Privacy

- **Stays on your phone.** Password, session and data are sealed with AES-256-GCM under an Android Keystore key.
- **Talks only to VTOP.** No server of ours, no accounts, no analytics. Read-only: upload endpoints are blocked in code. It also downloads two public files, the academic calendar and the faculty directory, and asks GitHub for the latest release when you tap *Check for updates*. None of these send anything about you.
- **Optional app lock** with fingerprint, face or screen lock.
- **Sign out erases everything** it stored, key included.

## Build

Needs JDK 17 and the Android SDK (API 35).

```bash
cd android
./gradlew assembleDebug
```

The captcha model weights and the slot timetable aren't part of this repository. Without them the app builds and runs but can't sign in. See [docs/05-captcha-solver.md](docs/05-captcha-solver.md) for how to supply them.

## Layout

| Path | |
|---|---|
| [`android/`](android) | The app: Kotlin, Jetpack Compose. `core` is plain JVM (VTOP client, captcha solver, parsers) and runs its tests on a laptop. |
| [`web/`](web) | A small Flask build of the same engine, for the desktop. |
| [`docs/`](docs) | VTOP endpoint reference, the captcha solver, the security model. |
| [`tools/`](tools) | Helper scripts. |

## Credits

Academic calendar and faculty directory from [IshanJ25/vtop-cal-data](https://github.com/IshanJ25/vtop-cal-data) and [vtop-fac-data](https://github.com/IshanJ25/vtop-fac-data). Fonts: Bagel Fat One and IBM Plex Mono (SIL OFL 1.1).

## License

[Apache-2.0](LICENSE). Not affiliated with VIT.
