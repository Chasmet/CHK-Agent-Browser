# Cut Vidéo intégré — CHK Agent Browser

**Objectif :** reprendre les fonctions essentielles de `Chasmet/Cut-vid-o-` sans modifier l'APK Cut Vidéo, son dépôt, ses données ou son identifiant Android.

## Périmètre intégré
- Nouvelle entrée **Cut** dans la barre de navigation du navigateur Android.
- Sélection d'une vidéo par sélecteur système ou dans **Fichiers CHK**.
- Découpe locale Media3 Transformer par 15 / 30 / 60 / 90 s, ou 1–600 s personnalisés, bornes début/fin, jusqu'à 100 morceaux par lot.
- Export séquentiel MP4 sans transfert cloud, via fichiers temporaires renommés seulement après achèvement.
- Bibliothèque `Fichiers CHK/CutVideo/lot_.../cut_XX.mp4`, lecture et partage du fichier.
- Plusieurs fiches par vidéo, avec compte, réseau, date/heure, visibilité, titre, description et 5 hashtags maximum.
- En cohérence avec le connecteur Cut Vidéo : bloc de métadonnées limité à 100 caractères.
- Rappels Android restaurés au redémarrage et après mise à jour; états « Programmé », « À publier » et « Publié (confirmé manuellement) ».
- Ouverture dans les onglets CHK connectés : YouTube Studio, TikTok Upload, Instagram et X.
- Métadonnées copiées dans le presse-papiers; partage MP4 via Android disponible.
- `CutVideo/publications.json` contient une copie lisible de la file de publication pour le contrôle via les outils MCP Fichiers.

## Partage depuis la bibliothèque Cut (mode CHK Browser)
Sur une vidéo de **Cut > Mes vidéos**, le bouton **Partager + métadonnées** affiche le réseau et sa fiche. Trois parcours sont disponibles :

- **Ouvrir dans CHK** : ouvre l'interface web du réseau dans un onglet de CHK Agent Browser sans quitter l'application. Les champs `video_path`, `platform`, `url`, `title`, `description`, `hashtags` et `state=ready_for_browser_upload` sont enregistrés dans `CutVideo/share_context.json`, uniquement pour poursuivre le transfert via le MCP du navigateur.
- **Partager Android** : conserve le sélecteur natif et les applications TikTok/Instagram/YouTube. Android transfère alors l'activité vers l'application choisie, qui n'est **pas** pilotable par les outils limités à CHK.
- **Copier seulement** : conserve la fiche complète dans le presse-papiers sans ouvrir d'autre écran.

Une application Android tierce ne peut pas être intégrée arbitrairement dans CHK : l'activité cible doit autoriser l'intégration inter-applications. Pour agir dans une application native après le partage, il faudrait développer un mode séparé d'automatisation d'accessibilité, explicitement activé par le propriétaire et soumis aux permissions et restrictions Android.

Le mode CHK ouvre le site ; **aucun téléversement ni publication n'est présumé réussi**. L'agent utilise les outils navigateur du MCP pour inspecter le formulaire, importer le MP4 depuis `video_path` et contrôler la preuve finale sur le réseau.

## Réseaux
| Plateforme | Adresse de création | Fonction |
|---|---|---|
| YouTube | https://studio.youtube.com/ | Programmation réelle dans le formulaire YouTube Studio après transfert |
| TikTok | https://www.tiktok.com/upload | Import et paramètres selon l'interface proposée au compte |
| Instagram | https://www.instagram.com/ | Flux de création selon l'interface Web du compte |
| X | https://x.com/compose/post | Création manuelle d'un post; ce module n'envoie pas automatiquement |

**Attention :** l'heure saisie dans Cut Studio est un **rappel local**, et non une confirmation de programmation sur le serveur d'un réseau. Le bouton « Publié » est une déclaration humaine, pas une preuve API. Aucun cookie n'est exporté et aucun identifiant de compte n'est stocké dans le module Cut. Les restrictions du site (MFA, CAPTCHAs, limites d'API, refus d'upload WebView) peuvent nécessiter une intervention humaine. Les droits et règles des réseaux restent applicables.

## ChatGPT / MCP
1. Les vidéos locales exportées sont accessibles avec `browser_files_list` sur `CutVideo`.
2. Le programme est consultable avec `browser_files_read_text` sur `CutVideo/publications.json`.
3. Pour charger le MP4 sur un réseau, ouvrir sa page avec `browser_open_url`, consulter le formulaire réel avec `browser_read_page`, puis utiliser `browser_upload_workspace_file` avec **le chemin exact** du morceau et le **domaine attendu**.
4. Compléter le formulaire avec les métadonnées de la fiche via les outils de navigation. Vérifier le résultat réel de la plateforme avant d'annoncer une publication. Ne jamais cocher « Publié » par simple supposition.
5. **Programmer directement depuis ChatGPT via le MCP existant :** appeler `browser_files_write_text` avec `path="CutVideo/schedule_inbox.json"`, `overwrite=true` et un `text` JSON décrivant entre 1 et 25 publications. Dans ce cas précis, l'écriture est interprétée comme une **commande native transactionnelle** de planification, et non comme la création d'un fichier brouillon. Le retour donne `imported`, `ids`, `state=scheduled_local_reminder` et `platform_publication_confirmed=false`. Les programmations apparaissent immédiatement dans l'onglet Cut et dans `CutVideo/publications.json`. Aucun nouvel endpoint Render n'est ajouté.
6. Exemple de contenu `text` (horodatage futur **en millisecondes Unix**) :

