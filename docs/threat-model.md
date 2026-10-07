# Modèle de menace — que détecte l'outil, que ne détecte-t-il pas ?

## Hypothèses de sécurité

1. **Traitement local.** Le navigateur (WebAssembly) et le CLI traitent le texte
   dans le processus de l'utilisateur. Le module du moteur **n'a aucune
   importation réseau** : c'est vérifié par une preuve de compilation
   (`crates/sensitive-core/tests/no_network.rs`, F-44) qui échoue dès qu'un
   symbole d'accès au réseau est symbolisé.
2. **Le rapport ne transporte jamais l'échantillon brut.** Un `Finding` porte
   des offsets absolus, un type, une plage et un aperçu *masqué*
   (`preview_masked` : `keep_last4` ou `type_only`). La reconstruction du texte
   masqué se fait côté interface à partir du document original.
3. **Les documents sont des entrées non confiées, même en local.** Le parseur
   ZIP (DOCX/XLSX/PPTX) applique des limites de sécurité (F-31) :
   - 5 000 entrées au maximum ;
   - 50 Mio de volume décompressé cumulé ;
   - ratio de compression maximal de 512 ;
   - refus dès qu'un membre de l'archive est chiffré.
   Toute entrée au-delà est **refusée avant lecture**, jamais « acceptée puis
   tronquée silencieusement », et aucune panique n'est tolérée (fuzz F-69).

## Ce que l'outil détecte (v1)

| Catégorie | Règles |
| --- | --- |
| Credentials | clé d'accès AWS, clé privée PEM, JWT, mot de passe en clair, clé d'API / secret |
| Financial | IBAN (mod 97), carte bancaire (Luhn), RIB (clé mod 97), EAN-13 (Luhn) |
| Infra | IP privée, identifiants dans une URL (basic auth), jeton dans une URL |
| PII | NIR (clé mod 97), adresse e-mail, numéro de téléphone français (+33 / 0X) |

Les règles marquent un niveau de confiance : `certain` (validation forte +
contexte), `probable` (validation seule, ou format + contexte), `possible`
(format seul, contexte absent). `possible` reste affiché et compte dans le
budget de faux positifs.

Formats d'entrée : texte brut (multi-encodages) et `.docx` / `.xlsx` / `.pptx`
(texte des XML de l'archive). Limite : 50 Mio par document.

## Ce que l'outil ne couvre PAS — et pourquoi il le dit

- **PDF et images** : refusés avec un message explicite (aucun OCR en v1).
  Un « aucun résultat » ne concerne que le texte réellement lu.
  Un document PDF peut parfaitement contenir des données sensibles ; l'outil
  refuse de conclure sur du contenu qu'il n'a pas ouvert.
- **Fichiers binaires et archives non bureautiques** : refusés avant lecture.
- **Contenu embarqué** dans les bureautiques : images, macros VBA, objets OLE,
  tableaux imagés — non extraits en v1.
- **Camouflage volontaire** : split d'IBAN, caractères Unicode approchants,
  images de texte, stéganographie. Les validateurs réduisent fortement les faux
  positifs mais n'empêchent pas un acteur délibéré de contourner la règle.
- **Fichiers > 50 Mio** : refusés avant lecture.

## Budget de faux positifs (F-65 / F-66)

Le corpus de référence (200 documents annotés, dont 50 sans donnée sensible)
est généré depuis les règles — arbitrage v1 — et exécuté comme **test** :
`crates/sensitive-core/tests/corpus_budget.rs`. Le seuil est ≤ 5 % de faux
positifs sur le sous-ensemble sans donnée sensible : au-delà, le test échoue
et le moteur est réputé défaillant (§8.2 du cahier des charges).
Mesures et exclusions détaillées : `docs/false-positives.md`.

## Contre-mesures au niveau de l'interface

- Les aperçus sont masqués par défaut (`pièce masquée`, `keep_last4`).
- La copie / l'export masquent **tous** les findings, y compris ceux que
  l'interface affine à l'affichage (niveau `Possible` désactivé par défaut).
- `Purger` réinitialise la session ; les tampons WASM sont effacés avant
  libération (`wipe`, F-76).
- Le traitement tourne dans un **Web Worker** (F-51) : la page reste réactive
  et le moteur n'a pas accès au DOM.

## Limites déclarées dans l'interface

Lorsqu'aucun résultat n'est trouvé, la page rappelle explicitement ce qui n'a
pas été couvert (PDF/images, macros, binaires, > 50 Mio, encodages non
reconnaissables, texte hors règles chargées). Voir `docs/false-positives.md`.