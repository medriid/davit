# daVIT for Android

Kotlin + Jetpack Compose, min SDK 26.

## Modules

| Module | What's in it |
|---|---|
| `core/` | Plain JVM, no Android: `VtopClient` (login, session reuse, downloads, the guard, per-stage timings), `CaptchaSolver`, Jsoup `Parsers`, `Feeds` (home page feeds and records) and `CoursePages` (materials, upload pages), `search/Fuzzy`, `scan/` (page finding, filters, a JPEG-only PDF writer), `SlotTimes`. `./gradlew :core:test` runs on a laptop. |
| `design/` | Riso components: ink pairs, `Overprint`, `Halftone`, `InkButton`, `InkCard`, `Seal` (the app mark), `Grain`, motion, and `Printing` (list entrances, the first-sync sheet, the pull drum, loading plates, haptics). Fonts and licences in [`design/FONTS.md`](design/FONTS.md). |
| `app/` | `data/`: `Repo` (sign-in, sync, sign-out), `Vault` (Keystore AES-256-GCM), `Snapshot` and `HomeFeeds` (encrypted cache), `CampusStore`, `MaterialsStore`, `FileStore` (downloads), `Uploads`, `Schedule` (week, now/next, the 75% maths). `ui/`: sign-in, the four tabs, screens above them (`Nav`, shared-element transitions), the account sheet and the mascot. |

## Writes

The guard in `VtopClient` blocks every write endpoint unless the request carries a
`WritePermit`, and only the consent-taking methods (`openUpload`, `upload`,
`submitUploadOtp`, `applyLeave`, each needing a `UserConsent` from a confirm button)
make one. Background sync never has one. Payments, password changes and registration
are blocked whatever the permit. `WriteFlowTest` checks this against a mock VTOP.

## Build and install

Needs JDK 17 and the Android SDK (platform 35). Create `local.properties` with
`sdk.dir=...` on a new machine.

```bash
./gradlew assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds can show made-up demo data without an account, for checking screens on
an emulator: `adb shell run-as dev.medriid.davit.dev touch files/demo`, then restart
the app. Release builds have no demo.

To sign in, the build needs the captcha model and the slot table in
`core/src/main/resources/dev/medriid/davit/vtop/` (both gitignored); see
[`../docs/05-captcha-solver.md`](../docs/05-captcha-solver.md).

## Tests

```bash
./gradlew :core:test :app:testDebugUnitTest
```

Login flow against a scripted mock VTOP, the parsers against fixtures, the
attendance rule exhaustively, and (when the model is present) bit-for-bit parity
between the Kotlin and Python captcha solvers.

## How the riso pieces map to Compose

| Web | Compose |
|---|---|
| `mix-blend-mode: multiply` | `graphicsLayer { blendMode = BlendMode.Multiply }`, or `BlendMode.Multiply` in draw calls |
| Halftone text | `TextStyle(brush = ShaderBrush(ImageShader(dotTile, Repeated, Repeated)))` |
| Register animation | `Animatable<DpOffset>` springing into place (`RisoMotion.register()`) |
| Paper grain | A seeded noise tile drawn over the root at 20% alpha, multiply |
| `prefers-reduced-motion` | `rememberMotionEnabled()` reads the system animator scale |
