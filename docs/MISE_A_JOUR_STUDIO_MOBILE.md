# Mise à jour CHK — navigateur, fichiers et Studio mobile

## Références examinées

Trois captures téléphone fournies le 9 octobre 2026 : navigation Chrome / Google (42 s), CapCut (48 s), gestionnaire de fichiers (28 s). Repères retenus : recherche compacte en haut, historique pendant la saisie, catégories et grille de fichiers, aperçu vidéo central, timeline horizontale et outils de montage en bas. L’identité de l’application reste CHK ; les résultats Google sont de vraies pages Web.

## Changements livrés

- Navigateur : accueil natif, raccourcis et favoris, reprise de navigation, suggestions issues de l’historique local, recherche vocale via le service Android disponible, barre supérieure compacte. Les onglets et les outils de navigation existants restent accessibles.
- Fichiers : tableau de bord du stockage CHK, catégories images/audio/vidéos/documents/archives/récents, miniatures, grille/liste, tri nom/date/taille, fil de dossiers, sélection multiple, copie/déplacement/corbeille, import du téléphone ou d’un fournisseur Android (cloud compris), entrée vers Studio.
- Studio : aperçu réel de la composition via Media3 CompositionPlayer, timeline vidéo et piste audio, import du téléphone/Fichiers, bornes de découpe, division au curseur, ordre/duplication/suppression, vitesse 0,25× à 4×, rotation, texte incrusté, huit filtres et fondu noir. Annuler/rétablir, sauvegarde automatique, projet JSON exportable et rechargeable.
- Formats : original, 9:16, 16:9, 1:1, 4:3, 3:4, 4:5, 2:1, 2,35:1 et ratio personnalisé entre 1:5 et 5:1. Adapter sans couper ou remplir par recadrage centré. Export local MP4 H.264/AAC en 480/720/1080 lignes ; la largeur suit le ratio.
- Audio : le son des vidéos est désormais conservé par défaut. Possibilité de le couper par plan ou globalement. Avec une piste audio externe, le son original est coupé par défaut ; le réactiver permet le mélange. Les audios externes sont consécutifs, commencent au début et doivent rester dans la durée du montage.
- Export : préparation hors du thread principal, notification annulable et verrou CPU temporaire renouvelé ; export dans un fichier temporaire avant remplacement atomique. Échec/annulation préservent le fichier précédent. Un rendu à la fois. Révisions du projet pour détecter un changement depuis MCP ou Studio.
- Lecture : lecteur Media3, ratios ajustés à l’écran, erreur explicite et ouverture dans une autre application si le décodeur manque.

## MCP sur le relais existant

`browser_files_catalog`, `browser_files_copy`, `browser_media_info`, `browser_media_codecs`, `browser_media_frame` s’ajoutent aux outils du navigateur, de Fichiers, Notes et Studio. Les projets exposent les nouvelles options de montage ; `expected_revision` permet d’éviter d’écraser une modification plus récente. L’inspection rend les dimensions, rotation, durée, pistes et disponibilité réelle des décodeurs. Les images vidéo sont limitées à 640 pixels et utilisent la keyframe la plus proche de l’instant demandé. Le partage d’aperçus doit être activé sur le téléphone.

Capacité annoncée `workspace_version=2`. Le relais conserve les opérations existantes pour les anciens APK et demande une mise à jour avant les nouvelles commandes. Aucun nouveau serveur payant ou compte CapCut nécessaire.

## Limites vérifiables

Ce Studio reprend les interactions essentielles de la référence, mais n’est pas une copie intégrale de CapCut : pas de catalogue de stickers/effets en ligne, de sous-titrage automatique, de détourage IA ni d’envoi sur des réseaux sociaux. CompositionPlayer est une API expérimentale Media3 ; les erreurs d’aperçu sont signalées. Les conteneurs et codecs réellement décodables dépendent d’Android et du téléphone ; ni « tous les formats » ni « toutes les tâches » ne peuvent être garantis. Les ratios n’impliquent pas la compatibilité du codec. Une image vidéo échantillonnée ne transcrit pas le son et ne remplace pas l’analyse de tous les frames.

L’éditeur vérifie les sources avec les extracteurs Android avant le rendu ; le lecteur Media3 peut prendre en charge davantage de conteneurs que l’éditeur. Import local : 512 MiB/fichier ; import distant MCP : 12 MiB/fichier et 32 MiB/lot. Montage : 40 plans / 20 minutes ; fichiers indexés : 5 000 par requête, pagination de 50. Copier un dossier récursivement n’est pas implémenté. Les catégories portent sur les fichiers importés dans CHK, et non sur tout le stockage privé des autres applications. Android 6 minimum, comme la version précédente du moteur vidéo.

Un arrêt forcé Android du processus interrompt le rendu ; les fichiers partiels restent masqués et ne remplacent pas le MP4 précédent. Le service d’export est non redémarrable : pas de reprise fictive à mi-rendu. Le projet actif est conservé ; les copies JSON permettent de garder plusieurs projets.
