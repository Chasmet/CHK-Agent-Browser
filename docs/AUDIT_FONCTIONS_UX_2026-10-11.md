# Tests, debug et expérience utilisateur — CHK Agent Browser

Base : `2245883c07905934abe3160e8691101ecc387b0e`, Release publique 1.0.168.
Sauvegarde : `backup/audit-ux-20261011-1250`.
Branche de travail : `feature/files-audit-ux-20261011-1250`.

## Défauts confirmés dans les sources et corrections

| Fonction | Défaut | Correction |
|---|---|---|
| Recherche Fichiers | Une catégorie ne cherchait que le nom de ses dossiers de premier niveau ; un dossier ouvert ne cherchait pas dans ses sous-dossiers | Recherche récursive bornée aux dossiers de la catégorie ou au dossier ouvert |
| Recherche globale | Le mode Récents servait de recherche et excluait les dossiers | Vue de recherche incluant les noms de dossiers ; Récents reste une vue de fichiers |
| Catégories | ZIP et fichiers non reconnus n’appartenaient à aucune des quatre catégories principales | Ces fichiers sont visibles dans Documents, sans déplacement |
| État de l’interface | Recherche perdue en passant entre Notes et Fichiers ; tri et grille/liste non enregistrés | Recherches indépendantes sauvegardées dans l’état Android ; préférences d’affichage persistantes |
| Retour | Impossible de simplement fermer la recherche avec Retour | Effacement du filtre et fermeture du clavier avant de quitter l’espace |
| Téléchargements | Tous les fichiers sur disque étaient présentés comme terminés, y compris un fichier encore écrit par DownloadManager | Exclusion des téléchargements non terminés ; contrôle juste avant import ; même liste pour UI et MCP |
| Sélection multiple | Premier échec interrompait le lot et masquait les éléments déjà traités | Traitement indépendant et bilan complet ; les échecs restent sélectionnés |
| Connexion | État actif basé uniquement sur le réglage enabled ; badge dans un en-tête masqué | État basé sur le service, les erreurs et le dernier contact ; indicateur visible et accès aux réglages |
| Navigation Cut/Notes | Au premier onResume, l’URL de fond pouvait remplacer le lien demandé ; extra Cut rejoué après recréation | Priorité au lien explicite pendant ce lancement ; consommation de l’extra ; pas de doublon après rotation |
| Notes | Une note vide était créée dès l’ouverture ou la fermeture | Un nouveau brouillon entièrement vide n’est pas enregistré |
| Mise à jour | Une tentative en échec était enregistrée comme vérification réussie pendant 24 h | Date de réussite enregistrée seulement après une réponse officielle exploitable ; délai de tentative de 10 min |
| Navigation mobile | L’icône Cut n’était pas recolorée avec les autres onglets | Même gestion des cinq icônes dans chaque espace |

## Vérifications effectuées sur le téléphone via le connecteur

La connexion était en ligne et répondait via la WebView de fond. Tests réalisés sans changer l’onglet Grok de l’utilisateur ni son projet Studio :

- statut MCP et espaces de travail ; liste des onglets ; lecture DOM et état de page ; statut de l’éditeur ;
- création d’un dossier temporaire dédié ; écriture/lecture UTF-8 avec accents et emoji ; refus d’écrasement sans autorisation ; renommage et recherche ;
- mise en corbeille puis restauration et relecture identique ;
- import base64 d’un fichier de test et relecture binaire identique ;
- création d’une note, lecture, mise à jour, refus d’une révision périmée, épinglage et export Markdown ;
- mise en corbeille/restauration de cette note avec révisions exactes.

Les données temporaires de test ont été mises en corbeille récupérable après vérification. Aucun fichier ou note de l’utilisateur n’a été modifié.

## Validation automatisée

La suite Android comporte 45 tests déclarés, dont huit nouveaux parcours : recherche imbriquée et dossiers, formats divers, recherche lors d’un changement d’espace et recréation, lien Cut lors d’une recréation, import d’un téléchargement incomplet, collision dans un déplacement multiple, note vide, indicateur de connexion.

