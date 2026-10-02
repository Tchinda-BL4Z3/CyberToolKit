# AppBundleRelease.md — Feuille de route de publication

**Projet :** CyberToolkit SECOPS
**Chemin :** `/home/tchinda/Documents/cybertoolkit`
**Date :** 01/10/2026
**Statut :** application non publiée. La clé de production existe, le bundle AAB
est vérifié, mais rien n'a été soumis au Play Store.

Ce document décrit ce qu'il reste à faire pour publier l'application, et dans quel
ordre. Il est écrit pour être repris plus tard tel quel : chaque étape indique la
commande exacte et la raison de la faire.

---

## 0. Où en est l'application aujourd'hui

État vérifié le 01/10/2026, pas supposé :

| Élément | Valeur | Vérifié |
| --- | --- | --- |
| `applicationId` | `com.example.cybertoolkit` | `aapt2 dump badging` |
| `versionCode` | `1` | `aapt2 dump badging` |
| `versionName` | `2.4.0` | `aapt2 dump badging` |
| `minSdk` / `targetSdk` | 24 / 36 | `aapt2 dump badging` |
| AGP | 9.1.1 | `libs.versions.toml` |
| Clé de production | `upload-keystore.jks`, alias `upload` | script fourni |
| Tests JVM | 142, 0 échec | `testDebugUnitTest` |
| Tests instrumentés | 6, 0 échec | émulateur `Pixel_5` |
| Lint | 0 avertissement | `lintDebug` |
| Bundle AAB | 3,1 Mo, signé | `bundleRelease` exécuté |
| Permissions réseau | `INTERNET` (client SSH opt-in) | `aapt2 dump badging` |

Le fait que `bundleRelease` produise un AAB signé de 3,1 Mo a été vérifié
localement avec une clé de travail. Le keystore de production n'a pas servi à
cette vérification, pour ne pas l'exposer inutilement.

## Permissions : ce qui a changé, et la déclaration à faire

L'app **déclare maintenant `INTERNET`**. C'est un changement volontaire, pour la
fonction B (client SSH). Il faut être précis sur ce que cela implique, parce que
la section ci-dessous affirmait le contraire avant.

### Pourquoi `INTERNET` ne rend pas l'app illégale

Ajouter `INTERNET` est normal et autorisé. Des milliers d'applications publiées
le font, dont des clients SSH (JuiceSSH, Termius). Ce qui compte pour Play n'est
pas la permission, mais trois choses :

1. **Ne rien faire de malveillant.** On ne se connecte qu'à une machine dont
   l'opérateur possède les identifiants. Aucun balayage d'un tiers, aucune
   exfiltration, aucune collecte dissimulée.
2. **Déclarer honnêtement** dans le formulaire Data Safety.
3. **Respecter le contrôle parental** et la politique sur le ciblage. Une app
   réseau doit être déclarée, ce qui est fait.

### Ce que l'app fait réellement du réseau

- Les modules **hors-ligne** (encodeur, crypto, verrouillage, labo simulé) n'ouvrent
  aucun socket. Ils n'ont pas de code réseau.
- Le **client SSH** est le seul chemin réseau. Il est **désactivé par défaut** et
  refuse de s'exécuter tant que l'utilisateur ne l'a pas activé dans Paramètres.
  Ce refus est appliqué dans le code (`SshSession.consentGiven`), pas seulement
  dans l'interface : un appelant qui oublierait de vérifier l'option serait quand
  même bloqué.
- Les identifiants (clé privée, passphrase) vivent en mémoire d'interface
  seulement. **Aucun fichier, aucune preference, aucun log.**
- `cleartextTrafficPermitted="false"` partout : pas de HTTP en clair, pas de
  descente de niveau possible. Un serveur SSH est déjà chiffré, il n'a besoin de
  rien.

### Réponses à donner dans Play Console > Data Safety

| Question | Réponse |
|---|---|
| L'app collecte-t-elle des données ? | **Non** |
| Les données sont-elles chiffrées en transit ? | **Oui** (SSH) |
| L'app peut être utilisée sans réseau ? | **Oui**, sauf la fonction SSH |
| Partage avec des tiers ? | **Non** |

Déclarer « aucune collecte » est exact : l'app n'envoie rien vers un service
tiers. La commande part vers *votre* hôte, qui est votre machine.

### La permission automatique d'AndroidX

`aapt2 dump badging` liste aussi :

```
uses-permission: com.example.cybertoolkit.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
```

Celle-ci est **ajoutée automatiquement par AndroidX** quand `targetSdk >= 33`,
pour empêcher la réception de diffusions par d'autres applications. Le code ne
la demande pas, et Play Console l'affichera sans que vous puissiez la retirer.
Ce n'est pas une fuite de données : c'est une restriction, l'inverse d'un risque.

