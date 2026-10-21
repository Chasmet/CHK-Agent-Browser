# Local 2 : protocole Android v4, relais Windows 1.4.0

Le canal reste ChatGPT → MCP Windows existant → APK Android. Aucun service Render/Supabase n’est appelé par ces opérations. La signature et le mécanisme de mise à jour de l’APK restent ceux de l’application installée.

## Avec Windows 1.3.0 déjà installé

Toutes les nouvelles actions Android passent par l’outil existant `android_command` :

```json
{"action":"android_action","args":{"action":"files_capabilities","args":{"offset":0}}}
```

Suivre `next_offset` jusqu’à -1. Le catalogue fournit des schémas d’arguments pour les fichiers, documents, transferts, dossiers externes, tâches et studio. `workspace_status` expose la version, le bilan des contrôles locaux, les limites et les états de navigateur/canaux.

`success:true` indique que l’action a répondu. Si `message` contient `result_in_file:true`, lire le résultat intégral par `files_transfer_read`, avec le chemin, la taille et `updated` retournés. Aucun JSON n’est coupé silencieusement. `pending` exige de consulter `android_result` avec l’identifiant de commande, sans envoyer l’action une deuxième fois.

## Avec Windows 1.4.0

Les 13 outils restent disponibles. Sept outils spécialisés s’ajoutent : `android_capabilities`, `android_files`, `android_document_create`, `android_media`, `android_transfer`, `android_tasks`, `android_transfer_file`. Les outils historiques `files_*` de premier niveau concernent Windows ; `android_files` concerne le téléphone.

### Copier un gros média sans le faire transiter dans les messages ChatGPT

```json
{"operation":"start","id":"rush-20261021-01","direction":"to_android","source":"Rushs/plan.mp4","destination":"Skano Premier/plan.mp4"}
```

Appeler ensuite le même outil `android_transfer_file` avec `{"operation":"status","id":"rush-20261021-01"}`. Le dossier Android doit exister. `direction:to_pc` effectue l’autre sens ; la destination Windows reste dans l’espace autorisé. Le travail s’effectue en arrière-plan, au maximum deux transferts simultanés. Seuls `state:completed` et `verified:true` confirment la copie. Le résultat contient SHA-256 et taille.

Après une coupure : `operation:resume` avec le même identifiant. Le relais relit le point de reprise du téléphone avant d’écrire un autre bloc. Un fichier source modifié, un bloc différent rejoué ou une destination déjà occupée provoquent une pause explicite. `cancel` arrête entre les blocs ; une commande déjà reçue peut terminer son bloc. Aucune action incertaine n’est répétée aveuglément.

Le transport binaire bas niveau est disponible via `android_transfer` ou les actions `files_transfer_*`. Import : begin → write → status → commit. `begin` exige id, path, size et sha256. Une écriture contient au plus 49 152 octets Base64. Export : `files_hash`, puis `files_transfer_read` avec path, offset, size et updated ; contrôler SHA-256 du fichier assemblé.

### Documents

```json
{"path":"Skano Premier/rapport.docx","format":"docx","title":"Rapport","text":"Premier paragraphe.\nDeuxième paragraphe."}
```

PDF et DOCX acceptent titre et texte ; XLSX/CSV un tableau `rows` ; PPTX un tableau `slides` d’objets `{title,text}` ; JSON un objet/tableau `data`. Le document est créé dans un fichier temporaire, contrôlé puis publié sans écraser un fichier existant. Conserver une nouvelle version pour les révisions.

Limites : contenu d’entrée de 64 Ko, texte et valeurs simples, une feuille XLSX, jusqu’à 50 diapositives ou 100 pages PDF. Pas de calcul de formules, ni de conservation des mises en page d’un Office arbitraire existant. La lecture native PDF/DOCX/images demeure disponible ; un lecteur Office externe peut ouvrir les XLSX/PPTX exportés.

### Tâches et montage

Les tâches gardent un journal local de 1 à 50 étapes, leurs résultats et des vérifications `expect`/`contains`. `files_task` et `android_tasks` acceptent submit/status/resume/cancel. Une étape d’effet déjà confirmée ne se répète pas lors de la vérification. Les états incertains exigent une inspection.

Le projet vidéo existant accepte les clips `path,start_ms,duration_ms`, filtres, vitesse, texte incrusté et pistes audio. Utiliser `video_editor_project_read`, sauvegarder avec `expected_revision`, lancer `video_editor_export`, attendre `video_editor_wait` ou lire `video_editor_status`, puis contrôler `video_editor_verify_output.valid`. L’attente est bornée à 120 s par commande et peut être renouvelée sans redémarrer le rendu. Un succès de démarrage n’est pas une confirmation du MP4 final. Les codecs et ressources du téléphone restent déterminants.

## Dossiers autorisés et contrôles locaux

`files_external` / `android_files(operation:external)` utilisent uniquement les accès SAF persistants accordés dans Android. `external_request_access` ouvre le sélecteur et indique `owner_action_required:true` ; il n’accorde aucun accès lui-même. Les exports existants sont refusés et la destination nouvellement écrite est relue pour comparer SHA-256. Aucun accès implicite aux données d’autres applications.

À la première ouverture d’une nouvelle version APK, des fichiers synthétiques testent lecture, écriture, SHA-256, archives et paquets documentaires. `files_self_test` relance ce diagnostic ; le bilan est visible dans Diagnostic et `workspace_status.health`. Les tests ne certifient pas les pages de sites tiers, les permissions SAF non accordées ou un export de média personnel. Le journal technique n’enregistre ni contenu des documents ni URL privée.

## Livraison

Le pack Windows 1.4.0 joint à la Release remplace le binaire existant via l’interface d’installation habituelle. Il conserve configuration, espace Windows, jeton et adresse MCP ; aucun nouveau serveur n’est à recréer. Les nouveaux outils apparaissent après actualisation du catalogue de la connexion ChatGPT. L’APK seul apporte déjà les nouvelles actions utilisables avec Windows 1.3.0.