Les tests préexistants couvrent également : saisie et clic unique vérifié, import dans un véritable input HTML, navigation WebView et récupération du moteur, permissions d’aperçu, stockage et fournisseur content://, révisions SQLite, sauvegarde des notes pendant recréation, bibliothèques des projets Studio, lecture/aperçu, rendu MP4 avec audio/ratio/vitesse, annulation/reprise, protection des sources, planification locale Cut, X immédiat et export réel de deux extraits depuis l’inbox MCP.

Contrôles exigés avant la publication :

1. `bash validation/check.sh`.
2. `./gradlew --no-daemon assembleDebug lintDebug`.
3. `./gradlew --no-daemon connectedDebugAndroidTest` sur Android API 30.
4. Compilation Release avec la signature persistante existante et `apksigner verify`.
5. Présence de l’APK attaché à la vraie GitHub Release et réponse de `releases/latest`.

Le conteneur local possède seulement un environnement Java d’exécution, sans javac ni SDK Android. Le téléchargement de Gradle est bloqué par son réseau. Les XML et différences sont vérifiés localement ; la compilation, le lint et l’exécution Android doivent être attestés par GitHub Actions, et non présentés comme ayant réussi localement.

## Résultats attestés — 11 octobre 2026

Les corrections ont été intégrées par la [PR #16](https://github.com/Chasmet/CHK-Agent-Browser/pull/16). Le commit de code testé et publié est `082c209c5f0c1c497eae5715e38f0bebe80cad96`.

La [validation de la branche, exécution #171](https://github.com/Chasmet/CHK-Agent-Browser/actions/runs/38135056432) et la [validation finale sur main, exécution #172](https://github.com/Chasmet/CHK-Agent-Browser/actions/runs/38135648845) ont réussi. Sur main, les rapports Android confirment **45 tests réussis, zéro échec, zéro test ignoré** :

| Suite Android | Tests réussis |
|---|---:|
| BrowserMediaUploadTest | 2 |
| CutStudioIntegrationTest | 4 |
| CutStudioAutomationIntegrationTest | 2 |
| GeneralReliabilityTest | 7 |
| UserExperienceIntegrationTest | 8 |
| VideoEditorIntegrationTest | 14 |
| VisibleAgentIntegrationTest | 1 |
| WorkspaceIntegrationTest | 7 |
| **Total** | **45** |

Les scripts de validation, la compilation debug, le lint, la compilation Release et la vérification `apksigner` ont tous réussi dans GitHub Actions. La vérification de la signature permanente existante a réussi avant la compilation Release ; l’applicationId reste `com.chk.agentbrowser`.

La [Release publique v1.0.172](https://github.com/Chasmet/CHK-Agent-Browser/releases/tag/v1.0.172) contient **CHK-Agent-Browser-1.0.172.apk**, 4 788 133 octets. La réponse officielle `releases/latest` indique cette version et l’URL attendue par le système de mise à jour intégré.

SHA-256 de l’APK publié : `19fdcec0de3b8d685df07579c1ac425c7f65d61a84c0ba4c0891e549db53811b`.

Installation : **Réglages → Rechercher et télécharger la mise à jour → Installer la mise à jour sur ce téléphone**, puis confirmation par l’installateur Android. Installer par-dessus l’application existante pour conserver les données. L’installation sur le téléphone n’est pas présentée comme ayant été effectuée par ces tests.

## Limites de couverture

- La sélection Android, l’installateur et les politiques de veille Honor/Huawei nécessitent des interactions système sur l’appareil. Les tests émulateur ne prouvent pas l’absence de coupure pendant une longue veille réelle.
- Les fonctions de connexion et formulaires sont testées sur des fixtures et pages contrôlées ; tous les sites tiers, comptes OAuth, codecs matériels et réseaux sociaux ne peuvent pas être garantis.
- Les rappels Cut restent locaux. Une programmation ou publication effective sur un réseau exige la confirmation du site ; aucune publication utilisateur n’a été lancée pour tester.
- L’application Cut Vidéo indépendante et les autres dépôts restent inchangés. Aucun nouveau serveur ni route Render n’est ajouté.
- La signature, l’applicationId, le stockage et la version de base de données sont conservés. L’installation finale sur le téléphone reste l’action du propriétaire.

Références Android : [DownloadManager](https://developer.android.com/reference/android/app/DownloadManager), [cycle de vie Activity](https://developer.android.com/reference/android/app/Activity).
