## Capacité et commandes Local 3 — 27 octobre 2026

Les fichiers/imports/reprises autorisent 6 000 000 000 octets, avec tailles et positions 64 bits. Ce plafond ne s’applique pas aux allocations RAM : le sélecteur natif utilise content://, les copies disque sont parcourues par blocs et les petits mécanismes JS/Blob gardent leur propre plafond. Deux transferts complets peuvent réserver jusqu’à 12 Go au total, sous contrôle de l’espace libre. Les lecteurs PDF/images restent bornés séparément.

Le serveur PC 1.0.4 reste compatible : catalogue de 83 noms, y compris les alias Studio désormais traduits par Android. HTTP et HTTPS sont autorisés pour les pages publiques ; le jumelage MCP et la source des APK restent HTTPS.

`browser_tabs` fournit un `id` stable pour chaque WebView pendant sa session. Utiliser cet id pour switch/close : l’index d’un autre onglet peut changer après une fermeture. La sélection indique selected=true même si une ancienne erreur réseau est présente dans network_warning ; ce résultat n’est pas une preuve de nouvelle navigation.

Téléchargement durable, via `task_submit` / action `android_action` :

```json
{"action":"desktop","args":{"tool":"browser","operation":"download_file","id":"cut-03","expected_host":"grok.com","url":"URL_HTTP_OU_HTTPS_DU_MEDIA","workspace_path":"Clips/Cut_03.mp4","mime_type":"video/mp4"}}
```

Le dossier de destination doit exister. `id` doit rester identique lors d’une reprise. Pour lire l’état, desktop/browser/operation=download_status avec ce même id. `downloading` signifie en cours ; attendre `state=completed` et `verified=true`. Un état unknown signifie qu’Android a été interrompu entre l’envoi natif et l’enregistrement de son identifiant : inspecter Téléchargements, ne pas renvoyer aveuglément.

Dans un workflow durable :

```json
{"id":"download-cut-03","action":"download_file","args":{"id":"cut-03","expected_host":"grok.com","url":"URL_DU_MEDIA","workspace_path":"Clips/Cut_03.mp4","mime_type":"video/mp4"},"verify":{"action":"desktop","args":{"tool":"browser","operation":"download_status","id":"cut-03"},"expect":{"state":"completed","verified":true}}}
```

Le runner observe la fin sans réexécuter l’envoi ; cette attente est bornée à deux heures et reste annulable. La taille, le fichier réel et son SHA-256 sont contrôlés avant l’import sans écrasement. Les informations finales incluent path, size, sha256 et download_id. Les limites du site distant continuent de s’appliquer.

Menu navigateur : Compatibilité du site (version ordinateur et cookies tiers par hôte), puis ouverture dans le navigateur externe. Caméra, microphone et localisation restent soumis aux autorisations du propriétaire ; aucun accès n’est accordé en arrière-plan.

L’opération desktop/browser/read_pdf permet la lecture d’une URL de document courante sans dépendre de son suffixe ; les redirections sont limitées et les cookies ne suivent pas un changement d’origine. Les images cachées de WebView ne sont jamais présentées comme des pixels actuels vérifiés.

---

# CHK Browser Local 3 — contrôle Android depuis le serveur personnel

## Architecture sans Render/Supabase

CHK Android se connecte directement à l’adresse HTTPS réelle du serveur CHK Universal Control du propriétaire, renseignée au moment de l'association avec son propre identifiant et une clé d'application privée. Ce canal utilise exclusivement les routes existantes du serveur universel :

- `POST /api/onboard` : association à usage unique.
- `GET /api/agent/{id}/poll` : récupération d'une tâche autorisée par le serveur.
- `POST /api/agent/{id}/result` : accusé et résultat.

Le connecteur ChatGPT est `CHK Browser Local 3`, déjà installé séparément. Les paramètres et l'état ne modifient ni `browser_mcp_v1` (Render), ni `chk_local2_v1` (Local 2). Le serveur doit être en ligne et joignable en HTTPS ; le PC et la connexion Internet consomment toujours des ressources physiques, mais ces échanges ne facturent aucune requête Render ou Supabase.

## Activation la première fois

1. Installer une Release GitHub signée par la clé habituelle depuis le bouton **Mise à jour** de CHK.
2. Ouvrir **CHK > Réglages > MCP Local 3**.
3. Dans ChatGPT, appeler l'outil `app_invite` du connecteur `CHK Browser Local 3`. Une invitation expire après 15 minutes.
4. Dans CHK, appuyer sur **Associer Local 3 avec une invitation**, renseigner l’URL HTTPS du serveur (ou celle de son endpoint `/mcp`), coller le code `chkinvite_...`, confirmer. L’URL ne doit pas contenir de jeton dans son chemin, ses paramètres ou son fragment.
5. Activer **CHK Browser Local 3** et le **Mode autonome** pour autoriser les commandes natives dans CHK.
6. Dans ChatGPT, utiliser `apps_list` (réponse structurée `{apps:[...]}`) pour retrouver l'identifiant de CHK Android, puis `task_submit` avec cet `app_id`. Les écritures passent obligatoirement par l'approbation du serveur ; `task_get` confirme le résultat effectif.

Le secret d'application n'est jamais inclus dans le code source, les logs, les rapports de diagnostic ou la sauvegarde Android. L'association survit aux mises à jour de l'APK portant la même signature. En cas de changement de téléphone ou de réinstallation, générer une nouvelle invitation.

## Fonctions et limites

Le catalogue d'appairage déclare les fonctions de lecture (`browser_status`, `browser_read_page`, fichiers, PDF, métadonnées MP3/MP4, aperçu, notes et états du montage), et les fonctions d'écriture (navigation, clic, frappe, onglets, déplacer/écrire des fichiers, notes, édition vidéo, opérations clavier, souris et tâches). La fonction `android_action` reçoit `{action: "...", args: {...}}` et délègue aux outils CHK natifs existants.

Les dossiers accessibles sont les dossiers privés CHK et les dossiers externes explicitement autorisés dans les réglages. Aucune permission root, accès universel au stockage, service d'accessibilité ou contrôle du système Android n'est ajouté. La souris et le clavier virtuels agissent dans CHK et ses WebViews, et non dans toutes les applications tierces.

Les vidéos MP4 restent sur le téléphone. Pour les fichiers volumineux, exploiter les commandes de lecture par blocs, le catalogue et les fonctions d'import/export natives ; ne pas transférer les vidéos entières dans la réponse MCP. Les résultats dépassant le format transportable sont sauvegardés dans `AgentResults/` et référencés par chemin et SHA-256.

Les tâches sensibles exigent une approbation dans le serveur universel. L'application ne contourne pas les contrôles des sites Web, des identifications ou les autorisations Android. Une réponse incertaine n'entraîne jamais la répétition automatique d'une écriture.

## Validation

- `./gradlew assembleDebug` et `connectedDebugAndroidTest` par GitHub Actions.
- Vérifier indépendance des trois interrupteurs, absence de jeton dans les sauvegardes et appairage refusé avec un code invalide.
- Vérifier une vraie association une fois l'APK installé et le PC disponible, puis `apps_list`, `browser_status`, `files_list`, l'exécution d'une tâche approuvée et la lecture de son résultat.
- Le pipeline Release `main` conserve signature habituelle, numéro de version automatique et mise à jour dans CHK ; aucun APK n'est renommé artificiellement.
