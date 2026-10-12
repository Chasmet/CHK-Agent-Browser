# Cut Vidéo — Parité de la bibliothèque dans CHK Agent Browser
Date : 12 octobre 2026
Source comparée : Chasmet/Cut-vid-o- (Release v2.0.3)
Dépôt modifié : Chasmet/CHK-Agent-Browser

## Isolation et conservation
- Branche de travail : feature/cut-parity-20261012, créée depuis main.
- Aucun changement dans le dépôt Cut-vid-o-, ni dans les routes Render et protocoles MCP.
- Les classes Cut de CHK sont indépendantes des fichiers de l'autre APK.
- Les MP4 existants restent dans le workspace privé CutVideo et les publications dans cut_studio_planning_v1.
- N'utiliser que la signature habituelle du workflow de release CHK pour préserver les données.
- Ne pas désinstaller l'ancienne APK pour mettre à jour : l'installer par-dessus avec la même signature.
- L'outil interne de mise à jour GitHub releases/latest reste inchangé.

## Écrans et fichiers
- CutStudioHomeActivity.java : accueil 4 cartes, couleurs noir + turquoise.
- CutStudioLibraryActivity.java : bibliothèque 3 niveaux (classements, dossiers, vidéos), miniatures, visualisation et partage.
- CutLibraryState.java : persistance autonome des classements, affectations, notes, suivi des réseaux et préférences visuelles.
- CutStudioSettingsActivity.java : paramètres et accès au gestionnaire de mise à jour existant.
- CutStudioActivity.java : import et découpage existants, désormais ouverts depuis l'accueil, planning individuel via extra, thème harmonisé.
- CutStudioStore.java : migration des chemins planifiés lorsque le dossier ou le MP4 est renommé; annulation des rappels de fichiers mis en corbeille.
- Navigation principale : ouvre l'accueil Cut.
- Manifest : ajoute les 3 nouvelles activités.

## Fonctions installées
1. Accueil CUT VIDÉO à quatre cartes (Importer, Mes vidéos, Assistant ChatGPT, Réglages).
2. Classements créés, renommés et supprimés sans effacer les MP4 des dossiers.
3. Déplacement **logique** des dossiers entre classements sans recopier les MP4.
4. Notes distinctes par classement et par dossier, avec insertion date/heure.
5. Liste et grille de vidéos, trois tailles de miniatures, tri date/nom/taille/nombre.
6. Miniatures vidéo extraites en tâche de fond ; présentation locale des tailles.
7. Lecture, programmation, partage, renommage et suivi manuel YouTube/TikTok/Instagram/X.
8. Sélection multiple, partage groupé et mise en corbeille avec confirmation.
9. Corbeille cachée CutVideo/.trash et bouton de restauration de fichiers ou dossiers, sans présumer restauration des rappels.
10. Renommage préservant les chemins des publications ; suppression logique annulant les rappels concernés.
11. Conservation du découpage Media3, de l'export et des commandes MCP cut_inbox/schedule_inbox, sans modification du vrai Cut Vidéo.

## Limites explicites
- La bibliothèque ne fusionne pas automatiquement les données privées de l'APK Cut Vidéo originale avec celles de CHK.
- Les programmations sont des rappels locaux Android, pas une publication réseau confirmée.
- Restaurer un MP4 depuis la corbeille ne restaure pas les publications précédemment annulées.
- Les miniatures dépendent des codecs pris en charge par le téléphone.
- La suppression physique de .trash reste à la charge de l'utilisateur via Fichiers CHK.
- Le lecteur et l'éditeur avancé de CHK conservent leurs moteurs existants : un simple changement graphique ne remplace pas les fonctions natives.

## Vérifications
- Compiler avec ./gradlew assembleDebug lintDebug.
- Exécuter connectedDebugAndroidTest incluant CutLibraryParityTest et CutStudioIntegrationTest.
- Tester sur téléphone réel : import 30 secondes, classement, note, création de fiche, renommage, redémarrage et mise à jour.
- Publier une release uniquement après validation; le workflow main signe avec la même clé persistante.
