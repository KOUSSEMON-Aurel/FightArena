# Spec de détection : les 6 gestes de combat (FightArena)

Version : 1.0
Statut : référence pour le prototype de détection

Ce document est la spec unique de référence pour la détection des gestes. Tout changement de seuil passe par ce fichier (pas de valeur en dur dans le code).

---

## 1. Décision bloquante : distance caméra standard

**Avant toute calibration : fixer la distance de jeu.**

Pour capter les 33 points du corps entier (nécessaire pour duck, esquive, garde), le téléphone doit être posé/calé à **2.0 à 2.2 mètres** du joueur, **en mode paysage**.

Pourquoi c'est bloquant :

- À cette distance, l'amplitude en pixels d'un jab devient petite et la précision de MediaPipe sur poignets/coudes se dégrade (moins de pixels par articulation = plus de bruit sur les seuils de vitesse).
- Les seuils de vitesse (jab ≥ 2.5 unités/s, hook ≥ 1.8 unités/s) sont fiables à 1.5 m mais bruités à 2.5 m.
- Il est impossible de calibrer "une bonne fois pour toutes" sans fixer la distance d'abord.

Règles :

1. La distance de jeu standard est 2.0-2.2 m, paysage, téléphone posé au sol contre un mur (comme Pushup Arena) ou sur un support.
2. Tous les seuils de cette spec sont calibrés **à cette distance** et uniquement celle-là.
3. Le jeu doit afficher un guide de placement (overlay "reculez / avancez" basé sur la hauteur du tronc en pixels) avant chaque session.
4. Si la pose est trop loin ou trop proche (tronc hors des bornes), le jeu bloque le lancement du round avec un message clair.

Validation : le guide de placement doit amener le joueur dans la zone où la hauteur du tronc est comprise dans une fourchette de ±10 % de la référence.

---

## 2. Prérequis communs à tous les gestes

| Élément | Valeur |
|---|---|
| Modèle | `PoseLandmarker` MediaPipe (33 points), modèle *full* sur téléphones moyens/hauts, *lite* en fallback |
| Fréquence cible | 30 fps, toléré 24 fps avec dégradation gracieuse |
| Source des landmarks | `pose_world_landmarks` (3D stables), pas les coordonnées pixels brutes |
| Normalisation | Toutes les distances en unité = distance épaule-hanche (tronc). Scale-invariant. |
| Lissage | Moyenne mobile sur 3 frames par landmark |
| Filtrage | Ignorer tout landmark avec visibilité < 0.5 |
| Pattern | Machine à états par geste : `IDLE → START → HOLD → TRIGGER → RESET` |
| Cooldown | 250 ms minimum entre deux coups |
| Anti-triche | Chaque geste a un seuil d'amplitude minimum ET de vitesse minimum |

Machine à états (rappel) : un geste ne compte qu'au passage en TRIGGER, et jamais deux fois sans repasser par RESET.

---

## 3. Les 6 gestes

### 3.1 Jab (coup droit avant)

| Paramètre | Valeur |
|---|---|
| Points suivis | épaule (11/12), coude (13/14), poignet (15/16) du bras qui frappe |
| Signal 1 : extension | `dist(poignet, épaule) / dist(épaule, hanche)` |
| Signal 2 : vitesse | vitesse du poignet vers l'avant, en unités/s |
| START | extension > 0.6 |
| TRIGGER | extension ≥ 0.95 ET vitesse ≥ 2.5 unités/s |
| RESET | extension < 0.65 |
| Anti-triche | extension < 0.95 au pic = pas de coup ; vitesse < 2.5 = pas de coup |

### 3.2 Hook (crochet latéral)

| Paramètre | Valeur |
|---|---|
| Points suivis | les 2 épaules, poignet du bras qui frappe, coude |
| Signal 1 : rotation | angle de la ligne épaules (gauche→droite) vs l'horizontale caméra, en degrés |
| Signal 2 : trajectoire | déplacement latéral du poignet depuis sa position de garde |
| START | rotation > 8° par rapport à la garde |
| TRIGGER | rotation ≥ 25° ET angle coude < 100° ET vitesse latérale poignet ≥ 1.8 unités/s |
| RESET | rotation < 10° et poignet revenu près du corps |
| Anti-triche | bras tendu = pas un hook (coude plié obligatoire) ; sans rotation du buste, pas de hook |

