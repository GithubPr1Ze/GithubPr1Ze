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

- Lecture USB **CCID** directe (sans pilote), toutes les données **Gen1** et **Gen2** avec les
  signatures de la carte. **Aucune écriture sur la carte.** Testé sur une vraie carte Gen2 V1
  avec un lecteur Alcorlink (058F:9540).
- **Un dossier par conducteur** (clé : n° de carte) ; toutes les lectures sont gardées en `.DDD`
  et fusionnées : l'historique dépasse la fenêtre conservée par la carte.
- **Email** : à chaque lecture, si aucun email n'est renseigné pour ce conducteur, l'application
  propose d'en ajouter un.
- **Trois onglets** : tableau de bord, calendrier, liste des jours ; détail complet d'une journée.
  Heures en **heure de Paris**.
- **Contrôles réglementaires**, chacun avec son article :
  - règlement (CE) 561/2006 (consolidé au 20/08/2020) : pauses (art. 7), conduite journalière,
    hebdomadaire et sur deux semaines (art. 6), repos journalier dans les 24 h et repos réduits
    (art. 8 § 2 et § 4), délai et enchaînement des repos hebdomadaires, compensation (art. 8 § 6) ;
  - Code des transports, conducteurs grands routiers : temps de service journalier (12 h),
    hebdomadaire (56 h) et trimestriel (689 h), travail de nuit (L3312-1), pauses (L3312-2) ;
    heures au-delà de 35 h et de 43 h (équivalence) et heures de nuit 21h–6h affichées.
- Import / export `.DDD`, journal technique copiable.

Limites : seules les données de la carte sont prises en compte (lieu du repos hebdomadaire,
retour au domicile, conduite en équipage et dérogations conventionnelles sont à vérifier).

## Feuille de route

Voir `CAHIER_DES_CHARGES.md` : sauvegarde NAS, envoi des rapports par email, découchers et repas
(GPS), fiche de paie estimée, exports PDF/CSV.

## Structure du code

| Dossier | Contenu |
|---|---|
| `core/` | Kotlin pur, testé sur JVM : protocole CCID, T=1, APDU, lecture carte, format `.DDD`, décodage, règles. |
| `app/` | Application Android (Jetpack Compose) : accès USB Host, interface. |

```bash
./gradlew -p core test          # tests (carte, lecteurs et semaines simulés)
./gradlew -p core dumpCard -Pout=../samples/carte.DDD   # lire une carte depuis un PC (PC/SC)
./gradlew :app:testDebugUnitTest  # captures d'écran (app/build/shots) si samples/carte-reelle.DDD existe
./gradlew :app:assembleDebug    # APK (nécessite le SDK Android)
```

Références : Règlement d'exécution (UE) 2016/799, Annexe 1C (Appendice 1 : dictionnaire de données,
Appendice 2 : spécifications des cartes, Appendice 7 : protocole de téléchargement).
