# Tacho Perso — Cahier des charges

> Document de travail. Annotez directement (ajoutez `>> votre remarque` sous une ligne,
> barrez ce qui ne vous convient pas). Rien n'est codé tant qu'il n'est pas validé ici.

## 1. Contexte

- Application Android **personnelle et gratuite** pour lire des cartes conducteur de
  chronotachygraphe et consulter les données (conduite, travail, dispo, repos).
- Utilisateur : **grand routier international**.
- Un seul téléphone : **Android 16**. Plusieurs conducteurs peuvent lire leur carte dessus.
- Lecteur : **Alcorlink USB Smart Card Reader** (VID 058F, PID 9540, CCID), via USB‑C OTG.
- Carte de référence : **Gen2 V1**, valide jusqu'au 15/05/2028.
- Tout reste **sur le téléphone** (pas de compte, pas de cloud).
- **Style : très professionnel** — sobre, lisible, typographie nette, couleurs limitées aux
  codes d'activité, pas de fioritures.
- **Conformité stricte** : tout calcul suit **à la lettre** la RSE (règlement (CE) 561/2006 et
  textes associés, dont le Paquet mobilité) et le **Code du travail français** (+ convention
  collective des transports routiers). Chaque règle implémentée cite son article ; les valeurs
  sont vérifiées dans les textes en vigueur (Légifrance, EUR‑Lex), jamais de mémoire.
- **Semaine** = du lundi 00h00 au dimanche 24h00 (définition de la RSE).

## 2. Version 1 — périmètre

### 2.1 Lecture de la carte
- Brancher le lecteur → Android propose d'ouvrir l'application.
- Bouton **« Lire la carte »** : lecture complète (quelques secondes), barre de progression.
- **Aucune écriture** sur la carte.
- Chaque lecture est enregistrée au format standard **.DDD** (preuve, relisible par d'autres logiciels).
- Messages clairs en cas de problème (pas de lecteur, carte mal insérée, autorisation refusée…).

### 2.2 Conducteurs
- Une **fiche conducteur** par carte, créée automatiquement à la première lecture
  (identifiée par le numéro de carte) : nom, prénom, n° de carte, validité, permis.
- **Email (facultatif)** :
  - à **chaque insertion** d'une carte, l'application vérifie si un email est renseigné pour ce
    conducteur : **oui → rien n'est demandé** ; **non → elle propose d'en ajouter un**
    (possible de passer, la question reviendra à l'insertion suivante) ;
  - pas de bouton « ne plus demander » (validé) ;
  - l'email ne sert à rien en V1, il prépare l'envoi des rapports (V2+).
- Liste des conducteurs, choix du conducteur affiché.

### 2.3 Historique
- Les lectures successives d'un même conducteur sont **fusionnées** : l'historique dépasse
  les ~28 jours gardés par la carte.
- Liste des lectures enregistrées, réouverture d'un ancien .DDD, **export .DDD**, **import .DDD**.

### 2.4 Affichage — 3 vues au choix
Passage d'une vue à l'autre par des **onglets en bas d'écran** (Tableau de bord · Calendrier ·
Jours), la dernière vue utilisée est rouverte au démarrage (validé).
Heures en **heure française** (changements d'heure gérés). Une option UTC est possible.

**Vue A — Tableau de bord**
```
┌─────────────────────────────────┐
│ DUPONT Jérôme      ▾ conducteur │
│ Dernière lecture : 08/11 18:42  │
├─────────────────────────────────┤
│ AUJOURD'HUI                     │
│ Conduite   6h12   Travail 1h05  │
│ Dispo      0h30   Repos   2h10  │
├─────────────────────────────────┤
│ SEMAINE (lun→dim)               │
│ Conduite 38h40  ███████░░  /56h │
│ QUINZAINE                       │
│ Conduite 71h15  ███████▓░  /90h │
├─────────────────────────────────┤
│ 7 derniers jours                │
│ ▆ ▇ ▅ ▁ ▇ ▆ ▃   (barres/jour)   │
└─────────────────────────────────┘
```

**Vue B — Calendrier**
```
┌─────────────────────────────────┐
│ ◀  Novembre 2026  ▶             │
│ L   M   M   J   V   S   D       │
│                         1       │
│ 2●  3●  4●  5●  6●  7○  8○      │
│ 9●  10● 11○ ...                 │
│ ● jour travaillé  ○ repos       │
│ (couleur = durée de conduite)   │
│ → appui sur un jour = détail    │
└─────────────────────────────────┘
```

**Vue C — Liste des jours**
```
┌─────────────────────────────────┐
│ Jeudi 5 novembre 2026    412 km │
│ ▓▓▓░░████████░░███████▒▒░░░░░░  │
│ 0h    6h    12h    18h    24h   │
│ Cond 8h45 Trav 1h30 Dispo 0h20  │
│ Repos 13h25                     │
├─────────────────────────────────┤
│ Mercredi 4 novembre 2026 ...    │
└─────────────────────────────────┘
```
Couleurs : conduite rouge, travail orange, dispo bleu, repos vert, carte retirée gris.

**Détail d'un jour** (depuis n'importe quelle vue) : frise, liste des changements
d'activité avec heures, totaux, km, véhicule(s) utilisé(s).

### 2.5 Contrôle réglementaire (V1, validé)
Principe : chaque contrôle affiche **la règle, l'article source, la valeur constatée et l'écart**.
Les seuils ci-dessous sont la liste de travail ; **chaque valeur sera vérifiée sur le texte en
vigueur (EUR‑Lex / Légifrance / convention collective) avant d'être codée**, et la source citée
dans l'application.

**RSE — règlement (CE) 561/2006 (+ Paquet mobilité, règl. (UE) 2020/1054)**
- Pause : après 4h30 de conduite cumulée, 45 min (ou 15 min puis 30 min).
- Conduite journalière : 9h, 10h autorisé 2 fois par semaine.
- Conduite hebdomadaire : 56h ; sur 2 semaines consécutives : 90h.
- Repos journalier : 11h (ou 3h + 9h), réduit à 9h au plus 3 fois entre deux repos hebdo ;
  repos dans une période de 24h après la fin du repos précédent.
- Repos hebdomadaire : 45h normal, 24h réduit avec compensation avant la fin de la 3e semaine ;
  au plus 6 périodes de 24h entre deux repos hebdo ; règle des 2 repos sur 2 semaines.
- Conditions spécifiques enregistrées sur la carte (ferry/train, hors champ) prises en compte.
- Limite : ce qui n'est pas sur la carte (lieu du repos hebdo normal, retour au domicile toutes
  les 3/4 semaines) ne peut être que **signalé à vérifier**, pas contrôlé.