**Conflit connu avec l'esquive latérale (section 5)** : le hook fait suivre les hanches chez les joueurs naturels, risque de double-déclenchement avec l'esquive. Règle d'exclusivité mutuelle : si un hook est déclenché, toute esquive est ignorée pendant 300 ms, et inversement.

### 3.3 Uppercut

| Paramètre | Valeur |
|---|---|
| Points suivis | poignet, coude, épaule, hanche du même côté |
| Signal : montée verticale | déplacement vertical du poignet depuis sa position basse |
| START | poignet sous la ligne des coudes ET hanche abaissée de ≥ 0.1 |
| TRIGGER | montée du poignet ≥ 0.4 × tronc en < 0.5 s ET angle coude < 120° pendant toute la montée |
| RESET | poignet redescendu sous le coude |
| Anti-triche | départ en haut = pas d'uppercut ; bras tendu = pas d'uppercut |

### 3.4 Duck (esquive basse)

| Paramètre | Valeur |
|---|---|
| Points suivis | nez (0), milieu des épaules, hanche |
| Signal : descente | chute du nez par rapport à la ligne des épaules |
| START | nez descend sous la ligne des épaules |
| TRIGGER | nez ≥ 0.15 × tronc sous la ligne des épaules, maintenu 250-400 ms |
| RESET | nez remonte au-dessus de la ligne des épaules |
| Anti-triche | incliner la tête seule sans abaisser les épaules = rien (on compare la ligne des épaules, pas juste le nez) |

**Décision tranchée : duck dégradé (genoux fléchis sans chute des épaules)**

- Un duck complet (genoux + épaules) = esquive totale : 0 dégât.
- Un duck dégradé (genoux seulement, épaules qui ne descendent pas) = **défense partielle : le coup touche mais inflige 50 % des dégâts réduits** (défense à 50 % de sa valeur).
- Justification : punir à 100 % un joueur qui a eu l'intention de s'accroupir serait injuste, et tout donner ouvrirait une triche facile (s'accroupir en faisant n'importe quoi). Le 50 % garde l'anti-triche efficace sans frustrer.
- Le duck dégradé est loggé (flag `duck_degraded` dans la trace) et sa valeur (0 %, 50 %, 100 %) est un paramètre de config JSON pour ajustement en playtest sans redeployer la logique.

### 3.5 Esquive latérale (pas de côté)

| Paramètre | Valeur |
|---|---|
| Points suivis | milieu des hanches, épaules |
| Signal : déplacement | déplacement horizontal du milieu des hanches depuis la position de repos |
| START | déplacement > 0.05 |
| TRIGGER | déplacement ≥ 0.2 × largeur d'épaules, atteint en < 0.6 s (direction gauche OU droite, stockée : utilisée contre les attaques directionnelles) |
| RESET | retour du bassin à < 0.1 de la position de repos |
| Anti-triche | bouger les bras sans déplacer le bassin = rien ; déplacement trop lent = pas d'esquive |

**Conflit connu avec le hook (section 5)** : les hanches suivent souvent la rotation du hook. Même règle d'exclusivité mutuelle que le hook (300 ms).

### 3.6 Garde / Bloc (les deux poings devant le visage)

| Paramètre | Valeur |
|---|---|
| Points suivis | les 2 poignets, les 2 épaules, nez |
| Signal : position de garde | les 2 poignets au-dessus de la ligne des épaules ET à < 0.5 × largeur d'épaules de l'axe vertical du nez |
| START | 1 poignet entre en garde |
| TRIGGER | les 2 poignets en garde, maintenus ≥ 500 ms |
| RESET | un poignet (ou les deux) sous la ligne des épaules |
| Anti-triche | un seul bras levé = pas un bloc ; bras en l'air à 45° = pas un bloc |

---

## 4. Fenêtre de télégraphe du bot

Aussi critique que les seuils : si le télégraphe est trop court, le duel est injouable même avec une détection parfaite ; trop long, il devient trivial.

### Définition

Une attaque du bot est une séquence temporelle paramétrable :

```
TELEGRAPH (durée T1) → IMPACT (point de jugement) → RECOVERY (durée T2)
```

- Pendant TELEGRAPH, le bot annonce son attaque (animation de vent, son, flèche directionnelle). Le joueur doit répondre avec le bon geste.
- À IMPACT, le jugement est évalué : le bon geste était-il en HOLD/TRIGGER au bon moment ?
- Pendant RECOVERY, le bot est vulnérable : c'est la fenêtre de contre-attaque (jab/hook/uppercut).

