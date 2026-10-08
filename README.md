# CHK Agent Browser

Navigateur Android Java avec liaison MCP via le relais Render existant. Onglets, favoris, historique, téléchargements et mises à jour signées via GitHub Releases.

## Travail autonome

Dans Réglages, activer une fois **Mode autonome**. Cet accord reste enregistré après fermeture, redémarrage et mise à jour. Tous les travaux demandés via ce connecteur sont concernés. Désactiver le switch revient au mode de confirmation par commande ; **Arrêter le MCP** dans la notification révoque la connexion immédiatement.

Les permissions du plugin ChatGPT et celles de l’APK sont indépendantes. L’APK ne peut pas modifier les confirmations imposées par ChatGPT. Les descriptions des outils du relais doivent refléter le mode autonome ; le serveur Render se trouve dans `Chasmet/Modeliseur-3d`, branche `agent/trellis-third-tab-auto-update`, module `backend/browser_relay.py`.

## Connexion écran éteint

La session utilise un service au premier plan de type `specialUse`, adapté au contrôle distant interactif, avec notification d’arrêt. Un verrou CPU borné est renouvelé pendant la session. Le retour d’un réseau déclenche immédiatement une tentative de reconnexion ; les erreurs utilisent un délai progressif plafonné à 30 secondes. Un résultat non livré reste en attente et est renvoyé sans refaire le clic ou la saisie. Les accusés de réception du relais sont idempotents ; une commande expirée ne bloque pas les commandes suivantes. Une commande interrompue avec résultat inconnu n’est pas rejouée automatiquement.

Dans Réglages, **Protéger la connexion écran éteint** ouvre la demande Android ciblée d’exemption de batterie. Sur Honor/Huawei, autoriser également le lancement automatique et l’exécution en arrière-plan dans les réglages système. Une session permanente consomme davantage de batterie. Sans exemption, Doze peut couper le réseau et ignorer les wake locks. Aucune application ne peut fonctionner lorsque le téléphone est totalement éteint ou après un arrêt forcé Android ; reprise au démarrage lorsque le système l’autorise, sinon à l’ouverture de l’application.

## Navigation et formulaires

La lecture MCP renvoie du texte et les sélecteurs CSS des contrôles visibles, sans valeurs des mots de passe. La saisie prend en charge les champs natifs et les zones éditables, avec événements compatibles avec les formulaires React. Les champs password, file, hidden et readonly sont bloqués. La sélection manuelle de fichiers est disponible dans le navigateur visible ; le moteur de fond n’automatise pas le sélecteur Android.

## Validation et APK

`bash validation/check.sh` vérifie les délais de reconnexion et exécute les scripts réellement livrés avec des cas de formulaire natif, champs protégés, sélecteurs et tailles de réponse. GitHub Actions compile et vérifie l’APK avec `assembleDebug lintDebug`, puis construit la Release avec la signature permanente existante. Les versions sont calculées avec le numéro croissant du workflow.

La connexion MCP, le package et la signature sont conservés pendant une mise à jour. Installer via **Réglages > Rechercher et télécharger la mise à jour**, ou depuis Releases.

## Vérification sur téléphone après installation

1. Activer le mode autonome et la protection batterie, conserver la même adresse MCP.
2. Vérifier lecture, clic et saisie sans dialogue Android par commande.
3. Éteindre l’écran pendant 15 minutes puis lancer une lecture MCP depuis ChatGPT.
4. Couper le réseau, le rétablir et vérifier la reconnexion et le résultat en attente.
5. Passer entre navigateur et ChatGPT pendant une commande : pas de double exécution.
6. Arrêter le MCP : aucune commande ne doit être exécutée ; réactiver puis redémarrer le téléphone pour vérifier la reprise.

Références Android : https://developer.android.com/training/monitoring-device-state/doze-standby et https://developer.android.com/develop/background-work/services/fgs/service-types
