//! VTOP's text captcha, solved on this computer: per-pixel saturation, six fixed cells, each
//! binarised against its own mean, then a single-layer classifier. Same arithmetic as Android.

const W: usize = 200;
const H: usize = 40;
const CELL_H: usize = 22;
const CELL_W: usize = 24;
const OUTPUTS: usize = 32;

pub struct Solver {
    alphabet: Vec<char>,
    weights: Vec<f64>,
    biases: Vec<f64>,
}

/// Rounds half to even, as Java's `Math.rint`.
fn rint(v: f64) -> f64 {
    let r = v.round();
    if (v - v.trunc()).abs() == 0.5 && r % 2.0 != 0.0 {
        r - v.signum()
    } else {
        r
    }
}

impl Solver {
    /// The model file: an alphabet line, then 528x32 + 32 big-endian doubles.
    pub fn parse(bytes: &[u8]) -> Option<Solver> {
        let nl = bytes.iter().position(|b| *b == b'\n')?;
        let alphabet: Vec<char> = bytes[..nl].iter().map(|b| *b as char).collect();
        let mut doubles = bytes[nl + 1..].chunks_exact(8).map(|c| f64::from_be_bytes(c.try_into().unwrap()));
        let weights: Vec<f64> = doubles.by_ref().take(CELL_H * CELL_W * OUTPUTS).collect();
        let biases: Vec<f64> = doubles.take(OUTPUTS).collect();
        if weights.len() != CELL_H * CELL_W * OUTPUTS || biases.len() != OUTPUTS {
            return None;
        }
        Some(Solver { alphabet, weights, biases })
    }

    /// Six characters; '?' where a cell lands on an output with no letter. `rgb` is row-major.
    pub fn solve(&self, width: usize, height: usize, rgb: &[[u8; 3]]) -> String {
        let at = |x: usize, y: usize| -> [u8; 3] {
            if width == W && height == H {
                rgb[y * W + x]
            } else {
                rgb[(y * height / H) * width + (x * width / W)]
            }
        };
        let mut sat = vec![0i32; W * H];
        for y in 0..H {
            for x in 0..W {
                let [r, g, b] = at(x, y);
                let (mx, mn) = (r.max(g).max(b) as f64, r.min(g).min(b) as f64);
                sat[y * W + x] = if mx == 0.0 { 0 } else { rint((mx - mn) * 255.0 / mx) as i32 };
            }
        }
        let mut out = String::with_capacity(6);
        for cell in 0..6 {
            let r0 = if cell % 2 == 0 { 8 } else { 13 };
            let c0 = (cell + 1) * 25 + 2;
            let mut sum = 0.0;
            for y in 0..CELL_H {
                for x in 0..CELL_W {
                    sum += sat[(r0 + y) * W + c0 + x] as f64;
                }
            }
            let mean = sum / (CELL_H * CELL_W) as f64;
            let (mut best, mut best_logit) = (0, f64::NEG_INFINITY);
            for k in 0..OUTPUTS {
                let mut logit = self.biases[k];
                for y in 0..CELL_H {
                    for x in 0..CELL_W {
                        if sat[(r0 + y) * W + c0 + x] as f64 > mean {
                            logit += self.weights[(y * CELL_W + x) * OUTPUTS + k];
                        }
                    }
                }
                if logit > best_logit {
                    best_logit = logit;
                    best = k;
                }
            }
            out.push(self.alphabet.get(best).copied().unwrap_or('?'));
        }
        out
    }

    pub fn solve_image(&self, bytes: &[u8]) -> Option<String> {
        let img = image::load_from_memory(bytes).ok()?.to_rgb8();
        let (w, h) = (img.width() as usize, img.height() as usize);
        let px: Vec<[u8; 3]> = img.pixels().map(|p| p.0).collect();
        Some(self.solve(w, h, &px))
    }

    /// A solve worth submitting: six known characters.
    pub fn is_confident(text: &str) -> bool {
        text.chars().count() == 6 && !text.contains('?')
    }
}
