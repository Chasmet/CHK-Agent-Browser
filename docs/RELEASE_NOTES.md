NOUVEAU — Cut Vidéo directement dans CHK Agent Browser.

- Nouvel onglet Cut dans la navigation mobile, sans modifier l'application Cut Vidéo autonome.
- Découpe MP4 hors ligne : 15, 30, 60, 90 secondes ou personnalisée, début/fin, exports séquentiels Media3.
- Bibliothèque des extraits dans Fichiers CHK/CutVideo ; lecture et partage des MP4.
- Programmation de rappels Android et métadonnées par réseau (YouTube, TikTok, Instagram, X).
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
