# CHK Studio, Tor et Local 3 — rapport de validation du 29 octobre 2026

La mission complète n'est pas livrée en production. Le code est sur la branche `feature/local3-studio-tor-20261029`, dans la [PR #76 en brouillon](https://github.com/Chasmet/CHK-Agent-Browser/pull/76). Aucun serveur PC, certificat de signature de production ou identifiant d'application de production n'a été modifié.

## État de livraison

| Élément | État constaté |
|---|---|
| Mixage audio original + musiques superposées | Implémenté ; exports Android décodés et mesurés indépendamment. |
| Gains piste/clip, dB, Mute/Solo, fondus, keyframes, ducking, limiteur | Implémentés ; couverture automatisée indiquée ci-dessous. |
| Recadrage tactile et MCP, aperçu Media3, export MP4 | Implémentés ; géométrie et images comparées sur un cas réel de rendu. |
| MCP audio/recadrage via le transport Local 3 existant | Code Android et tests natifs ; parcours complet à travers le serveur PC et le téléphone non validé. |
| Icône Tor indépendante | Présente dans le code ; ouvre une autre application Android. |
| Connexion Tor / diagnostic réel / domaine .onion / déconnexion | Non vérifiés ; CHK ne peut pas observer ou arrêter Tor Browser externe. Fonction Tor complète non acquise. |
| Build de production signé, GitHub Release, mise à jour intégrée | Non publiés pour cette branche. |
| Téléphone | Lecture MCP effective : version 1.0.520 au dernier contrôle ; autorisations persistantes actives. Nouvelle branche Studio/Tor non installée. |
| Serveur PC | Lecture effective : CHK Universal Control 1.0.4, running, browser_connected=true. Fichiers `pc/` inchangés. |

## Vérifications et échecs

