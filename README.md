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
- **Today at a glance.** The class that's on now and what's next (its room a tap from the map), then what's due, an exam or registration that's close, and what changed since you last looked.
- **Academics.** Marks, assignments, exams, grades with your CGPA and each semester's GPA, credit progress, a semester planner, and your teachers by course (search by course, code or name; cabins open on the map), proctor, HOD and dean.
- **Assignments, end to end.** Download the question paper and your own submission, and upload from the phone: type and size checked first, a confirm tap, progress, and VTOP's emailed code when you replace a file.
- **Scan to PDF.** Photograph pages (or pick them from your gallery) into one PDF: the page's edges are found for you, shadows lifted, with Enhanced, Grey or black-and-white filters. Crop with a magnifier, turn, reorder, insert pages, put two photos on one page, name it, then upload it straight to the assignment (fitted under VTOP's 4 MB), save it or share it. Photos never leave the phone unless you do.
- **Course materials.** Every course page with your own faculty's uploads first, by module. Files download with progress and open offline; a forgiving search (typos are fine) runs across all your courses.
- **VTOP's verdicts.** Debarment remarks on your attendance and on Today, with an alert when one appears; the registration window with a reminder; pending feedback.
- **The rest of VTOP.** Class and proctor messages, Spot-light, club events, hostel leave (apply, status, history), gate log, receipts, fees letters, library dues, and your curriculum with syllabus PDFs.
- **Campus map.** The campus in 2.5D, printed in the same two inks. Search a block, a mess, an ATM or a room (PRP 327 finds Block E, 3rd floor), tap a building to see what's inside, and get walking or cycling routes from where you are. Mess menus, opening hours and the PRP floor plan are on each place's card.
- **Your week.** Each day's classes, the whole week as a grid, and the academic calendar with holidays, exam dates and swapped days.
- **Alerts you set up.** Warn below 75, 80 or 85%, when skips run low, course by course; a reminder before class with the room and what missing it would cost; new marks, deadlines, exams and registration, each on its own switch. A home-screen widget shows your next class.
- **Signs in by itself.** VTOP's captcha is solved on the phone by a tiny model; sessions are reused, so most refreshes skip login entirely.
- **Riso look.** Four two-ink palettes on paper or night print, halftone bars, and a mascot you can dress up.

<div align="center">
<img src="docs/assets/mascots.png" width="560" alt="Mascot styles">
</div>

## Download

Get the latest APK from [Releases](https://github.com/medriid/davit/releases). Android 8 or newer. After that, daVIT downloads new releases by itself and asks you to install them.

## Privacy

- **Stays on your phone.** Password, session and data are sealed with AES-256-GCM under an Android Keystore key.
- **Talks only to VTOP.** No server of ours, no accounts, no analytics. It also downloads public files (the academic calendar, the faculty directory and the campus map, refreshed weekly) and asks GitHub for the latest release once a day (or when you tap *Check*; turn it off in Settings). None of these send anything about you.
- **Writes only when you ask.** Background sync only reads. An assignment upload or a hostel leave application goes to VTOP only after you've confirmed it on screen; every other write (payments, registration, password changes) is blocked in code.
- **Files stay local.** Course materials and documents you open are kept in the app's private storage for offline use; delete them any time from Downloads.
- **Location stays put.** The map reads your location only while it's open, to show where you are and route from you. It's never stored or sent.
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

The campus map (buildings, rooms, PRP floors, mess menus and the walking network) is the work of [Jeman](https://www.linkedin.com/in/jemankalita/), shared under the MIT licence. Map data © OpenStreetMap contributors (ODbL).

Academic calendar and faculty directory from [IshanJ25/vtop-cal-data](https://github.com/IshanJ25/vtop-cal-data) and [vtop-fac-data](https://github.com/IshanJ25/vtop-fac-data). Fonts: Bagel Fat One and IBM Plex Mono (SIL OFL 1.1).

## License

[Apache-2.0](LICENSE). Not affiliated with VIT.
