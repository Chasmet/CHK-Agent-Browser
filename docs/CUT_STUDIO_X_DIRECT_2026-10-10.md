# Cut Studio intégré — X sans programmation

## Périmètre
Le module Cut Studio est intégré à CHK Agent Browser. L'application autonome `Chasmet/Cut-vid-o-` et le relais Render ne sont pas modifiés. Stockage des MP4 sous `Fichiers CHK/CutVideo/`, plan local sous `CutVideo/publications.json`.

## Préparation des publications
- **YouTube, TikTok, Instagram** : préparer les métadonnées, choisir une date/heure **future**, sauvegarder un rappel Android local ; ouvrir le service et **vérifier sa vraie programmation** avant de marquer « publié ».
- **X** : préparer un brouillon sans date ni heure, ouvrir `https://x.com/compose/post`, coller les métadonnées, transférer le MP4 depuis CHK Fichiers et publier immédiatement. Aucune alarme X et aucune programmation X simulée.
- **Comptes** : CHKNOIRSHADOW pour les quatre réseaux ; QG pour YouTube et TikTok seulement.
- **Validation** : le bouton « Valider : publié » exige une confirmation de l'utilisateur. Un import MCP ne peut jamais valider une publication à distance.

## Automatisation MCP déjà disponible
1. `browser_files_list` : inventorier les MP4 sous `CutVideo`.
2. `browser_files_write_text` sur `CutVideo/schedule_inbox.json` : créer jusqu'à 25 fiches en une transaction, sans doublons grâce à `request_id`. `at` est un horodatage futur en millisecondes pour YouTube, TikTok et Instagram ; pour X, le champ peut être absent, il est enregistré à `0`.
3. `browser_files_read_text` sur `CutVideo/publications.json` : consulter les fiches réellement sauvegardées.
4. `browser_open_url` et `browser_read_page` : ouvrir la page de la plateforme dans la session connectée, vérifier son interface actuelle.
5. `browser_upload_workspace_file` avec `expected_host` et le chemin exact du MP4 : transférer un fichier local vers le formulaire HTML autorisé. Les formulaires peuvent varier ; un transfert réussi ne prouve pas une publication.
6. Utiliser les contrôles de saisie et la vérification de la plateforme ; ne jamais annoncer « publié » ni « programmé sur le réseau » sans preuve visible.

## Limites
La programmation du module Cut Studio est un **rappel local**, pas une programmation serveur garantie sur YouTube, TikTok ou Instagram. Une vraie mise en ligne ou programmation dépend de l'authentification du compte et des formulaires ou API disponibles. Le module ne contourne pas les protections des réseaux. Il n'accède pas aux fichiers privés de l'application Cut Vidéo autonome sans importation autorisée.

## Vérification
- Test Android `CutStudioIntegrationTest` : X sans heure, commande MCP X sans `at`, import idempotent, validation de compte QG, rejet de dates manquantes pour YouTube.
- Vérification GitHub Actions sur branche avant fusion.
- Installation via Release signée en conservant le même package et la même clé.
