# Les cinq outils IA de CHK

Outils réservés à l’agent, avec le même accord MCP et le même clavier Google pour le propriétaire. Ils concernent les écrans de CHK et sa WebView. Aucun service d’accessibilité, accès root, injection dans les autres applications ou permission de stockage globale n’est ajouté.

## Souris tactile

`browser_desktop_inspect(target="native")` donne les fenêtres et dialogues CHK, textes, boutons, coordonnées, contexte et révision. `target="web"` observe le viewport WebView. `browser_desktop_screenshot` fournit les pixels et leurs dimensions sous le contrôle du partage d’aperçus existant.

`browser_pointer` envoie de vrais MotionEvent Android : `tap`, `double_tap`, `long_press`, `drag`, `swipe`, `back`. Les pages canvas ne nécessitent pas de sélecteur HTML. Chaque geste exige le contexte observé ; la révision facultative protège aussi contre un changement de contenu sans changement de fenêtre. Un rechargement WebView, une rotation ou un changement de dialogue invalide le contexte.

Coordonnées `view` : pixels du viewport décrit par `width/height`. Coordonnées `image` : pixels de la capture réduite `image_width/image_height`, convertis séparément sur chaque axe. `duration_ms=0` laisse le connecteur choisir la durée appropriée ; les durées explicites vont de 40 à 5 000 ms, avec un minimum adapté à l’appui long.

`gesture_sent` et `touch_accepted` confirment le transport tactile. Ils ne certifient pas le résultat métier, un envoi, une publication ou un paiement : observer et vérifier le nouvel écran. Les gestes arrêtés en cours envoient CANCEL et signalent le résultat incertain. Les fenêtres sécurisées refusent les captures. Les champs protégés sont exclus de l’arbre structuré.

## Vision

L’arbre comprend jusqu’à 160 éléments et signale sa limite. Les captures font au maximum 720 × 960 pixels et 140 Ko JPEG. `capture_source=window_pixels` désigne les pixels de la fenêtre Android ; `retained_view_render` désigne un rendu des vues conservées, notamment quand CHK est en pause. Ce second rendu ne prétend pas représenter l’écran physique d’une autre application ou une vidéo SurfaceView.

## Explorateur avancé

`browser_files_batch` prépare un plan de 1 à 500 chemins sélectionnés, ou les fichiers d’un `folder` filtrés par `suffix`. Modes : renommage séquentiel (`prefix`, `start`), classement MIME (`organize`), copie, déplacement, corbeille, transformation de texte. Toujours inspecter le plan, puis exécuter `operation=run` avec son identifiant.

Les destinations existantes, doublons, cycles de renommage et chemins hors de Fichiers CHK sont bloqués. Les versions de source/destination sont contrôlées entre plan et exécution. Chaque élément a son résultat durable ; les réussites ne sont pas rejouées. Les collisions et modifications de source n’empêchent pas les autres éléments. Un traitement interrompu reste `uncertain` et exige une inspection ; créer un nouveau plan uniquement pour les éléments à reprendre. Pages de 25 éléments avec `next_offset`.

`browser_external_files` donne accès uniquement aux dossiers réellement sélectionnés avec le sélecteur Android et une autorisation persistante. Opérations `external_roots`, `external_request_access`, `external_list`, `external_read_text`, `external_import`, `external_export`, `external_rename`, `external_revoke`. Les droits en lecture seule et les révocations sont respectés. Aucun chemin `/sdcard` ni URI arbitraire n’est accepté. Le sélecteur Android reste piloté par le propriétaire ; `owner_action_required` ne signifie pas accès obtenu. Gestion des accès depuis Réglages → Dossiers externes autorisés à l’agent.

## Tâches complexes

`browser_task_submit(id, steps)` démarre une tâche locale de 1 à 50 étapes. Chaque étape a un `id`, une `action`, des `args` et peut avoir `verify={action,args,expect/contains}`. Les vérifications lisent uniquement l’état ; les gestes exigent une vérification. Une chaîne exacte `$creation.id` ou `$observation.controls.0.selector` référence le résultat JSON d’une étape confirmée.

Le journal est enregistré avant l’action et avant sa vérification. Une vérification échouée suspend la tâche ; la reprise vérifie à nouveau sans rejouer l’action. Après une interruption pendant un effet, la tâche reste incertaine. Si une vérification est disponible, la reprise commence par elle. `retry_uncertain=true` demande explicitement une nouvelle exécution ; ne jamais l’utiliser aveuglément après un envoi. Un identifiant existant avec une autre définition est refusé.

`browser_task_control` lit, reprend ou annule la tâche ; résultats paginés par 10 étapes, extraits signalés comme tels. Le MCP et le mode autonome doivent rester actifs. Leur désactivation suspend les tâches ; aucun redémarrage silencieux après destruction du processus. Les étapes attendent au maximum 30 secondes une réponse ; une absence de réponse demeure incertaine. Aucun lot d’images n’est stocké dans les journaux.

## Terminal autorisé

`browser_terminal` exécute CHK Script v1, un langage JSON de traitements autorisés. Exemple :

```json
{
  "version": 1,
  "commands": [
    {"command": "rename", "folder": "Dossier", "suffix": ".jpg", "prefix": "photo-"},
    {"command": "transform", "paths": ["Dossier/donnees.json"], "transform": "format_json", "output_suffix": ".pretty.json"}
  ]
}
```

`operation=validate` fournit un aperçu sans modification ; `run` lance une tâche journalisée, puis `status/resume/cancel` la contrôle. Jusqu’à 50 commandes, chacune sur 500 fichiers. Traitements texte : JSON indenté, remplacement littéral, texte vers Markdown, lignes vers JSON. Les sources texte sont limitées à 64 Ko et 12 000 caractères ; les formats binaires PDF/DOCX ne sont pas convertis par ce moteur. Le script ne possède ni shell, Python, JavaScript, processus système, réseau, root ou accès aux données privées hors de Fichiers CHK.

## Transport compatible

Sans attendre le rafraîchissement du catalogue du connecteur, l’outil existant `browser_type` accepte `selector="chk://desktop"` et `text` contenant le JSON d’une requête :

```json
{"tool":"vision","target":"native"}
```

Les valeurs `tool` sont `vision`, `pointer`, `files`, `tasks`, `terminal`. Le relais négocie `desktop_version=1` et demande la mise à jour d’un ancien APK avant toute mise en file. La même URL MCP est conservée. Le transport ancien limite `text` à 8 000 caractères ; les nouveaux outils acceptent une requête jusqu’à 60 Ko. Les captures et résultats ne sont jamais tronqués en JSON corrompu.

## Vérification

Les nouveaux tests Android couvrent les vrais clics, double-clics, appuis longs, déplacements, capture JPEG, protection des champs, révisions périmées, canvas web et rechargements, renommage de 200 fichiers sans doublon, collisions et modifications de source, classement MIME, tâche de 50 actions vérifiées, reprise sans note dupliquée, script réel et refus des chemins externes sans autorisation. Les tests MCP couvrent schémas, négociation de version, isolation de l’appareil, transport des captures et résultats partiels. Les résultats de compilation, lint et émulateur seront enregistrés après exécution dans GitHub Actions.

Références : [MotionEvent Android](https://developer.android.com/reference/android/view/MotionEvent), [PixelCopy](https://developer.android.com/reference/android/view/PixelCopy), [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files).
