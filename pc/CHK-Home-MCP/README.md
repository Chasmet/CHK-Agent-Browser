# CHK Home MCP — serveur PC Windows, sans Render

Service MCP Windows natif, distribué sous forme de ZIP avec **deux vrais exécutables x64** (serveur et installateur graphique) construits par GitHub Actions. Aucun appel Render ni cle API OpenAI.

## Adresse prevue et isolation

HTTPS : https://pc-salon.taild9e4de.ts.net:8443/mcp/TON-JETON-PRIVE

Le jeton est genere automatiquement sur Windows, jamais partage sur GitHub ni dans les logs. L'adresse ci-dessus est un MODELE : elle n'est pas encore installee/validee. L'installateur copie l'URL reelle dans le presse-papiers du PC.

Le service écoute sur 127.0.0.1:8766 ; Tailscale Funnel expose uniquement le port HTTPS 8443. Le port HTTPS 443 de ton site IIS n'est pas touche. Ne lance jamais Funnel sur 443 a l'aveugle : cela pourrait exposer publiquement ton tableau de bord CHK Home.

## Installation

1. Ouvrir les Actions GitHub de CHK Agent Browser, workflow CHK Home MCP Windows, branche feature/pc-home-mcp-20261021.
2. Telecharger et extraire completement l'artefact CHK-Home-MCP-Windows.
3. Sur le PC Windows pc-salon, double-cliquer sur **CHK-Home-Installation.exe** (interface graphique, aucune commande CMD).
4. Cliquer sur le bouton **INSTALLER ET ACTIVER**, puis confirmer. Le logiciel copie le serveur dans LOCALAPPDATA, active son demarrage automatique utilisateur, teste les 10 outils MCP locaux, puis configure Tailscale Funnel sur le port 8443 si autorise.
5. Cliquer sur **COPIER MON LIEN MCP**. Le jeton reste masque a l'ecran. Dans ChatGPT : Plugins > Ajouter > URL du serveur > Ctrl+V. Authentification Sans authentification (le jeton est dans l'URL).
6. Verifier que ChatGPT peut reellement appeler server_info. Ne pas affirmer que l'adresse est operationnelle avant cet essai.

Si Funnel est interdit, deja utilise sur 8443, ou si ChatGPT refuse le port 8443, l'installation publique s'arrete sans perturber le serveur IIS existant. Il faudra une solution HTTPS alternative sans exposer ton site prive.

## Outils disponibles

server_info, files_list, files_read_text, files_write_text, files_mkdir, files_move, files_trash, files_restore, video_probe, video_cut.

Tous les fichiers sont limites au dossier Documents/CHK-Workspace. Les chemins absolus, echappements hors du workspace et liens symboliques vers l'exterieur sont refuses. Suppression vers une corbeille recuperable. Les exports video requierent FFmpeg/FFprobe installes localement. Pas de shell, PowerShell ou commande administrateur a distance : le niveau de risque est trop eleve sur un serveur public.

## Sécurité et limites

Le jeton d'acces 256 bits est stocke localement dans LOCALAPPDATA/CHKHomeMCP/config.json et ne doit jamais etre partage. L'URL privee permet des ecritures dans CHK-Workspace. En cas de fuite, changer le jeton puis redemarrer le serveur.

Serveur HTTP lie exclusivement au loopback ; HTTPS termine par Tailscale Funnel. Requetes authentifiees, controle de taille, rejet Origin, limites de concurrence, pas de journalisation de l'URL secret.

Demarrage automatique sous la session utilisateur Windows et non sous SYSTEM : une session PC doit etre ouverte apres le redemarrage. Le PC doit rester allume et connecte ; Render n'est jamais utilise pour ces appels MCP.

Le developpement/test Windows sur GitHub Actions ne prouve ni l'activation de Funnel ni la connexion ChatGPT au PC reel. Aucune ancienne route IIS ou modification Render ne figure dans ce projet.

## Contenu ZIP version interface graphique

- **CHK-Home-Installation.exe** — ouverture en double clic ; boutons INSTALLER ET ACTIVER, VERIFIER, COPIER MON LIEN MCP ; aucune fenetre CMD.
- **CHK-Home-MCP.exe** — serveur MCP Windows local 127.0.0.1:8766 ; ne pas le lancer manuellement.
- **README.md** — consignes et securite.

Ne telecharge pas les anciennes versions de l'installateur CMD : cette version les remplace.
