# Faux positifs — budget, mesure, exclusions

## Définition (cahier des charges §8.2)

Une *fausse alerte* est une donnée sensible déclarée dans un document qui n'en
contient **pas**. Le taux se calcule sur le sous-ensemble de documents sans
donnée sensible :

```
taux de faux positifs = (documents sans donnée sensible ayant ≥ 1 finding)
                        / (documents sans donnée sensible)
```

Le seuil a été fixé en v1 à **≤ 5 %**, exécuté comme **test** : le dépassement
fait échouer `crates/sensitive-core/tests/corpus_budget.rs` et l'outil est
réputé défaillant.

## Corpus de référence (F-65)

200 documents annotés, dont **50 sans donnée sensible** :

- **150 documents positifs** : un motif réel par règle du catalogue (valeurs de
  contrôles calculées : mod 97 pour IBAN/RIB/NIR, Luhn pour carte/EAN, JWT,
  PEM, etc.), répétés sur 10 préambules différents. Règle vérifiée que chaque
  règle est couverte par au moins un document.
- **50 documents négatifs** : textes crédibles, volontairement trompeurs
  (mots-clés de contexte présents sans valeur, formats quasi-conformes mais
  non valides, identifiants neutres). Ils n'approchent jamais une valeur
  véritablement valide (un IBAN valide dans un négatif serait un bug du corpus).

Arbitrage v1 : corpus généré depuis les règles (le cahier laissait la porte
ouverte à des documents réels annotés ; cette option reste possible en v2).

## Mesure actuelle

Relation de fumée sur le corpus (test `corpus_budget`): **0 faux positif**
sur 50 documents négatifs, 0 faux négatif sur les 150 positifs. Le budget de
5 % est vérifié à chaque `cargo test --workspace`.

## Ce qui est exclu du budget — et pourquoi

- **`possible` sans contexte** compte comme finding : aucune donnée sensible ne
  doit être déclarée sur un document propre, quel que soit le niveau de
  confiance. En revanche, l'interface affiche par défaut `certain` et
  `probable`, et l'utilisateur peut activer le niveau `possible`.
- L'ajout d'une nouvelle règle doit passer par le corpus : un motif trop large
  (ex. `\b\d{16}\b` sans Luhn) fait grimper les FP et bloque la livraison.

## Garanties d'intégrité du corpus

- Les documents positifs sont générés par rapport aux validateurs : valeur
  invalide = faux négatif détecté par le test de recouvrement.
- Le corpus est déterministe (aucun générateur aléatoire) : la mesure est
  reproductible.
- Le fuzzer (F-69) ne remplace pas le corpus : il garantit l'absence de panique
  sur entrée arbitraire, le corpus garanti la justesse de la détection.