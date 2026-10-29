# CHK Studio / Tor / Local 3 — contrat de la branche de validation

État : travail sur `feature/local3-studio-tor-20261029`, PR #76 en brouillon.
Ce document décrit le code de la branche, pas une version installée sur le téléphone.
La signature de production, l'applicationId `com.chk.agentbrowser` et les réglages existants ne sont pas modifiés.
CHK Universal Control PC 1.0.4 et tous les fichiers `pc/` sont inchangés.

## Transport et preuve d'exécution

Le serveur 1.0.4 conserve son catalogue : les nouvelles opérations utilisent son action `desktop` déjà autorisée.
Utiliser `apps_list` pour choisir l'application Android et relire chaque tâche avec `task_get`.
Une tâche `queued` ou `running` ne prouve aucune modification.
Sur le téléphone encore en 1.0.476, l'essai réel `desktop / tool=studio / capabilities` a retourné `failed`, « Outil IA inconnu ».
Les nouvelles opérations attendent donc l'installation d'un APK qui les contient.

```json
{"app_id":"ID_ANDROID_LU_PAR_APPS_LIST","action":"desktop","request_id":"transport-mix-01","args":{"tool":"studio","operation":"capabilities"}}
```

La timeline existante accepte aussi `clip_id` pour déplacer, modifier ou supprimer un clip ; les index restent acceptés pour les anciens clients.

Les noms `studio_audio_tracks`, etc. sont également acceptés dans `args.operation` (préfixe `studio_` facultatif).
Le nom d'une nouvelle opération n'est pas une action directe du serveur 1.0.4.

## Modèle et compatibilité

