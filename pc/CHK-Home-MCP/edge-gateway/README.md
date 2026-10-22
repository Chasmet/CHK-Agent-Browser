# Local 2 : réduire les requêtes sans réinstaller le relais

La passerelle 1.1.0 conserve la fonction `chk-home-mcp-gateway`, son URL,
le serveur Windows, le secret existant et les 13 ou 20 outils du relais.
Aucun cache de fichier, résultat d'action ou état de connexion n'est utilisé.
Render reste indépendant. Android communique directement avec Windows,
y compris lorsque l'adresse de la passerelle a été importée dans Local 2.

## Changements mesurables

- L'autodiagnostic appelle Windows directement : **une invocation Edge**.
  L'ancien code se rappelait via Supabase : quatre invocations si les trois
  vérifications aboutissaient, trois avec le relais actuel de 13 outils
  (son assertion obsolète `tools.length === 10` interrompait la vérification).
- `server_info` inclut `android_channel` : **une requête ChatGPT** pour les
  états PC et Android, au lieu de deux. Cela reste deux lectures Windows,
  sans commande envoyée au téléphone. Un échec de lecture Android indique
  `available=false` et conserve les informations PC reçues.
- Les descriptions des outils existants indiquent comment utiliser
  `browser_batch` (1..12 étapes) et `files_task` (1..50 étapes persistantes).
  Le schéma et le nombre des outils restent identiques. Un lot réduit les
  soumissions réseau ; son suivi peut nécessiter des appels supplémentaires.
- Après un résultat `pending`, `android_result` conseille 3 secondes entre
  lectures, avec le même identifiant. Ce conseil ne force pas le client
  à patienter et ne relance pas la commande.
- L'attente du relais passe de 23 à 55 secondes pour couvrir les 20/30
  secondes d'attente Windows et le transport. Après une coupure ou un délai,
  l'exécution reste **inconnue**, sans répétition automatique.

Une connexion récente ne prouve ni l'exécution ni l'affichage. Le diagnostic
vérifie séparément le transfert, la version, le catalogue réel et l'identité
lorsque le relais la fournit. La version 1.3.0 ne fournit pas de fingerprint :
elle est indiquée `not_provided_by_relay`, sans inventer cette preuve.
Un Android absent peut être signalé alors que le trajet Windows est valide.

## Lot natif avec le relais 1.3.0

Après découverte de `files_capabilities`, soumettre une tâche locale via
`android_command`, sans installer de nouvel EXE :

```json
{
  "action": "android_action",
  "args": {
    "action": "files_task",
    "args": {
      "operation": "submit",
      "id": "verification-local2-001",
      "steps": [
        {"id": "etat", "action": "workspace_status", "args": {}},
        {"id": "racine", "action": "files_list", "args": {"path": ""}}
      ]
    }
  }
}
```

Le mode autonome doit être actif. Lire `files_task` avec `operation=status`
et le même `id` pour suivre la progression ; ne pas resoumettre des effets
incertains. Les actions sensibles conservent leurs contrôles Android.

Le test réel du 22 octobre a révélé que `workspace_status` lisait la WebView
sur le fil disque lorsqu'un navigateur était ouvert. L'APK corrigé capture
l'état du navigateur sur le fil principal, puis lit le stockage sur le fil
IO. Le test Android de régression utilise une vraie WebView visible, en
commande individuelle puis dans un lot de deux actions.

## Vérification et déploiement

```sh
node --test pc/CHK-Home-MCP/edge-gateway/tests/gateway.test.ts
```

Déployer **index.ts et gateway.ts**, avec `index.ts` comme point d'entrée,
dans la fonction existante. L'authentification par capacité Windows est
conservée (`verify_jwt=false`, comme auparavant). Aucun secret dans Git,
aucune clé Supabase transmise au PC. Le corps reste limité à 96 Kio.
L'autodiagnostic est non destructif et ne contient pas le secret dans son
rapport. Les requêtes avec Origin sont refusées.

Le gain global dépend des appels du client ; ces changements ne permettent
pas d'annoncer une baisse de 50 ou 90 % de toute la consommation. Les journaux
sur 24 heures ne sont pas un compteur de facturation mensuel. Chaque appel
entrant reste une invocation Edge, même si son résultat était mis en cache.

Référence : https://supabase.com/docs/guides/functions/limits
