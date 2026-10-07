//! F-67 : le moteur et la CLI ne doivent dépendre d'aucun réseau.
//!
//! La vérification porte sur `Cargo.lock` : une dépendance réseau qui
//! transiterait par une autre crate serait détectée ici même si elle n'est
//! pas citée directement dans les manifestes.

use std::path::PathBuf;

const DENY: &[&str] = &[
    "reqwest",
    "ureq",
    "hyper",
    "attohttpc",
    "curl",
    "curl-sys",
    "isahc",
    "native-tls",
    "openssl",
    "rustls",
    "tokio",
    "async-std",
    "mio",
    "socket2",
    "websocket",
    "jsonrpc",
    "smoltcp",
    "ipnetwork",
];

#[test]
fn aucune_dependance_reseau_dans_le_lockfile() {
    let lock = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../Cargo.lock");
    let content = std::fs::read_to_string(&lock)
        .expect("Cargo.lock introuvable : lancez d'abord `cargo build` à la racine du workspace");

    let mut offenders = Vec::new();
    for line in content.lines() {
        let line = line.trim();
        if let Some(name) = line.strip_prefix("name = \"") {
            if let Some(name) = name.strip_suffix('"') {
                if DENY.contains(&name) {
                    offenders.push(name.to_string());
                }
            }
        }
    }

    offenders.sort();
    offenders.dedup();
    assert!(
        offenders.is_empty(),
        "dépendances réseau présentes dans le lockfile : {offenders:?}"
    );
}

#[test]
fn le_moteur_ne_lien_aucun_socket() {
    let manifest = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("Cargo.toml");
    let content = std::fs::read_to_string(&manifest).expect("manifeste illisible");
    assert!(
        !content.contains("tokio") && !content.contains("async-std"),
        "le moteur est synchrone par principe : {content}"
    );
}
