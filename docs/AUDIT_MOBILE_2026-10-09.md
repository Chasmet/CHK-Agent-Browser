# Audit et refonte mobile — CHK Agent Browser

Périmètre : vidéo fournie, application Android Java, navigation WebView, service distant, transferts, stockage, notes, provider, sauvegardes, distribution et module MCP isolé sur le relais existant. Référence examinée : `528f60b` pour Android, `61f411c` pour le relais. Aucun changement de package, clé Android, jeton du téléphone ou routes du Modéliseur 3D.

## Constats et corrections

| Domaine | Constat initial | Mise à jour |
|---|---|---|
| Ergonomie | Plusieurs barres permanentes ; petites actions et libellés de 12 sp | Barre d’adresse en bas, navigation Navigateur/Fichiers/Notes, commandes de 48 dp, menu regroupé, sélecteur d’onglets vertical |
| Navigation | Onglets perdus à la réouverture ; manque d’outils de lecture | Restauration des adresses et onglet actif, recherche dans la page, taille du texte enregistrée, lecture agrandie, appui long sur les liens, partage, vidéo plein écran |
| Fichiers | Téléchargements sans espace de travail organisé | Dossiers, import multiple via sélecteur Android, documents UTF-8, renommage/déplacement, recherche locale, ouverture, partage, export et récupération des téléchargements existants |
| Médias | Ouverture dépendante d’autres applications | Lecteur d’images avec zoom, prévisualisation vidéo/audio locale ; PDF et autres documents ouverts avec une application compatible |
| Notes | Fonction absente | Notes persistantes, recherche titre/contenu, épinglage, autosauvegarde, listes avec cases textuelles, extraits de pages avec URL source, partage et export Markdown |
| Données | Pas de protection prévue contre les éditions concurrentes | SQLite et numéros de révision pour les notes ; refus d’un brouillon périmé et possibilité de conserver une copie ; écriture atomique des documents |
| Suppression | Risque de pertes en ajoutant de nouvelles commandes | Corbeille récupérable pour fichiers/dossiers et notes ; restauration sans écraser les destinations |
| MCP | Commandes uniquement web ; réponses ordinaires tronquées à 11 000 caractères | Outils Files/Notes, capacités annoncées par l’APK, réponses de travail plus grandes et tests de transport Unicode sans troncature |
| Importation web | Les fichiers étaient préparés uniquement sur le relais | Envoi direct d’un fichier de l’espace Fichiers par URI de lecture seule ; vérification de réception par le site |
| Concurrence des transferts | Nettoyage d’une importation pouvait retirer les transferts récents d’une autre | Suppression des seuls identifiants appartenant à l’importation terminée |
| Mise à jour | Pas d’interrupteur de vérification automatique ni progression dans Réglages | Interrupteur mémorisé, vérification au démarrage au plus une fois par jour, progression du téléchargement ; signature/package/version vérifiés avant l’installation |
| Compatibilité | Nouveaux usages pouvant casser Android 5 | API minimales vérifiées par lint, tri compatible API 21 ; Java et compile/target 34 conservés |

## Stockage et sécurité

Les contenus résident dans le stockage privé de CHK Agent Browser sur le téléphone. Le serveur ne devient pas un stockage permanent des notes et documents. Les commandes ne donnent pas accès aux autres dossiers du téléphone ni aux préférences contenant le jeton MCP. Le sélecteur Android donne accès aux fichiers que le propriétaire choisit, sans permission globale de stockage. Le provider ne permet que la lecture des fichiers préparés ou de l’espace Fichiers ; les URI partagées reçoivent des droits ponctuels.

Les écritures, importations, déplacements et commandes utilisent une seule file de travail pour éviter de bloquer l’écran et de faire concourir les modifications. Les notes utilisent une base SQLite ; les documents texte utilisent AtomicFile. Une mise à jour ne supprime pas ces données. Les règles de sauvegarde Android incluent le nouvel espace et sa base, et excluent toujours le jeton MCP. La corbeille n’est pas vidée automatiquement.

## Limites explicites

- Le moteur reste Android System WebView. Aucun benchmark n’établit une supériorité générale sur Chrome en vitesse, sécurité ou compatibilité.
- Les deux WebView visible/arrière-plan restent des contextes JavaScript distincts. Partager cookies et URL ne reproduit pas tous les états temporaires d’un formulaire. Cette limite préexistante reste à prendre en compte.
- Import MCP par relais : 12 Mo par fichier, 32 Mo par lot, 1 à 8 fichiers ; import local : 512 Mo par fichier. Envoi d’un lot de fichiers locaux vers un site : 512 Mo total, soumis aux limites du site.
- Documents créés : UTF-8 (TXT, Markdown, CSV, JSON, HTML, code). Les PDF/DOCX/ZIP/images/vidéos peuvent être importés, stockés et partagés ; aucune fausse conversion en PDF ou DOCX.
- Notes : titre de 120 caractères et texte de 12 000 caractères. Pagination des fichiers et notes par 50 éléments ; recherche des fichiers dans le dossier courant. Lecture binaire par morceaux de 8 Ko.
- Les cases des notes sont une notation textuelle `[ ]` / `[x]`, sans moteur de tâches ni rappel automatique.
- L’arrêt complet du téléphone et l’arrêt forcé Android empêchent le MCP de fonctionner. Le service et les mécanismes de reconnexion existants sont conservés.
- L’APK et le relais doivent être actualisés. Une ancienne APK reçoit un message de mise à jour pour les nouvelles commandes. ChatGPT peut devoir actualiser sa liste d’outils du connecteur.
- La vérification automatique se fait lors de l’ouverture de l’application ; aucun réveil périodique supplémentaire n’est ajouté.
- Les préférences de consentement du propriétaire restent appliquées. Le partage d’aperçus d’images respecte le réglage existant.

## Vérifications prévues et reproductibles

`bash validation/check.sh` : scripts web, clic vérifié, reconnexion et confinement des chemins du nouvel espace.

`./gradlew assembleDebug assembleDebugAndroidTest lintDebug` : compilation réelle APK/SDK Android 34, compatibilité API 21 et analyse Android.

`./gradlew connectedDebugAndroidTest` : cycle de vie fichier et provider, refus d’écrasement, corbeille/restauration, notes/révisions, import binaire, autosauvegarde et changement d’espace sans destruction du navigateur.

Relais : `python -m pytest -q backend/tests` ; tests d’isolation, compatibilité des anciennes APK, schéma des outils, notes Unicode longues et nettoyage ciblé des transferts.

Le workflow GitHub conserve la signature persistante existante, compile debug/release, vérifie la signature, publie l’APK en Release et ajoute les tests sur émulateur Android avec KVM.

## Références de conception

- Cibles tactiles Android : https://developer.android.com/guide/topics/ui/accessibility/views/apps-views
- Sélecteur et export de fichiers Android : https://developer.android.com/training/data-storage/shared/documents-files
- Installation par-dessus une application existante : https://developer.android.com/studio/publish/app-signing
