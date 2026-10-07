//! F-69 — fuzzing du moteur de règles (`engine::scan`) sur des entrées
//! arbitraires : aucun tableau, aucun dépassement, aucune panique.

#![no_main]

use libfuzzer_sys::fuzz_target;
use sensitive_core::{engine::scan, ScanOptions, RuleSet};
use std::sync::OnceLock;

fn rules() -> &'static RuleSet {
    static RULES: OnceLock<RuleSet> = OnceLock::new();
    RULES.get_or_init(RuleSet::embedded)
}

fuzz_target!(|data: &[u8]| {
    if data.len() > 1024 * 1024 {
        return;
    }
    let text = String::from_utf8_lossy(data);
    let _ = scan(&text, rules(), &ScanOptions::all());
});