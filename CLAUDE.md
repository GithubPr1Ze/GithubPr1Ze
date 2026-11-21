# Tacho Perso : notes pour Claude

Application Android personnelle qui lit la carte conducteur (chronotachygraphe) par un lecteur USB et
en tire heures, repos, découchés, ferry, frais de route (protocole IDCC 16) et contrôle de paie.
Utilisateur : conducteur routier international français. Interface et code commenté **en français**,
fuseau **Europe/Paris** partout. Fonctionnalités et décisions : [CAHIER_DES_CHARGES.md](CAHIER_DES_CHARGES.md).

## Règles impératives
- **Dépôt public** : ne jamais committer de donnée personnelle (nom, n° de carte, coordonnées du
  domicile, immatriculations, sociétés, bulletins/salaires, .DDD réels, captures app/build/shots).
  Tests avec données fictives (domicile de test = Arras 50.291, 2.777 ; « Société A/B »).
  `samples/` (dump réel) et `private/` sont ignorés par git.
- Discuter d'une nouvelle fonctionnalité et obtenir un « go » avant de coder.
- Commits : `git -c user.name=GithubPr1Ze -c user.email=133530535+GithubPr1Ze@users.noreply.github.com commit` (ne jamais modifier
  la config git), ligne `Co-Authored-By` en fin de message. Branche `claude/android-driver-card-reader-i3lwnd`.
- Monter `versionCode` / `versionName` dans [app/build.gradle.kts](app/build.gradle.kts) à chaque livraison.

## Construire, tester, installer (PC Windows, Git Bash)
```sh
export JAVA_HOME=$(ls -d ../dev-tools/jdk-17* | head -1)
./gradlew -p core test                 # règles et décodage (JVM pur)
./gradlew :app:testDebugUnitTest       # Robolectric + captures Roborazzi → app/build/shots/*.png
./gradlew :app:assembleRelease         # APK signé avec la clé privée (local.properties → tachoperso.signing)
../platform-tools/adb.exe install -r app/build/outputs/apk/release/app-release.apk   # débogage sans fil
./gradlew -p core inspect -Pfile=../samples/carte-reelle.DDD   # analyse du dump réel (local uniquement)
```
- Toujours installer l'APK **release** : un APK debug ne met pas à jour l'appli installée (signature).
- La CI GitHub ne fait que compiler et tester, elle ne publie pas d'APK.
- Modifications multi-lignes : de petits scripts Python d'édition (remplacement exact + assert) sont
  plus fiables que les heredocs Bash.

## Carte du code
**core/** (Kotlin JVM, sans Android)
- `ccid/` pilote CCID + protocole T=1, `apdu/` APDU ; `tacho/TachoCardDownloader` lecture Gen1/Gen2.
- `tacho/` décodage Annexe 1C : `DddFormat` (.DDD), `CardData`, `DriverActivity`, `Places` (lieux +
  GNSS), `SpecificConditions` (ferry/train), `Vehicles` (véhicules utilisés, VIN), `CardReport` (vue lisible).
- `rules/` : `Timeline`/`Analysis` (RSE, conduite, service, nuit), **`DriverHistory`** (point d'entrée :
  fusionne les lectures, lieux, nuits, ferry, véhicules, frais), `Nights` (domicile/découché, lieu du repos),
  `Stops` (lieux de coupure regroupés à 2 km), `Ferry`, `Expenses` (protocole 1974, avenant 81), `EmployerExpenses` (mode employeur ×1,18 + corrections
  par jour), `Amplitude` (prise → fin de poste − 45 min si conduite > 4h30 ou amplitude > 6h, par mois), `MonthlyReport`.
- Tests : un fichier par sujet dans `core/src/test/kotlin` ; `FakeTachoCard` simule une carte.

**app/** (Compose, Material 3)
- `MainActivity` → `MainScreen` (navigation, menus, lanceurs de fichiers) + `MainViewModel` (`UiState`).
- `DriverRepository` : un dossier par conducteur (clé = pays + 14 premiers caractères de la carte),
  `profile.json`, `reads/*.DDD`, `settings.json` (domicile, corrections nuits/jours, paie, bulletins,
  société par véhicule).
- `BaremeRepository` (barème intégré + mise à jour depuis le dépôt public tacho-perso-bareme),
  `Reminders` (WorkManager quotidien : lecture < 28 j, expiration carte, récap mensuel),
  `MonthlyMail` (e-mail prérempli via la messagerie du téléphone, PDF + .DDD, FileProvider),
  `ReportPdf`, `Backup` (.zip complet), `PlaceNames` (géocodage en cache), `UsbReaders`.
- `ui/` : un fichier par écran ou onglet (Dashboard, Calendar, Days, Places = onglet Lieux, Ferry,
  Amplitude = onglet Amplitude, Expenses, Pay, Nights, Edit = repos à préciser/corrections, Vehicles) ; `StopsMap` (seul fichier
  qui touche osmdroid, bibliothèque archivée fin 2024) ; `Components` (Panel, KeyValue…), `Theme`.

## Restant à faire
- Lieux de coupure : la carte de référence n'a presque aucun lieu « fin de journée » (la carte reste
  dans le chrono la nuit ; les « début » sont des saisies de pays après ferry/tunnel). Seules les
  coupures situées à 2 km près par un point GNSS sont classées (~20 %). À vérifier avec le
  conducteur : une saisie « pays fin » le soir enregistre-t-elle une position exacte ?
- Récapitulatifs annuels/trimestriels par société (`DriverHistory.companyOn`).
- Écart inexpliqué : bulletins = 1 déjeuner et 1 dîner de moins par mois que la carte.
