//! The captcha model and slot table aren't in the repository, so a build from source runs
//! but can't sign in (docs/05). The map and reviews come from the Android app's assets.

use std::path::Path;

fn main() {
    println!("cargo:rustc-check-cfg=cfg(has_private_data)");
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
    let res = root.join("android/core/src/main/resources/dev/medriid/davit/vtop");
    let out = std::env::var("OUT_DIR").unwrap();
    println!("cargo:rerun-if-changed={}", res.display());
    if res.join("captcha_model.bin").exists() && res.join("slot_times.txt").exists() {
        std::fs::copy(res.join("captcha_model.bin"), Path::new(&out).join("captcha_model.bin")).unwrap();
        std::fs::copy(res.join("slot_times.txt"), Path::new(&out).join("slot_times.txt")).unwrap();
        println!("cargo:rustc-cfg=has_private_data");
    }
    let assets = root.join("android/app/src/main/assets");
    let data = root.join("desktop/ui/data");
    std::fs::create_dir_all(&data).unwrap();
    for (from, to) in [("faculty-reviews/reviews.json", "reviews.json"), ("campus-map/campus.json", "campus.json"), ("campus-map/geo.json", "geo.json")] {
        std::fs::copy(assets.join(from), data.join(to)).unwrap();
        println!("cargo:rerun-if-changed={}", assets.join(from).display());
    }
    tauri_build::build()
}
