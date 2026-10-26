## Local 3 — autorisations persistantes et workflows autonomes (26 octobre 2026)

- Réglages : interrupteur propre à Local 3 pour autoriser ou révoquer les commandes automatiques, sous le mode autonome déjà accepté. Aucun changement de l’activation Local 2 ou Render.
- Synchronisation authentifiée de cette autorisation avec le serveur PC : navigation, saisie, fichiers et Studio ne demandent plus une validation serveur à chaque commande autorisée.
- 83 capacités déclarées : outils directs pour l’import web, l’état d’import, la relecture des formulaires et les workflows durables (démarrer, suivre, reprendre, annuler).
- Workflows sans le précédent plafond de 50 étapes, progression paginée et arrêt effectif lors d’une révocation Local 3.
- Une commande interrompue sur Android est signalée comme résultat inconnu, sans rejouer son effet. Les résultats volumineux sont conservés comme fichiers lisibles par blocs.
- Nécessite le serveur PC 1.0.4 pour les autorisations persistantes et les nouveaux outils MCP. Aucun quota de nombre total de demandes ; les formats, tailles des paquets et autorisations du système restent techniques.
- L’alignement des WAV dans Studio n’est pas un moteur de synchronisation labiale ; cette mise à jour n’ajoute pas de moteur lip-sync.
- Même package, même signature permanente, conservation des associations, fichiers, notes et projets.

## Local 3 — vision et liaison Android fiables (26 octobre 2026)

- Vision web/native accessible par browser_inspect et browser_capture, sous le contrôle du partage d’aperçus existant.
- Transport Local 3 indépendant des limites historiques Local 2 : captures et réponses paginées conservées en entier.
- Lecture des résultats volumineux par files_transfer_read, avec date, taille et empreinte de contrôle.
- Synchronisation des 74 capacités après mise à jour, sans refaire l’association ; les anciens serveurs continuent de recevoir leurs commandes et sont signalés dans le diagnostic.
- Réglages : diagnostic Local 3 détaillé, dernière commande et bouton de reconnexion/actualisation des outils.
- Nécessite le serveur Universal Control compatible pour la synchronisation et le retour des images MCP.
- Package, clé de signature persistante, paramètres, fichiers, notes et projets existants conservés.

## CHK Browser Local 3 — troisième connecteur MCP et mise à jour automatique (26 octobre 2026)

- Troisième connexion MCP indépendante dans Réglages : activation/désactivation sans toucher à Render ni Local 2.
- Association à CHK Universal Control par URL HTTPS réelle du serveur personnel et invitation temporaire, sans jeton dans l’APK ni dans les sauvegardes.
- Commandes natives CHK : navigateur/onglets, clavier, pointeur virtuel, fichiers, documents PDF, médias MP3/MP4, Notes et Studio, dans les limites des autorisations Android.
- Diagnostic du canal, reprise après redémarrage, confirmations des commandes, protection contre leur répétition en cas de panne réseau.
- Conservation de la mise à jour intégrée à CHK : Release GitHub signée avec la même identité Android, installation par-dessus et conservation des données.

## Studio — calques et aperçu rendu sur Android (24 octobre 2026)

- Jusqu’à trois incrustations image/vidéo au-dessus de la piste principale, avec début, durée, position, largeur relative et opacité. Sons des incrustations coupés. Les plans et sources restent intacts.
- Piste Calques visible dans la timeline et réglages accessibles depuis la barre Outils. Import depuis les catégories CHK ou le téléphone. Ordre de superposition modifiable.
- Aperçu rendu à la demande en 480p : utilise la même composition Media3 que l’export final pour montrer les calques et les effets. Progression, annulation, cache privé supprimable, retour au mode source après modification. Aucun export final n’est créé par ce bouton.
- Le mode source est identifié clairement ; l’aperçu rendu nécessite un calcul local et n’est pas un effet instantané. Le contrôle MCP accepte la piste layers et ses réglages.
- Validation des bornes des calques, protection de leurs sources contre l’écrasement, empreintes incluses dans les instantanés de file d’export et chemins relatifs inclus à l’import des projets JSON.

## Studio tactile — montage direct sur téléphone (24 octobre 2026)

- Timeline avec curseur central fixe, défilement au doigt, zoom par pincement/boutons et double appui sur la règle pour cadrer le projet. Miniatures vidéo échantillonnées dans le plan, pas une image répétée unique.
- Poignées tactiles de découpe avec durée affichée pendant le geste ; limites de source, vitesse et durées photo respectées. Appui long pour réordonner les plans. Les sources originales restent intactes.
- Sélection directe des segments audio et texte. Outils contextuels horizontaux avec boutons lisibles, au lieu de huit icônes comprimées sur une ligne. Aperçu vidéo agrandi et état d’export superposé.
- Vitesse de 0,25× à 4× avec curseur, durée résultante et écoute. Volume indépendant des plans et des segments audio, de 0 à 100 %, appliqué à l’aperçu et au MP4 exporté. La commande MCP video_editor_timeline accepte le même réglage volume.
- Duplication immédiatement après le plan choisi. La piste musicale séquentielle est raccourcie à la fin du montage après découpe/changement de vitesse ; annuler restaure le projet antérieur.
- Import multiple depuis le sélecteur Fichiers CHK à miniatures, catégories et recherche, sans recopier les médias déjà présents. Les imports du téléphone restent accessibles.
- Projets, historique persistant, contrôle MCP et signature Android existants conservés. Le moteur reste une piste vidéo séquentielle, une suite audio et les textes par plan ; les filtres/animations GPU gardent leur aperçu brut explicitement indiqué et leur rendu à l’export.