- Les anciens projets gardent leur disposition audio séquentielle tant que le mode multipiste n'est pas activé. Raccourcir la vidéo en dessous de la durée des anciens audios active le modèle v2 pour conserver toutes les instructions audio, au lieu de les supprimer.
- L'activation crée `audio_model_version:2`, `audio_tracks` et les positions `offset_ms` des anciens clips, sans changer leurs sources ni leur ordre.
- L'interface propose « Piste parallèle » et « Mixeur ». Chaque nouvel import parallèle crée une piste distincte.
- `studio_id` est l'identifiant stable d'un clip ; `id` est celui d'une piste. La piste `original` représente le son des vidéos de base.
- Les pistes possèdent volume, Mute, Solo, fondus, keyframes et configuration de ducking. Les clips possèdent leur propre gain et leurs fondus/keyframes.
- Les pistes superposées sont sommées en flottants dans un fichier temporaire sur disque. Un limiteur stéréo lié à attaque instantanée et relâchement de 100 ms précède la conversion PCM16 du master WAV stéréo 48 kHz.
- La fin réelle d’une piste peut précéder celle de la vidéo : seule une fin de flux décodée est complétée par du silence. Les paquets AAC de priming sont conservés pour le décodage et les timestamps négatifs sont respectés ; le nombre de frames de silence ajouté est retourné dans la preuve.
- Le master est utilisé par la même composition Media3 pour l'aperçu rendu et le MP4. Les sources audio restent intactes. Le MP4 contient un mixage stéréo des sources, pas des stems séparés.
- Limites de ressources actuelles : montage de 20 min, 100 plans vidéo, 40 clips audio et 40 pistes, 500 keyframes par cible. Les reçus atomiques sont conservés dans le projet (128 écritures MCP par projet maximum, puis refus explicite plutôt qu'un effacement du journal).
- Le cache audio dépend des réglages audio et des SHA-256 des sources ; recadrer une image ne relance pas la reconnaissance vocale.

## Unités et gains

Toutes les positions et durées éditoriales sont des **millisecondes entières**.
`start_ms` et `duration_ms` définissent la fenêtre dans la source ; `offset_ms` place un clip audio sur la timeline.
La durée de lecture est `duration_ms / speed`. Les keyframes d'un clip sont relatives à son début sur la timeline, après changement de vitesse ; celles d'une piste sont relatives au montage.

`gain = percent / 100`, `dB = 20 × log10(gain)`, `gain = 10^(dB/20)`.
Le silence est représenté par `percent:0` ou `db:"-Infinity"`. 100 % = 0 dB ; 200 % ≈ +6,0206 dB.
Le gain effectif multiplie : volume de piste × automation de piste × fondus de piste × volume du clip × automation du clip × fondus du clip × ducking. Mute/Solo s'appliquent séparément.
Les keyframes utilisent une interpolation linéaire du gain ; avant/après la courbe, la première/dernière valeur est maintenue.

## Révisions et répétitions

Les lectures exigent `project_id` (sauf `capabilities`). Les écritures exigent en plus `expected_revision` et un `request_id` stable de 1 à 80 caractères (lettres, chiffres, tirets, soulignés).
Les paramètres identiques rejouent le reçu enregistré avant de tester la nouvelle révision. Réutiliser le même ID pour des paramètres différents est refusé.
Les reçus et la modification appartiennent à la même sauvegarde atomique. Après sauvegarde, le handler relit la révision et le reçu.
`completed` pour une modification signifie **projet sauvegardé** ; le champ `verified:project_persistence_only` ne valide pas un export.

Erreurs exploitables : `PROJECT_MISMATCH`, `REVISION_CONFLICT`, `REQUEST_ID_REUSED_WITH_DIFFERENT_PARAMETERS`, `TRACK_NOT_FOUND`, `CLIP_NOT_FOUND`, `EXPORT_ACTIVE`, `RECEIPT_LEDGER_FULL`, `MIX_STORAGE_LOW`, `SOURCE_AUDIO_TRUNCATED`.
Après une déconnexion, lire le projet et la file avant toute répétition ; utiliser le même identifiant pour la même demande.

## Audio : commandes

Tous les exemples suivants sont les `args` de `task_submit(action="desktop")`.
Remplacer les identifiants et la révision par ceux du projet courant.

| Opération | Paramètres spécifiques / résultat |
|---|---|
| `audio_tracks` | Renvoie pistes, clips stables, durées, positions, gains, Mute/Solo et formats réellement inspectés. |
| `audio_analyze` | `clip_id`, `start_ms`, `duration_ms` ; décodage PCM, RMS, crête échantillon, silences et loudness intégrée LUFS mono/stéréo sur les fenêtres d’au moins 400 ms. `detect_speech:true` ajoute les intervalles Vosk sur la piste correspondante. |
| `audio_track_create` | `track_id`, `name` ; crée seulement la piste demandée et active explicitement le modèle v2. |
| `audio_clip_set` | `clip_id`, `track_id`, `offset_ms`, `speed` ; affectation et placement d'un clip audio. |
| `audio_set_volume` | `scope:track` et `track_id`, ou `scope:clip` et `clip_id` ; `percent` OU `db`, `mute`, `solo` (piste), `audio_fade_in_ms`, `audio_fade_out_ms`. |
| `audio_keyframes` | `mode:read/replace/upsert/delete`, cible ; points `{id,time_ms,gain}` ou `point_id,time_ms,percent/db`. |
| `audio_ducking` | Cible piste, `config` avec `enabled`, `voice_track_id`, `reduction_db`, `threshold_dbfs`, `attack_ms`, `release_ms`. |
| `audio_mix` | Lance la file durable sur le snapshot courant et attend son état final. Une tâche MCP en cours reste en cours pendant l'export. Ne remplace pas une destination existante : choisir une nouvelle sortie. |
| `audio_mix_status` | Renvoie la vraie file avec les états des jobs et la progression du moteur. `state:completed` désigne la lecture de l'état, pas les jobs contenus. |
| `audio_verify` | Décodage du MP4 courant, SHA-256, niveaux et vérification technique. Ne prétend pas identifier toutes les sources par le seul drapeau audio du conteneur ; `verification_complete:false` et `sources_audible_verified:false`. |

```json
{"tool":"studio","operation":"audio_tracks","project_id":"PROJET_UUID"}
```

```json
{"tool":"studio","operation":"audio_set_volume","project_id":"PROJET_UUID","expected_revision":12,"request_id":"music-volume-15","scope":"clip","clip_id":"MUSIQUE_UUID","percent":15,"audio_fade_in_ms":300,"audio_fade_out_ms":500}
```

```json
{"tool":"studio","operation":"audio_keyframes","project_id":"PROJET_UUID","expected_revision":13,"request_id":"music-curve-01","scope":"clip","clip_id":"MUSIQUE_UUID","mode":"replace","points":[{"id":"intro","time_ms":0,"gain":0},{"id":"plein","time_ms":1000,"gain":1}]}
```

```json
{"tool":"studio","operation":"audio_ducking","project_id":"PROJET_UUID","expected_revision":14,"request_id":"music-duck-01","scope":"track","track_id":"MUSIQUE_PISTE","config":{"enabled":true,"voice_track_id":"original","reduction_db":-18,"threshold_dbfs":-40,"attack_ms":100,"release_ms":500}}
```

```json
{"tool":"studio","operation":"audio_mix","project_id":"PROJET_UUID","expected_revision":15,"request_id":"export-mix-01"}
```

Le ducking requiert Vosk FR ou EN. L'installation initiale du modèle existant se fait par HTTPS ; aucune voix n'est envoyée à un serveur. Les mots reconnus sont croisés avec l'énergie PCM. La reconnaissance, notamment sur le rap, reste faillible. L'attaque anticipe les intervalles détectés ; le relâchement remonte progressivement après les mots. Sans mots reconnus, la musique reste au niveau demandé ; cela ne signifie pas que la vidéo est dépourvue de voix.

## Recadrage : commandes

| Opération | Paramètres spécifiques |
|---|---|
| `crop_open` | `clip_id` ; dimensions, géométrie enregistrée et durée. |
| `crop_set` | `clip_id`, `rectangle:{x,y,width,height}`, `units:normalized/pixels`, `ratio:free/9:16/16:9/1:1/4:5`. |
| `crop_move` | `clip_id`, `dx`, `dy`, `units:normalized/pixels` ; déplacement borné. |
| `crop_resize` | `clip_id`, `width`, `height`, unités et ratio ; renvoie le rectangle contraint. |
| `crop_rotate` | `clip_id`, `degrees` absolus de −180 à 180, positifs dans le sens antihoraire. |
| `crop_flip` | `clip_id`, `horizontal`, `vertical` booléens absolus, pas une bascule répétable. |
| `crop_preview` | `clip_id`, `time_ms` local au clip ; génère une image issue d'un MP4 de prévisualisation de la composition Media3. Renvoie `jpeg_base64` et le SHA-256 du MP4 temporaire. Respecte le consentement de partage d'aperçus. |
| `crop_apply` | `clip_id`, objet `crop` complet ; sauvegarde atomique gardée comme les autres écritures. |

Référentiel : source orientée comme affichée (métadonnée de rotation incluse), origine en haut à gauche, avant les effets géométriques du clip. Les coordonnées normalisées sont dans `[0,1]`, largeur/hauteur strictement supérieures à 0,01. L'effet `Crop` de Media3 adapte les dimensions ; la rotation et les inversions suivent le recadrage. La rotation précise peut créer des bandes dans le rectangle de sortie. Une commande de recadrage ne change aucune durée.

```json
{"tool":"studio","operation":"crop_set","project_id":"PROJET_UUID","expected_revision":15,"request_id":"crop-vertical-01","clip_id":"VIDEO_UUID","units":"normalized","ratio":"9:16","rectangle":{"x":0.1,"y":0.05,"width":0.8,"height":0.9}}
```

```json
{"tool":"studio","operation":"crop_preview","project_id":"PROJET_UUID","clip_id":"VIDEO_UUID","time_ms":1000}
```

L'outil tactile fournit quatre poignées, déplacement, pincement pour zoomer le cadrage, ratios, angle précis, quart de tour, inversions, réinitialisation, annulation et aperçu rendu à la demande. La miniature d'édition de la source reste distincte de l'image effectivement rendue.
Les keyframes spatiales ne sont pas prises en charge ; les paramètres inconnus sont refusés.

## Tor

L'icône ouvre un écran séparé puis le paquet Android attendu `org.torproject.torbrowser`.
Aucun proxy, VPN, cookie, historique ou stockage du WebView CHK n'est partagé avec ce navigateur externe.
Le diagnostic `https://check.torproject.org/` est ouvert exclusivement dans ce paquet.
CHK ne lit pas les pages de l'autre application : présence du paquet, lancement et connexion réseau Tor sont trois faits différents.
Le paquet est détecté par son identifiant ; sa signature officielle n'est pas attestée par cette branche.

```json
{"app_id":"ID_ANDROID","action":"desktop","args":{"tool":"tor","operation":"status"}}
```

`tor_connected:null`, `tor_verified:false`, `can_verify_tor_connection:false`, `can_disconnect_external_tor:false` sont intentionnels.
`open` ne renvoie pas un succès de connexion. Le bouton Déconnexion explique l'action à faire dans Tor Browser ; il ne peut pas arrêter ce processus externe.
Les domaines `.onion` sont refusés par la politique URL CHK et par l'interception des requêtes WebView principales et sous-ressources, avant leur requête HTTP.
Il ne s'agit pas d'une attestation globale d'absence de fuite réseau : aucun audit réseau de Tor Browser ni essai réel .onion n'a été réalisé sur le téléphone pour cette branche.

## Compilation et limites de livraison

Le projet existant utilise Java, minSdk 23, targetSdk 34, compileSdk 36 et Media3 1.11.1. Ils sont conservés ; cette branche n'annonce pas de support Android API 21 ni de compilation SDK 34.
Le workflow Android de production et `signing/prepare_ci.py` gardent la clé persistante et sa vérification d'empreinte ; cette clé n'est pas régénérée. Les versions restent gérées par les runs GitHub de la chaîne existante.
Le workflow supplémentaire ne publie qu'un **artifact de test debug**, package suffixé `.debug`, et les preuves ; il ne crée pas de Release.

La publication de production reste bloquée par les vérifications réelles manquantes : installation sur appareil, workflow entier via Local 3, diagnostic Tor et .onion, déconnexion, contrôle d'une mise à jour sans perte de données.
Le rééchantillonnage du mode audio v2 modifie la hauteur lors des changements de vitesse. Le downmix des sources de plus de deux canaux n'est pas un moteur surround de mastering.
Le limiteur contrôle les crêtes échantillon ; le true peak inter-échantillons doit être contrôlé indépendamment. La mesure LUFS native utilise la pondération K et les portes BS.1770, pour le mono et la stéréo ; le script FFmpeg la compare au décodage indépendant des exports de test.

Validation AAC : le contrôle indépendant recherche le décalage du signal décodé sur ±2048 échantillons à 48 kHz, refuse tout décalage supérieur à 20 ms et exige un gain médian de voix entre 90 et 110 %. Cette mesure ne prouve pas la synchronisation labiale sur un tournage humain.
