# MCP direct sur Android — sans Render (branche de test)

Le nouveau mode **MCP DIRECT — Tailscale** s'exécute dans le service Android `AgentService`, en premier plan, sur le port TCP 8765. Il n'utilise ni `tunnel-client.exe`, ni le PC Windows, ni les routes Render.

## Précision sur l'accès

- Le téléphone détecte son adresse IPv4 active sur le réseau Tailscale (100.64.0.0/10).
- L'adresse privée est **HTTP** : `http://<IP_TAILSCALE>:8765/mcp/<capability-privée>`. Elle est accessible seulement depuis un autre appareil du même tailnet. **Ne la colle pas dans ChatGPT** : ChatGPT cloud ne peut pas atteindre le tailnet ni accepter une URL privée non HTTPS comme connecteur public.
- Une connexion publique et sécurisée nécessitera une véritable terminaison HTTPS accessible depuis Internet, autorisée, éprouvée et compatible MCP. Ce chantier est distinct. **Aucune adresse publique n'est validée dans cette branche.**
- L'URL `https://pc-salon.taild9e4de.ts.net/` désigne le PC, pas le téléphone ; rallumer le PC ne configure pas automatiquement un serveur MCP au chemin `/mcp`.

## Comportement

- Réglages : « Activer le serveur MCP DIRECT sur mon téléphone (0 Render) ». Le mode demande une confirmation explicite et réutilise le service au premier plan existant.
- L'identité et le jeton d'installation sont créés localement et conservés dans les préférences privées Android exclues de la sauvegarde automatique.
- Les appels `AgentClient.request` vers Render sont **refusés** tant que `direct_mode` est actif ; aucun polling ni heartbeat n'est lancé.
- `GET /health` n'affiche pas de secret ; `POST /mcp/<jeton>` exige la possession d'un lien privé, et `POST /mcp` peut utiliser `Authorization: Bearer <jeton>`.
- Uniquement des pairs loopback ou Tailscale et aucune requête portant l'en-tête `Origin`; le serveur est limité à 2 workers, 6 clients en file, 32 KiB de corps.
- Outils : `browser_status`, `browser_tabs`, `browser_read_page`, `browser_open_url`, `browser_click`, `browser_type`, `browser_scroll`, `browser_get_form`.
- Les commandes qui modifient le navigateur demandent le mode autonome CHK déjà consenti. L'arrêt du MCP arrête également le serveur direct.

## Validation

1. Compiler `./gradlew assembleDebug lintDebug` sur GitHub Actions.
2. Installer une variante **signée avec le certificat de l'application déjà installée** avant de tester sur le téléphone réel. Ne pas désinstaller CHK pour contourner un changement de signature.
3. Connecter Tailscale sur Android et activer le mode direct. Vérifier la nouvelle IP réelle dans Réglages.
4. Depuis **un autre client Tailscale autorisé** : `GET http://<IP_TAILSCALE>:8765/health` doit renvoyer `public_https:false, render:false`.
5. Tester `initialize`, `tools/list`, `tools/call` avec le jeton. Vérifier qu'une requête sans jeton retourne 401.
6. Vérifier les fonctions Android, la consommation de batterie, l'arrêt du service et sa reprise sans jamais contacter Render.

### Mise à jour APK et signature

Le workflow de publication existant récupère actuellement sa clé de signature via le service Render. **Cette branche n'est pas fusionnée sur `main`**, ce qui évite une Release et cet appel pendant le développement. Un APK debug distinct n'est pas une mise à jour compatible de l'installation actuelle. Une mise à jour Release sans même clé n'est pas possible ; transférer la clé de signature vers un stockage sécurisé approprié nécessite une procédure séparée. Ne pas publier de Release prétendument identique sans cette validation.
