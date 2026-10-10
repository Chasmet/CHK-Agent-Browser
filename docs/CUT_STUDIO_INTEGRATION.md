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
8. Le connecteur ne pilote **pas encore la découpe native** via une commande spécifique : l'utilisateur peut découper depuis l'onglet ou l'agent peut exploiter l'éditeur vidéo MCP existant si approprié. Une **programmation locale n'équivaut jamais à une programmation distante sur YouTube ou TikTok**, et les outils existants n'accordent pas d'accès OAuth. Pour publier réellement : naviguer, transférer le fichier, régler les champs, confirmer sur le site et recueillir une preuve vérifiable. Sur X, préparer puis publier au moment voulu (pas de programmation automatisée intégrée).


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