## Importation — mêmes dossiers que Fichiers (24 octobre 2026)

- Nouveau sélecteur CHK plein écran : Images, Vidéos, Audio, Documents et Récents utilisent les catégories et dossiers du gestionnaire Fichiers, sans déplacement des médias.
- Miniatures, grille/liste, recherche dans les sous-dossiers, tri et sélection multiple conservée entre dossiers. Bouton Importer explicite et liste de sélection consultable.
- Les formats refusés par le site restent visibles et expliqués ; filtre optionnel. Formats acceptés MIME/extensions/valeurs séparées par virgules corrigés. Les fichiers au-delà de la première page sont accessibles.
- Lecture du catalogue et vérification des fichiers en arrière-plan. Fichiers déplacés, annulation et changement de page n’annoncent aucun succès.
- Fichiers Android reste disponible pour Photos, Téléchargements et les fournisseurs autorisés. Aucun changement de serveur MCP, de signature ou de données utilisateur.

## Importation web Local 2, session Android et formulaires vérifiés

- Importer les fichiers CHK dans les sites depuis le MCP Windows existant, y compris 1.3.0, via `desktop` / `tool:browser`. Découverte par `files_browser_capabilities`.
- Transfert local des gros MP4 par flux vers la WebView, y compris en arrière-plan ; reçu contrôlé et journal anti-doublon. La sélection du fichier et la fin du téléversement sur le site restent distinctes.
- Conservation des mêmes onglets et du DOM lorsque l'écran navigateur est détruit en mode autonome ; réattachement au retour dans CHK.
- Remplissage et relecture des champs dynamiques, dates, heures, options et descriptions. Programmation avec contrôle des champs, du compte et de la preuve finale via le journal de publication existant.
- Aucun changement de serveur, de lien MCP, de permissions Android, de signature APK ou de données utilisateur. Android peut toujours interrompre le processus ; les sites peuvent imposer une vérification humaine.

## Vidéos 9:16 — lecteur CHK en plein écran (23 octobre 2026)

- Depuis Fichiers, un MP4 ouvre directement le lecteur vidéo dédié, et non plus l'afficheur texte de documents.
- Vidéo verticale affichée sur toute la largeur en mode ajusté, sur fond noir, sans bandes latérales grises générées par l'ancien conteneur 260 dp.
- Bouton « Remplir l’écran » / « Afficher toute la vidéo » pour choisir entre plein écran recadré et image complète.
- Le JSON technique n'apparaît plus sous la vidéo ; les métas TXT restent consultables et copiables séparément.
- Les commandes MCP `files_open` conservent la confirmation de visibilité du lecteur.

## Local 2 — reconnexion, service Android et diagnostics (23 octobre 2026)

- Reconnexion agit aussi sur Local 2 ; réveil après changement de réseau et retour dans CHK, sans répéter une commande en cours.
- Maintien du service idempotent, reprises espacées après arrêts rapprochés et arrêt de notification respecté pour les deux canaux.
- Erreurs HTTP/DNS/TLS/délai distinctes, compteurs Local 2 séparés de Render, historique des commandes identifié par canal.
- État du service, refus de démarrage et dernier motif de fin de processus signalé par Android, sans attribuer arbitrairement chaque destruction à Android.
- La disponibilité du service MCP Windows derrière Funnel reste distincte de la présence Tailscale ; un HTTP 502 côté PC nécessite toujours un diagnostic PC.

## Render MCP — dossiers visibles et renommage de fiches (22 octobre 2026)

- Corrige l’écart entre le chemin affiché « Documents » et le chemin de stockage « Categories/Documents ». Les commandes MCP existantes acceptent désormais `Documents/...`, `Images/...`, `Vidéos/...` et `Audio/...` en plus des chemins réels ; les anciens chemins et le contenu des dossiers sont conservés.
- `browser_files_list(path="")` annonce `visible_categories` avec le chemin simple et le chemin canonique. Les commandes existantes de lecture, aperçu, renommage, copie et corbeille fonctionnent dans ces sous-dossiers, sans nouveau service Render et sans PC Windows.
- Régression Android ajoutée : liste, lecture texte/binaire, renommage, corbeille/restauration, protection contre la traversée des dossiers.
- Ce correctif concerne les fichiers **importés dans CHK**. Les fichiers d’autres applications et les répertoires Android externes continuent à exiger une autorisation explicite du propriétaire via le sélecteur système (SAF). Aucun accès root, aucune suppression de protection Android.
- Mises à jour : même application, même package et même signature Release persistante via GitHub Actions ; données privées conservées à l’installation par-dessus la version précédente.