**Code du travail / Code des transports / convention collective (transport routier de marchandises)**
- Temps de service = conduite + autres travaux + temps à disposition.
- Durées maximales de temps de service (journalière, hebdomadaire, moyenne sur période).
- Amplitude de la journée.
- Pause du temps de travail (au-delà de 6h de travail).
- Travail de nuit : plage horaire, durée maximale en cas de travail de nuit.
- Repos quotidien et hebdomadaire au sens du droit du travail.
- Équivalences grand routier et seuils de majoration : **calculés et affichés**, sans valorisation
  en euros (la paie reste pour plus tard).

**Affichage des contrôles**
- Tableau de bord : pastilles « conforme / à surveiller / infraction » sur le jour, la semaine,
  la quinzaine.
- Calendrier : jour marqué en cas d'infraction.
- Détail d'un jour : liste des contrôles avec règle, article, constaté, écart.
- Mention claire : outil d'aide personnelle, **sans valeur légale**.

### 2.6 Divers V1
- Thème clair / sombre automatique (suit le téléphone).
- Journal technique (copiable) pour le diagnostic.
- APK compilé automatiquement par GitHub et téléchargeable depuis le téléphone.

## 3. Version 2 — périmètre (décisions du 14/11/2026, à annoter)

### 3.0 Principes modifiés
- **Fuseau : heure de Paris partout**, y compris à l'étranger (jours, semaines, nuit, repas).
- **Application individuelle** : liée au premier conducteur lu, par les **14 premiers caractères**
  du n° de carte (identifiant conducteur). Les 2 derniers (remplacement / renouvellement,
  ex. `003` → `004`) peuvent changer : la nouvelle carte est reconnue et son historique fusionné.
  - Lecture d'une autre carte **refusée** ; consultation libre (protégée par le verrouillage du
    téléphone, empreinte/code en option).
  - Bouton **« Réinitialiser l'application »** (confirmation + export des .DDD avant effacement).
  - Migration : à la mise à jour, choisir le propriétaire ; les autres dossiers sont proposés à
    l'export puis supprimés. La liste des conducteurs (§ 2.2) disparaît.
