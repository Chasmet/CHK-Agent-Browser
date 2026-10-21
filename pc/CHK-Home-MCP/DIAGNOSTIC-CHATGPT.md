# CHK Agent Browser Local — Diagnostic de connexion ChatGPT (21 octobre 2026)

## Résultats confirmés

1. Le service Windows `CHK-Home-MCP.exe` tourne sur `127.0.0.1:8766` et renvoie 10 outils. Il ne dépend pas de Render.
2. `pc-salon.taild9e4de.ts.net:8443` répond publiquement, y compris en 5G sans VPN Tailscale (GET: `Only POST MCP operations`).
3. Depuis le PC, l'installateur a validé un vrai POST `initialize` via Tailscale Funnel HTTPS.
4. La V4 Cloudflare Quick Tunnel génère un domaine en HTTPS 443 mais la requête POST MCP publique n'a pas abouti. **Aucun HTTP status précis n'a été collecté**. Son diagnostic de la V4 est insuffisant pour conclure à une cause précise.
5. Les tests GitHub Actions `39590889286` ont **réussi avec le client officiel Python MCP** : `ClientSession.initialize`, `list_tools` et `call_tool('server_info')` via Streamable HTTP, sans utiliser notre client de test interne. L'exécutable Windows est également compilé.
6. Malgré cela, ChatGPT Plugins affiche `Impossible de contacter le serveur MCP` depuis PC et téléphone.

## Ce que ces résultats excluent

- Pas une panne de l'application Windows elle-même.
- Pas simplement une erreur DNS ou l'absence de HTTPS Tailscale.
- Pas une erreur de protocole évidente : l'interopérabilité avec le client officiel est validée localement.

## Ce qui reste possible, non confirmé

- Restrictions réseau entre le backend ChatGPT et les ports HTTPS non standards comme `8443`, ou politiques propres au domaine `.ts.net`.
- Refus d'un client particulier ou du mécanisme de découverte par ChatGPT.
- Disponibilité / habilitation du plugin personnalisé dans l'offre ou le workspace.
- Incompatibilité des Quick Tunnels Cloudflare avec des réponses SSE : Cloudflare documente explicitement l'absence de SSE. **Ne pas déclarer que c'est nécessairement la cause : le CHK MCP actuel répond en JSON.**

## Plan technique recommandé, sans réinstaller ni toucher IIS

1. **Cesser les modifications aléatoires du MCP Windows.** Conserver les dix outils, leur configuration et le test SDK officiel en CI.
2. Tester un **endpoint HTTPS public sur 443 avec domaine stable**, indépendant de l'hôte IIS privé. Un **Cloudflare Tunnel nommé** avec un hostname d'un domaine possédé est une option. Les Quick Tunnels à URL aléatoire ne sont pas adaptés à la production.
3. Avant de configurer les secrets, tester publiquement les statuts de `initialize`, `tools/list`, `notifications/initialized` et `server_info` en `POST` avec les en-têtes standards. Ne jamais mettre le jeton dans les logs.
4. Si le test réussit mais ChatGPT refuse toujours, isoler l'éligibilité et les exigences de l'interface ChatGPT sans toucher au PC.
5. Ne pas faire passer Tailscale Funnel sur le port `443` déjà utilisé pour l'interface IIS privée, car cela pourrait l'exposer.
6. Révoquer la clé API OpenAI et faire tourner le secret MCP précédemment partagés dans la conversation avant usage sensible.

## Contraintes du projet

- L'ancien connecteur CHK Agent Browser sur Render reste intact.
- Nouveau connecteur sans consommation d'heures Render.
- Aucune exigence de CMD pour le propriétaire.
- Pas de modification du site IIS existant.
- Pas de fausse URL présentée comme prête tant qu'un test depuis ChatGPT n'a pas réussi.

## Sources

- OpenAI: https://developers.openai.com/plugins/build/mcp-server
- OpenAI: https://help.openai.com/en/articles/12584461-connecting-custom-mcp-servers-in-chatgpt
- Cloudflare: https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/
- CI MCP SDK: https://github.com/Chasmet/CHK-Agent-Browser/actions/runs/39590889286