## Local 2 — efficacité des requêtes et état du navigateur (22 octobre 2026)

- Passerelle HTTPS 1.1.0 : diagnostic sans récursion Supabase, catalogue réel de 13/20 outils, identité vérifiée lorsqu'elle est disponible et connexion Android séparée de la preuve d'exécution/affichage.
- `server_info` retourne aussi le canal Android ; conseils de lots persistants et de suivi sans renvoi d'une commande incertaine, délai de transport adapté à Windows.
- Correction Android : `workspace_status` consulte la vraie WebView sur le fil principal, puis le stockage sur IO. Corrige l'erreur de fil WebView en commande individuelle et dans `browser_batch`.
- Même serveur, même URL, mêmes permissions et signature APK ; aucune réinstallation du relais Windows requise.

## Fichiers — ouverture intégrée, métas copiables et lecteurs locaux (22 octobre 2026)

- Un appui sur META_A_COPIER.txt ouvre le contenu directement dans CHK, avec sélection et Copier tout. Recherche, Markdown simple, validation JSON/JSONL stricte et tableaux CSV/TSV.
- Lecture seule par défaut. Édition volontaire des textes jusqu’à 1 Mo ; nouvelle copie UTF-8 ou remplacement explicite protégé par SHA-256 avec sauvegarde en corbeille. Les gros textes sont lus progressivement.
- Détection du contenu par signature, encodage et structure des paquets. Les extensions trompeuses et formats non pris en charge ont des avertissements ; le secours hexadécimal n’exécute rien.
- Visionneuse locale PDF, extraction DOCX/XLSX/PPTX/OpenDocument, images et lecteur Media3 selon les codecs disponibles. ZIP consultables sans extraction, sélection d’entrées par MCP, protections de taille et de chemin ; SQLite en lecture seule.
- Le lecteur audio intégré de Fichiers reste disponible. Les aperçus et conversions d’images respectent rotation et miroir EXIF, sans modifier la source.
- Copies converties en PNG/JPG/WEBP ; conversions documentaires simples à partir de textes, JSON et CSV/TSV. Aucune sortie existante écrasée.
- Catalogue natif v5 : files_open, files_detect_format, files_copy_content, files_search_content, files_convert, files_extract_archive, files_database, files_verify et files_batch. Les 13 outils Local 2 existants restent utilisables. files_open confirme seulement une visionneuse chargée et au premier plan.
- Limites détaillées dans docs/UNIVERSAL_FILES.md : pas encore de lecture native de tous les formats propriétaires, éditeur Office fidèle, 3D/CAD, archives non ZIP ou PDF professionnel complet. Les mises à jour et la signature habituelles sont conservées.

## Studio — photos, montage MCP et exports sur Android (22 octobre 2026)

- PNG, JPG/JPEG et WEBP utilisent le mode image natif Media3, avec durée de 1 à 60 secondes et cadence 24/30/60 fps dans le projet. Le lecteur de pistes vidéo n’est plus appelé pour ces photographies.
- Import multiple jusqu’à 100 médias ; les photos sont ajoutées avec une durée initiale de 5 secondes, réglable dans Découper. Miniatures et prévisualisation acceptent aussi les photos.
- Ouverture des projets de montage JSON depuis Fichiers, validation avant changement du projet actif, résolution des chemins relatifs. Un JSON ordinaire conserve son ouverture habituelle.
- Timeline visible : déplacement des plans par appui long, zoom/panoramique avec positions de début et fin. Historique annuler/rétablir conservé par projet après fermeture ; sauvegarde atomique et récupération du brouillon le plus récent.
- MCP Local 2 : découverte Studio, import de projet, opérations atomiques sur la timeline avec révision, réglages, textes, filtres, durées, mouvements et historique. Les actions passent aussi par les 13 outils du relais Windows 1.3.0 existant.
- File durable de 1 à 10 projets par demande, jusqu’à 40 entrées conservées. Les projets sont figés, leurs sources contrôlées par SHA-256, leurs destinations distinctes et sans écrasement. Un request_id rejoué n’ajoute pas une seconde série. Écran Exports dans Studio et commandes MCP pour consulter, suspendre et reprendre.
- Après arrêt du processus, les exports actifs deviennent interrompus ; Reprendre vérifie le MP4 existant avant une nouvelle tentative. La notification de rendu protège la succession des exports ; Annuler suspend aussi la file.
- Avant publication : contrôle de durée, présence de la musique demandée et décodage d’une image au milieu de chaque plan, comparaison avec la source pour détecter un plan noir inattendu. Un échec conserve la sortie précédente.
- Relais Windows 1.4.1 : catalogue android_media étendu à Studio ; aucune réinstallation nécessaire pour utiliser les nouvelles actions avec android_action.
- Ce lot n’ajoute pas encore de composition vidéo multipiste avec superpositions, transitions croisées, masques, LUT, transcription automatique, mixeur audio parallèle, proxies ni export 4K. Les pistes vidéo et audio restent séquentielles. Aucun générateur vidéo ni rendu sur PC.

