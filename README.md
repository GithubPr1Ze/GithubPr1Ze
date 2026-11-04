# Tacho Perso

Application Android **gratuite et personnelle** pour lire sa **carte conducteur de chronotachygraphe**
avec un lecteur de carte à puce USB branché sur le téléphone, puis consulter ses activités
(conduite, travail, disponibilité, repos).

> Outil d'usage personnel. Il ne remplace pas l'archivage légal effectué par l'employeur,
> même si le fichier `.DDD` produit est au format standard.

## Matériel

- Un lecteur de carte à puce USB **conforme CCID** (ex. ACS ACR39U, Identiv SCR3310, ou tout
  lecteur « PC/SC » courant), 15–30 €. Un modèle directement en USB‑C évite l'adaptateur.
- Un téléphone Android 8+ compatible **USB OTG**, et si besoin un adaptateur USB‑C → USB‑A.
- La carte conducteur **n'a pas de code PIN** : la lecture est libre.

## Installer l'APK

Chaque envoi sur GitHub compile automatiquement l'application (onglet *Actions*). L'APK est publié
dans les **Releases** du dépôt (pré-versions `apk-N`) : ouvrez la page depuis le téléphone,
téléchargez `TachoPerso-N.apk`, et autorisez l'installation depuis le navigateur.
Toutes les versions sont signées avec la même clé : une nouvelle version s'installe par-dessus
l'ancienne sans perdre l'historique.

## Utilisation

1. Brancher le lecteur (Android peut proposer d'ouvrir Tacho Perso directement).
2. Insérer la carte, puce vers le haut/contacts vers le lecteur.
3. **Lire la carte** : la lecture complète prend quelques secondes.
4. Chaque lecture est enregistrée dans le téléphone au format `.DDD` ; **Exporter .DDD** permet de
   la copier ailleurs, **Ouvrir .DDD** de relire un fichier existant (issu d'un autre logiciel).
5. En cas de problème : *Journal technique* → **Copier**, et transmettre le texte.

## Ce que fait la V1

- Communication USB **CCID** directe (sans pilote) : lecteurs niveau APDU, TPDU T=0 et TPDU T=1.
- Lecture de toutes les données des applications **Gen1 (TACHO)** et **Gen2 (SMRDT)**, avec les
  signatures calculées par la carte, comme un logiciel de téléchargement. **Aucune écriture sur la carte.**
- Enregistrement au format `.DDD` (Annexe 1C, Appendice 7).
- Affichage : identité, permis, validité, génération de carte, dernier véhicule, et pour chaque jour
  une frise des activités avec les totaux et les kilomètres.

## Feuille de route

- V2 : véhicules utilisés, lieux de début/fin de journée, positions GNSS, événements et anomalies,
  affichage en heure locale.
- V3 : contrôle des règles du règlement (CE) 561/2006 (pauses, conduite journalière / hebdomadaire /
  bi-hebdomadaire, repos journaliers et hebdomadaires) et alertes.
- V4 : historique fusionné sur plusieurs lectures, exports PDF/CSV.

## Structure du code

| Dossier | Contenu |
|---|---|
| `core/` | Kotlin pur, testé sur JVM : protocole CCID, T=1, APDU, lecture carte, format `.DDD`, décodage. |
| `app/` | Application Android (Jetpack Compose) : accès USB Host, interface. |

```bash
./gradlew -p core test          # tests (carte et lecteurs simulés)
./gradlew :app:assembleDebug    # APK (nécessite le SDK Android)
```

Références : Règlement d'exécution (UE) 2016/799, Annexe 1C (Appendice 1 : dictionnaire de données,
Appendice 2 : spécifications des cartes, Appendice 7 : protocole de téléchargement).
