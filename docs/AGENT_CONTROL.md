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

Après l’entrée, la vision et le clavier sont relus. `after` contient la nouvelle observation. `action_sent` vaut vrai si l’entrée est acquittée, faux lors d’un refus préalable, et null si son effet reste incertain (`effect_uncertain=true`). `outcome_confirmed` devient vrai uniquement si les conditions explicites `expect.field_value`, `expect.text_contains` ou `expect.url_contains` deviennent satisfaites après l’action. Si elles étaient déjà vraies, aucun geste n’est envoyé (`already_satisfied`). Un geste envoyé sans condition métier reste non confirmé. Les valeurs des champs longues sont bornées dans les observations.

Conserver le même `id` pour relire une action : son reçu durable est renvoyé avec `replayed=true`, sans répétition de l’entrée. Réutiliser cet id avec une autre demande est refusé. Une interruption entre l’effet et son reçu produit `state=unknown` : observer l’écran et décider de l’étape suivante, sans renvoyer aveuglément.

La souris apparaît sous forme de repère turquoise dans CHK ; le clavier a son indicateur. Ces repères utilisent ViewOverlay et n’interceptent pas les gestes du propriétaire.

## Plusieurs tâches

`control/parallel` reçoit `jobs`, de 1 à 4 workflows par demande. Chaque job contient `id` et `steps`, au format durable existant. Tous les schémas sont validés avant le démarrage ; chaque job retourne ensuite son état individuel. Une réponse de soumission ne prouve pas la fin : utiliser `workflow_status`, `workflow_resume` ou `workflow_cancel`.

Dans un workflow, une étape `desktop/control/observe` peut fournir `$vue.observation_id` et les champs observés à l’étape suivante. Une étape `desktop/control/act` exige une lecture `verify` explicite comme les autres écritures navigateur. Les workflows imbriqués sont refusés.

Les entrées d’onglets distincts peuvent se chevaucher. La souris et le clavier partagent un verrou par onglet ; une entrée native exclut les autres gestes pour éviter les conflits sur la fenêtre. Les téléchargements Android, les calculs/exportations Studio et les étapes asynchrones progressent indépendamment ; les écritures de fichiers et les mutations d’un même projet conservent leur sérialisation et leurs révisions. Cela ne crée pas plusieurs fils d’interface Android ni plusieurs exports simultanés du même moteur.

## Validation

Les tests instrumentés couvrent la saisie web et native avec relecture, un clic natif suivi de vision, la relecture sans double clic, un journal interrompu, une observation périmée, une collision sur la même cible, une saisie d’un autre onglet pendant un geste long et deux workflows durables indépendants. La suite complète et la signature sont vérifiées par le workflow APK existant avant publication.

## Écouter les fichiers et les voix

Le même `task_submit(action=desktop)` accepte `args.tool=hearing`. `local3_status.agent_hearing` et `hearing/status` indiquent les capacités et les modèles de transcription réellement installés.

```json
{"tool":"hearing","operation":"listen","path":"Audio/Voix.wav","start_ms":12000,"max_seconds":5}
```

`listen` décode un vrai passage et fournit `audio_base64`, `mime_type=audio/wav` et son SHA-256. Le client agent doit transmettre ces octets comme bloc MCP AudioContent (`type=audio`, `mimeType=audio/wav`) pour écouter le signal ; ne pas imprimer la chaîne Base64 comme texte ni affirmer avoir écouté une simple transcription. Le client d’orchestration peut extraire la réponse de `task_get` et utiliser son rendu audio. Si ce client ne sait pas recevoir de son, signaler cette limite, même si l’extrait a été produit.

Les extraits sont PCM16 mono 16 kHz, bornés à 5 secondes pour le transport MCP ; choisir `start_ms` pour écouter d’autres passages. Seul le passage choisi est transmis, sous l’autorisation existante de partage d’aperçus. Le fichier source n’est pas modifié. Les sources Android jusqu’à 6 Go restent lues progressivement ; tous les formats et codecs propriétaires ne sont pas garantis.

`hearing/analyze` accepte le même chemin, `start_ms`, `max_seconds` de 1 à 120 et `silence_dbfs` facultatif. Il mesure les niveaux RMS/crête en dBFS par canal, une enveloppe de 100 ms, les plages silencieuses et les échantillons proches du maximum numérique. Ce dernier indicateur suggère une saturation possible ; ce n’est pas une mesure LUFS ni une preuve de distorsion audible. Les mesures proviennent des échantillons décodés.

`hearing/transcribe` accepte `language=fr|en`, `start_ms` et `max_seconds`. Le moteur Vosk s’exécute localement, après installation du modèle linguistique HTTPS au premier usage. La parole chantée ou bruitée peut être mal reconnue ; aucune identité du locuteur ou synchronisation phonétique n’est déduite. Le décodage direct WAV évite de demander un décodeur Android audio/raw inexistant.

`hearing/media`, avec `tab_id` et `expected_host` facultatifs, lit les sources des éléments audio/vidéo du véritable onglet CHK. Cette découverte n’est pas une capture sonore. Pour une source HTTP/HTTPS accessible, utiliser le téléchargement durable vers CHK, puis écouter le fichier. Les flux blob, les contenus DRM et le son d’autres applications ne sont pas extraits automatiquement. Aucun microphone n’est enregistré.

Les actions natives équivalentes `media_listen`, `media_analyze_audio`, `media_transcribe` et `media_audio_capabilities` passent également par android_action. Les analyses/transcriptions peuvent faire partie des workflows ; les extraits audio volumineux s’écoutent séparément.


## Recovery and private captures

`already_satisfied` is a no-op, never proof that input occurred. Inspect `action_sent` and `outcome_confirmed`; `ok` alone is insufficient. Verified clicks have a 0.8–25 s deadline per phase, including missing JavaScript callbacks; waiting for a disabled button and verifying its effect have separate budgets (50 s total maximum). A late reply is discarded and no click is retried. Observe again after an uncertain result.

Local 3 keeps up to four leased tasks, each with a persistent receipt. Independent diagnostics and explicit tab targets can progress concurrently; shared/default targets are serialized. Server 1.0.4 may still choose to return a single outstanding lease; the APK cannot override that server policy. Generic command deadlines are 45 s (verified clicks 60 s), audio analysis 180 s, transcription/large file operations 900 s, bounded batches 150 s.

For hidden WebViews, CHK temporarily renders the existing tab on an OWN_CONTENT_ONLY private VirtualDisplay, never AUTO_MIRROR or PUBLIC. No external app capture, screen recording permission or new pairing is used. PixelCopy reads the CHK Presentation window after WebView visual readiness, and DOM revision is compared again. Original parent, index, dimensions, visibility and preraster preference are restored. `private_webview_pixels` means a current CHK private rendering, not the physical phone display. Protected media may be blank; changing pages or unsupported OEM renderers return explicit unavailable/cache results within six seconds.