**Dernier commit de code :** [0f3c4b72275aab322f25f4637966d5ecd5342d49](https://github.com/Chasmet/CHK-Agent-Browser/commit/0f3c4b72275aab322f25f4637966d5ecd5342d49).

Le [run ciblé 40924523174](https://github.com/Chasmet/CHK-Agent-Browser/actions/runs/40924523174) est **completed / success** : compilation debug, lint, compilation des tests, **36 tests Android réussis, zéro échec, zéro ignoré**, puis vérification indépendante FFmpeg réussie. Le nouveau test de reçus couvre annulation, sauvegarde complète sans journal, duplication et retour au projet original.

Le [run général 40924523214](https://github.com/Chasmet/CHK-Agent-Browser/actions/runs/40924523214) est **completed / success** : premier groupe de 72 tests réussi, puis **272 tests Android réussis, zéro échec, zéro ignoré** sur la suite complète. Ces nombres se recouvrent : ils ne doivent pas être additionnés en un nombre de tests uniques. Les lecteurs indépendants ouvrent aussi les documents PDF, DOCX, XLSX, PPTX, JSON, CSV et TXT générés sur Android.

L’exécution générale déclenchée par la PR [40924532424](https://github.com/Chasmet/CHK-Agent-Browser/actions/runs/40924532424) est également `completed / success`.

[Rapports de la suite générale](https://github.com/Chasmet/CHK-Agent-Browser/actions/runs/40924523214/artifacts/12920807305), rétention deux jours. Le dernier commit de code testé reste celui indiqué ci-dessus ; le commit de rapport ne modifie pas ce code.

Le [run précédent bb99d5c](https://github.com/Chasmet/CHK-Agent-Browser/actions/runs/40921513800) a compilé l'APK debug, passé lint et **34 tests Android, 0 échec, 0 ignoré** sur Android 11/API 30 émulé. Son contrôle FFmpeg initial a échoué car sa recherche de priming AAC était limitée à ±256 échantillons. Sur les mêmes fichiers récupérés, la recherche documentée de ±2048 échantillons trouve 448 échantillons de décalage (9,333 ms à 48 kHz). Le contrôle corrigé refuse les décalages supérieurs à 20 ms et exige un gain de voix entre 90 et 110 % ; il passe sur ces fichiers. Il ne prouve pas une synchronisation labiale humaine.

Les contrôles locaux de scripts navigateur/clavier/chemins, `git diff --check`, la syntaxe shell/Python et l'analyse syntaxique des 180 fichiers Java passent. L'analyse syntaxique Java seule n'est pas une compilation Android ; les compilations et tests instrumentés sont ceux des Actions référencées.

Des échecs antérieurs ont aussi révélé : géométrie de sortie invalide quand la rotation absente devenait NaN ; suppression de pistes non ciblées ; fin audio AAC antérieure à la durée vidéo ; récupération des preuves empêchée par la désinstallation du debug après les tests. Des corrections et des tests couvrent ces cas. Les reçus MCP sont conservés lors des annulations et sauvegardes complètes ; une duplication crée un nouveau journal sans toucher à celui de l'original.

Le téléphone répond réellement à `local3_status` ; la dernière lecture retourne maintenant 1.0.520. La nouvelle opération `desktop/tool=studio/capabilities` a retourné « Outil IA inconnu » sur 1.0.476. Le même contrôle a été refait sur 1.0.520 : tâche `failed`, « Outil IA inconnu ». Ces résultats ne sont pas des succès de bout en bout des nouvelles commandes.

## Preuves MP4 indépendantes

Les fichiers de référence ci-dessous proviennent des exports Android du commit 0f3c4b7. Le contrôle utilise FFmpeg, ffprobe et NumPy, séparément du moteur Android. Les valeurs exactes et SHA-256 figurent dans [studio-proof-0f3c4b7.json](validation/studio-proof-0f3c4b7.json).

| Contrôle | Mesure sur le fichier décodé |
|---|---|
| Voix originale synthétique 440 Hz à 100 % | 99,65 % du gain source après export AAC. |
| Musique WAV 880 Hz à 15 % | Amplitude 0,090359 ; source environ 0,610. |
| Voix humaine réelle + musique | Gain médian de voix 97,57 % après compression. |
| Ducking pendant les mots reconnus | Amplitude musicale médiane 0,011375 ; silence 0,090266, soit environ −18 dB. |
| Décalage audio mesuré | 448 échantillons / 9,333 ms ; seuil de test 20 ms. |
| Loudness du mixage humain | −24,3 LUFS ; calcul natif confronté à FFmpeg, tolérance 0,3 LU. |
| Crête réelle suréchantillonnée par FFmpeg | −19,2 dBFS pour le cas voix humaine ; absence d'échantillon plein niveau. |
| Limiteur/keyframes | Test PCM : saturation avant limiteur, sortie ≤ −1 dBFS, silence automatisé avant 1 s puis son après 1,5 s. |
| Recadrage | Sortie 480 × 540 ; durée conservée à environ 2 s ; rouge en haut, vert en bas après inversion/rotation. |
| Aperçu rendu contre export | Erreur moyenne absolue 0,084383 sur une échelle 0–255. |

| Source verticale à 1 s | Aperçu Media3 à 1 s | MP4 exporté à 1 s |
|---|---|---|
| ![Source réelle](validation/crop-source-0f3c4b7.png) | ![Aperçu rendu réel](validation/crop-preview-0f3c4b7.png) | ![Export MP4 réel](validation/crop-export-0f3c4b7.png) |

Les captures sont extraites des MP4 par FFmpeg et réduites uniquement pour cette présentation. Les mesures utilisent le décodage des fichiers, sans redessin ni image générée. Le test recadre la moitié haute de la source, inverse horizontalement puis tourne de +90° dans le sens antihoraire.

SHA-256 de `studio-human-mixed.mp4` : `23393c16c0d166634e9717e081a0ae624f1d28e439b96518c4bd59a02a39413f`.

SHA-256 de `studio-crop-proof.mp4` : `b24e452e953d4925bfb00cc3f308831eea8aa1aa58e0ace8b26cee7e8fb0aff9`.

[Artifact du dernier run validé](https://github.com/Chasmet/CHK-Agent-Browser/actions/runs/40924523174/artifacts/12920571807) : APK debug, rapports, WAV, MP4 et preuves JSON ; rétention GitHub de sept jours. SHA-256 APK debug : `f37a4ab72995ec1abf16378afe89c12c08f60370f3237ad5f8dc7842199e3c0d` (160 904 390 octets). Il s'agit d'un APK de test `com.chk.agentbrowser.debug`, pas d'une mise à jour signée de l'application de production.

## Commandes et paramètres MCP

[Contrat complet, toutes les opérations, erreurs, unités et exemples](STUDIO_AUDIO_CROP_MCP_20261029.md).

Le serveur PC 1.0.4 n'est pas modifié : `task_submit` utilise `action:"desktop"`, `args.tool:"studio"`, puis `task_get` lit l'état réel. Un état `queued` ou `running` ne constitue jamais un export réussi.

Opérations audio : `studio_audio_tracks`, `studio_audio_analyze`, `studio_audio_set_volume`, `studio_audio_keyframes`, `studio_audio_ducking`, `studio_audio_mix`, `studio_audio_verify`, plus `audio_track_create`, `audio_clip_set`, `audio_mix_status`.

Opérations spatiales : `studio_crop_open`, `studio_crop_set`, `studio_crop_move`, `studio_crop_resize`, `studio_crop_rotate`, `studio_crop_flip`, `studio_crop_preview`, `studio_crop_apply`. Le préfixe `studio_` est facultatif dans `args.operation`.

```json
{"action":"desktop","request_id":"transport-volume-01","args":{"tool":"studio","operation":"studio_audio_set_volume","project_id":"PROJET_UUID","expected_revision":12,"request_id":"music-15","scope":"clip","clip_id":"MUSIQUE_UUID","percent":15}}
```

```json
{"action":"desktop","request_id":"transport-crop-01","args":{"tool":"studio","operation":"studio_crop_set","project_id":"PROJET_UUID","expected_revision":13,"request_id":"crop-01","clip_id":"VIDEO_UUID","units":"normalized","ratio":"1:1","rectangle":{"x":0.1,"y":0.1,"width":0.7,"height":0.7}}}
```

Les commandes renvoient révision et valeurs effectives. Relire les pistes/le clip après modification. Positions et durées : millisecondes entières. Référentiel spatial : source orientée comme affichée, origine en haut à gauche, avant les effets. Conversion : `gain=percent/100`, `dB=20 log10(gain)` ; silence `percent:0` ou `db:"-Infinity"`.

## Limites et travaux restant avant publication

- Tester l'APK sur le téléphone avec CHK Browser Local 3 : import, modifications, reprises après coupure, captures avant/après, export et vérification finale à travers le vrai transport MCP.
- Valider Tor réellement dans Tor Browser officiel et un domaine .onion légitime. La présence du paquet attendu ne vérifie ni sa signature officielle ni son circuit Tor. Les états Connecté/Erreur Tor et la déconnexion effective ne sont pas pilotables depuis cette architecture externe actuelle.
- Le navigateur CHK ordinaire bloque les domaines .onion avant ses requêtes WebView. Aucun proxy/VPN global n'est ajouté ; les données Web de CHK et de l'autre application sont séparées. Cela ne constitue pas un audit réseau indépendant ni un test de fuite DNS réalisé sur le téléphone.
- Les fixtures exercées utilisent notamment WAV PCM mono 16 kHz, AAC mono/stéréo et un master 48 kHz. La matrice exhaustive des codecs Android, des appareils, des changements de vitesse et des transitions n’a pas été testée. Le limiteur est exercé sur PCM ; le contrôle indépendant de crête vraie porte sur le MP4 humain décrit, pas sur toutes les combinaisons de gains et codecs.
- Le moteur n'effectue pas de preuve universelle de présence audible de chaque source ou de synchronisation labiale. `audio_verify` le dit explicitement. L'analyse indépendante prouve les cas de test décrits.
- Recadrage animé par keyframes non pris en charge. La miniature d'édition affiche la source ; utiliser l'aperçu réellement rendu pour les effets et l'écoute du mixage.
- Un changement de vitesse rééchantillonne l'audio et change sa hauteur. Le mixage final est stéréo 48 kHz ; le traitement de sources à plus de deux canaux est une réduction simple, pas un mastering surround. LUFS natif mono/stéréo seulement ; crête vraie contrôlée indépendamment.
- Limites héritées : montage 20 min, 100 plans vidéo, 40 clips audio, 40 pistes, 500 keyframes par cible ; journal limité à 128 écritures MCP par projet puis erreur explicite, duplication possible.
- SDK conservés : minSdk 23, compileSdk 36, targetSdk 34 ; aucune baisse du SDK du dépôt.
- Construire et vérifier l'APK de production avec la clé persistante existante seulement après les validations manquantes ; publier la Release puis tester `releases/latest`, téléchargement et installation sans perte de données. Ces étapes ne sont pas encore effectuées.

Aucune disponibilité de **cette branche** dans l'application n'est confirmée. La version installée observée au dernier contrôle est 1.0.520 ; le système de mise à jour de cette branche n'a pas été validé par une installation réelle.

Une lecture distincte de `releases/latest` a trouvé [v1.0.520](https://github.com/Chasmet/CHK-Agent-Browser/releases/tag/v1.0.520), publiée le 29 octobre à 17:47:34 UTC. Elle correspond au travail Cut sur `main` (`dcd1154f4887e7d2782c955051e91803c361b394`), pas à cette PR Studio/Tor. La publication seule ne prouve pas sa détection dans la mise à jour intégrée. Une lecture MCP ultérieure confirme toutefois la version 1.0.520 sur le téléphone ; ce constat ne concerne pas les fonctions Studio/Tor de cette PR. La branche Studio/Tor reste basée sur le commit antérieur `d98b4b8` ; une simulation de fusion à trois voies avec ce nouveau `main` est sans conflit et conserve à l’identique `CutStudioLibraryActivity.java` et `CUT_STUDIO_INTEGRATION.md`. La fusion réelle et les tests du binaire fusionné ne sont pas effectués.

## Fichiers modifiés

Liste exacte des fichiers de code, ressources, tests et documentation par rapport à la base `main` initiale `d98b4b8329557d443954ff73b06ce8b7d847fba1` au commit de code indiqué, suivie des preuves ajoutées par ce rapport :

- `.github/workflows/android.yml`
- `.github/workflows/studio-audio-crop.yml`
- `app/src/androidTest/assets/LICENSE-vosk-human-speech.txt`
- `app/src/androidTest/assets/README-human-speech.md`
- `app/src/androidTest/assets/vosk-human-speech.wav`
- `app/src/androidTest/java/com/chk/agentbrowser/StudioAudioCropTest.java`
- `app/src/androidTest/java/com/chk/agentbrowser/StudioMultitrackIntegrationTest.java`
- `app/src/androidTest/java/com/chk/agentbrowser/StudioTouchIntegrationTest.java`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/assets/LICENSE-libebur128.txt`
- `app/src/main/java/com/chk/agentbrowser/AgentTasks.java`
- `app/src/main/java/com/chk/agentbrowser/AudioDecoder.java`
- `app/src/main/java/com/chk/agentbrowser/BackgroundBrowser.java`
- `app/src/main/java/com/chk/agentbrowser/BrowserPolicy.java`
- `app/src/main/java/com/chk/agentbrowser/DesktopCommands.java`
- `app/src/main/java/com/chk/agentbrowser/Local3Client.java`
- `app/src/main/java/com/chk/agentbrowser/Local3TaskPolicy.java`
- `app/src/main/java/com/chk/agentbrowser/MainActivity.java`
- `app/src/main/java/com/chk/agentbrowser/MediaTranscriber.java`
- `app/src/main/java/com/chk/agentbrowser/StudioAudio.java`
- `app/src/main/java/com/chk/agentbrowser/StudioAudioAnalysis.java`
- `app/src/main/java/com/chk/agentbrowser/StudioAudioMixer.java`
- `app/src/main/java/com/chk/agentbrowser/StudioAudioPanel.java`
- `app/src/main/java/com/chk/agentbrowser/StudioAutomation.java`
- `app/src/main/java/com/chk/agentbrowser/StudioCrop.java`
- `app/src/main/java/com/chk/agentbrowser/StudioCropView.java`
- `app/src/main/java/com/chk/agentbrowser/StudioEdits.java`
- `app/src/main/java/com/chk/agentbrowser/StudioLoudness.java`
- `app/src/main/java/com/chk/agentbrowser/StudioMcp.java`
- `app/src/main/java/com/chk/agentbrowser/StudioProjectLibrary.java`
- `app/src/main/java/com/chk/agentbrowser/StudioRenderPreview.java`
- `app/src/main/java/com/chk/agentbrowser/StudioTimeline.java`
- `app/src/main/java/com/chk/agentbrowser/TorEntryActivity.java`
- `app/src/main/java/com/chk/agentbrowser/TorMcp.java`
- `app/src/main/java/com/chk/agentbrowser/TorNetworkGuard.java`
- `app/src/main/java/com/chk/agentbrowser/VideoEditorActivity.java`
- `app/src/main/java/com/chk/agentbrowser/VideoEditorEngine.java`
- `app/src/main/res/drawable/ic_nav_tor.xml`
- `app/src/main/res/layout/activity_main.xml`
- `docs/RELEASE_NOTES.md`
- `docs/STUDIO_AUDIO_CROP_MCP_20261029.md`
- `validation/install_studio_test_model.sh`
- `validation/prepare_studio_test_model.py`
- `validation/studio_android_checks.sh`
- `validation/verify_studio_exports.py`

- `docs/VALIDATION_STUDIO_TOR_20261029.md`
- `docs/validation/studio-proof-0f3c4b7.json`
- `docs/validation/crop-source-0f3c4b7.png`
- `docs/validation/crop-preview-0f3c4b7.png`
- `docs/validation/crop-export-0f3c4b7.png`
