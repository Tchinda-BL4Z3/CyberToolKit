# ProjectSecurityScanner — Cahier des charges

> **Statut** : document de cadrage, à valider avant toute ligne de code.
>
> **Note de placement** : ce document est écrit dans le dépôt `cybertoolkit`, qui est
> un projet *différent*, arrêté et non publié. Il doit être déplacé vers son propre
> dépôt `ProjectSecurityScanner/` avant le premier commit, sinon il sera versionné
> avec le projet abandonné.

---

## 1. Résumé en une phrase

Un détecteur de données sensibles, **entièrement local**, qui inspecte un document
avant que l'utilisateur ne l'envoie, et le dit en langage clair.

## 2. Le problème

Quand quelqu'un joint un PDF à un courriel, colle un tableau dans une IA
conversationnelle, ou dépose un fichier sur un espace de partage, personne ne
vérifie si ce document contient un numéro de carte, un IBAN, une clé d'API ou une
clé privée.

Ce problème est **récent** : il vient d'usages nouveaux (IA conversationnelle,
partage massif, télétravail), pas d'un manque ancien. Les outils existants
(`gitleaks`, `trufflehog`, `detect-secrets`) couvrent très bien les **dépôts Git
et l'historique de commits**. Ils ne couvrent pas les **documents qu'une personne
s'apprête à transmettre**.

> **À valider avant d'investir** : cette affirmation d'espace non occupé est une
> perception, pas une étude de marché. Vérifier sur les stores, les collections
> d'extensions et les dépôts GitHub avant la phase 2. Si l'espace est occupé,
> l'angle « avant partage » reste valable mais le positionnement change.

## 3. Objectifs et non-objectifs

### Objectifs

1. Détecter les données sensibles dans un document **sans jamais l'envoyer**.
2. Ne jamais afficher un faux positif comme une certitude.
3. Être utilisable sans compte, sans installation pour la version web, et en
   ligne de commande pour l'automatisation.
4. Chaque règle ajoutée doit fonctionner sur **toutes** les plateformes cibles.

### Non-objectifs (explicites, pour éviter la dérive)

- **Pas de protection en temps réel** contre l'exfiltration. L'outil avertit au
  moment du partage, il n'intercepte pas le trafic réseau.
- **Pas d'OCR** en version 1. Une image scannée n'est pas analysée, et l'outil
  doit le dire explicitement plutôt que de renvoyer un « aucun résultat » trompeur.
- **Pas d'analyse de dépôt Git** — c'est le territoire de `gitleaks`, déjà bien couvert.
- **Pas de conformité réglementaire**. Ce n'est pas un produit DLP d'entreprise et
  il ne doit pas prétendre l'être.
- **Pas de détection de « tout ce qui est rare »**. Une heuristique d'entropie
  seule ne suffit pas : trop de faux positifs.

---

## 4. Décisions d'architecture prises à la racine

Ces décisions sont prises **maintenant** parce qu'elles coûtent cher à changer
après. C'est le sens de « sans faute à la racine ».

### 4.1 Un seul moteur, compilé plusieurs fois

Le moteur de détection est **un crate Rust sans dépendance à une plateforme**.
Il est compilé en natif (desktop, CLI) et en `wasm32` (web, mobile). Une règle
écrite une fois fonctionne partout.

**Conséquence à accepter** : le moteur ne peut utiliser aucun crate qui n'existe
pas en WASM. Vérifier chaque dépendance avec `cargo tree --target wasm32-unknown-unknown`
avant de l'ajouter.

**Interdit** : réécrire le moteur en TypeScript pour le web. Ce serait deux
implémentations à maintenir et à maintenir *correctement*, ce qu'aucun projet
open source ne survit.

### 4.2 Le moteur ne fait aucun accès réseau

`crates/sensitive-core/` ne doit contenir **aucune dépendance réseau**, et c'est
vérifié automatiquement (§8.4). C'est ce qui rend la promesse « 100 % local »
auditable par lecture du code, et pas seulement affirmée.

