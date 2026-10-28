# daVIT for Android

Kotlin + Jetpack Compose, min SDK 26.

## Modules

| Module | What's in it |
|---|---|
| `core/` | Plain JVM, no Android: `VtopClient` (login, session reuse, read-only guard, per-stage timings), `CaptchaSolver`, Jsoup `Parsers`, `SlotTimes`. `./gradlew :core:test` runs on a laptop. |
| `design/` | Riso components: ink pairs, `Overprint`, `Halftone`, `InkButton`, `InkCard`, `Seal` (the app mark), `Grain`, motion. Fonts and licences in [`design/FONTS.md`](design/FONTS.md). |
| `app/` | `data/`: `Repo` (sign-in, sync, sign-out), `Vault` (Keystore AES-256-GCM), `Snapshot` (encrypted cache), `Schedule` (week, now/next, the 75% maths). `ui/`: sign-in, the four tabs, the account sheet and the mascot. |

## Build and install

Needs JDK 17 and the Android SDK (platform 35). Create `local.properties` with
`sdk.dir=...` on a new machine.

```bash
./gradlew assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

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
