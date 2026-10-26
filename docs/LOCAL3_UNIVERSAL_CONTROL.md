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