### 4.3 Détection par extraction puis validation — jamais par regex géante

C'est la décision technique la plus importante du projet.

**Interdit** : une expression régulière qui essaie de valider un numéro de carte
ou d'IBAN. Non seulement c'est illisible, mais `fancy-regex` (qui supporte le
lookaround) **retourne en arrière** et est donc vulnérable au ReDoS sur une donnée
non contrôlée — c'est-à-dire précisément nos données.

**Architecture retenue, en deux temps** :

1. **Extraction** — le crate `regex` (temps linéaire, sans retour en arrière, pas
   de ReDoS) identifie des **candidats** : des séquences de chiffres ou de
   caractères alphanumériques à contexte plausible.
2. **Validation** — du code Rust écrit à la main applique les règles de structure :
   clé de Luhn pour les cartes, modulo 97 pour l'IBAN, somme de contrôle EAN-13,
   structure des clés privées, etc.

Cette séparation rend le ReDoS **structurellement impossible** dans la phase
critique, au lieu de compter sur la prudence de l'auteur d'une regex.

### 4.4 Les faux positifs sont la contrainte d'architecture, pas un bug

C'est le point le plus important du cahier des charges, et il doit conditionner
la conception, pas la réparer plus tard.

Un numéro de commande, une date, un identifiant de facture et un numéro de carte
se ressemblent. Un détecteur qui confond les deux est **désactivé par
l'utilisateur en deux jours**, et ce désactivation est un échec produit, pas un
bug.

**Le seuil de faux positifs est fixé avant l'écriture de la première règle.** Il
est mesuré sur un corpus réel (§8.2) et.any change afterward est un changement
de produit qui doit être justifié par écrit.

Chaque détection porte donc un **niveau de confiance** et non un booléen :

| Niveau | Signification | Présentation |
|---|---|---|
| `Certain` | Structure valide + contexte explicite (« IBAN : », « carte n° ») | Alerte |
| `Probable` | Structure valide, contexte absent ou faible | Alerte douce, avec justification affichée |
| `Possible` | Structure invalide mais forme compatible | Non listé par défaut, disponible en mode détaillé |

Afficher un `Possible` comme une certitude est le mode de défaillance principal
à éviter.

### 4.5 Plateformes cibles

| Cible | Moteur | Statut |
|---|---|---|
| Linux (Ubuntu/Debian) | natif | prioritaire |
| Windows (10 et 11) | natif | à trancher avec l'utilisateur |
| macOS | natif | via CI uniquement (voir §6.3) |
| Web | WASM | phase 2 |
| Android | WASM en webview | phase 4 |
| iOS | WASM en webview | phase 4, non construite |

**Points durs, vérifiés** :

- L'accès aux fichiers locaux dans le navigateur **n'existe que dans Chromium**.
  Firefox a une position normative négative, Safari s'y oppose pour des raisons
  de sécurité. Aucune évolution à attendre.
  → **La version web se limite au glisser-déposer et au presse-papiers.** L'UI
  doit l'annoncer au lieu de promettre un accès disque.
- **Tauri ne compile pas macOS depuis Linux.** Un Mac est requis, ou les runners
  macOS de GitHub Actions, gratuits pour un dépôt public.
- **iOS exige un Mac et un compte développeur Apple** pour la distribution, et
  une revue App Store stricte pour un outil de sécurité. Format WASM identique,
  donc ce sera une compilation et non une réécriture — mais pas avant d'avoir un
  usage réel sur mobile.

### 4.6 Licence AGPL-3.0

Le moteur et le CLI sont sous **AGPL-3.0**. Objectif : empêcher l'embarquement des
règles de détection dans un produit fermé sans publication. C'est cohérent avec un
outil de sécurité dont la valeur est dans les règles.

---

## 5. Spécification du moteur de détection

### 5.1 Format d'une règle

Les règles sont **déclaratives**, pas du code. Un contributeur doit pouvoir ajouter
une règle sans écrire de Rust — c'est ce qui rend le projet ouvert.