### Valeurs par défaut

| Paramètre | Valeur V1 |
|---|---|
| T1 (télégraphe) | **1.5 s** par défaut |
| T2 (recovery / fenêtre de contre) | 1.0 s |
| Fenêtre de validité du geste défensif | T1 entier (le geste doit être actif à IMPACT) |

### Difficulté

La difficulté réduit progressivement T1 (variable globale de session, pas par joueur en PvP) :

| Niveau | T1 |
|---|---|
| Facile | 2.0 s |
| Normal | 1.5 s |
| Difficile | 1.0 s |
| Brutal | 0.8 s |

Ces valeurs sont des points de départ à valider en playtest. T1 et T2 sont dans le config JSON.

### Types d'attaque du bot (V1)

| Attaque | Geste défensif attendu |
|---|---|
| Coup à la tête (crochet visé haut) | Duck |
| Coup au corps | Bloc |
| Coup direct gauche | Esquive droite (ou bloc) |
| Coup direct droit | Esquive gauche (ou bloc) |

---

## 5. Protocole anti-conflit : hook vs esquive latérale

**Risque** : un vrai hook fait souvent suivre les hanches. Deux gestes peuvent se déclencher pour un seul mouvement.

**Règle d'exclusivité mutuelle (appliquée en code) :**

- Si `hook` passe en TRIGGER, toute détection d'`esquive_laterale` est ignorée pendant 300 ms, et inversement.
- L'ordre de priorité est neutre : le premier qui atteint son TRIGGER gagne, l'autre est supprimé de la fenêtre.

**Protocole de test obligatoire pendant la calibration :**

1. Enregistrer 50 hooks propres avec logging simultané des deux signaux (rotation buste + déplacement bassin).
2. Vérifier que `hook` et `esquive_laterale` ne se déclenchent jamais ensemble sur ces clips.
3. Si des doubles déclenchements apparaissent : resserrer un des deux seuils (rotation ≥ 25° → 30°, ou déplacement bassin ≥ 0.2 → 0.25) avant d'ajuster la règle temporelle.
4. Les clips deviennent des cas de test de régression permanents (voir section 6).

---

## 6. Protocole de calibration

### Conditions fixes (obligatoires)

- Distance caméra : 2.0-2.2 m, paysage, téléphone calé (section 1).
- Même téléphone de référence pendant toute la calibration initiale.
- 3 à 5 personnes de gabarits différents (au moins 1 petit, 1 grand, 1 femme).

### Méthode par geste

1. Pour chaque geste : enregistrer 50 exécutions "propres" et 50 "mous/lentes/déformées".
2. Les seuils doivent séparer les deux populations à 100 % (aucun propre rejeté, aucun mou accepté).
3. Log en temps réel des signaux (extension, vitesse, rotation, déplacement) pendant la calibration pour régler les seuils visuellement.
4. Les enregistrements sont stockés comme suite de tests de régression (pattern identique aux fichiers `benchmark_*.data` de Pushup Arena : ce sont des traces de poses labellisées).
5. Toute modification future d'un seuil doit repasser la suite complète : 0 régression autorisée.

### Ordre de travail recommandé

1. **Distance caméra standard** (section 1) : bloquant, tout le reste en dépend.
2. **Jab + Garde** en premier : les deux gestes les moins ambigus, aucun conflit croisé. Valide tout le pipeline (landmarker, lissage, normalisation, machine à états, guide de placement).
3. **Hook + Esquive ensemble** (pas séparément) : appliquer le protocole anti-conflit de la section 5.
4. **Duck** : règle du duck dégradé déjà tranchée (50 %), valider la distinction en calibration.
5. **Uppercut** en dernier : le plus exigeant en précision de pose.

### Configuration des seuils

Tous les seuils, timings (T1, T2, cooldown, HOLD) et la valeur du duck dégradé vivent dans un **fichier de config JSON versionné** (ex : `config/detection.json`), chargé au démarrage. Jamais de valeur en dur dans le code. Cela permet les ajustements de playtest sans recompiler ni redeployer la logique.

---

## 7. Trace de validation serveur (rappel)

Le client envoie au serveur une trace compacte par round (timestamps, amplitude, vitesse et geste de chaque détection, flags `duck_degraded`, précision globale) et pas seulement le score final. Le serveur revalide les seuils anti-triche sur cette trace (précision < 40 % = flag joueur). Détails complets dans la spec PvP async (document séparé à venir).
