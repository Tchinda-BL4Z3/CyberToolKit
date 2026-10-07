//! F-69 — fuzzing du parseur d'archives bureautiques (`sensitive-extract`) :
//! le ZIP est une entrée non confiée, même en local.

#![no_main]

use libfuzzer_sys::fuzz_target;
use sensitive_extract::extract;

fuzz_target!(|data: &[u8]| {
    if data.len() > 8 * 1024 * 1024 {
        return;
    }
    // Erreurs attendues (archive refusée, limites F-31) : on vérifie seulement
    // l'absence de panique, d'explosion mémoire ou de dépassement.
    match extract(data) {
        Ok(_) | Err(_) => {}
    }
});