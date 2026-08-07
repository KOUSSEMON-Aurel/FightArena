# TODO FightArena

Suivi des étapes restantes. Référence : `docs/gesture-spec.md` (spec v1.0).
Pipeline pose OK : 20fps, drop=0, One-Euro + dead-reckoning (lag ~0.5 frame).

## 1. Détection des 6 gestes — EN COURS

- [x] Config JSON versionnée (`app/src/main/assets/config/detection.json`) : tous les seuils, timings, cooldown, duck_degraded — chargé au démarrage, jamais de valeur en dur dans le code
- [x] Normalisation scale-invariant : distances en unité = distance épaule-hanche (tronc)
- [x] Machine à états par geste : IDLE → START → HOLD → TRIGGER → RESET
- [x] Jab (bras gauche + droit) : extension + vitesse
- [x] Garde / Bloc (2 poignets en garde ≥ 500 ms)
- [x] Hook (rotation épaules + coude plié + vitesse latérale)
- [x] Uppercut (montée poignet depuis bas + hanche abaissée + coude plié)
- [x] Duck (nez sous ligne épaules, maintenu 250 ms) + flag duck_degraded (genoux seulement)
- [x] Esquive latérale (déplacement bassin, direction stockée)
- [x] Anti-conflit hook vs esquive (exclusivité mutuelle 300 ms)
- [x] Cooldown 250 ms entre deux coups + anti-triche (amplitude ET vitesse)
- [x] Logs des signaux réels (extension, vitesse, rotation, déplacement) pour calibration
- [ ] Vérifier sur téléphone les 6 gestes + régler les seuils si besoin

## 2. Guide de placement (distance 2.0-2.2 m)

- [ ] Overlay "reculez / avancez" basé sur la hauteur du tronc en pixels
- [ ] Blocage du lancement du round si tronc hors bornes (±10 %)

## 3. Config JSON

- [x] Seuils de détection dans `config/detection.json` (fait avec le point 1)
- [ ] T1/T2 (télégraphe du bot), fenêtres de validité dans le config

## 4. Anti-conflit hook vs esquive

- [x] Règle d'exclusivité mutuelle 300 ms en code (fait avec le point 1)
- [ ] Protocole de test : 50 hooks propres, vérifier zéro double déclenchement, clips de régression

## 5. Calibration

- [ ] Log en temps réel des signaux (extension, vitesse, rotation, déplacement) → déjà posé (point 1), à affiner en playtest
- [ ] 50 exécutions propres + 50 mous par geste, 3-5 gabarits, séparation 100 %
- [ ] Traces de régression `benchmark_*.data` (pattern Pushup Arena)

## 6. Bot avec télégraphe (gameplay)

- [ ] TELEGRAPH (T1) → IMPACT → RECOVERY (T2) avec fenêtre de validité du geste défensif
- [ ] 4 types d'attaque : tête → Duck, corps → Bloc, direct gauche → esquive droite, direct droit → esquive gauche
- [ ] Difficulté : T1 = 2.0 / 1.5 / 1.0 / 0.8 s

## 7. Trace serveur PvP (doc séparée à venir)

- [ ] Trace compacte par round : timestamps, amplitude, vitesse, geste, flags duck_degraded, précision globale
- [ ] Revalidation serveur des seuils anti-triche (précision < 40 % = flag)