```yaml
id: fr.iban
name: IBAN
severity: high
extract:
  # regex crate : temps linéaire, aucun retour en arrière
  pattern: '[A-Z]{2}\d{2}[A-Z0-9]{10,30}'
  max_length: 34
validate:
  # validation structurelle en Rust, référencée par nom
  algorithm: iban_mod97
  require_country_prefix: true
context:
  # mots-clés qui font passer une detection de Possible a Probable
  keywords: [iban, rib, iban, compte bancaire, swift, bic]
  keywords_case_insensitive: true
  # distance maximale en caracteres autour du candidat
  window: 64
redaction:
  strategy: keep_last4   # garder les 4 derniers caracteres
```

**Justification de la séparation YAML/Rust** : la *forme* d'une règle est une
donnée, la *validation* est de la logique vérifiée par des tests. Mélanger les
deux dans la regex rend les règles non testables unitairement et non auditables.

### 5.2 Validateurs requis en v1

Implémentés en Rust, chacun avec ses tests et ses cas limites :

| Validateur | Algorithme | Cas limites à tester |
|---|---|---|
| `luhn` | clé de Luhn | longueur invalide, groupes de zéros, espaces et tirets séparateurs |
| `iban_mod97` | ISO 7064 mod 97-10 | longueur par pays, caractères de remplissage, casse |
| `ean13` | checksum EAN-13 | chiffre de contrôle, espaces |
| `private_key_pem` | en-tête PEM | `BEGIN ... PRIVATE KEY`, tous les algorithmes, variantes d'espacement |
| `aws_access_key` | préfixe + longueur | faux positifs possibles, à combiner avec du contexte |
| `jwt` | structure base64url × 3 | segments non-base64, signature vide |
| `email` | RFC 5322 simplifié | adresses locales exotiques, à_recvoyer en `Possible` |
| `iban_fr_rib` | structure RIB français + clé | clé RIB modulo 97 |

### 5.3 Extraction de texte

| Format | Bibliothèque | Difficulté |
|---|---|---|
| Texte brut | — | trivial |
| Markdown, CSV, JSON, YAML | — | trivial |
| DOCX / XLSX / PPTX | `zip` + `quick-xml`, `calamine` | faible (ce sont des ZIP contenant du XML) |
| PDF | `lopdf`, puis `pdf-extract` | **élevée** — voir §9.2 |
| HTML, e-mail (.eml) | `html5ever` ou regex structurelle | moyenne |

**Encodage** : `chardetng` pour la détection. Un détecteur qui rate le Latin-1 ou
l'UTF-16 laisse passer des fuites silencieusement, et un « aucun résultat » sur un
fichier effectivement lisible est le pire mode de défaillance possible.

**Garde-fous d'analyse de fichiers** (les documents sont des entrées non
confiées, même en local) :

- Limite de taille de fichier (configurable, défaut 50 Mo)
- Limite de ratio de décompression ZIP — **protection zip-bomb**
- Limite de profondeur de récursion XML — protection billion-laughs
- Nombre maximal d'éléments analysés, pour borner le temps de traitement
- Aucun `unwrap()` sur une donnée du document ; toute erreur est un résultat, pas un panic

---

## 6. Structure du dépôt

