# CHK Studio — Réalisateur local (14 octobre 2026)

## Objectif
Renforcer **l'éditeur Android existant**, sans API IA, sans compte cloud ni nouvel abonnement. Les outils sont exécutés sur le téléphone ; les sources restent dans Fichiers CHK.

## Modifications livrées dans cette branche

### 1. Audio et transcription
- L'audit inspecte la première piste audio **WAV PCM16** et estime le tempo à partir des attaques énergétiques par autocorrélation locale (fenêtre maximum 90 secondes).
- L'interface indique le BPM estimé et un indicateur de confiance. Ce n'est pas une détection de beat certifiée à l'image près ; les demi/doubles tempos sont possibles.
- Depuis le menu Audio, l'utilisateur peut lancer la **transcription locale Vosk FR** déjà prise en charge par CHK. Le modèle doit être installé, avec téléchargement initial possible. Aucun extrait audio n'est transmis à une API.

### 2. Diagnostic des cuts
- Audit non destructif des sources, durées et fondus.
- Trois images analysées par segment lorsque le décodeur le permet : luminosité moyenne, différence locale de pixels comme indicateur de texture, alertes sur les plans très sombres ou très clairs.
- Les résultats sont **indicatifs**, pas une inspection intégrale du mouvement. Ni détection fiable des erreurs de visage, ni correction automatique du lip-sync.

### 3. Timeline
- Deux nouvelles commandes pour avancer ou reculer d'environ une image (33 ms à 30 fps).
- Les outils existants conservent les fonctions couper, diviser, déplacer, lecture, pistes audio et export.
- Le déplacement ±33 ms est **approximatif** si le média n'est pas à 30 images/s ; il ne promet pas une précision universelle à l'image près.

### 4. Étalonnage GPU
- Nouvelle interface de réglage par segment : exposition, chaleur des couleurs, saturation (limites contrôlées avant export).
- Les presets permettent un **passage progressif** de réglages entre le début et la fin d'un plan, au moyen d'une matrice RGB calculée localement par Media3.
- L'aperçu ExoPlayer existant montre les rushes bruts ; les nouveaux réglages sont appliqués au MP4 exporté. Ne pas confondre aperçu et rendu final.

### 5. Effets
- Les filtres Media3, fondus, ralentis, titres et rotations déjà présents restent disponibles.
- Le nouveau preset **Lumière progressive** anime exposition, saturation et température d'un cut sans régénérer la vidéo.
- Les particules 3D, les transitions multicouches et le motion blur avancé restent **à développer** : ne pas les annoncer comme implémentés.

### 6. Anti-doublons
- La commande d'audit compare les sommes SHA-256 de fichiers locaux distincts.
- Les segments découpés qui réutilisent volontairement la même source sont signalés séparément ; ils ne sont pas qualifiés de doublons accidentels.
- Dans l'explorateur interne CHK, un même chemin ne peut plus être ajouté une deuxième fois automatiquement.
- Lors d'une importation Android, les fichiers sont comparés en flux (taille + contenu) aux sources déjà présentes ; la copie CHK nouvellement créée est écartée si elle est identique. Le fichier original de l'utilisateur n'est pas supprimé.
- **Important :** cette protection ne peut ni détecter à l'avance les générations sur le serveur de Grok, ni empêcher Grok de facturer deux générations. Une vérification de la bibliothèque Grok et une validation cut par cut restent nécessaires avant de générer.

## Sécurité et performance
- Aucun envoi audio ou vidéo à un serveur pendant l'audit ou l'étalonnage.
- Calculs lourds sur exécuteur de fond ; limites de fichier SHA-256 pour l'audit.
- Modifications explicites du projet seulement via une action utilisateur ; l'audit n'applique jamais ses suggestions automatiquement.
- Pas de suppression en masse, pas de régénération Grok, pas d'écrasement silencieux des projets existants.

## Tests
- Test instrumental PCM16 : tempo artificiel 120 BPM.
- Test d'audit : un même fichier utilisé dans deux segments est une réutilisation normale.
- Test du schéma d'export : valeurs d'étalonnage rejetées si hors bornes.
- Compilation et tests Android à suivre via GitHub Actions avant fusion de cette branche.

## Évolutions futures
- Décodage BPM de MP3/AAC via MediaCodec/MediaExtractor ; analyse des refrains.
- Retouche vidéo locale intelligente des erreurs de visage et détection lèvres/paroles, si les modèles embarqués sont suffisamment précis et compatibles.
- Précision temporelle basée sur les vrais PTS/fps source ; fondus/crossfades superposés.
- Masques, courbes RGB complètes, LUT et effets GPU avancés.
- Export 4K/H.265 conditionné aux capacités du téléphone.
