# LogErrorFile — Rapport de mise à jour

**Projet :** CyberToolkit SECOPS
**Chemin :** `/home/tchinda/Documents/cybertoolkit`
**Date :** 30/09/2026
**Version :** 2.4.0 (`versionCode 1`)
**Périmètre :** mise en service de la refonte Compose, sécurisation du verrou, correction des défauts bloquants, mise en place de la validation automatisée et de la documentation.

---

## 1. État final

| Indicateur | Avant | Après |
| --- | --- | --- |
| Compilation Kotlin | 7 erreurs | **0 erreur, 0 avertissement** |
| Tests JVM | 2 templates (`assertEquals(4, 2 + 2)`) | **142 tests, 0 échec** |
| Tests instrumentés | aucun | **6 tests, 0 échec** (émulateur) |
| Android Lint | non exécuté | **0 erreur, 0 avertissement** |
| Tests de régression prouvés | — | **6 régressions injectées puis détectées** |
| APK debug | non produit | **16 Mo** |
| APK release (R8 + signature) | jamais construit | **1,2 Mo, signature v2 valide** |
| Identifiant d'application | `com.aistudio.cybertoolkit.encdx` | **`com.example.cybertoolkit`** |
| Icônes de launcher | 4 fichiers sur 10 corrompus | **10/10 correctes** |
| Documentation | README du template AI Studio | **README + modèle de menace** |

**Code applicatif :** 20 fichiers Kotlin, 6 116 lignes.
**Tests :** 16 fichiers Kotlin, 2 943 lignes.
**Chaînes :** 159 ressources, parité FR/EN vérifiée.

### Artefacts

| Fichier | Valeur |
| --- | --- |
| `CyberToolkit-2.4.0-debug.apk` | SHA-256 `26cb219d2748189079b3b07daf4880666955a549a77ba88d1a85c6ad16ccafba` |
| Identifiant vérifié | `com.example.cybertoolkit` / `versionCode 1` / `targetSdk 36` |
| Permissions réseau déclarées | **aucune** |

> L'identifiant a changé : les installations existantes sous
> `com.aistudio.cybertoolkit.encdx` doivent être désinstallées manuellement, sinon
> les deux applications coexistent sur le même téléphone.

Commandes de validation, toutes vertes :

```bash
./gradlew :app:testDebugUnitTest      # 142 tests, 0 échec
./gradlew :app:lintDebug              # 0 erreur, 0 avertissement
./gradlew :app:verifyRoborazziDebug   # image de référence versionnée
./gradlew :app:assembleDebug          # app-debug.apk, 16 Mo
./gradlew :app:assembleRelease        # R8 + signature v2, 1,2 Mo
./gradlew :app:connectedDebugAndroidTest   # 6 tests sur appareil
```

---

## 2. Erreurs de compilation corrigées

L'arbre réécrit ne compilait pas. Sept erreurs, toutes réelles.

| # | Fichier | Erreur | Cause | Correction |
| --- | --- | --- | --- | --- |
| 1 | `MainActivity.kt:201` | `Unresolved reference 'LaunchedEffect'` | import retiré lors d'une réécriture, alors que l'effet `FLAG_SECURE` l'utilise | import rétabli |
| 2 | `MainActivity.kt:444` | `Unresolved reference 'Context'` | le receiver de l'extension est fully-qualified, mais le type local `Context?` ne l'est pas | `import android.content.Context` ajouté |
| 3 | `CyberComponents.kt:57` | `Unresolved reference 'LocalTextStyle'` | importé depuis `androidx.compose.ui.platform` au lieu de `androidx.compose.material3` | package corrigé |
| 4 | `CyberComponents.kt:339` | `Unresolved reference 'T'` | le paramètre générique `<T>` avait disparu de la signature | `fun <T> CyberSegmentedControl(...)` restauré |
| 5 | `CryptoTabScreen.kt:167,338` | `Unresolved reference 'padding'` | import `layout.padding` manquant | import ajouté |
| 6 | `MainActivity.kt:155` | `LocalLifecycleOwner` déprécié | l'API a migré vers `lifecycle-runtime-compose` | `androidx.lifecycle.compose.LocalLifecycleOwner` |
| 7 | `EncoderTabScreen.kt:150` | `Icons.Default.Input` déprécié | remplacé par la variante auto-miroir | `Icons.AutoMirrored.Filled.Input` |