```
ProjectSecurityScanner/
├── Cargo.toml                     # workspace
├── LICENSE                        # AGPL-3.0
├── SECURITY.md                    # politique de remontée, coordonnées
├── CONTRIBUTING.md                # comment ajouter une règle
├── README.md                      # promesse « local », avec preuve
├── crates/
│   ├── sensitive-core/            # LE moteur
│   │   ├── Cargo.toml
│   │   ├── src/
│   │   │   ├── lib.rs
│   │   │   ├── rule.rs            # structures de règles
│   │   │   ├── engine.rs          # orchestration extraction→validation
│   │   │   ├── validators/        # luhn, mod97, ean13, ...
│   │   │   ├── context.rs         # scoring de contexte
│   │   │   ├── confidence.rs      # niveaux de confiance
│   │   │   ├── redact.rs          # masquage pour le rapport
│   │   │   └── extract/           # pdf, docx, texte, encodage
│   │   ├── rules/                 # YAML, versionnés et relus
│   │   ├── tests/
│   │   │   ├── corpus/            # documents réels annotés
│   │   │   └── thresholds.rs      # le budget de faux positifs
│   │   └── fuzz/                  # cargo-fuzz
│   ├── sensitive-cli/             # premier livrable
│   └── sensitive-wasm/            # cible wasm32
├── apps/
│   ├── web/                       # Vite + TS, worker
│   └── desktop/                   # Tauri, phase 3
└── .github/workflows/
    ├── ci.yml                     # tests multi-OS
    ├── fuzz.yml
    └── release.yml                # artefacts non signés
```

---

## 7. Spécification des livrables, par phase

### Phase 1 — Moteur + CLI (le premier livrable, et le seul qui compte au départ)

**Pourquoi commencer par la CLI** : c'est le seul endroit où la précision des
règles se mesure. Une interface élégante sur des règles imprécises ne produit
qu'un outil que les gens désactivent.

Interface cible :

```bash
scanner scan fichier.pdf
scanner scan --stdin < texte
scanner scan --format json --exit-code 2 rapport.pdf   # pour la CI
scanner rules list
scanner rules explain fr.iban                          # pourquoi cette règle existe
```

`--exit-code 2` sur finding est ce qui rend l'outil utilisable dans un pipeline :
c'est le mode qui sera utilisé quotidiennement par les équipes, plus que l'interface graphique.

**Critères d'acceptation** :
- les validateurs de §5.2 sont implémentés et testés
- le corpus de test passe avec le seuil de faux positifs fixé
- `cargo fuzz` ne trouve aucune violation en une heure d'exécution
- aucun crate réseau dans `sensitive-core` (test automatisé)

### Phase 2 — Web (WASM)

Le presse-papiers et le glisser-déposer. Zéro installation, ce qui en fait le
meilleur canal de découverte.

**Particularités** :
- le moteur tourne dans un **Web Worker** — sinon l'interface se fige pendant
  l'analyse d'un gros PDF
- l'interface doit annoncer clairement que l'accès disque complet n'est pas
  disponible hors Chromium
- le bundle WASM doit rester raisonnable ; mesurer à chaque release

### Phase 3 — Desktop (Tauri)

Windows, Linux, macOS. Intégration au gestionnaire de fichiers : clic droit sur un
PDF, « Scanner avant d'envoyer ». C'est le geste qui rend le produit réel, et il
est impossible sur le web.

Prérequis à documenter pour l'utilisateur : `libwebkit2gtk-4.1-dev` sur
Debian/Ubuntu.

### Phase 4 — Mobile

Webview installable (PWA), sans app native au départ. iOS en dernier, et
seulement si un usage réel le justifie.

---

## 8. Stratégie de test

### 8.1 Tests unitaires

Une règle sans test unitaire n'est pas mergée. Chaque règle a au minimum :
un cas positif, un cas négatif qui lui ressemble, et un cas limite.

### 8.2 Corpus et budget de faux positifs — le cœur de la qualité

**Définition du corpus** : des documents réels annotés — contrats, relevés
bancaires, factures, correspondances, tickets, relevés d.identity. Cible
initiale : 200 documents, dont au moins 50 ne contiennent **rien** de sensible.

**Mesure** :

```
taux de faux positifs = (faux positifs / documents sans donnée sensible)
```

**Seuil à fixer avant la phase 1.** Recommandation : ≤ 5 % sur le sous-ensemble
sans donnée sensible. Au-delà, l'outil sera désactivé.

Ce test doit être **un test exécutable**, qui échoue si le budget est dépassé, et
non une mesure manuelle.

### 8.3 Fuzzing

