# ProjectSecurityScanner

Analyseur local de documents texte qui repère les données sensibles — mots de passe,
clés et jetons, données personnelles, coordonnées bancaires, fuites d'infrastructure —
**sans rien envoyer sur le réseau**.

- **Moteur** : Rust (`crates/sensitive-core`), analyse linéaire sans regex catastrophique,
  15 règles YAML déclaratives réparties dans `crates/sensitive-core/src/rules/`.
- **Interface** : TypeScript + Vite en DOM natif (`apps/web`, aucun framework),
  le moteur tourne dans un Web Worker via WebAssembly.
- **Ligne de commande** : binaire `scanner` (`crates/sensitive-cli`).
- **Même moteur partout** : le CLI, l'application web et le WASM compilent le même code Rust,
  avec les mêmes règles et les mêmes validateurs (`luhn`, `iban_mod97`, `ean13`, `private_key_pem`,
  `aws_access_key`, `jwt`, `email`, `iban_fr_rib`).
- **Licence** : AGPL-3.0-or-later.
- **Aucun réseau** : pas de crate réseau dans le workspace, pas de requête sortante depuis
  l'application (voir `crates/sensitive-core/tests/no_network.rs`).
- **Aucune fuite** : un `Finding` ne porte jamais l'échantillon brut, seulement des offsets
  et un aperçu masqué (`••••••••••••0000`, quatre derniers caractères conservés).

Le cahier des charges complet est dans [`ProjectSecurityScanner.md`](ProjectSecurityScanner.md).

## Prérequis

- Rust stable via rustup, avec la cible WebAssembly :

  ```sh
  rustup target add wasm32-unknown-unknown
  ```

- Node.js 20 ou supérieur (npm).

Aucune clé d'API, aucun compte, aucune variable d'environnement ne sont nécessaires.

## Architecture

```
Cargo.toml                    workspace Rust
crates/sensitive-core/        moteur : règles YAML, évaluation, confiance, masquage, JSON
crates/sensitive-cli/         binaire `scanner` (clap), décodage des encodages
crates/sensitive-wasm/        exports WASM `pss_alloc` / `pss_dealloc` / `pss_scan`
apps/web/                     application Vite (DOM natif), Worker, page de smoke test
```

## Commandes

Depuis la racine :

```sh
cargo test --workspace        # tests Rust (règles, validateurs, moteur, WASM, no-network)
cargo clippy --all-targets    # analyse statique, sans warning
cargo fmt --all --check       # formatage
```

Depuis `apps/web/` :

```sh
npm install
npm run lint                  # TypeScript, --noEmit
npm run wasm                  # compile le moteur et copie le .wasm dans src/wasm/
npm run dev                   # serveur de développement sur http://localhost:3000
npm run build                 # vérifie le TypeScript puis produit dist/
npm test                      # cargo test --workspace
npm run test:smoke            # test de bout en bout dans Chrome headless (voir plus bas)
```

Ligne de commande :

```sh
cargo run -p sensitive-cli -- rules list                 # 15 règles
cargo run -p sensitive-cli -- rules explain fin.iban      # détail d'une règle
cargo run -p sensitive-cli -- scan --format text --redact fichier.txt
echo 'IBAN FR7600000000000000000000000' | cargo run -p sensitive-cli -- scan --stdin --exit-code
```

Codes de sortie du CLI : `0` rien trouvé, `1` erreur d'usage, `2` détection trouvée
(avec `--exit-code`).

## Vérifications

Dernier passage complet :

| Vérification | Résultat |
| --- | --- |
| `cargo test --workspace` | 63 tests, 0 échec (dont corpus 200 docs + budget FP) |
| `cargo clippy --all-targets` | 0 warning |
| `cargo fmt --all --check` | propre |
| `npm run lint` (`tsc --noEmit`) | 0 erreur |
| `npm run build` | OK (WASM 1 768 ko) |
| `npm run test:smoke` | SMOKE_OK — 3 scénarios (texte, parcours app, .docx) |
| smoke test Chrome (`npm run test:smoke`) | OK, 4 détections, 0 valeur en clair |
| parcours réel (saisie → rapport) | OK, 2 détections masquées |

## Smoke test de bout en bout

`apps/web/scripts/smoke.mjs` pilote Chrome en mode sans-tête via le protocole DevTools
(sans dépendance npm) :

```sh
npm run dev          # terminal 1
npm run test:smoke   # terminal 2
```

Trois scénarios sont couverts :

1. `smoke.html` charge le WASM dans le Worker, analyse un document contenant IBAN,
   carte, e-mail et JWT, et publie le résultat dans le DOM : 4 détections
   (3 certaines, 1 probable), aucune valeur en clair, aperçus masqués.
2. La page d'accueil réelle reçoit une saisie, lance le scan, affiche le rapport,
   et le test vérifie que ni l'IBAN ni le numéro de carte n'apparaissent en clair.
3. Un vrai `.docx` (archive générée en mémoire) est déposé dans la page : le texte
   est extrait par le WASM, 3 détections, encodage `docx` affiché, aucune valeur en clair.

## Limites assumées

- Textes et fichiers bureautiques analysés : `.docx`, `.xlsx` et `.pptx` (extension du texte des XML de l'archive) et fichiers texte. Les PDF, images et archives non bureautiques sont refusés explicitement : l'application explique pourquoi plutôt que de faire semblant de les lire (c'est le pire mode de défaillance du cahier des charges).
- Encodage deviné (BOM, `chardetng`), 50 Mio par document au maximum.
- L'interface sépare honnêtement ce qui a été couvert de ce qui ne l'a pas été.

## Documentation

- [`docs/build.md`](docs/build.md) — prérequis et compilations (Rust, WASM, web, fuzz)
- [`docs/threat-model.md`](docs/threat-model.md) — modèle de menace, ce qui est détecté et ce qui ne l'est pas
- [`docs/false-positives.md`](docs/false-positives.md) — budget de faux positifs, corpus, mesure
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — ajouter une règle (avec modèle)
- [`SECURITY.md`](SECURITY.md) — politique de remontée de faille

## Licence

AGPL-3.0-or-later. Voir [`LICENSE`](LICENSE).
