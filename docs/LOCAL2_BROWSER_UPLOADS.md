# Importation web et programmation depuis Android / Local 2

## Transport existant, y compris Windows 1.3.0

Les MP4 restent sur Android. Le PC relaie de petits messages MCP, le navigateur
Android transmet le fichier au site. Aucun transfert vidéo par JSON/Base64 MCP
n'est nécessaire pour un fichier déjà présent dans CHK. Wi-Fi et données mobiles
utilisent les mêmes commandes HTTPS, si le relais PC et le site sont accessibles.

Découvrir les possibilités avec `android_command` :

```json
{"action":"android_action","args":{"action":"files_browser_capabilities","args":{}}}
```

Le porteur `desktop` est accepté par le serveur Windows 1.3.0 déjà installé :

```json
{
  "action":"android_action",
  "args":{
    "action":"desktop",
    "args":{
      "tool":"browser",
      "operation":"upload_file",
      "id":"short_01_upload_v1",
      "expected_host":"studio.youtube.com",
      "selector":"input[type=file]",
      "files":[{"workspace_path":"Categories/Videos/MonDossier/video.mp4"}]
    }
  }
}
```

Inspecter d'abord le DOM avec `browser_read_page` / `get_form` et utiliser le
sélecteur exact du champ demandé. `expected_host` doit correspondre exactement
au domaine HTTPS de l'onglet. 1..8 fichiers, 512 Mio locaux au total ; le site et
le téléphone peuvent imposer des limites supplémentaires. Le sélecteur Android
natif reçoit une URI locale en lecture seule. Si le site refuse de l'ouvrir, les
fichiers de 32 Mio au plus ont le repli JS historique ; les gros fichiers sont
refusés explicitement, sans fausse confirmation ni copie entière en mémoire.

`id` est stable par sélection. Rejouer le même id retourne le journal existant,
sans sélectionner à nouveau. Un contenu différent sous le même id est refusé.
Lire le journal même sans navigateur :

```json
{"action":"android_action","args":{"action":"files_browser_upload_status","args":{"id":"short_01_upload_v1"}}}
```

`selected` signifie que les fichiers sont entrés dans le champ du site. Le
journal indique toujours `server_upload_verified:false` : il faut ensuite
observer la progression et la fin du téléversement sur le site. Le reçu capture
l'événement de sélection avant qu'un site ne vide son input. Les noms et tailles
sont contrôlés. Une interruption est `unconfirmed` / `interrupted`, jamais une
invitation à renvoyer le fichier aveuglément. Les 32 derniers journaux terminés
sont conservés ; les sélections encore actives ne sont pas évincées.

## Champs et formulaires dynamiques

Même enveloppe `android_action` / `desktop`, avec `tool:"browser"` :

```json
{
  "tool":"browser","operation":"fill_form","expected_host":"studio.youtube.com",
  "fields":[
    {"selector":"#titre","kind":"text","value":"Titre du Short"},
    {"selector":"#description","kind":"text","value":"Description et hashtags"},
    {"selector":"#audience","kind":"check","value":true},
    {"selector":"#visibility","kind":"select","value":"schedule"},
    {"selector":"#date","kind":"text","value":"2027-01-24"},
    {"selector":"#heure","kind":"text","value":"18:00"}
  ]
}
```

Ces sélecteurs sont des exemples, pas une promesse concernant le DOM YouTube.
Détecter les vrais contrôles de la page. `get_form` inspecte les formulaires,
contenteditable, radios et contrôles dynamiques, y compris les shadow roots
ouverts. Les sélecteurs évitent les identifiants DOM dupliqués quand un ancêtre
permet de les distinguer. Les widgets de date spécifiques à un site peuvent
nécessiter des clics vérifiés, plutôt qu'une saisie HTML simple.

`fill_form` valide tous les champs avant édition, émet les événements et relit
les valeurs après 500 ms. `verify_form` contrôle les mêmes valeurs sans écrire.
Un champ refusé ou remplacé n'est pas un succès. Maximum 24 champs. Les mots de
passe, champs fichier et champs cachés ne sont pas modifiables par cet outil.
`click_verified` exige une condition observable et ne répète jamais le clic.

## Publication / programmation

1. Préparer un manifeste avec `publication_prepare` : média, compte, titre,
   légende, `schedule_at` avec décalage UTC explicite et destination.
2. Importer le média et observer la fin réelle du téléversement.
3. Remplir et vérifier titre, description, audience, visibilité, date, heure et
   fuseau affichés par le site. Tenir compte des changements d'heure.
4. Utiliser l'opération navigateur `publication_submit` avec les `fields` à
   revérifier, l'id du manifeste, `mode:"schedule"`, `expected_host`,
   `account_selector`, la cible du clic et une preuve finale spécifique
   (`expected_text` ou `expected_url_contains`).
5. Relire `publication_status`. `prepared` n'est pas programmé sur le site.
   `schedule_browser_confirmed` exige un clic suivi de la preuve demandée.
   Un résultat incertain ne doit pas entraîner un second clic automatique.

Les sélecteurs et confirmations dépendent de la plateforme. Une connexion au
compte, une vérification humaine ou une restriction WebView peut nécessiter
l'intervention du propriétaire ; aucune API privée de plateforme n'est simulée.

## Session Android

En mode autonome Local 2, quitter l'écran navigateur conserve les WebViews
existantes dans le processus de l'application. Une destruction de MainActivity
transfère les mêmes objets (DOM, valeurs, cookies, fichiers sélectionnés et
requêtes réseau) au gestionnaire retenu. Revenir dans CHK réattache ces objets,
sans ouvrir un navigateur séparé ni recharger les formulaires.

Les pages ne sont pas recréées silencieusement si le processus Android entier
est tué. Un arrêt explicite du mode autonome ou de Local 2 détruit la session
retenue. `browser_status` distingue disponibilité, visibilité et rétention.
Le PC doit rester allumé pour Local 2. Cette évolution ne contourne ni les
restrictions d'Android ni celles des réseaux ou des sites.

## Vérification

Tests sur de vraies WebViews via le dispatcher Local 2, Render désactivé :
MP4 synthétique de 34 Mio et lecture de ses derniers octets, sélection en
arrière-plan, champ vidé par le site, idempotence, domaine erroné, formulaires
contrôlés, maintien du DOM et des fichiers sélectionnés après destruction / retour
de l'activité, arrêt du propriétaire et programmation simulée à clic unique.
Les tests n'utilisent aucun compte YouTube ni média personnel.