Aucune permission `ACCESS_NETWORK_STATE`, `CAMERA`, `RECORD_AUDIO` ni
`ACCESS_FINE_LOCATION`. Le client SSH n'en a besoin d'aucune : `INTERNET` seule
suffit à ouvrir une socket.

---

## 1. La règle la plus importante sur le versionCode

**`versionCode` doit être un entier strictement croissant. Jamais deux fois le
même, jamais en arrière.**

Ce n'est pas une convention, c'est ce que fait le système : Android compare les
`versionCode` et refuse d'installer une version inférieure ou égale à celle
déjà installée. Deux conséquences concrètes :

- Vous ne pouvez pas republicer `1`. Si l'application est déjà installée chez
  quelqu'un, votre mise à jour sera refusée.
- Vous ne pouvez pas *baisser* le numéro pour « repartir de zéro ».

### Convention à adopter

`versionCode` suit la version majeure × 1000 + correctif :

| `versionName` | `versionCode` | Pourquoi |
| --- | --- | --- |
| `2.4.0` (actuelle) | `1` | première publication |
| `2.4.1` | `2` | correction de bug |
| `2.5.0` | `1001` | nouvelle version mineure |
| `3.0.0` | `2000` | refonte |

Le multiplicateur 1000 vous laisse 999 correctifs entre deux versions majeures.
C'est largement suffisant, et cela rend le numéro lisible d'un coup d'œil.

### Le piège du code d'usine

`versionCode 1` convient pour une première publication. Ensuite, chaque build
publié doit incrémenter le numéro dans `build.gradle.kts` avant l'upload.

Le risque n'est pas l'oubli du numéro, c'est l'oubli de le changer *après* avoir
publié : dans ce cas l'upload est refusé, ce qui est un échec visible et
récupérable. Il vaut donc mieux un contrôle trop strict qu'un contrôle absent.

**Recommandation :** la table ci-dessus, saisie à la main. Deux numéros à garder
juste, c'est plus sûr qu'un mécanisme automatique qu'on oublie de déclencher.

### Où changer

`app/build.gradle.kts`, lignes 79-80 :

```kotlin
versionCode = 1        // -> 2 pour la prochaine correction
versionName = "2.4.0"  // -> "2.4.1"
```

Ces deux lignes sont les seules à modifier. `versionName` est purement
informatif (affiché dans les réglages) ; `versionCode` est celui qui compte pour
le système.

---

## 2. Ce qui change pour le Play Store : l'APK devient un AAB

### 2.1 Pourquoi un AAB

Un APK contient tout : les ressources de toutes les langues, tous les écrans. Un
AAB contient un *index* et laisse Play choisir ce qu'il faut envoyer à chaque
téléphone. Play generates alors un APK par appareil.

Conséquence pour cette application : elle n'a que deux langues (`fr`, `en` —
`localeFilters` dans `build.gradle.kts`). Le gain est faible ici. Mais **Play
 refuse aujourd'hui les APK pour les nouvelles applications** : c'est le format
obligatoire, pas une option.

### 2.2 Ce qui change dans la pratique

Trois différences entre ce que vous faites maintenant et ce que vous ferez :

| | APK (`assembleRelease`) | Bundle (`bundleRelease`) |
| --- | --- | --- |
| Extension | `.apk` | `.aab` |
| Installation manuelle | possible, par câble | impossible, Play only |
| Upload à Play | refusé pour les nouvelles apps | **obligatoire** |
| `apksigner verify` fonctionne | oui | non, vérifier autrement |

La commande de build change simplement le mot `assemble` en `bundle` :

```bash
KEYSTORE_PATH="$PWD/upload-keystore.jks" \
STORE_PASSWORD='...' KEY_PASSWORD='...' KEY_ALIAS=upload \
./gradlew :app:bundleRelease
```

Le résultat : `app/build/outputs/bundle/release/app-release.aab`.

Vous n'avez **rien** à changer dans le code ou la configuration. C'est déjà prêt
et vérifié.

---

## 3. L'identité de signature : trois clés, pas une

C'est le point le plus contre-intuitif du processus, et celui qui casse les
mises à jour futures si on l'ignore.

Quand vous publiez sur Play, Google génère **sa propre clé** (`app signing key`)
et la conserve. Votre clé (`upload key`) ne sert qu'à prouver à Google que c'est
vous qui envoyez le bundle.

```
vous ──upload key──> Google Play ──app signing key──> téléphone
```

Deux conséquences :

1. **La clé que vous avez créée reste la clé d'upload.** C'est bien. Elle est
   utilisée pour authentifier vos envois, pas pour signer ce qui arrive sur les
   téléphones.
