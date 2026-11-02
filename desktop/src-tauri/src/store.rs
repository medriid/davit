//! What stays on this computer: your password in the system's keychain, and everything else
//! in files encrypted with a key kept there too. Signing out erases all of it.

use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Key, Nonce};
use base64::Engine;
use rand::RngCore;
use std::path::PathBuf;

const SERVICE: &str = "daVIT";
const B64: base64::engine::GeneralPurpose = base64::engine::general_purpose::STANDARD;

pub struct Store {
    dir: PathBuf,
    previews: PathBuf,
}

fn entry(name: &str) -> Result<keyring::Entry, String> {
    keyring::Entry::new(SERVICE, name).map_err(|_| "This computer's keychain isn't available.".to_string())
}

impl Store {
    pub fn new(dir: PathBuf, cache: PathBuf) -> Store {
        std::fs::create_dir_all(&dir).ok();
        let previews = cache.join("previews");
        std::fs::create_dir_all(&previews).ok();
        Store { dir, previews }
    }

    pub fn previews(&self) -> &PathBuf {
        &self.previews
    }

    fn key(&self) -> Result<Vec<u8>, String> {
        let e = entry("vault-key")?;
        if let Ok(k) = e.get_password() {
            if let Ok(bytes) = B64.decode(k) {
                if bytes.len() == 32 {
                    return Ok(bytes);
                }
            }
        }
        let mut k = vec![0u8; 32];
        rand::thread_rng().fill_bytes(&mut k);
        e.set_password(&B64.encode(&k)).map_err(|_| "Couldn't save to this computer's keychain.".to_string())?;
        Ok(k)
    }

    /// Writes [name], encrypted. Replaced in one step, so a crash can't leave half a file.
    pub fn write(&self, name: &str, text: &str) -> Result<(), String> {
        let key = self.key()?;
        let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(&key));
        let mut nonce = [0u8; 12];
        rand::thread_rng().fill_bytes(&mut nonce);
        let sealed = cipher.encrypt(Nonce::from_slice(&nonce), text.as_bytes()).map_err(|_| "Couldn't encrypt.".to_string())?;
        let mut out = nonce.to_vec();
        out.extend(sealed);
        let tmp = self.dir.join(format!("{name}.tmp"));
        std::fs::write(&tmp, out).map_err(|e| e.to_string())?;
        std::fs::rename(&tmp, self.dir.join(name)).map_err(|e| e.to_string())
    }

    pub fn read(&self, name: &str) -> Option<String> {
        let bytes = std::fs::read(self.dir.join(name)).ok()?;
        if bytes.len() < 13 {
            return None;
        }
        let key = self.key().ok()?;
        let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(&key));
        let plain = cipher.decrypt(Nonce::from_slice(&bytes[..12]), &bytes[12..]).ok()?;
        String::from_utf8(plain).ok()
    }

    pub fn save_credentials(&self, username: &str, password: &str) -> Result<(), String> {
        entry("credentials")?
            .set_password(&serde_json::json!({ "u": username, "p": password }).to_string())
            .map_err(|_| "Couldn't save to this computer's keychain.".to_string())
    }

    pub fn credentials(&self) -> Option<(String, String)> {
        let v: serde_json::Value = serde_json::from_str(&entry("credentials").ok()?.get_password().ok()?).ok()?;
        Some((v["u"].as_str()?.to_string(), v["p"].as_str()?.to_string()))
    }

    /// Sign-out: nothing of the account stays behind, the key included.
    pub fn wipe(&self) {
        if let Ok(e) = entry("credentials") {
            let _ = e.delete_credential();
        }
        if let Ok(e) = entry("vault-key") {
            let _ = e.delete_credential();
        }
        std::fs::remove_dir_all(&self.dir).ok();
        std::fs::create_dir_all(&self.dir).ok();
        std::fs::remove_dir_all(&self.previews).ok();
        std::fs::create_dir_all(&self.previews).ok();
    }
}