## Local 2 — fichiers, documents et transferts vérifiés (21 octobre 2026)

- Catalogue Android version 4 avec schémas paginés : `android_action` → `files_capabilities`. Compatible avec le serveur Windows 1.3.0 déjà installé.
- Imports binaires jusqu’à 512 Mo, blocs de 48 Ko, point de reprise durable, rejeu de bloc identique sans doublon, contrôle SHA-256 final et publication atomique. Exports par blocs de 8 Ko avec contrôle de version.
- Édition texte avec contrôle du SHA-256 lu et sauvegarde récupérable en corbeille. SHA-256 et présence des fichiers, copie de dossiers, sauvegardes ZIP et extraction protégée dans un nouveau dossier. Les destinations existantes sont refusées ; quota de 5 000 entrées et 512 Mo décompressés.
- Création locale de PDF, DOCX, XLSX, PPTX, JSON, CSV et TXT sans service cloud. Extraction native des valeurs XLSX et textes PPTX. Documents Office simples, éditables, sans macros ni moteur de calcul ; l’édition fidèle d’un document Office arbitraire n’est pas incluse.
- Dossiers Android externes : création de sous-dossiers dans les accès persistants autorisés. Les exports sont relus et leur SHA-256 comparé avant confirmation.
- Les aperçus JPEG sont adaptés à la taille du transport. Un résultat volumineux est conservé intégralement dans `Fichiers/AgentResults`, avec chemin et SHA-256, au lieu d’un JSON tronqué.
- Tâches persistantes étendues aux documents, archives, vérifications et montage. Attente d’un export existant puis vérification du MP4, sans relancer l’export en cas de délai.
- Contrôles locaux automatiques à la première ouverture après une mise à jour, avec fichiers synthétiques uniquement ; bilan affiché dans Diagnostic. La navigation, les accès externes et le rendu vidéo dépendent de leurs conditions réelles et ne sont pas certifiés par ce test de stockage.
- Relais Windows 1.4.0 : 20 outils, dont 7 spécialisés Android ; copies PC ↔ Android en arrière-plan, reprise et SHA-256. L’installateur existant conserve la configuration et l’URL privée ; aucune création de nouveau serveur.
- Le pack Windows est joint à cette même Release après ses tests Windows. Les nouveaux outils spécialisés exigent cette mise à jour du relais ; les nouvelles actions Android restent utilisables avec les 13 outils de Windows 1.3.0.
- Vérifications : tests Android de stockage, formats, reprise, ZIP malveillant et tâches ; lecture indépendante des documents réellement produits par Android. Signature permanente et mise à jour APK intégrée conservées.

## Local 2 — onglets Android partagés et fichiers indépendants (21 octobre 2026)

- Les commandes Local 2 utilisent les véritables onglets du navigateur Android, y compris lorsque CHK passe derrière ChatGPT ou Réglages. Elles ne basculent plus vers le moteur distinct du relais Render.
- Si Android ferme l'activité du navigateur, Local 2 indique qu'il faut rouvrir CHK ; aucune navigation cachée n'est déclarée visible. Les fichiers restent utilisables sans navigateur ouvert.
- Fichiers, dossiers et tâches Local 2 fonctionnent lorsque Render est désactivé. Le canal d'origine et le consentement autonome sont contrôlés séparément ; désactiver Local 2 interrompt ses commandes.
- Une navigation n'est confirmée qu'après chargement et lecture du document réel. Le résultat distingue page affichée et page prête pour le retour dans CHK. Les erreurs HTTP, délais et changements d'onglet produisent un résultat non confirmé.
- `browser_tabs` accepte les opérations list, new, close et switch ; `browser_open_url` conserve son option new_tab.
- La création d'un dossier vérifie sa présence dans le stockage Android. Les écrans Fichiers sont actualisés après une opération réussie.
- Les clics Local 2 utilisent `expected_selector`, `expected_text` ou `expected_url_contains` pour vérifier le résultat, avec protection contre le double clic. Une condition manquante bloque le clic. La saisie est relue dans le champ après les événements de la page ; une valeur annulée par le site n'est pas déclarée confirmée.
- Réglages et Diagnostic séparent connexion Local 2, mode autonome, disponibilité du navigateur, exécution, acquittement PC et preuve d'affichage.
- Tests Android ajoutés avec Render désactivé, sur le vrai WebView et le stockage de test. Le package, la signature persistante, les données et la mise à jour intégrée sont conservés.

## Correctif Local 2 — connexion Android vérifiée (21 octobre 2026)

