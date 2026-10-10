# CHK Studio — Feuille de route montage mobile inspiré des standards de CapCut
Date : 2026-10-10
Entreprise : Sync 30 Numérique
Produit : CHK Agent Browser (Android natif, stockage CHK Fichiers hors ligne)
Dépôt : Chasmet/CHK-Agent-Browser

## Vérification de la démonstration utilisateur
Vidéo fournie « 1000152739.mp4 » : durée ~37,5 s.
- Bouton « Exporter » dans la barre supérieure : ouvre le dialogue d'export d'un projet déjà généré, proposant « Remplacer » ou « Nouvelle copie » ; n'affiche pas les actions de lecture / sauvegarde / partage.
- Bandeau inférieur « Export terminé · toucher pour lire / partager » : actions de sortie disponibles ici.
- Aperçu vertical occupe une faible proportion de la hauteur. Pas de bouton plein écran.
- Timeline déjà présente avec six clips, les WAV et un zoom au pincement, mais les boutons zoom n'étaient pas visibles.

## Intégrations livrées dans le code (à valider sur APK)
1. Aperçu plein écran immersif : *même* ExoPlayer/PlayerView et position conservée, barre de lecture, scrubbing, plan précédent/suivant, fermeture et retour timeline.
2. Export supérieur unifié : si le MP4 existe, accès direct « Lire / Enregistrer sur le téléphone / Partager », ou nouvelle copie/remplacement. Bandeau inférieur emploie les mêmes actions.
3. Zoom +/- de la timeline, en plus du pincement.
4. 4 filtres vidéo GPU Media3 supplémentaires : Vibrant, Cinéma doux, Désaturé, Sépia. Disponibles dans les options du plan, calculés via la bibliothèque open source AndroidX Media3 utilisée par l'export et l'aperçu.
5. Préservation des projets, fichiers sources et de l'ancien export ; édition locale.

## Outils open source étudiés, à intégrer de manière progressive
| Composant | Ressource | Intérêt | Décision |
| AndroidX Media3 / Transformer | https://github.com/androidx/media | montage/export Android hardware, effets OpenGL, audio/vidéo, aperçu | DÉJÀ INTÉGRÉ, à étendre. Apache 2.0 |
| Android-GPUImage-Plus | https://github.com/wysaid/android-gpuimage-plus | filtres GL étendus, color grading | OPTION pour shaders avancés, licence MIT : tester ABIs, performance, APK, provenance des binaires FFmpeg |
| FFmpegKitNext | https://github.com/arthenica/ffmpeg-kit (annonce de successeur) | codec/containers, sous-titres, audio complexe, speed ramp | ÉVALUATION SEULEMENT : mainteneur, taille APK, maintenance, LGPL/GPL et compatibilité Google Play à auditer avant import |
| ActionCut | https://github.com/naveenneog/ActionCut | inspiration ergonomie (timeline multipiste, actions, WorkManager) | RÉFÉRENCE D'ARCHITECTURE ; vérifier licence explicite avant toute copie de code, aucune copie effectuée |
| OpenTimelineIO | https://github.com/AcademySoftwareFoundation/OpenTimelineIO | interchange entre outils de montage | PHASE ULTERIEURE : formats de timeline, conversion/interop, pas moteur de rendu Android |

## Écarts encore ouverts vis-à-vis de CapCut
- Vraies transitions entre deux vidéos (fondu croisé, zoom, push, flash) au lieu du seul fondu au noir appliqué aux plans.
- Timeline multipiste audio indépendante avec déplacements, volumes, fondus, musique, voix, préécoute et forme d'onde décodée.
- Courbes de vitesse, keyframes de cadrage, pan et zoom, effets et masques.
- Sous-titres synchronisés, texte animé, stickers, chroma key, suppression du fond.
- Export 1080/4K selon codec/téléphone, résolution, FPS, bitrate, AVC/HEVC ; file d'attente d'exports reprise sur échec.
- Modèles de projets, undo/redo plus robuste et playback A/V multi-pistes.
La similarité ergonomique n'autorise pas de copier des assets ou du code propriétaires de CapCut.

## Validation exigée
- Démarrage sur téléphone réel Android et tests de la fenêtre plein écran, rotation/panneaux système, seek vers 5,15,25,35,45,55 s.
- Le bouton Exporter en haut doit offrir la lecture, l'enregistrement SAF Android et le partage d'un fichier déjà produit ; le même flux au bas de l'écran.
- Une capture d'écran de l'aperçu et un rendu MP4 composé de plusieurs séquences différentes pour contrôler la cohérence.
- CI Gradle assembleDebug + connectedDebugAndroidTest + Release signée par la clé déjà configurée.
