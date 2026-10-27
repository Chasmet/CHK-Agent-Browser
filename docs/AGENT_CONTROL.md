# Contrôle CHK : vision, souris, clavier et tâches concurrentes

Le transport reste le connecteur Local 3 existant. Appeler `task_submit` avec `action=desktop` et `args.tool=control`, puis `task_get` pour obtenir la réponse Android. Le serveur PC 1.0.4 et son catalogue de 83 noms restent compatibles. `local3_status.agent_control` décrit ces capacités.

Le contrôle concerne les écrans et les onglets de CHK, pas les autres applications Android. Les autorisations persistantes, le mode autonome et le partage d’aperçus restent applicables. La révocation arrête les nouvelles entrées ; aucun effet incertain n’est rejoué automatiquement.

## Observer

```json
{"tool":"control","operation":"status"}
```

La réponse liste les onglets et leur `id`, l’onglet affiché et les entrées actuellement réservées. Les identifiants appartiennent à la session WebView et restent valides lors du passage de CHK à son moteur conservé.

```json
{"tool":"control","operation":"observe","target":"web","tab_id":"ID_RETOURNE_PAR_STATUS","capture":true}
```

Pour un écran natif : `target=native`. L’observation contient les contrôles visuels, leurs coordonnées, un `observation_id` valable 45 secondes et `keyboard.controls` avec les sélecteurs des champs. La capture est facultative et exige le partage d’aperçus. `capture_current`, `visual_verified`, `capture_source` et `capture_age_ms` distinguent pixels actuels, pixels conservés et DOM sans image. Une WebView cachée ne reçoit pas de faux pixels actuels.

## Agir et relire automatiquement

```json
{"tool":"control","operation":"act","id":"saisie-projet-001","observation_id":"ID_DE_L_OBSERVATION","input":{"tool":"keyboard","operation":"replace","selector":"SELECTEUR_RETOURNE_DANS_KEYBOARD_CONTROLS","text":"Mon projet"},"expect":{"field_value":"Mon projet"},"capture_after":true}
```

La souris utilise `input.tool=pointer`, `gesture=tap|double_tap|long_press|drag|swipe|back`, `x/y` et, pour un déplacement, `end_x/end_y/duration_ms`. Les coordonnées et le contexte viennent de l’observation. Les champs natifs et web utilisent le clavier existant : sélection, saisie, remplacement, touches et annulation lorsque disponibles.

Avant l’entrée, l’interface est observée de nouveau et sa révision doit correspondre. Un onglet fermé ou une interface modifiée provoque un refus sans basculer vers une autre cible. L’onglet ciblé ne devient pas automatiquement l’onglet affiché.

Après l’entrée, la vision et le clavier sont relus. `after` contient la nouvelle observation. `action_sent` décrit l’envoi ; `outcome_confirmed` devient vrai uniquement si les conditions explicites `expect.field_value`, `expect.text_contains` ou `expect.url_contains` sont satisfaites. Un geste envoyé sans condition métier reste non confirmé. Les valeurs des champs longues sont bornées dans les observations.

Conserver le même `id` pour relire une action : son reçu durable est renvoyé avec `replayed=true`, sans répétition de l’entrée. Réutiliser cet id avec une autre demande est refusé. Une interruption entre l’effet et son reçu produit `state=unknown` : observer l’écran et décider de l’étape suivante, sans renvoyer aveuglément.

La souris apparaît sous forme de repère turquoise dans CHK ; le clavier a son indicateur. Ces repères utilisent ViewOverlay et n’interceptent pas les gestes du propriétaire.

## Plusieurs tâches

`control/parallel` reçoit `jobs`, de 1 à 4 workflows par demande. Chaque job contient `id` et `steps`, au format durable existant. Tous les schémas sont validés avant le démarrage ; chaque job retourne ensuite son état individuel. Une réponse de soumission ne prouve pas la fin : utiliser `workflow_status`, `workflow_resume` ou `workflow_cancel`.

Dans un workflow, une étape `desktop/control/observe` peut fournir `$vue.observation_id` et les champs observés à l’étape suivante. Une étape `desktop/control/act` exige une lecture `verify` explicite comme les autres écritures navigateur. Les workflows imbriqués sont refusés.

Les entrées d’onglets distincts peuvent se chevaucher. La souris et le clavier partagent un verrou par onglet ; une entrée native exclut les autres gestes pour éviter les conflits sur la fenêtre. Les téléchargements Android, les calculs/exportations Studio et les étapes asynchrones progressent indépendamment ; les écritures de fichiers et les mutations d’un même projet conservent leur sérialisation et leurs révisions. Cela ne crée pas plusieurs fils d’interface Android ni plusieurs exports simultanés du même moteur.

## Validation

Les tests instrumentés couvrent la saisie web et native avec relecture, un clic natif suivi de vision, la relecture sans double clic, un journal interrompu, une observation périmée, une collision sur la même cible, une saisie d’un autre onglet pendant un geste long et deux workflows durables indépendants. La suite complète et la signature sont vérifiées par le workflow APK existant avant publication.