`cargo-fuzz` sur chaque point d'entrée : le chargeur de PDF, le parseur XML, le
moteur de règles. Objectif : aucune panique, aucun dépassement de délai sur une
entrée arbitraire. Les documents sont des entrées non confiées même en local.

### 8.4 Tests de propriété structurelle

Tests automatiques qui vérifient les invariants du dépôt :

- `sensitive-core` ne dépend d'aucun crate réseau
- aucune règle n'utilise de retour en arrière (`fancy-regex`) dans la phase
  d'extraction
- `sensitive-core` compile pour `wasm32-unknown-unknown`
- aucun secret (clé, mot de passe) dans l'historique Git — vérifié par `gitleaks`
  dans la CI, sur le modèle même du projet

---

## 9. Risques identifiés

### 9.1 Le faux positif (probabilité : élevée, impact : fatal)

Traité en §4.4 et §8.2 : c'est une contrainte de conception, pas un bug à
corriger. **Si ce risque se matérialise, le projet s'arrête** — un détecteur que
les utilisateurs désactivent ne vaut rien, même avec une détection impeccable.

### 9.2 Le PDF (probabilité : élevée, impact : fort)

Les textes sont dans des flux compressés, les polices ont des encodages
exotiques, et un parseur naïf renvoie « aucun résultat » sur un document
parfaitement lisible. C'est le format le plus coûteux du projet.

**Atténuation** : commencer par les formats faciles (§5.3) et traitées le PDF
comme un chantier séparé. Ne pas annoncer le support PDF avant qu'il ne soit
réellement fiable — un « aucun résultat » silencieux est pire qu'un « non pris
en charge ».

### 9.3 L'espace concurrentiel (probabilité : moyenne, impact : fort)

Traité en §2. Vérifier avant la phase 2.

### 9.4 La distribution (probabilité : quasi certaine, impact : fort)

**« Utile à beaucoup d'utilisateurs » et « atteignable seul » sont deux
problèmes différents.** Le premier est la distribution, pas le code, et c'est là
que la majorité des projets échouent. À traiter explicitement : dépôt public,
page de démonstration accessible sans installation, canal communautaire.

### 9.5 L'analyse statique dans l'usage réel

Si le moteur produit un taux de faux positifs ingérable sur un sous-ensemble de
documents (par exemple des tableaux financiers), il faut pouvoir **désactiver une
catégorie de règles** sans recompiler. À prévoir dans la conception du format de
règle.

---

## 10. Chaîne logicielle et sécurité du projet lui-même

Un outil de sécurité doit être lui-même irréprochable.

| Contrôle | Outil |
|---|---|
| Verrouillage des dépendances | `Cargo.lock` versionné, `cargo deny` |
| Audit des dépendances | `cargo audit` en CI |
| Fuzzing | `cargo-fuzz`, exécution nocturne |
| SBOM | `cargo-cyclonedx` ou équivalent |
| Analyse statique | `clippy` avec `-D warnings` |
| Formatage | `rustfmt`, vérifié en CI |
| Secrets dans l'historique | `gitleaks` en CI |
| Actions GitHub | épinglées par SHA de commit, jamais par tag |

**Signature des binaires** : hors périmètre en open source initial. Les binaires
seront publiés **non signés**, avec une procédure de contournement du Gatekeeper
macOS et du SmartScreen Windows documentée dans le README. C'est acceptable pour un
projet open source non publié en store ; ce ne le sera plus le jour où une
signature est requise.

---

## 11. Documentation à livrer

- `README.md` — la promesse « 100 % local », **avec le moyen de la vérifier**
- `CONTRIBUTING.md` — comment ajouter une règle, avec un modèle
- `SECURITY.md` — politique de remontée de faille
- `docs/false-positives.md` — le budget, les mesures, et ce qui est exclu
- `docs/threat-model.md` — ce que l'outil détecte et ce qu'il ne détecte pas
- `docs/build.md` — prérequis par plateforme, dont `libwebkit2gtk-4.1-dev`

## 12. Questions ouvertes à trancher avec l'utilisateur