**L'erreur 4 deserve attention.** C'est la seule qui aurait produit un bug *silencieux* plutôt qu'une panne : sans `<T>`, `List<Pair<T, String>>` se résout vers un type erroné, le sélecteur de format de l'encodeur affiche des valeurs arbitraires et rien ne signale l'anomalie.

---

## 3. Défauts de conception corrigés

### 3.1 Double source de vérité pour la cible des payloads

`CyberToolkitViewModel` exposait `payloadHost` et `payloadPort` **et** `CyberSettings` exposait `defaultHost`/`defaultPort`. Modifier l'hôte dans l'onglet Payloads et modifier la valeur par défaut dans Paramètres écrivait dans deux endroits distincts ; une valeur écrasait silencieusement l'autre.

Correction : `payloadHost`/`payloadPort` supprimés. Les deux écrans lisent et écrivent désormais `settings`. `payloadEnv` reste dans le ViewModel car c'est un état de session, pas une préférence.

### 3.2 Plafond de 24 h contournable

`durationForLevel(level)` ne plafonnait pas : le `coerceAtMost(MAX_DURATION_MS)` était appliqué chez l'appelant. Un appelant oubliant la ligne obtenait une pénalité de 2^40 secondes.

Correction : le plafond est dans `durationForLevel`, `MAX_DURATION_MS` est exposé en `const`, et `registerFailure` n'applique plus rien.

### 3.3 Branches de sécurité non testables

`state()` lisait `System.currentTimeMillis()` et `SystemClock.elapsedRealtime()` en interne. Les deux branches les plus importantes — **recul de l'horloge** et **redémarrage de l'appareil** — étaient donc inatteignables depuis un test JVM, puisqu'elles exigeaient de manipuler l'horloge réelle.

Correction : découpage en `stateAt(nowWall, nowMono)`, `registerFailureAt(...)` et `noteActivityAt(...)`. `state()` devient une délégation d'une ligne. La logique de sécurité est inchangée, elle est simplement pilotable et donc prouvable.

---

## 4. Sécurité — analyse détaillée

### 4.1 Escalade de pénalité non prouvée

`registerSuccess()` remettait `KEY_LEVEL` à zéro. Analyse de la chaîne causale :

1. L'attaquant échoue 3 fois → niveau 1, blocage de 10 min.
2. Le blocage expire, l'attaquant réessaie 3 fois → `registerFailure` lit `level = 1`, pénalité de 20 min, puis écrit `level = 2`.
3. S'il ne réussit jamais, `registerSuccess` n'est jamais appelé et le niveau continue de croître.

**Conclusion après vérification : le mécanisme était correct**, mais rien ne le prouvait. Le comportement voulu est documenté dans le KDoc de `registerSuccess` : un code correct est le signal que l'opérateur est le propriétaire, l'escalade ne croît donc que pour quelqu'un qui n'entre jamais.

Deux tests le verrouillent désormais : `escalation grows for someone who never gets in` (3 tours, niveaux 1 → 2 → 3) et `a successful unlock resets the escalation`.

### 4.2 Verrou de rotation de code non conforme au réglage

`autoLockAfterCodeChange` existait comme réglage et comme interrupteur, mais le dialogue de confirmation **verrouillait toujours** après 3 secondes, sans consulter le réglage. Le retour et le bouton « Verrouiller maintenant » avaient le même effet.

Correction : le dialogue lit `lockAfterRotation`. Si le réglage est actif, le décompte et le verrouillage forcé sont conservés (y compris sur fermeture, puisque l'opérateur a demandé d'être verrouillé). Si le réglage est désactivé, le dialogue devient une confirmation ordinaire, dismissible, avec un libellé et un texte d'explication distincts.

### 4.3 Avertissement du code usine figé

`isUserConfigured` et `usesFactoryCode` étaient calculés dans un `remember { }` sans clé : figés à la première composition. L'avertissement « vous utilisez encore le code usine » survivait donc à la rotation de code qui venait de le résoudre.

Correction : un `securityRevision` incrémenté après chaque rotation réussie, et les deux lectures sont ré-évaluées.

### 4.4 Registre anti-bruteforce

Le code efface le code en clair de `SharedPreferences` au profit de PBKDF2-HMAC-SHA256 (210 000 itérations, sel aléatoire de 128 bits). Le test `the code is never stored in clear text` vérifie le **fichier de préférences brut**, pas les accesseurs : une future écriture de chiffres en clair échoue le build.