- Suppression du faux état « connecté » lorsque le PC répond HTTP 200 avec une erreur MCP ou sans canal Android.
- Vérification du nom du serveur avant la connexion ; contrôle des réponses de commande et de l'acquittement des résultats.
- Compatibilité automatique avec Windows MCP 1.3.0 lorsque Tailscale Funnel retire le préfixe /mcp : nouvelle tentative ciblée sur le même PC, sans réinstallation du serveur.
- Diagnostic dans Réglages : hôte réellement contacté, version PC, route utilisée, âge du dernier échange et suivi de commande ; l'URL privée complète reste masquée.
- Les nouveaux serveurs Windows 1.3.1 exposent une empreinte d'installation commune à server_info, android_status et au canal Android. Le serveur 1.3.0 ne fournit pas cette empreinte ; le diagnostic l'indique.
- Rafraîchissement continu de l'état Local 2, maintien du service et reprise après mise à jour lorsque seul Local 2 est activé.
- Tests de protocole Android et tests HTTP des différentes routes authentifiées.

## CHK Agent Browser — MCP Local 2 Android (21 octobre 2026)

- Deux transports indépendants dans Réglages : relais Render existant et nouveau MCP Local 2 via le PC Windows.
- Le nouveau canal Android communique avec CHK Home MCP sur HTTPS, sans requêtes applicatives vers Render ni Supabase pour ce canal.
- Le navigateur CHK conserve ses fonctionnalités et ses données ; les commandes navigateur Local 2 sont filtrées.
- Le serveur Windows propose 13 outils, dont le nouveau pont Android, en conservant ses dix outils fichiers/vidéo.
- La connexion et l'exécution des commandes via Local 2 nécessitent la nouvelle version du serveur Windows installée sur le PC ; sans celle-ci, le relais Local 2 reste indisponible.
- L'application Android conserve son identifiant de package et le mécanisme de mise à jour signé existant.

## CHK Agent Browser — collecte des commentaires X/TikTok (19 octobre 2026)