1. **Windows 10** : le support est terminé depuis octobre 2025. Viser Windows 11
   uniquement, ou garder Windows 10 en « best effort » documenté ?
2. **Budget de faux positifs** : quel seuil est acceptable ? La recommandation est
   ≤ 5 %, mais c'est un arbitrage de produit.
3. **Corpus** : l'utilisateur accepte-t-il d'annoter des documents personnels réels,
   ou faut-il construire le corpus à partir de jeux de données publics ?
4. **PDF** : faut-il le livrer en v1 au risque de l'imperfection, ou le différer ?
5. **Langues des règles** : les règles sont-elles fournies en français seulement, ou
   également en anglais ? Cela change la stratégie de tests.

---

## 13. Ce qui n'est **pas** décidé ici

- Le format exact du rapport (JSON, console, HTML) — à définir en phase 1
- Le mécanisme de mise à jour des règles à distance — **à ne pas faire** : les
  règles sont dans le dépôt, donc auditable via Git
- Le nom du projet — celui-ci est un placeholder


---

## 14. Matrice des fonctionnalités

Identifiants `F-xx` de référence. Tâches, pull requests et discussions pointent
vers ces numéros, jamais vers « la fonctionnalité que je vois à l'écran ».

### Niveaux de priorité

| Prio | Signification |
|---|---|
| **P0** | Nécessaire pour que le produit existe |
| **P1** | Nécessaire pour qu'il soit crédible |
| **P2** | Facultatif — il vaut mieux le faire bien que le faire vite |
| **P3** | Si le temps le permet, sans regret si c'est abandonné |

### A. Moteur de détection — le cœur

| # | Fonctionnalité | Prio |
|---|---|---|
| F-01 | Extraction de candidats (tokens à contexte plausible) | P0 |
| F-02 | Validation Luhn (cartes bancaires) | P0 |
| F-03 | Validation IBAN modulo 97-10 | P0 |
| F-04 | Détection de clés privées PEM (tous algorithmes) | P0 |
| F-05 | Détection JWT (structure base64url x 3) | P0 |
| F-06 | Détection de clés d'API connues (AWS, GitHub, Slack, Stripe…) | P0 |
| F-07 | Détection d'e-mails et de numéros de téléphone | P1 |
| F-08 | Validation EAN-13 / code-barres produit | P1 |
| F-09 | Clé RIB française (structure + clé modulaire) | P1 |
| F-10 | Détection de certificats et de clés SSH privées (PEM/DER) | P1 |
| F-11 | Détection de mots de passe en clair (`password=`, `mot de passe :`) | P1 |
| F-12 | Scores de confiance à trois niveaux (`Certain` / `Probable` / `Possible`) | P0 |
| F-13 | Pondération par contexte lexical (« IBAN : », « carte n° ») | P0 |
| F-14 | Numéro de sécurité sociale / NIR français (clé de contrôle) | P2 |
| F-15 | Détection d'adresses IP et de métadonnées EXIF | P2 |
| F-16 | Entropie comme **signal secondaire** uniquement | P2 |

**F-16 est un piège** : l'entropie seule génère trop de faux positifs. Elle ne
sert qu'à *renforcer* une structure déjà suspecte, jamais à déclencher une alerte.

### B. Gestion des règles

| # | Fonctionnalité | Prio |
|---|---|---|
| F-17 | Format de règle déclaratif (YAML) versionné dans Git | P0 |
| F-18 | Chargement et validation du schéma de règle au démarrage | P0 |
| F-19 | Erreur claire sur règle invalide, avec numéro de ligne | P0 |
| F-20 | Catégories activables / désactivables sans recompiler | P1 |
| F-21 | Seuil de confiance configurable par catégorie | P1 |
| F-22 | Règles communautaires via pull request, revue obligatoire | P1 |
| F-23 | `rules explain` — pourquoi cette règle existe, avec sources | P1 |
| F-24 | Profil par pays (IBAN FR, NIR FR, SIREN…) | P2 |
| F-25 | Signature cryptographique des règles distribuées | P3 |