Le registre lui-même est passé de `rememberSaveable` (donc dans le `Bundle` d'état d'instance, effaçable par un simple force-stop) à `SharedPreferences`, avec double horloge et marqueur de crue pour détecter le recul de l'horloge.

---

## 5. Icônes de launcher — 4 fichiers corrompus

### 5.1 Diagnostic

La vérification `IconDipSize` d'Android Lint a signalé des dimensions aberrantes. Lecture directe des en-têtes WebP (`VP8X`) :

| Fichier | Dimensions déclarées | Attendu |
| --- | --- | --- |
| `mipmap-xxhdpi/ic_launcher.webp` | 36 803 × 9 421 313 | 144 × 144 |
| `mipmap-xxhdpi/ic_launcher_round.webp` | 36 803 × 9 421 313 | 144 × 144 |
| `mipmap-xxxhdpi/ic_launcher_round.webp` | 49 091 × 12 567 041 | 192 × 192 |

Une image de 12 567 041 px de haut représente **~242 Go décompressés**. Elle ne se compresse qu'à quelques kilo-octets parce qu'elle est presque entièrement plate, ce qui explique que `du` n'ait rien révélé et que le défaut soit passé inaperçu.

### 5.2 Correction

Les 10 icônes de densité ont été régénérées à partir du même dessin que l'icône adaptative, par `tools/generate_launcher_icons.py` (nouveau fichier). Le script :

- évalue les données de chemin du vecteur `ic_launcher_foreground.xml` plutôt que de redessiner à l'œil ;
- applique la transformation de groupe exacte (échelle 0,62 autour du pivot 54,54) ;
- rend à 8× puis sous-échantillonne, pour des bords lisses dès 48 px ;
- **relit l'en-tête du fichier écrit et échoue si la dimension est incorrecte**.

Le rendu a été validé numériquement (aucun modèle multimodal disponible pour une inspection visuelle) :

| Contrôle | Résultat |
| --- | --- |
| Dimensions, 5 densités | 48 / 72 / 96 / 144 / 192 px, exactes |
| Dimensions dans l'APK final | 10/10 correctes (`VP8L`) |
| Centrage du blason | centroïde à 1,35 px du centre sur 192 px (0,48 px sur 48 px) |
| Symétrie verticale | 97,6 % (l'écart résiduel est de l'anticrénelage) |
| Opacité des 4 coins | 0 — masque arrondi et masque rond corrects |
| Trou de serrure | présent à toutes les tailles |
| Poids total | 52 Ko pour les 10 fichiers |

### 5.3 Bug de transformation de l'icône adaptative

`ic_launcher_foreground.xml` combinait `pivotX/pivotY = 54` **et** `translateX/translateY = 20.52`. Or 20,52 = 54 × (1 − 0,62) : c'est la compensation nécessaire quand le pivot est à l'**origine**. Combinée à un pivot au centre, elle décalait le blason de 20,5 unités vers la droite et vers le bas, hors de la zone de sécurité de 66 dp.

Correction : pivot conservé, translation supprimée. Commentaire dans le fichier expliquant pourquoi.

---

## 6. Bugs d'affichage et d'accessibilité

| Fichier | Problème | Correction |
| --- | --- | --- |
| `strings.xml` FR, ligne 189 | `Mode 100%% hors-ligne` s'affichait **littéralement** avec les `%%` : `Resources.getString(int)` n'applique pas `String.format` sans argument | « Mode hors-ligne complet » |
| `strings.xml` FR + EN, ligne 197 | même bug `%%` dans le texte « À propos » | reformulé sans le symbole |
| `EncoderTabScreen.kt` | entrée vide : affichait un placeholder **et** une boîte d'erreur | état neutre silencieux ; `EMPTY_INPUT` ne produit plus de message |
| `HomeScreenLock.kt:263` | `contentDescription = ""`, un anti-pattern d'accessibilité (TalkBack ne lit rien) | conteneur purement décoratif, modificateur de sémantique supprimé |
| `MainActivity.kt` | `val palette` inutilisé après réécriture | supprimé |

Le bug `%%` méritait un traitement de fond : `%` dans une chaîne de ressources est ambigu. `%%` ne s'affiche correctement que si la chaîne est utilisée **avec** des arguments de format, et `%` seul est interprété par Lint comme un début de spécification (`missing conversion character in '% h'`). Le seul correctif robuste est donc de supprimer le symbole.