```json
{
  "entries": [{
    "request_id": "clip-001-youtube",
    "path": "CutVideo/lot_123/cut_01.mp4",
    "platform": "youtube",
    "account": "chknoirshadow",
    "title": "Mon clip",
    "description": "Nouvel extrait",
    "hashtags": "#shorts",
    "visibility": "public",
    "at": 1893456000000
  }]
}
```

7. Avant chaque envoi : lister les véritables MP4 dans `CutVideo`, contrôler leurs métadonnées, proposer des heures adaptées au fuseau horaire voulu. Le même `request_id` rend une relance **idempotente** (mise à jour sans doublon). L'importation valide **l'intégralité du lot** avant enregistrement : chemin MP4 local existant, compte/réseau autorisé, date future, métadonnées limitées à 100 caractères et 5 hashtags. Une entrée invalide annule tout le lot. `published=true` est refusé dans les imports automatiques.
8. **Découpage automatique réel à la demande, uniquement dans CHK Agent Browser :** après avoir importé un MP4 avec `browser_files_import` ou `browser_files_import_download`, choisir le `source_path` exact retourné par `browser_files_list`. Appeler `browser_files_write_text` avec `path="CutVideo/cut_inbox.json"`, `overwrite=true` et un `text` JSON unique :

```json
{
  "request_id": "clip-alpha-30s-001",
  "source_path": "Videos/alpha.mp4",
  "seconds": 30,
  "start_ms": 0,
  "end_ms": null
}
```

9. La réponse immédiate est `state=running`, avec `output_folder`, `completed` et `total`. **Elle n'atteste pas que le MP4 est terminé.** Lire `CutVideo/cut_status.json` avec `browser_files_read_text` jusqu'à `state=completed`; vérifier ensuite les MP4 réels avec `browser_files_list` sur `output_folder`. En cas de `failed`, rapporter l'erreur et garder les morceaux exportés. Le fichier source n'est jamais supprimé. Limites : 1–600 s par morceau, au maximum 100 morceaux, un export simultané.
10. Pour annuler le découpage demandé : `browser_files_write_text` avec `path="CutVideo/cut_cancel.json"` et `text='{"request_id":"clip-alpha-30s-001"}'`. Seuls les morceaux déjà terminés sont conservés.
11. **Automatisation de publication sur demande** : après les exports réussis, générer des métadonnées adaptées *au contenu de chaque morceau vérifié*, importer les fiches dans `CutVideo/schedule_inbox.json` puis accéder aux sites dans des onglets CHK. Une planification locale **n'équivaut jamais** à une publication distante validée, et le serveur ne possède pas d'accès OAuth implicite. Ne prétendre à une publication effective qu'après confirmation vérifiable du site. X : aucun programmateur natif.
12. **Aucun changement dans `Chasmet/Cut-vid-o-`.** Tout est réalisé par l'application CHK Agent Browser et son MCP de fichiers existant, sans nouvelle route Render. La commande est lancée uniquement sur instruction explicite de l'utilisateur.


## Conservation et architecture
- Cut Vidéo d'origine : **inchangé**.
- CHK Agent Browser : mêmes `applicationId`, signature CI existante, espace `workspace`, données déjà présentes, relay Render et autres routes **inchangés**.
- Planification locale dans `SharedPreferences` (`cut_studio_planning_v1`) et copie JSON dans Fichiers CHK.
- Les MP4 créés sont conservés même après suppression des rappels; les fichiers sources ne sont jamais écrasés.
- La suppression ou la désinstallation de l'application efface ses données privées, contrairement à une mise à jour signée avec la même clé.
- Quitter la fenêtre pendant un export le stoppe proprement. Les morceaux déjà terminés restent disponibles.
- Android peut décaler les rappels selon les restrictions batterie et de notifications.

## Validation
Le workflow GitHub `.github/workflows/android.yml` exécute `bash validation/check.sh`, `./gradlew assembleDebug lintDebug` et les tests Android sur émulateur. Le test `CutStudioIntegrationTest` contrôle le stockage de la file, les restrictions de compte et le nouvel onglet. La Release signée n'est publiée que par le workflow existant sur `main`, après succès des tests.