2. **Si vous perdez votre clé d'upload, vous pouvez la réinitialiser** depuis la
   console Play (une fois par semaine, avec une attente de 5 jours). Si vous
   perdez la clé que Google possède, c'est Play qui s'en occupe.

C'est exactement pour ça que votre sauvegarde sur Drive était la bonne idée.

### Ce qu'il ne faut pas faire

Ne pas activer `Play App Signing` sur une clé que vous réutilisez ailleurs. Une
seule clé pour un seul usage.

---

## 4. Préparer la console Play

À faire une fois, avant le premier upload.

### 4.1 Compte développeur

- Frais d'inscription : 25 USD, **payés une seule fois à vie**.
- Vérification d'identité : carte d'identité ou passeport, selon le pays.
- Délai : quelques jours à quelques semaines selon les vérifications.
- Pour une organisation, il faut un D-U-N-S (numéro d'immatriculation) — pas
  nécessaire pour un compte particulier.

### 4.2 Fiche de l'application

Play Console → *Create app*. Les champs obligatoires :

| Champ | Valeur suggérée |
| --- | --- |
| Nom (30 car.) | `CyberToolkit SECOPS` |
| Langue par défaut | Français |
| App or Game | App |
| Free or paid | Free |
| Category | **Developer tools** ou **Utilities** |

`Developer tools` correspond le mieux à une boîte à outils technique. Notez que
cette catégorie a des exigences de politique de confidentialité.

### 4.3 Déclaration de protection des données

Obligatoire, et c'est le point où les applications mal documentées se font
rejeter. Pour cette application, la réponse est simple et vérifiable :

| Question | Réponse | Justification vérifiable |
| --- | --- | --- |
| L'app collecte-t-elle des données ? | **Non** | Aucun envoi vers un tiers ; réseau limité au client SSH opt-in |
| Données partagées ? | Non | Aucune destination réseau |
| Données collectées ? | Non | Tout reste dans `SharedPreferences`, local |
| Chiffrement au repos ? | S/O | Rien ne quitte l'appareil |
| Suppression possible ? | S/O | Rien à supprimer côté serveur |

Cette application est **plus facile à documenter qu'une app moyenne**, précisément
parce que seul le client SSH utilise le réseau, sur activation explicite, et
qu'aucun module ne peut envoyer de données à un tiers. C'est un vrai argument de
vente, pas
seulement une formalité.

### 4.4 Politique de confidentialité

Play exige une URL de politique de confidentialité **hébergée publiquement**
(comme un lien GitHub Pages), pas un PDF joint.

Pour cette application, une page d'une page suffit, parce que le contenu est
limité à : cette application ne collecte aucune donnée et n'envoie rien à un
réseau, les données restent sur votre appareil, le code de verrouillage n'est
jamais stocké en clair mais uniquement sous forme de dérivation
PBKDF2-HMAC-SHA256 avec un sel aléatoire de 128 bits
(`CyberSecurityManager.kt`), et n'est jamais transmis.

Si le dépôt est privé, attention : l'URL doit être accessible sans authentification.

### 4.5 Classification par âge

Formulaire standard. Pour une application de formation technique sans contenu
ni interaction sociale : **3+** ou **13+**, selon votre position.

---

## 5. Le premier upload

### 5.1 Piste de test interne

**Commencez par la piste de test interne.** Elle est disponible immédiatement
après création du compte, sans revue, et permet de tester sur vos propres
téléphones pendant des jours.

C'est le bon ordre : la revue d'une nouvelle application Play peut prendre
plusieurs jours. Vous ne voulez pas découvrir un problème d'installation après
avoir attendu une semaine.

### 5.2 Procédure

1. Play Console → *Testing* → *Internal testing* → *Create new release*
2. Uploader `app/build/outputs/bundle/release/app-release.aab`
3. Play affiche un avertissement si `versionCode` est déjà utilisé
4. *Review release* → *Start rollout to Internal testing*

### 5.3 Opt-in des testeurs

Pour rejoindre une piste interne, chaque testeur doit ouvrir son lien
d'invitation. Le lien apparaît dans l'onglet *Testers*.

Pour vous : votre compte Google est normalement ajouté automatiquement comme
testeur interne. Sinon, ajoutez-le dans *Testers* → *Create email list*.

### 5.4 Attendre la propagation

Comptez quelques heures. L'icône Play Store met parfois plus de temps à
apparaître que l'installation elle-même.

---

## 6. Avant toute sortie publique

Une liste de vérification, dans cet ordre. Chaque point a déjà un équivalent
vérifié dans le projet, ce qui réduit le risque.

| # | Vérification | État |
| --- | --- | --- |
| 1 | `versionCode` strictement supérieur au dernier publié | à faire au moment du build |
| 2 | Bundle AAB signé avec la clé d'upload | testé, fonctionne |
| 3 | 142 tests JVM verts | fait |
| 4 | 6 tests instrumentés verts sur appareil | fait sur émulateur |
| 5 | Lint à 0 avertissement | fait |
| 6 | Réseau limité au client SSH opt-in | fait, `INTERNET` déclarée, gate dans le code |
| 7 | Testé sur un **téléphone réel** | **à faire** |
| 8 | Ancien `com.aistudio.cybertoolkit.encdx` désinstallé | à faire sur chaque appareil |
| 9 | Code d'usine `11111` changé, verrou testé | à faire sur appareil réel |
| 10 | Déclaration de protection des données remplie | à faire |
| 11 | Politique de confidentialité en ligne | à faire |
| 12 | Classification par âge remplie | à faire |

Les points 7, 8 et 9 sont ceux que l'émulateur ne peut pas vous dire. Ce sont les
seuls vrais risques techniques qui restent.

---

## 7. Sortie publique

### 7.1 Déploiement progressif

Ne publiez pas d'un coup à 100 %. Utilisez le déploiement progressif :

- **5 %** pendant 48 h : surveillez le tableau de bord
- **20 %** si aucun crash
- **100 %** quand vous êtes satisfait

Le Console affiche les taux de crash ANR par version. Un crash qui passe
inaperçu à 5 % devient un problème à 100 %.

### 7.2 Avant tout : ne pas oublier les reconstructions natives

Si l'application utilise du code natif (C/C++/Rust), chaque bibliothèque doit
cibler **64 bits** : Play refuse les bundles qui ne contiennent que du 32 bits.

Cette application est purement Kotlin/Compose : **pas de code natif**. Donc pas
de vérification 64 bits à faire.

### 7.3 Obligation de mise à jour obligatoire (optionnel mais recommandé)

Play permet de rendre une application **obligatoirement actualisable**, ce qui
force la mise à jour sur les appareils compatibles. Utile pour une application de
sécurité, où laisser d'anciens codes de verrouillage en circulation est un
risque réel.

Accessible dans *Release* → *App integrity* → *Manage* →
*Enable app updates enforcement*.

À n'utiliser que si l'application n'a pas de version legacy à préserver.

---

## 8. Ce qui reste ouvert aujourd'hui

| Point | Statut | Pourquoi |
| --- | --- | --- |
| Keystore de production | **Créé** | sauvegarde sur Drive, à conserver hors du dépôt |
| Version de publication | `versionCode 1`, `versionName 2.4.0` | à incrémenter au prochain build |
| Compte développeur Play | Non créé | 25 USD + vérification d'identité |
| Fiche, données, politique | Non rédigées | sections 4.2 à 4.5 ci-dessus |
| Test sur téléphone réel | Non fait | le seul écart technique réel restant |

---

## 9. Récapitulatif des commandes

```bash
# Construire le bundle pour Play
KEYSTORE_PATH="$PWD/upload-keystore.jks" \
STORE_PASSWORD='...' KEY_PASSWORD='...' KEY_ALIAS=upload \
./gradlew :app:bundleRelease

# Vérifier l'APK debug avant toute chose (optionnel)
./gradlew :app:assembleDebug

# Vérifier la signature d'un APK (ne fonctionne pas sur un .aab)
~/Android/Sdk/build-tools/37.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk

# Validation complète avant publication
./gradlew :app:testDebugUnitTest :app:lintDebug \
          :app:verifyRoborazziDebug :app:connectedDebugAndroidTest
```

Pour la clé d'upload elle-même :

```bash
~/Android/Sdk/build-tools/37.0.0/apksigner verify --print-certs app.aab
```

sur un `.aab` ne fonctionne pas directement : Play affiche l'empreinte de la
clé attendue dans *Setup* → *App integrity*, et l'upload est refusé si la clé
ne correspond pas.

---

## 10. Erreurs fréquentes à éviter

| Erreur | Conséquence |
| --- | --- |
| Réutiliser le même `versionCode` | Rejet de l'upload, ou installation refusée |
| Baisser le `versionCode` | Installation refusée sur les appareils existants |
| Uploader un `.apk` | Rejeté par Play |
| Uploader un `.aab` signé avec une clé de travail | Rejeté, ou pire accepté puis non maintenable |
| Mettre le `.jks` dans Git | La clé est compromise, il faut tout réinitialiser |
| Publier à 100 % d'un coup | Un crash passe inaperçu jusqu'à trop tard |
| Oublier de changer le code d'usine `11111` | Toute l'application est publiquement déverrouillable |
| Ne pas désinstaller l'ancien identifiant | Deux consoles sur le même téléphone |

---

*Document à mettre à jour au fur et à mesure des étapes. La clé de production
et son mot de passe n'apparaissent nulle part ici, et ne doivent jamais être
écrits dans un fichier versionné.*