---

## 7. Réglages qui n'étaient pas câblés

### 7.1 Choix du délai d'auto-lock

`CyberSettings.AUTO_LOCK_CHOICES` existait — liste de 0, 15, 30, 60, 300, 900 secondes — mais **n'était jamais lu**. L'auto-lock n'offrait que deux états : « immédiat » ou « valeur par défaut ». Un opérateur ne pouvait pas choisir.

Correction : sélecteur à 6 positions branché sur la liste existante. Un sélecteur plutôt qu'un interrupteur, parce qu'un interrupteur sur une durée crée une ambiguïté de lecture.

### 7.2 Chaînes et ressources mortes

14 ressources inutilisées signalées par Lint. Chaque cas examiné individuellement :

- **Câblées** : `settings_autolock_immediate`, `settings_autolock_body` (via le nouveau sélecteur), `encoder_error_empty` (supprimée à la place, l'état vide étant devenu silencieux).
- **Obsolètes par conception, supprimées** : `lock_status_no_code` (impossible depuis qu'une installation neuve est amorcée avec le code usine), `lock_status_console_blocked` (doublon de `lock_status_blocked`), `cd_toggle_code_visibility` (le verrou n'affiche jamais le code en clair), `action_close`, `action_share`, `app_version`, `settings_autolock_value`, `cd_console_locked`, `settings_autolock_changed`.
- **Drawable mort supprimé** : `ic_cyber_shield.xml`, jamais référencé — c'est l'icône adaptative (`foreground` + `background`) qui avait remplacé le JPEG de 616 Ko.
- **Couleurs mortes supprimées** : `cyber_icon_bg_start` et `cyber_icon_bg_end`, référencées nulle part dans le projet.

Les 3 `UnusedResources` restants ont disparu après nettoyage. Les 22 avertissements subsistant sont tous des suggestions de style sans impact fonctionnel : `UseKtx` (11), `PluralsCandidate` (4), `Typos` (3, dont « trafic » qui est l'orthographe française correcte), `UnusedAttribute` (2, attributs API 29/33 avec `minSdk 24`), `InlinedApi` (1, `ClipDescription.EXTRA_IS_SENSITIVE` gardé volontairement pour le chemin API 33+), `RedundantLabel` (1).

---

## 8. Fichiers supprimés

| Fichier | Raison |
| --- | --- |
| `app/src/main/java/com/example/HomeScreenLock.kt` | obsolète, remplacé par `ui/lock/HomeScreenLock.kt` |
| `app/src/main/java/com/example/SettingsTabScreen.kt` | obsolète, remplacé par `ui/settings/SettingsTabScreen.kt` |
| `app/src/main/java/com/example/CyberSecurityManager.kt` | obsolète, remplacé par `security/CyberSecurityManager.kt` |
| `app/src/main/res/drawable/ic_cyber_shield.xml` | drawable mort |
| `app/src/test/java/com/example/ExampleUnitTest.kt` | test template |
| `app/src/test/java/com/example/ExampleRobolectricTest.kt` | test template |
| `app/src/androidTest/java/com/example/ExampleInstrumentedTest.kt` | test template |

---

## 9. Tests ajoutés

Les 3 fichiers de test d'origine étaient des templates générés (`assertEquals(4, 2 + 2)`), et l'un référençait `com.example.CyberSecurityManager.parseDigits`, un chemin de paquetage supprimé. Ils référençaient donc du code inexistant.

| Fichier | Tests | Couverture |
| --- | --- | --- |
| `crypto/CryptoCoreTest.kt` | 15 | vecteurs MD5/SHA-256 publiés, vecteur RFC 4231 HMAC, aller-retour AES-GCM, refus mauvaise passphrase, détection d'altération, IV aléatoire, forces de passphrase |
| `security/CyberSecurityManagerTest.kt` | 17 | absence de code en clair dans le XML brut des préférences, migration legacy qui efface le clair, rejet des codes malformés, code faible, aller-retour par le code usine |
| `security/LockoutGuardTest.kt` | 14 | budget, persistance après mort de processus simulée, rejeu sur recul d'horloge, survie au redémarrage, escalade, plafond 24 h, marqueur de crue |
| `crypto/CodecsTest.kt` | 11 | aller-retours, et surtout que chaque échec est une erreur typée qui n'atteint jamais la zone copiable |
| `payloads/PayloadGeneratorTest.kt` | 15 | validation hôte et port, repli `LHOST`/`LPORT`, pureté de la génération |
| `GreetingScreenshotTest.kt` | 1 | **supprimé** — ne capturait rien (voir 10.2) ; remplacé par `LockScreenScreenshotTest` |

### 9.1 Itération 1 : 7 échecs sur 72

| Échec | Diagnostic |
| --- | --- |
| 2 × Base64 « not mocked » | `Codecs` utilise `android.util.Base64`, non mocké hors Robolectric. Ajout du runner. |
| Longueur SHA-1 = 40 | mon assertion, pas le code |
| `my-host_01.example.com` refusé | le code a raison, `_` est illégal dans un hostname (RFC 1123). Mon assertion était fausse. |
| Plafond 24 h au niveau 5 | mon calcul d'arithmétique : 640 min = 10,7 h. Le plafond n'intervient qu'au niveau 8. |
| Marqueur de crue | `noteActivity()` sans seam temporel, test non concevable |
| Escalade niveau 2 | mon attente était fausse, voir section 4.1 |

**Bilan : 1 seam de production ajouté, 6 assertions corrigées, 0 bug de production masqué.**

---

## 10. Correctifs P0 à P3 (revue du 01/10/2026)

### 10.1 P0 — le sélecteur de thème ne repeignait rien

`CyberToolkitApp` enveloppait le contenu dans le thème vert par défaut de
`MyApplicationTheme`, puis déléguait le réglage utilisateur à un
`CompositionLocal` interne. Le fournisseur interne l'emportait sur l'alias
externe... sauf que le setter lui passait `settings` *avant* l'instanciation du
ViewModel : le thème était donc réinitialisé au vert au premier rendu.

Le `CompositionLocal` au milieu de l'arbre ne pouvait de toute façon pas
repeindre une fenêtre déjà composée. La correction est structurelle :
`CyberToolkitApp` collecte les réglages **avant** d'appliquer le thème et
enveloppe directement `CyberToolkitContent`. Le contrat
`MyApplicationTheme { CyberToolkitApp() }` est conservé tel quel.

### 10.2 P1 — couverture des chemins de sécurité

| Manque | Correction |
| --- | --- |
| Parcours de verrou non testé | `LockScreenFlowTest` (8 tests) : code usine, refus, compteur, trois échecs, gel des barillets, blocage durable, reset |
| Auto-lock non testable | `AutoLockSession` extrait, horloge `elapsedRealtime` injectable, 14 tests dont recul d'horloge en fermeture |
| `FLAG_SECURE` non vérifié | `ScreenCapturePolicyTest` (2 tests) + `FLAG_SECURE` réel en test instrumenté |
| Capture d'écran fantôme | `GreetingScreenshotTest` supprimée : elle capturait dans le vide et comparait l'image du template |
| Rotation du code, dialogue de verrou, dialogue d'effacement | `SettingsSecurityDialogTest` (13 tests) |
| **Câblage de l'auto-lock non testé** | `AutoLockWiringTest` : le test JVM prouvait l'arithmétique, rien ne prouvait que l'observateur l'appelait |
| Thème de bout en bout | `DeviceConsoleTest` : déverrouiller, réglages, choisir chaque ambiance, vérifier le repeint |

> Le câblage de l'auto-lock était le manque le plus sérieux : supprimer
> `ON_STOP -> autoLock.onStop(...)` laissait les 14 tests unitaires verts alors que
> la console restait ouverte indéfiniment en arrière-plan. Vérifié en injectant la
> régression : `AutoLockImmediateWiringTest` échoue, les autres passent.

### 10.3 P2 — packaging et publication

- **Identifiant** : `com.aistudio.cybertoolkit.encdx` → `com.example.cybertoolkit`,
  aligné sur le paquetage source. Les installations existantes doivent être
  désinstallées manuellement, sinon les deux applications coexistent.
- **Release jamais construite** : `assembleRelease` échouait avant d'atteindre R8,
  sur un closure `doLast` qui capturait l'objet script Gradle — interdit par le
  cache de configuration. Les variables d'environnement sont désormais lues dans
  `doLast` via `System.getenv`, sans aucune référence au script.
- **Validation réelle** : release R8 de 1,2 Mo, signature v2 valide, aucune
  permission réseau, installée et lancée sur l'émulateur sans crash. Le keystore
  jetable utilisé pour cette vérification a été supprimé ; **aucun matériau de
  signature n'est versionné**.

### 10.4 P3 — dette et robustesse

- **Champ mort** : `CyberSettings.strictOfflineMode` était persisté, jamais lu et
  non modifiable. L'application ne déclare aucune permission réseau : l'état
  hors-ligne est une propriété du build, pas un réglage. Champ supprimé, et
  l'entrée `strict_offline` existante effacée à la prochaine sauvegarde.
- **Onglet perdu à la mort du processus** : `selectedTab` était un
  `mutableIntStateOf(0)`. Il survit à une rotation, donc seemed correct, mais pas
  à un reclaim de mémoire. Passé en `SavedStateHandle`. Le test vérifie aussi que
  **rien d'autre** n'entre dans le bundle : l'état de déverrouillage n'est pas
  sérialisable sur disque et ne doit jamais le devenir.
- **Lint** : 22 avertissements → **0**. 11 `UseKtx` (mécaniques), 4
  `PluralsCandidate` (convertis en `<plurals>` FR et EN), 2 `UnusedAttribute` et
  1 `RedundantLabel` (manifest), 1 `InlinedApi` (voir ci-dessous), 3 `Typos` qui
  étaient des **faux positifs** : `CYB1` est un préfixe de format littéral et
  « trafic » est du français correct. Ces trois-là sont ignorés explicitement, avec
  le motif, plutôt que « corrigés » en faux.
- **`ClipDescription.EXTRA_IS_SENSITIVE`** (API 33) : l'avertissement `InlinedApi`
  est un faux positif — c'est une constante `String` inlinée à la compilation, donc
  aucun risque de liaison au runtime sur API 24-32. Le comportement des deux côtés
  de la frontière est asserts par `SecureClipboardTest` (14 tests, SDK 24/31/33) :
  sur les anciennes versions, c'est le délai d'expiration qui protège le secret.

### 10.5 Régressions injectées puis détectées

Un test vert ne prouve rien tant qu'il n'a jamais échoué. Six défauts ont été
réintroduits un par un, pour vérifier que la suite les attrape :

| Régression injectée | Test qui l'a détectée |
| --- | --- |
| `CyberToolkitTheme` sans thème utilisateur | `ThemeWiringTest` |
| Auto-lock sans garde de recul d'horloge | `AutoLockSessionTest` |
| `strictOfflineMode` remis dans le modèle | `CyberSettingsStoreTest` |
| Migration `strict_offline` retirée | `CyberSettingsStoreTest` |
| Bouton de confirmation du déverrouillage débranché | `SettingsSecurityDialogTest` |
| `ON_STOP -> autoLock.onStop(...)` neutralisé | `AutoLockImmediateWiringTest` |

Une septième tentative, par corruption de l'image de référence Roborazzi, a
d'abord **échoué** : des octets ajoutés après `IEND` sont ignorés par le
décodeur PNG. La dérive n'a été correctement détectée qu'en remplaçant les
pixels. Idem pour la tentative de « casser » le câblage du dialogue, dont la
remplacement n'a d'abord pas correspondu au motif : le test passait alors sans rien
exécuter. Deux vérifications ont donc donné un faux vert avant d'être correctes.

### 10.6 Référence Roborazzi désormais versionnée

L'image de référence était écrite dans `app/build/outputs/roborazzi`, répertoire
couvert par `app/.gitignore` : elle n'était jamais versionnée, et un checkout
propre n'avait rien à comparer. Sortie déplacée vers `src/test/screenshots/`.

---

## 11. Documentation

### 10.1 README réécrit

Le README était encore celui du template AI Studio : il demandait de créer un fichier `.env` avec `GEMINI_API_KEY`, contenait un lien vers une application AI Studio obsolète, et ne mentionnait ni les 5 onglets, ni le modèle de sécurité, ni les commandes de build réelles.

Réécrit avec : les 5 onglets, le modèle de sécurité, la procédure de build debug et release, l'arborescence du projet, et le détail de la suite de tests.

### 10.2 `docu/THREAT_MODEL.md` créé

Modèle de menace complet : actifs et modèle d'attaquant (accès physique sur appareil déverrouillé, ou extraction des fichiers privés), contrôles par couche, et **cinq risques résiduels explicitement assumés**, dont le reset du registre par root + reboot + édition de fichiers, et le fait qu'un débogueur sur appareil rooté bat un verrou d'interface.

### 10.3 Une affirmation corrigée avant publication

Une première version affirmait que le wipe réinitialisait les préférences de sécurité. **C'était faux** : `clearSessionData()` ne vide que les tampons en mémoire (entrées encodeur et crypto, payload AES, résultat de hachage, secret). Le code de verrouillage et les réglages sont conservés, ce que confirme la chaîne `settings_wipe_body` déjà présente.

Vérification faite : `clearCombination()` existe et réamorce le code usine, mais n'est appelé **par aucun écran** — uniquement par un test. Le document le précise. C'est le comportement voulu : effacer le verrou depuis l'application déverrouillée serait un moyen de le neutraliser.

---

## 12. Version et métadonnées

`versionName` Passing de `"1.0"` à `"2.4.0"` pour correspondre à la version affichée dans l'interface (`CYBERTOOLKIT SECOPS v2.4.0`). `versionCode` laissé à 1.

---

## 13. Points laissés ouverts

Ces points étaient ouverts avant la revue du 01/10/2026. Ils sont tous résolus.

| Point | Statut |
| --- | --- |
| Build release | **Résolu.** `assembleRelease` construit, R8 actif (1,2 Mo contre 16 Mo), signature v2 vérifiée, APK installé et lancé sans crash. La garde `verifyReleaseSigningMaterial` refuse toujours un build sans `KEYSTORE_PATH`/`STORE_PASSWORD`/`KEY_PASSWORD`. |
| Tests instrumentés (`androidTest`) | **Résolu.** 6 tests dans `DeviceConsoleTest` et `AutoLockWiringTest`, verts sur `Pixel_5`. |
| `GreetingScreenshotTest` | **Supprimée.** Elle ne capturait rien et comparait l'image du template. Remplacée par `LockScreenScreenshotTest`, dont la référence est versionnée. |
| 22 avertissements Lint | **Résolu.** 0 avertissement. |
| Observations sur appareil | **Partiellement résolu.** `FLAG_SECURE`, le verrouillage, le lockout, le câblage de l'auto-lock et le changement de thème sont observés à l'exécution sur émulateur. La vibration et les `navigationInsets` ne le sont toujours pas. |

### 12.1 Ce qui reste réellement ouvert

| Point | Pourquoi |
| --- | --- |
| Aucun keystore de production | Le pipeline est validé, mais aucun certificat réel n'existe. Il doit être fourni avant toute publication. |
| Téléphones existants sous l'ancien identifiant | `com.aistudio.cybertoolkit.encdx` doit être désinstallé à la main. Migration de données impossible sans cooperation de l'utilisateur. |
| Aucun test sur matériel réel | L'émulateur ne dit rien du comportement sur un GPU, un écran ou une couche constructeur spécifiques. |
| Vibration et `navigationInsets` | Non observés à l'exécution ; aucun test ne les couvre. |
| `FLAG_SECURE` ne bloque pas la capture d'écran | `FLAG_SECURE` masque l'aperçu dans le sélecteur de tâches. Il n'empêche pas une capture d'écran volontaire ; c'est une limite de la plateforme, pas du code. |

---

## 14. Récapitulatif des commandes de vérification

```bash
# Compilation stricte, aucun avertissement
./gradlew :app:compileDebugKotlin --no-daemon --rerun-tasks

# Suite complète
./gradlew :app:testDebugUnitTest --no-daemon

# Lint
./gradlew :app:lintDebug --no-daemon

# Image de référence : réenregistrer volontairement après un changement voulu
./gradlew :app:recordRoborazziDebug --no-daemon

# Comparaison d'image contre la référence versionnée
./gradlew :app:verifyRoborazziDebug --no-daemon

# APK debug
./gradlew :app:assembleDebug --no-daemon

# APK release : R8 + signature (nécessite un vrai keystore en production)
KEYSTORE_PATH=... STORE_PASSWORD=... KEY_PASSWORD=... KEY_ALIAS=... \
  ./gradlew :app:assembleRelease --no-daemon

# Tests sur appareil ou émulateur
./gradlew :app:connectedDebugAndroidTest --no-daemon

# Régénération et vérification des icônes
python3 tools/generate_launcher_icons.py
```

Résultat au 01/10/2026 : `BUILD SUCCESSFUL`, **142 tests JVM à 0 échec**, **6 tests instrumentés à 0 échec**, **0 avertissement Lint**, 10 icônes conformes dans l'APK, release R8 signée valide.
