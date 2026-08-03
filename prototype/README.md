# FightArena - Prototype de détection (étape 0)

Prototype de validation de la détection des gestes. Spec de référence : `../docs/gesture-spec.md`.

## Lancer

```bash
cd prototype
npm install
npm run dev
```

Le serveur tourne en HTTPS auto-signé (obligatoire pour la caméra sur téléphone) :

- Sur ce PC : `https://localhost:5173/`
- Sur téléphone (même réseau WiFi) : `https://<IP-du-PC>:5173/` (adresse affichée dans la console, ex. `https://192.168.100.6:5173/`)

Sur téléphone, le navigateur affiche un avertissement de certificat non reconnu : cliquer sur "Avancé" puis "Continuer" (certificat auto-signé, normal en dev).

## Tester en 2 minutes

1. **Démarrer la caméra** (bouton en haut). Si le squelette ne se voit pas : bascule avant/arrière.
2. **Placement** : téléphone posé/calé, paysage, à 2.0-2.2 m de vous. La pastille "Position OK" doit s'afficher (reculez/avancez sinon).
3. **Jab** : garde puis coup droit rapide. Le compteur du bras doit s'incrémenter, l'écran flashe rouge. Un jab lent ou à demi-extension ne doit PAS compter (seuils spec : extension ≥ 0.95, vitesse ≥ 2.5).
4. **Garde** : les deux poings devant le visage pendant ≥ 500 ms. L'indicateur passe ACTIVE (bloc), l'écran flashe or.

## Protocole de calibration (section 6 de la spec)

Objectif : 50 clips "propres" et 50 "mous" par geste, séparés à 100 % par les seuils.

1. Bouton "Enregistrer propre" : faites 3 s de jabs propres (la capture s'arrête seule).
2. Bouton "Enregistrer mou" : faites 3 s de jabs lents/à demi-extension.
3. Vérifiez dans les événements : chaque clip propre doit déclencher (`déclenché: oui`), chaque clip mou doit échouer (`déclenché: non`).
4. "Exporter CSV" : télécharge les stats par clip pour analyse (pic extension, pic vitesse, déclenchement, garde).
5. En cas de chevauchement : ajuster les seuils dans `src/config.js` (bloc `jab`), puis recommencer. Tous les seuils sont concentrés ici, jamais en dur ailleurs.

## Fichiers

```
index.html          Interface (vidéo + overlay + panneau)
src/main.js         Pipeline : caméra, PoseLandmarker, jab, garde, calibration
src/config.js       Tous les seuils (miroir de config/detection.json de la spec)
src/style.css       Style sombre "arena"
public/models/      Modèle pose_landmarker_full.task (9 Mo, MediaPipe Google)
```

## Prochaines étapes (ordre spec section 6)

1. Jab + garde validés sur 3-5 personnes de gabarits différents.
2. Hook + esquive ensemble (protocole anti-conflit section 5 de la spec).
3. Duck (règle duck dégradé 50 %), puis uppercut.
4. Puis PvE bot par-dessus (fenêtre de télégraphe T1 = 1.5 s).