**F-25 est à ne pas faire** tant que les règles vivent dans Git : Git assure déjà
l'intégrité et l'historique. Une couche de signature au-dessus serait de la
complexité sans benefit.

### C. Extraction de contenu

| # | Fonctionnalité | Prio |
|---|---|---|
| F-26 | Texte brut, Markdown, CSV, JSON, YAML, logs | P0 |
| F-27 | Détection d'encodage (`chardetng`) : Latin-1, UTF-16, etc. | P0 |
| F-28 | DOCX / XLSX / PPTX (ZIP + XML) | P0 |
| F-29 | HTML et e-mails `.eml` | P1 |
| F-30 | **PDF** | P1 |
| F-31 | Limites de sécurité : taille, ratio ZIP, profondeur XML | P0 |
| F-32 | Fichier chiffré / encodage inconnu → « non analysable » explicite | P1 |
| F-33 | OCR sur images scannées | P3 |
| F-34 | Analyse des métadonnées des images | P2 |

**F-31 n'est pas optionnel** : les documents sont des entrées non confiées même en
local. Sans plafond de ratio de décompression, un ZIP est une bombe.

### D. Confidentialité et masquage

| # | Fonctionnalité | Prio |
|---|---|---|
| F-35 | Masquage (`redact`) : remplacer par `•••` en gardant 4 derniers caractères | P0 |
| F-36 | Copie du document masqué dans le presse-papiers | P0 |
| F-37 | Export du document masqué (`.txt`, `.md`) | P1 |
| F-38 | Journal d'audit **sans** contenu sensible | P1 |
| F-39 | Chiffrement du cache local des rapports | P2 |
| F-40 | Purge automatique du cache | P1 |

**F-38 est un piège classique** : le journal d'audit ne doit jamais contenir les
données détectées, sinon l'outil devient lui-même une base de données de données
sensibles.

### E. CLI — premier livrable