- **« Rien ne quitte le téléphone » est assoupli** pour deux échanges seulement : lecture du
  barème public (§ 3.3) et envoi du rapport mensuel depuis le compte Google du conducteur (§ 3.5).

### 3.1 Lot A — Positions
- Décoder les lieux de début/fin de journée (EF Places) et les positions GNSS toutes les 3 h de
  conduite (EF GNSS_Places). La carte de référence en contient (~112 lieux, ~336 points).
- Pas de position si le véhicule n'a pas de chronotachygraphe intelligent : l'appli le gère.
- Passages de frontière / chargements : uniquement cartes Gen2 V2 → plus tard.

### 3.2 Lot B — Domicile et découchés
- Repos > 24 h → lieu du repos ; domicile = zone (~2 km) la plus fréquente sur plusieurs semaines.
- Lieux nommés (Maison, Dépôt…), domicile déclarable à la main.
- Chaque nuit : domicile / découché, **corrigeable à la main** (la correction prime).
- Statistiques des lieux de coupure (nuits en cabine, lieux fréquents, frais) **dans l'appli
  seulement**, pas dans le PDF.

### 3.3 Lot C — Frais de route
- Barème public : https://github.com/GithubPr1Ze/tacho-perso-bareme (`bareme.json`, taux par
  date d'effet + conditions par article du protocole du 30/04/1974). Vérifié à chaque démarrage,
  dernière version gardée en cache (fonctionne hors réseau).
- En vigueur : avenant n° 81 du 2/12/2025 (BOCC 2026-01, étendu le 3/02/2026), au 01/01/2026.
- NAO 2027 : ajoutée au barème **uniquement une fois signée**.
- Règles : repas (art. 3), casse-croûte (art. 5), grand déplacement (art. 6), indemnité
  spéciale (art. 7), nuit (art. 12), étranger +18 % (art. 13, à vérifier).

### 3.4 Lot D — Paye
- Régime grand routier (équivalence, décompte, seuils 25 % / 50 %) **vérifié dans les textes**
  avant de coder.
- Taux de cotisation déduits de 3 fiches de paye déposées dans `private/` (exclu de Git),
  enregistrés comme paramètres modifiables.
- Comparaison fiche réelle (heures payées, brut, net) / estimation.

### 3.5 Lot E — Rappels, rapport, onglet graphique
- Rappels : téléchargement avant 28 jours, expiration de la carte.
- Rapport mensuel PDF (heures + frais), généré sur le téléphone.
- **Envoi automatique** le 1er du mois **depuis le compte Google du conducteur, vers lui seul**
  (autorisation « envoi uniquement », pas de mot de passe stocké, aucune copie à un tiers).
  WorkManager, nouvel essai si pas de réseau. .DDD bruts joints. Pas de Google Drive.
  - Secours : notification « rapport prêt » → partage Android en un appui.
  - Prérequis : projet Google Cloud, vraie clé de signature de l'APK (dans `secrets`).
- Onglet graphique (Compose + Material 3, déjà en place) : frise de la journée, jauges de
  conduite restante semaine/quinzaine, carte des points (OpenStreetMap, sans clé). Thème sombre
  selon le téléphone, animations discrètes.

### 3.6 Ordre
A → B → C → E (rappels + PDF) → D → E (e-mail + graphique). Un APK testable par lot.

### 3.7 Idées toujours en attente
- Sauvegarde automatique sur le NAS, exports CSV, notes sur une journée.

## 4. Points ouverts

1. ~~Vues~~ → onglets en bas d'écran (validé).
2. ~~Rappel d'email~~ → proposé à chaque insertion tant qu'il manque, sans « ne plus demander » (validé).
3. ~~Semaine~~ → lundi 00h00 – dimanche 24h00 (validé).
4. ~~Style~~ → très pro (validé).
5. ~~Contrôle RSE / Code du travail~~ → en V1 (validé).
6. ~~Fuseau~~ → heure de Paris partout (validé 14/11/2026).
