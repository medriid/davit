# Bundled fonts

All three families are under the SIL Open Font License 1.1
(https://openfontlicense.org), which allows bundling them in an app.
Files were fetched from Google Fonts as static TTFs.

| File in `src/main/res/font` | Family | Weight | Copyright |
|---|---|---|---|
| `bagel_fat_one.ttf` | Bagel Fat One | 400 | The Bagel Fat One Project Authors |
| `plex_mono_regular.ttf` | IBM Plex Mono | 400 | IBM Corp. |
| `plex_mono_semibold.ttf` | IBM Plex Mono | 600 | IBM Corp. |

Bagel Fat One is 1.5 MB because it carries Korean glyphs. Subsetting it to Latin
is an easy APK saving for M8.