- Nouvelle action MCP `collect_public_comments` autorisée dans `browser_task_submit`, compatible avec les onglets visibles et autonomes. Le moteur de tâches reconnaît également `scroll` pour les séquences lecture/défilement.
- Collecte locale bornée de 1 à 8 URL HTTPS de publications X ou de vidéos TikTok, jusqu'à 15 défilements par publication (90 au total), plafond de 500 commentaires par lot.
- Lecture des commentaires réellement présents dans le DOM de la WebView (pas d'API privée ni de contournement de connexion, de CAPTCHA ou de restrictions de plateforme). Les pages inaccessibles ou les redirections vers Google Play sont déclarées comme telles.
- Déduplication SHA-256 sur le texte normalisé, suppression des mentions de comptes et des URL intégrées aux commentaires, sans conservation du pseudonyme des auteurs.
- Écriture locale dans Fichiers CHK : `Presidentielle_2027/commentaires_<id>_001.jsonl` et `commentaires_<id>_rapport.json`. Sorties fractionnées en petits fichiers pour rester sous 64 Ko ; identifiants de lots uniques.
- Essai d'affichage WebView de bureau uniquement lors de l'ouverture des vidéos TikTok par le collecteur ; restauration de l'agent utilisateur initial après collecte. Les commentaires TikTok peuvent rester bloqués côté plateforme.
- Aucun calcul automatique d'intention de vote : commentaires, vues et likes ne sont pas assimilés à des bulletins. Tests Android ajoutés pour limites, URLs, anonymisation et autorisation de la tâche.
- Le package Android, la signature persistante, les fichiers existants et le relais Render restent inchangés.

## Correctif urgent Fichiers — renommage et lisibilité (18 octobre 2026)

- **Renommage sans risque pour le format :** le nom du fichier est modifié sans exposer son extension à l'édition. `.mp4`, `.mp3`, `.jpg` et les extensions composites d'archives sont automatiquement conservées.
- Fenêtre compacte, claire et à contraste élevé ; saisie du nom sur plusieurs lignes, nom initial pré-sélectionné et extension affichée séparément en lecture seule.
- Coller un nom contenant déjà l'extension n'ajoute plus un doublon `.mp4.mp4`.
- Noms de fichiers plus lisibles : grille à deux colonnes, miniatures et cartes plus hautes, plusieurs lignes de titre ; bouton d'options individuel visible.
- Les actions de création/import utilisent aussi la nouvelle fenêtre de saisie plus lisible.
- Tests Android supplémentaires pour la conservation réelle des extensions, la fenêtre de renommage, les textes longs et les cartes de la grille.
- Aucun changement des routes Render, de l'identité Android, de la signature persistante, du navigateur, du Cut ni des données conservées.

## CHK Publication Agent · 18 octobre 2026

- Outils MCP de publication et programmation via les tâches locales existantes : `publication_prepare`, `publication_status`, `publication_list`, `publication_cancel`, `publication_submit`.
- Préparation de campagnes organiques multi-plateformes, métadonnées, hashtags, médias locaux, calendrier ISO avec fuseau et journal anti-doublon.
- Sécurisation du clic final : domaine HTTPS exact, identité du compte vérifiée dans le document, médias non modifiés, preuve visible avant confirmation ; pas de répétition d'une tentative incertaine.
- Historique des publications dans Réglages ; aucune collecte de mots de passe, aucune modification des routes Render.
- Les publicités payantes restent en préparation uniquement. Aucun achat ni engagement budgétaire automatique.
- Tests Android pour les domaines sociaux, la persistance, les états incertains, le verrouillage des comptes et les promotions payantes.

## Session protégée et Fichiers virtualisés (18 octobre 2026)

- La navigation dans Fichiers utilise désormais RecyclerView + GridLayoutManager + DiffUtil, ce qui ne crée des vues et miniatures que pour les fichiers affichés à l'écran.
- Les catégories, dossiers, opérations par lot, recherche, grilles, listes et Notes conservent les mêmes chemins et paramètres.
- La session MCP autonome garde son onglet lorsque le navigateur visible passe en arrière-plan : plus de chargement silencieux ni d'écrasement de la dernière page autonome.
- Les deux moteurs WebView partagent les cookies Android, mais pas l'état JavaScript des formulaires ; un même moteur unique demeure un chantier séparé.
- Une nouvelle action de tâche MCP `resume_visible_page` ouvre explicitement dans un nouvel onglet autonome l'URL mémorisée de la dernière page visible ; cela ne copie jamais les champs et ne remplace pas l'onglet MCP courant.
- Le relais Render et les routes existantes restent inchangés ; aucune activation de SSE sans contrat serveur confirmé.
- Signature persistante, identité Android et système de mise à jour GitHub conservés.

## CHK Agent Browser — MCP rapide et ergonomie (18 octobre 2026)

- Session sûre : le retour à l'application ne remplace plus automatiquement l'onglet visible par la page du moteur MCP autonome. Action explicite « Reprendre la page MCP » dans le menu navigateur.
- Restauration économe : seuls les onglets réellement ouverts chargent la page à la reprise ; les autres attendent leur sélection. Suppression de la double navigation lors de la création d'un onglet.
- Navigation : la barre inférieure se masque au défilement vers le bas et revient en remontant ; favicons affichés lorsque disponibles dans la grille d'onglets.
- MCP : interrogation du relais plus fréquente lorsque l'application est au premier plan (1,2 s au lieu de 3 s en régime normal), sans augmentation de la fréquence en arrière-plan et avec temporisation des erreurs.
- MCP : limitation des reconnexions rapprochées lors des changements de Wi-Fi ou de réseau mobile.
- Diagnostic : latence réseau, moyenne mobile, nombre d'échanges réussis/échoués et durée de dernière commande.
- Batterie : option explicite de mode éco dans Réglages, désactivée par défaut pour préserver la continuité de la connexion en veille.
- Fichiers : la sélection multiple ne reconstruit plus intégralement la liste, ne perd plus le défilement et ne redécode plus toutes les miniatures à chaque sélection.
- Tests : nouvelles assertions Java pour le mode de polling réactif et test Android de restauration différée des onglets.
- La signature Android, le package, les routes Render et les données existantes sont conservés. Aucun changement au Cut Vidéo indépendant.
- Restent à valider sur un appareil réel : autonomie batterie, performances sur dossiers très volumineux et navigation sur comptes tiers connectés.

## Import vidéo CHK haute qualité via sélecteur natif (16 octobre 2026)

- Nouveau chemin d'importation pour `browser_upload_workspace_file` : les fichiers explicitement désignés dans CHK Files sont fournis au WebView par URI Android `content://`, sans recopier leur contenu dans le relais Render ni encoder le média en base64.
- Préparation locale jusqu'à 512 Mo par sélection, avec contrôle du nom, de la taille, de la destination HTTPS et du document de navigation ; un seul téléchargement est remis au champ demandé.
- Le navigateur cherche un bouton visible « Sélectionner des fichiers » associé au champ du site, puis effectue un toucher natif borné pour solliciter `onShowFileChooser` ; les WebViews visible et autonome utilisent le même chemin.
- Un sélecteur qui ne s'ouvre pas, une navigation pendant l'opération ou une réception non confirmée ne sont jamais considérés comme des publications réussies.
- La méthode JavaScript historique reste uniquement un repli pour les petits fichiers (32 Mo maximum) ; les médias volumineux ne sont pas dupliqués en mémoire du moteur JS.
- Test Android ajouté pour un fichier vidéo de 34 Mio, sans compresser ni supprimer la source. Validation réelle sur YouTube Studio requise après installation de l'APK.

## Navigation rapide, gestion des onglets et traduction EN/FR (16 octobre 2026)

- Onglets en grille de cartes inspirée des navigateurs mobiles : voir, ouvrir, sélectionner ou fermer un onglet.
- Menu Web réorganisé et bouton de traduction dans la barre d’adresse.
- Traduction de texte directement dans la page : anglais vers français, français vers anglais et retour au texte original. Les modèles ML Kit sont téléchargés au premier usage, puis fonctionnent hors ligne ; les pages HTML et cookies ne sont pas envoyés au relais.
- Jusqu’à 12 onglets dans la session visible et dans le moteur MCP autonome, au lieu de 8 pour ce dernier.
- Commandes Android nouvelles : `new_tab`/`open_tab`, `list_tabs`, `close_tab`, `translate_page`. Compatibilité `tabs`, `switch_tab`, `close_popup` conservée.
- Les nouvelles commandes peuvent être exécutées via `browser_task_submit` sans ajouter d’outil direct au relais (mode autonome requis). L’enregistrement d’outils MCP directs distincts sur Render reste une opération indépendante.
- Les URLs de création via MCP doivent être HTTPS publiques. Les autres espaces Fichiers, Cut, Studio et Notes ne changent pas.
- Test instrumenté de création, sélection, inventaire et fermeture d’onglets. Conservation de l’application et de sa signature pour préserver les données lors de l’installation.

## Lecteur universel IA — documents, images, audio et vidéo (12 octobre 2026)

- Lecture des fichiers PDF par extraction native PDFBox (jusqu'à 8 pages par demande), et reconnaissance OCR locale des pages scannées avec ML Kit.
- Lecture du contenu des DOCX, et détection de texte dans les images JPG, PNG, BMP et WebP.
- Aperçu d'une page PDF sous forme d'image; réglage du consentement aux aperçus respecté.
- Lecture des PDF HTTPS ouverts dans la WebView avec les cookies de la session en cours : téléchargement temporaire en cache, lecture seule, fichier effacé à la fin et refus des redirections HTTP.
- Transcription locale des pistes audio et vidéo par Vosk en français et en anglais, avec modèle open source téléchargé au premier usage (environ 40 Mo), puis utilisation sans réseau. Aucun envoi d'audio à Render.
- Nouveaux outils MCP version 3 `browser_document_read`, `browser_document_page`, `browser_media_transcribe` et `browser_media_model_status`; les outils précédents continuent de fonctionner.
- Les fichiers, la clé Android de signature, les données et les réglages MCP restent inchangés lors de la mise à jour.
- Limites : les formats médias dépendent des codecs Android, OCR/ASR ne sont pas infaillibles, aucune lecture des fichiers d'autres applications sans import autorisé.

## Cinq outils IA — souris, vision, fichiers, tâches et terminal

- Vrais gestes tactiles Android dans CHK et ses pages web : clic, double-clic, appui long, déplacement et retour ; canvas et commandes natives inclus.
- Vision des fenêtres, menus, messages et coordonnées ; captures de CHK sous le contrôle du partage d’aperçus existant.
- Sélection et plans de 500 fichiers : renommage en série, classement, copie, déplacement, corbeille et transformations texte ; bilan individuel et reprise sans répéter les réussites.
- Accès aux dossiers externes sélectionnés et autorisés par Android, avec retrait des accès depuis Réglages.
- Tâches locales de 50 étapes avec vérifications, journal durable, pause, reprise et annulation ; résultats incertains signalés.
- Terminal CHK Script pour les traitements de fichiers autorisés et conversion de texte/JSON/Markdown, avec aperçu avant exécution.
- Le clavier Google, les données existantes et la même URL MCP sont conservés. Aucun service d’accessibilité ni accès root ajouté.

## Clavier agent MCP — saisie native et formulaires web

- Nouvel outil réservé à ChatGPT : saisie dans les dialogues et écrans CHK ainsi que dans les pages web, sans remplacer le clavier Google de l’utilisateur.
- Texte et nombres, insertion au curseur, sélection, effacement, navigation entre champs, annulation/rétablissement et touche Entrée.
- Remplissage de plusieurs champs avec bilan individuel ; cases et choix pris en charge, sans envoi automatique du formulaire par un lot.
- Ciblage des contrôles observés et contrôle des valeurs, y compris après les mises à jour des scripts web ; erreurs et résultats incertains explicités.
- Compatible avec l’outil MCP existant, la même adresse et le même accord autonome. Les champs protégés restent exclus.
- Quatorze nouveaux tests Android, ajoutés aux 45 parcours existants.

## Audit des fonctions et confort d’utilisation — 11 octobre 2026

- Recherche dans les sous-dossiers des catégories et dans le dossier ouvert ; recherche globale des dossiers en plus des fichiers.
- Archives et formats non reconnus accessibles dans Documents ; accueil conservé avec les six catégories.
- Recherche séparée pour Fichiers et Notes, conservée lors des changements d’écran et recréations Android ; Retour ferme d’abord la recherche.
- Choix grille/liste et tri mémorisés.
- Téléchargements encore en cours, en pause ou en échec exclus des imports ; vérification renouvelée avant la copie.
- Opérations multiples : bilan des réussites et échecs, poursuite du lot après une collision, sélection des éléments non traités conservée.
- Indicateur ChatGPT visible, avec état de connexion ou de reconnexion et accès aux réglages ; icône Cut cohérente dans chaque espace.
- Liens ouverts depuis Cut et Notes respectés au lancement ; pas de nouvel onglet Cut dupliqué lors d’une recréation Android.
- Fermer une nouvelle note vide ne crée plus de note Sans titre.
- Une erreur de recherche de mise à jour n’empêche plus toute nouvelle tentative pendant 24 heures ; nouvelle tentative automatique possible après dix minutes au prochain démarrage.
- Huit nouveaux tests Android de non-régression, ajoutés à la suite navigateur, import HTML, Fichiers, Notes, Studio et vrais exports MP4 Cut.

Installation via Réglages, par-dessus la version existante avec la même signature Android. Les projets, fichiers, notes et paramètres MCP sont conservés.

## Cut Studio : automatisation native à la demande (2026-10-11)

- Le **Cut Vidéo intégré à CHK Agent Browser**, et non l'application Cut Vidéo indépendante, peut découper des vidéos MP4 depuis Fichiers CHK via une commande MCP.
- `browser_files_write_text` sur `CutVideo/cut_inbox.json` démarre l'export local Media3, dans un sous-dossier du navigateur, sans déplacer ni supprimer la source.
- `CutVideo/cut_status.json` contient l'état vérifiable (`running`, `completed`, `failed`, `cancelled`), le nombre de morceaux et le dossier de sortie.
- `CutVideo/cut_cancel.json` permet une annulation ciblée par identifiant de demande.
- L'interface Cut Studio propose un aperçu, les modes rapide/personnalisé/rogner, et une bibliothèque organisée par dossiers.
- Le planning MCP préexistant reste indépendant : aucune publication distante n'est considérée accomplie sans vérification sur le réseau.
- Ni Render ni l'application externe Cut Vidéo ne sont modifiés par cette mise à jour du navigateur.

NOUVEAU — Fichiers CHK organisés par catégories.

- Accueil « Mes fichiers » épuré : uniquement les six icônes Images, Vidéos, Audio, Documents, Téléchargements et Récents ; aucun dossier sous les icônes.
- Dossiers visibles et accessibles à l'intérieur de chaque catégorie, avec navigation et retour Accueil.
- Bouton Créer / Importer contextuel ; nouveaux dossiers classés automatiquement sous Categories/Images, Videos, Audio, Documents.
- Dossiers et MP4 historiques inchangés sur disque ; toujours accessibles depuis leurs chemins MCP et depuis les catégories, sans migration destructrice.
- Recherche, tri, import multiple, fichiers et corbeille conservés.

ACTUALISATION CUT STUDIO — X sans programmation : fiche prête à publier immédiatement, sans date/heure ni alarme Android. L'import MCP accepte X sans horodatage. Une publication ne passe à « Publié » qu'après confirmation explicite. Les rappels YouTube/TikTok/Instagram restent locaux et ne sont pas des programmations certifiées sur les réseaux.

NOUVEAU — Cut Vidéo directement dans CHK Agent Browser.

- Nouvel onglet Cut dans la navigation mobile, sans modifier l'application Cut Vidéo autonome.
- Découpe MP4 hors ligne : 15, 30, 60, 90 secondes ou personnalisée, début/fin, exports séquentiels Media3.
- Bibliothèque des extraits dans Fichiers CHK/CutVideo ; lecture et partage des MP4.
- Rappels Android locaux pour YouTube, TikTok et Instagram ; fiche de publication immédiate sans rappel pour X.
- Liens directs vers les outils de création des réseaux via la WebView existante, formulaires validables sur place.
- Copie des métadonnées et import depuis le presse-papiers ; gestion multi-comptes CHKNOIRSHADOW et QG.
- Suivi local « Programmé », « À publier », « Publié » ; l'état Publié est une validation manuelle, pas une garantie API.
- Copie du planning dans CutVideo/publications.json consultable via les outils MCP Fichiers.
- Tests Android de non-régression.

Fiabilité : conservation des données existantes, de la signature permanente et du mécanisme de mise à jour intégrée. Les publications ne sont pas expédiées automatiquement : elles doivent être confirmées sur les sites ou par une API autorisée.

Mise à jour générale de fiabilité du navigateur, des fichiers, des notes et du Studio.

- Récupération des onglets après interruption du moteur WebView ; message et action pour rouvrir la page.
- Téléchargements avec noms distincts et prise en compte du nom envoyé par le site.
- Imports multiples plus robustes, noms longs et Unicode conservés, destination du sélecteur restaurée.
- Import web lié à la page autorisée ; abandon des actions retardées lorsque la commande expire ou est arrêtée.
- Réponses MCP conservées en JSON complet ; dépassements explicites au lieu de réponses corrompues ou bloquées.
- Sauvegarde des notes pendant une rotation d’écran sans doublon.
- Annulation/relance des exports isolée ; protection supplémentaire des sources audio.
- Studio : file de sauvegarde, attente avant fermeture, arrêt des vérifications quand l’écran du Studio est masqué.
- Mise à jour : téléchargement identique réutilisé et vérification de l’APK en arrière-plan dans Réglages.

Même application et même signature Android. Installer par-dessus la version existante, sans désinstaller.