| # | Fonctionnalité | Prio |
|---|---|---|
| F-41 | `scan <fichier>` | P0 |
| F-42 | `--stdin` (analyse de flux, indispensable pour l'usage IA) | P0 |
| F-43 | `--format json` pour intégration CI | P0 |
| F-44 | `--exit-code 2` sur détection | P0 |
| F-45 | `--seuil` (certain / probable / possible) | P1 |
| F-46 | `--exclude-categorie` | P1 |
| F-47 | `--redact` — produit la version masquée sur stdout | P1 |
| F-48 | `--no-color` / sortie propre en script | P1 |
| F-49 | `rules list` / `rules explain` | P1 |
| F-50 | `--watch` optionnel sur dossier | P3 |

### F. Web (WASM)

| # | Fonctionnalité | Prio |
|---|---|---|
| F-51 | Moteur en Web Worker (l'UI ne doit pas figer) | P0 |
| F-52 | Analyse du presse-papiers | P0 |
| F-53 | Glisser-déposer de fichiers | P0 |
| F-54 | Message honnête : accès disque complet = Chromium seulement | P0 |
| F-55 | Rapport interactif avec surlignage des zones détectées | P1 |
| F-56 | Téléchargement du document masqué | P1 |
| F-57 | PWA installable | P2 |
| F-58 | Mesure et plafond de taille du bundle WASM | P1 |

**F-54 est une obligation** : sans elle, la moitié des utilisateurs découvrira
l'outil sur Safari et pensera qu'il est cassé.

### G. Desktop (Tauri)

| # | Fonctionnalité | Prio |
|---|---|---|
| F-59 | Sélection de fichier et analyse | P0 |
| F-60 | Intégration « clic droit → Scanner avant d'envoyer » | P1 |
| F-61 | Glisser-déposer depuis le gestionnaire de fichiers | P1 |
| F-62 | Surveiller un dossier et notifier | P2 |
| F-63 | Intégration au presse-papiers système | P2 |
| F-64 | Mode « scan rapide » à l'ouverture du document | P2 |

**F-60 rend le produit réel** : c'est le geste qui n'existe pas sur le web.

### H. Qualité — non négociable

| # | Fonctionnalité | Prio |
|---|---|---|
| F-65 | Corpus de 200 documents réels annotés | P0 |
| F-66 | Budget de faux positifs exécuté comme **test** | P0 |
| F-67 | Test : `sensitive-core` sans dépendance réseau | P0 |
| F-68 | Test : le core compile en `wasm32` | P0 |
| F-69 | `cargo fuzz` sur les parseurs et le moteur | P0 |
| F-70 | Tests unitaires + cas limites par règle | P0 |
| F-71 | CI multi-OS (Linux, Windows, macOS) | P1 |
| F-72 | `gitleaks` sur l'historique Git du projet | P1 |
| F-73 | `cargo deny` + `cargo audit` en CI | P1 |
| F-74 | SBOM généré à chaque release | P2 |
| F-75 | Property tests sur les validateurs (mod 97, Luhn) | P1 |

### I. Sécurité du projet lui-même

| # | Fonctionnalité | Prio |
|---|---|---|
| F-76 | `zeroize` sur tout secret manipulé | P0 |
| F-77 | Aucune requête réseau dans le core ni dans le CLI | P0 |
| F-78 | `SECURITY.md` + procédure de remontée de faille | P1 |
| F-79 | Actions GitHub épinglées par SHA de commit | P1 |
| F-80 | Analyse statique `clippy -D warnings` | P1 |

### J. Documentation et distribution

| # | Fonctionnalité | Prio |
|---|---|---|
| F-81 | README avec la promesse « 100 % local » **prouvée** | P0 |
| F-82 | `CONTRIBUTING.md` + modèle de règle | P1 |
| F-83 | Guide de compilation par plateforme | P1 |
| F-84 | Documentation de contournement Gatekeeper / SmartScreen | P1 |
| F-85 | Page de démonstration accessible sans installation | P1 |
| F-86 | Documentation des limites (« ce que l'outil ne détecte pas ») | P0 |

**F-86 compte double** : c'est ce qui distingue un outil de sécurité honnête d'un
outil qui promet trop. Un « aucun résultat » doit toujours dire *ce qui a été
analysé*.

### Répartition par priorité

| Prio | Nombre | Identifiants |
|---|---|---|
| P0 | 36 | F-01–06, F-12, F-13, F-17–19, F-26–28, F-31, F-35, F-36, F-41–44, F-51–54, F-59, F-65–70, F-76, F-77, F-81, F-86 |
| P1 | 36 | F-07–11, F-20–23, F-29, F-30, F-32, F-37, F-38, F-40, F-45–49, F-55, F-56, F-58, F-60, F-61, F-71–73, F-75, F-78–80, F-82–85 |
| P2 | 11 | F-14–16, F-24, F-34, F-39, F-57, F-62–64, F-74 |
| P3 | 3 | F-25, F-33, F-50 |
| **Total** | **86** | |

Les effectifs sont calculés depuis les tableaux ci-dessus, pas écrits à la main :
corriger une priorité dans une ligne impose de corriger ce tableau (verifier avec
un script, le total doit rester 86).

### Ce qui coûte vraiment le travail

Sur ces 86 fonctionnalités, **trois en représentent environ 80 % de l'effort** :

- **F-30 (PDF)** — le parseur le plus difficile : flux compressés, encodages de
  polices exotiques. Un parseur naïf renvoie « aucun résultat » sur un document
  parfaitement lisible, ce qui est le pire mode de défaillance possible.
- **F-65 + F-66 (corpus et budget)** — annoter 200 documents réels prend des
  semaines et n'est pas négociable. Sans eux, toute décision sur le seuil est un
  avis, pas une mesure.
- **F-69 (fuzzing)** — les règles de détection sont, par construction, des
  parseurs de données non fiables.

Les 83 autres sont, une fois ces trois maîtrisées, du travail d'artisan.
