# Construction — ProjectSecurityScanner

Prérequis par plateforme et compilations documentées. Le produit cible
navigateur (WebAssembly) et CLI (`scanner`), puis Desktop Tauri en phase dédiée.

## Prérequis communs

- **Rust** stable (rustup), avec la cible `wasm32-unknown-unknown` :
  ```sh
  rustup target add wasm32-unknown-unknown
  ```
- **Node.js** 20+ (18 a été utilisé au développement) pour l'application web.
- **Chrome / Chromium** en mode sans-tête pour le smoke test de bout en bout
  (`--headless=new` est requis ; voir ci-dessous).

### Linux

Le CLI et le moteur WASM n'ont aucune dépendance système particulière. Pour
l'application web (Vite), aucun paquet natif. Le futur wrapper Desktop (Tauri 2,
phase dédiée) requiert `libwebkit2gtk-4.1-dev` + `build-essential` + `libssl-dev`
sur Debian/Ubuntu (non installé pour l'instant, hors scope v1).

### Windows 10/11

Phase dédiée ultérieure (décision v1). Aucune compilation Windows n'est requise
avant cette étape.

## Compiler

```sh
# Moteur + CLI
cargo build --workspace

# WebAssembly (fabrique apps/web/src/wasm/sensitive_wasm.wasm)
cd apps/web && npm run wasm

# Application web (génère dist/)
npm install          # une fois
npm run build
npm run dev          # serveur de développement
```

## Vérifier

```sh
cargo test --workspace
cargo clippy --workspace --all-targets
cargo fmt --all --check
cd apps/web
npm run lint
npm run build
npm run test:smoke
```

Le smoke test (`npm run test:smoke`) démarre son propre serveur Vite sur le
port 3113, ouvre un Chrome sans-tête piloté en CDP, et exécute 3 scénarios :
document d'échantillon (`smoke.html`), parcours complet de l'application, et
analyse d'un `.docx` de démonstration généré en mémoire. Il échoue si une
valeur sensible apparaît en clair dans la page.

## Fuzzer (F-69)

```sh
rustup toolchain install nightly --profile minimal
cargo +nightly install cargo-fuzz            # une fois, à la racine du dépôt
cargo +nightly fuzz run scan_engine          # moteur de règles (aucune panique)
cargo +nightly fuzz run archive_zip          # parseur DOCX/XLSX/PPTX
```

## Artefacts

| Artefact | Origine |
| --- | --- |
| `sensitive_wasm.wasm` | `cargo build -p sensitive-wasm --release --target wasm32-unknown-unknown`, copié par `npm run wasm` |
| `dist/` | `npm run build` (Vite) |
| binaire `scanner` | `cargo build -p sensitive-cli` |

Version consultée : v0.1.0 — 60+ tests Rust, clippy 0 warning, formatage propre,
smoke 3 scénarios passants.