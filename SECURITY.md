# Contribuer à ProjectSecurityScanner

Le projet détecte des données sensibles **localement** : aucun réseau, aucun
échantillon brut transporté, aucune dépendance qui violate ce contrat.
Toute contribution doit le préserver.

## Convention d'ingénierie

- **Commentaires** : interdits dans le code sauf si le maintien l'exige (invariants
  de sécurité, algorithmes non triviaux). Le code se lit tout seul.
- **Zéro émulation** : aucun rapport « de démonstration » ni donnée inventée.
  Un « aucun résultat » n'est déclaré que sur du texte réellement lu.
- **Pas de secret dans le dépôt** : `.env*`, `*.pem`, `*.key` sont exclus ; une
  valeur de test ressemblant à un secret doit rester dans un fichier de test
  qui ne contient que des valeurs de démonstration.

## Ajouter une règle de détection

1. Ouvrir le catalogue : `crates/sensitive-core/src/rules/<catégorie>.yaml`
   (`credentials.yaml`, `financial.yaml`, `infra.yaml`, `pii.yaml`).
2. Copier un bloc `- id: …` existant et modifier :
   - `id` : préfixe de catégorie (`cred.`, `fin.`, `infra.`, `pii.`) + nom unique ;
   - `extract.pattern` : regex Rust `regex` **sans look-around** (non supporté),
     avec `max_length` pour borner les `(?:…)*` ;
   - `validate` : algorithme nommé dans `crates/sensitive-core/src/validators/mod.rs`
     si une validation renforce la détection (checksum, structure) ;
   - `context.keywords` : mots qui font passer `possible` → `certain`/`probable` ;
   - `redaction.strategy` : `keep_last4` ou `type_only` (jamais la valeur en clair) ;
   - `confidence` : diligence sur `format_only`, qui déclenche même sans contexte.
3. Ajouter le validateur éventuel **et son test unitaire** dans `validators/mod.rs`.
4. Ajouter une ligne dans le corpus `crates/sensitive-core/tests/corpus_budget.rs`
   (motif positif, AMA valeur de contrôle calculée) et, si utile, un cas fou dans
   les documents négatifs. **Le budget de faux positifs est un test** : ≤ 5 % sur
   50 documents sans donnée sensible.
5. Lancer la chaîne de contrôle (section suivante). Le corpus vérifie que chaque
   règle du catalogue est couverte par au moins un document.

## Chaîne de contrôle obligatoire

```sh
# Lecteur Rust (prérequis : rustup / cargo, cible wasm32)
cargo test --workspace
cargo clippy --workspace --all-targets        # 0 warning attendu
cargo fmt --all --check

# Web (prérequis : Node 20+)
cd apps/web
npm run lint                                   # tsc --noEmit
npm run wasm                                   # régénère src/wasm/sensitive_wasm.wasm
npm run build
npm run test:smoke                             # navigateur headless réel, 3 scénarios
```

## Fuzzing (F-69)

```sh
rustup toolchain install nightly --profile minimal
cargo +nightly install cargo-fuzz              # une fois
cargo +nightly fuzz run scan_engine            # moteur de règles
cargo +nightly fuzz run archive_zip            # parseur DOCX/XLSX/PPTX
```

Aucune panique ni dépassement n'est toléré sur une entrée arbitraire : les
documents sont des entrées non confiées, même en local.

## Fichiers utiles

| Chemin | Rôle |
| --- | --- |
| `crates/sensitive-core/src/rules/*.yaml` | catalogue des règles |
| `crates/sensitive-core/src/validators/mod.rs` | validateurs et leurs tests |
| `crates/sensitive-core/tests/corpus_budget.rs` | corpus 200 docs + budget FP |
| `crates/sensitive-core/tests/no_network.rs` | preuve « aucun réseau » (F-44) |
| `crates/sensitive-extract/src/lib.rs` | extraction DOCX/XLSX/PPTX |
| `crates/sensitive-wasm/src/lib.rs` | ABI `pss_scan` / `pss_scan_file` |
| `apps/web/scripts/smoke.mjs` | smoke de bout en bout (navigateur réel) |
</think>

<｜DSML｜tool_calls>
<｜DSML｜invoke name="write">
<｜DSML｜parameter name="content" string="true"># Politique de sécurité — ProjectSecurityScanner

## Portée

L'outil traite des données potentiellement sensibles **localement**. Ce document
décrit la procédure de signalement et les points d'entrée à protéger.

## Signaler une vulnérabilité

- Ne créez pas d'issue publique contenant une valeur réelle (IBAN, carte,
  jeton) : rédigez l'exemple avec des valeurs de démonstration ou des symboles.
- Envoyez le signalement à une adresse du responsable de maintenance de ce
  dépôt (issue privée, ou courriel sécurisé si mentionné dans les métadonnées
  du dépôt).
- Décrivez : l'entrée déclenchante, la version concernée, l'impact attendu.

Tout signalement pertinent est traité sous réponse confirmant la prise en
compte, puis correctif documenté dans l'historique.

## Sensibilité du produit

Les données d'entrée (textes, fichiers bureautiques) sont traitées exclusivement
en mémoire dans le processus du navigateur / du CLI, sans réseau sortant. Le
rapport **ne transporte jamais l'échantillon brut** : offsets absolus et aperçus
masqués (`keep_last4` / `type_only`). La re-décoration du texte original est
faite côté interface à partir des offsets.

## Points d'entrée à surveiller

- `crates/sensitive-wasm/src/lib.rs` : ABI `pss_scan` (texte) et
  `pss_scan_file` (archive DOCX/XLSX/PPTX). Entrées arbitraires : fuzzées
  (`fuzz/fuzz_targets/scan_engine.rs`, `archive_zip.rs`, F-69).
- `crates/sensitive-extract/src/zip_limits.rs` : limites anti-bombe ZIP
  (entrées, volume décompressé, ratio) — F-31. Un dépassement doit échouer,
  jamais consommer la mémoire du client.
- `crates/sensitive-core/src/rules/*.yaml` : motifs `regex` **sans look-around**.
- Côté web : `apps/web/src/worker.ts` (exécution hors UI), `engineClient.ts`.
- La purge(`Purger`) supprime le texte et les findings de la session ; les
  tampons WASM sont effacés avant libération (`wipe`, F-76).

## Ce qui n'est pas couvert

- PDF et images : refusés explicitement avant lecture (aucun OCR en v1).
- Fichiers binaires et archives non bureautiques : refusés.
- Fichiers > 50 Mio : refusés avant lecture.
- L'apparence d'un résultat « aucun » ne porte que sur le texte réellement lu.

## Secrets du dépôt

`.env*`, `*.pem`, `*.key` sont exclus via `.gitignore` ; aucune valeur de
validation n'y figure. Signalez tout secret de configuration retrouvé dans
l'historique comme un incident.