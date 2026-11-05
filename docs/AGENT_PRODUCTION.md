# Production vidéo Android via Local 3

Le canal existant `desktop` reçoit `tool=production`. `operation=capabilities` expose la version APK installée, la liste des opérations et leurs paramètres. Le serveur PC 1.0.4 garde son protocole actuel.

## Parcours de production

1. `project_create` avec `request_id` stable et `name`. Conserver le `project_path` retourné.
2. `record_start` avec ce chemin, un nouvel identifiant et le `tab_id` obtenu par l'inventaire des onglets. L'agent continue à naviguer dans cette même WebView. `record_stop` termine l'encodage. Lire `record_status` jusqu'à `state=completed` et `valid=true`.
3. `asset_add` copie et vérifie les documents, images, vidéos ou musiques déjà présents dans CHK.
4. `narration_voices` expose les voix françaises réellement accessibles. `narrate` synthétise le texte ou les segments fournis. Le statut contient les WAV, leurs empreintes, leurs durées mesurées et leurs positions. Une voix qui demande Internet le déclare. La qualité dépend du moteur Android installé. Une erreur de voix reste un échec explicite. `narration_resume` reprend les segments interrompus et conserve les segments vérifiés.
5. `assemble` crée un projet CHK Studio éditable en 1080p. Les segments vocaux donnent les sous-titres et les points de baisse de musique. Les fondus, le mouvement optionnel, le titre et le générique utilisent les fonctions du moteur Studio. Une courte extension utilise la dernière image décodée et est déclarée dans `last_frame_hold_ms`. Au-delà de 15 secondes manquantes, l'agent doit ajouter des plans.
6. `export` ajoute ce snapshot à la file Studio. `export_status` retourne les vrais états. `verify` contrôle les images échantillonnées, la durée et les niveaux audio décodés.
7. `publication_prepare` prépare la fiche du MP4 vérifié. La programmation emploie ensuite les outils navigateur et `publication_submit` existants, avec domaine, compte et preuve affichée par la plateforme. La préparation seule conserve `remote_verified=false`.

Les captures, documents, médias, narrations, fichiers de montage, exports et fiches de publication sont regroupés sous `Projects/<request_id>`.

## Capture réelle de l'onglet

La WebView contrôlée est temporairement attachée à une Presentation Android privée. Son affichage matériel alimente directement la surface d'entrée du MediaRecorder H.264. Le MP4 reçoit les pixels composés par Android au fil des véritables changements de page.

L'enregistrement se limite à cet onglet CHK. Les contenus protégés restent protégés. Ce premier moteur enregistre une vidéo sans piste audio navigateur. La narration et les médias autorisés fournissent les pistes audio du montage. Une notification permet l'arrêt. Les durées sont bornées à dix minutes. Après interruption du processus, le fichier partiel reste signalé comme non vérifié.

Les captures visuelles de l'agent pendant l'enregistrement lisent les pixels de la même Presentation avec PixelCopy. À l'arrêt, la WebView retrouve son parent, ses dimensions et son identifiant.

## Workflows durables

L'envoi `desktop / tool=tasks / operation=submit` accepte `auto_resume=true`. Cette option est persistée par workflow et garde les anciennes tâches compatibles. Au redémarrage de Local 3, la reprise traite les étapes confirmées comme terminées, recommence les lectures interrompues et vérifie l'effet d'une écriture interrompue. Une écriture incertaine sans postcondition reste en pause.

Une vérification peut donner `timeout_ms` (200 à 900000) et `poll_ms` (200 à 5000). Le moteur relit la postcondition pendant ce délai. Il ne répète pas l'écriture qui la précède. Les erreurs de lecture ont deux reprises bornées. Les échecs de vérification définitifs restent à examiner par l'agent.

## Validation

`ProductionIntegrationTest` vérifie les dossiers durables, les références de médias, un enregistrement MP4 contenant réellement une page rouge puis bleue, la restauration de la WebView, la récupération d'actions interrompues, le projet Studio et les courbes de baisse musicale. La voix française du moteur réellement installé doit aussi être contrôlée sur le téléphone. Le fournisseur de synthèse annoncé est le moteur Android installé.
