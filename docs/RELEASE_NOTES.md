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
