# 05 · Captcha solver

VTOP's text captcha is a 200×40 image with **6 characters** drawn in colour over
a grey speckle background, from the alphabet
`ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (31 chars — note no `I`, `O`, `0`, `1`).

daVIT reads it on the device with a tiny model: one dense layer, no network call.
The pipeline is in `web/captcha_solver.py` and, bit-for-bit identical, in
`android/core/.../CaptchaSolver.kt`.

## The model
A **single dense layer with softmax** (i.e. multinomial logistic regression):
- `weights`: 528 × 32
- `biases`: 32
- 32 outputs, but only the first 31 map to characters (the 32nd is an unused
  "blank" class; if argmax lands there the solve is rejected and retried).

Stored at `web/model/captcha_model.json`:
```json
{ "weights": [[...32...], ...528 rows...], "biases": [...32...],
  "alphabet": "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" }
```

### Supplying the weights
The trained weights are **not in this repository**. To build a working copy, put
`captcha_model.json` (format above) in `web/model/`, then convert it for Android:

```bash
py tools/model_to_bin.py web/model/captcha_model.json
```

That writes `android/core/src/main/resources/dev/medriid/davit/vtop/captcha_model.bin`
(the alphabet, a newline, then 528×32 weights and 32 biases as big-endian doubles).
Both paths are gitignored. Without them the app builds and runs but says it can't
sign in, and the parity test is skipped.

## The pipeline
1. **Rasterize** to 200×40 RGB. VTOP serves the captcha natively at 200×40, so no
   resampling happens in practice (a pixel-exact match to the browser canvas).
2. **Saturation map.** For each pixel: `s = (max(R,G,B)==0) ? 0 :
   round((max-min)*255/max)`. This is HSV saturation ×255 — it suppresses the
   grey noise (low saturation) and keeps the coloured glyphs (high saturation).
3. **Slice 6 cells.** Character `i` (0-5):
   - rows: `[8:30]` for even `i`, `[13:35]` for odd `i` (22 rows)
   - cols: `[(i+1)*25+2 : (i+2)*25+1]` (24 cols)
   - → each cell is 22×24 = **528** values (matches the weight rows).
4. **Binarize** each cell against its own mean (`v > mean ? 1 : 0`), flatten
   row-major → a 528-length bit vector.
5. **Classify.** `logits = bits · W + b`; softmax; `argmax`; index into the
   alphabet. Do this for all 6 cells → the 6-char string.

## Verification
Done against live VTOP **without any real credentials**: fetch a real captcha,
solve it, then submit `/login` with the solved captcha and a bogus username. VTOP
replied **"invalid credentials"** (not "invalid captcha") — proving VTOP accepted
the solved captcha. See `web/captcha_solver.py` and the login flow in
`web/vtop_client.py`.

## Accuracy & retries
It's a small linear model, so it won't be 100% per attempt. `vtop_client.login()`
retries with a **fresh captcha** each time (up to `max_attempts`, default 12),
which makes an overall success very likely. A solve that produces fewer than 6
valid chars (or hits the blank class) is discarded and retried.

## Limitations
- Only the **text** captcha. VTOP picks the captcha per visit; when it serves
  Google **reCAPTCHA** alone, daVIT walks back in from `/open/page` with a growing
  pause (up to 10 times) until it gets the text one, and only then gives up.
- If VIT ever changes the captcha font/layout/size, the model would need
  retraining. The preprocessing geometry (cell slicing) is also layout-specific.
