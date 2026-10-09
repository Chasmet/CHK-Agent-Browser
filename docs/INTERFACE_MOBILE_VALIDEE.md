# Interface mobile validée — CHK Agent Browser

La version précédente ne respectait pas assez les exemples Chrome, gestionnaire de fichiers et CapCut. Le 9 octobre 2026, la nouvelle maquette à trois écrans a été validée par l’utilisateur, avec deux exigences supplémentaires : fluidité et navigation MCP visible.

## Comportement livré

- Accueil sombre avec recherche Google, vrais favoris locaux, cartes d’historique avec domaines courts et icônes locales des sites. Les liens de découverte ouvrent les services réels ; ce ne sont pas des actualités simulées.
- Fichiers : un seul en-tête, contenu clair, catégories compactes, dossiers en lignes, miniatures en grille, recherche escamotable, tri, création/import flottants, corbeille et opérations existantes conservées. La bibliothèque contient les fichiers importés dans CHK, pas une copie silencieuse de tout le téléphone.
- Studio : prévisualisation extensible, qualité accessible avant l’export, timeline proportionnelle à la durée avec clips, audio et textes effectivement présents, sélection/déplacement du curseur, zoom par pincement, six outils principaux et commandes de plan. La hauteur s’adapte aux petits téléphones. Les effets et les commandes existants restent accessibles.
- Correction de l’aperçu : les durées des sources vidéo/audio sont désormais fournies au lecteur de composition, comme l’exige Media3. Le mode SurfaceView compatible avec ce lecteur est utilisé.
- Découpe : curseurs de début/fin, contrôle de durée minimale et action « Tout le média ». L’import vidéo conserve la durée complète au lieu de tronquer silencieusement à dix minutes. Les limites explicites du moteur restent 40 plans et 20 minutes de montage.
- Export : multiplexeur MP4 natif Media3 et délai de surveillance adapté aux rendus lourds, résumé du **projet complet**, choix « Remplacer » ou « Nouvelle copie » lorsque le nom existe déjà, rendu temporaire protégeant l’ancienne vidéo en cas d’échec. Une vidéo créée peut être lue, partagée ou enregistrée par le sélecteur Android dans un emplacement du téléphone.
- Import multiple : collecte des médias et une seule sauvegarde du lot. Les médias impossibles à importer sont signalés ; les sauvegardes ne se concurrencent plus pour chaque vidéo.
- Navigation MCP : le navigateur visible montre l’action du connecteur, le clic/la saisie mettent en évidence la cible, le défilement est animé. L’accueil natif est masqué pendant le travail pour montrer la vraie page Google et son DOM. Les changements de projet MCP sont repris dans Studio avec contrôle de révision.

## Fluidité

Les miniatures utilisent deux décodeurs indépendants des opérations de fichiers et un cache borné à 12 Mio. La timeline est dessinée sur Canvas : le déplacement du curseur ne reconstruit pas la liste des vues et ne redécode pas les vidéos. L’accueil est réutilisé tant que favoris/historique n’ont pas changé. La recherche reste temporisée, les opérations de disque et l’analyse multimédia restent hors du thread d’interface.

Cela réduit les sources de ralentissement ; la cadence réelle dépend du téléphone, du codec et du média. Aucun objectif de FPS sur téléphone physique n’est présenté comme mesuré sur un émulateur.

## Vérifications prévues avant publication

Compilation debug et instrumentation, lint sans erreur, contrôles Java/JavaScript, tests Android de fichiers/notes existants, aperçu Media3 prêt, export réel audio+vidéo avec filtres/vitesse/ratio, montage de sept plans (21 secondes) et remplacement, dialogue présentant les deux choix d’export, clic MCP dans la vraie WebView visible exécuté une fois, vérification visuelle des écrans. La chaîne GitHub Actions doit réussir avant publication de l’APK signé avec la clé persistante.

## MCP et mise à jour

Le protocole et les outils de navigateur/Fichiers/Notes/Studio déjà déployés sont conservés. Le relais existant reste compatible ; les changements de visibilité et de fluidité se trouvent dans l’APK. Le travail apparaît dans l’onglet visible lorsque CHK est au premier plan. En arrière-plan, le service autonome existant conserve son fonctionnement.

Le package Android, le stockage et la clé de signature persistent. La mise à jour utilise les GitHub Releases publiques, sans clé API ou PAT dans l’application. Les fonctions propriétaires de CapCut et la prise en charge universelle de tous les codecs ne sont pas annoncées.

Les favicons locaux ont été récupérés par le service public Google S2 pour google.com, youtube.com, chatgpt.com et github.com ; ils servent uniquement à identifier les raccourcis des sites.
