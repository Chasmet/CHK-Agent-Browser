# Local 2 : reconnexion et diagnostic par canal

Le rapport de l'APK 338 indique un échec d'échange avant identification du PC,
avec plusieurs fins/reprises du service Android. Les événements anciens de
Render (fichiers et Studio) ne prouvent pas une connexion Local 2 actuelle.
Une présence Tailscale n'est pas une réponse du service MCP. Un HTTP 502
également renvoyé par l'adresse Funnel directe ne peut pas être réparé par
un simple changement de l'APK : le routage/service PC reste à diagnostiquer.

## Corrections Android

- Le bouton Reconnexion réveille désormais les deux canaux activés, au lieu
  d'appeler uniquement le client Render. Un changement de réseau réveille
  aussi Local 2. Aucune commande en cours n'est invalidée ou resoumise.
- Les demandes de maintien d'un service déjà actif deviennent idempotentes.
  Les transports arrêtés restent réactivables après changement d'adresse.
- Retour sur un écran CHK : reprise du service si un canal est activé.
- Le bouton de notification « Arrêter le MCP » désactive les deux canaux et
  annule la reprise. Avant correction, Local 2 restait activé et pouvait
  redémarrer après l'arrêt demandé.
- Les tentatives réseau échouées sont espacées progressivement (6 à 45 s).
  Les reprises du service après des arrêts rapprochés attendent 1 à 15 min,
  selon les restrictions Android, sans boucle de redémarrage immédiate.
- Les erreurs HTTP (notamment 401/403, 404, 429 et 502), DNS, TLS, délai,
  réponse JSON invalide, erreur RPC et réponse trop volumineuse sont
  distinguées. Aucun corps de réponse, URL privée ou message réseau brut
  n'est copié dans le diagnostic.
- Les compteurs et latences Local 2 sont séparés des mesures Render et
  explicitement comptés depuis le démarrage du processus. Le dernier échec
  sûr est conservé entre redémarrages ; les commandes Local 2 ont leur
  catégorie de journal distincte.
- Le rapport indique l'état foreground et la durée du service, sa dernière
  fin connue, un éventuel refus de démarrage et la reprise demandée.
  `onDestroy` n'est plus présenté comme une preuve qu'Android a tué le
  service. Sur Android 11+, l'historique système des fins du seul processus
  principal CHK est consulté, sans lire de traces ni d'autres applications.

La gestion de batterie reste sous contrôle du propriétaire. Aucun ajout de
permission, aucune modification d'URL/secret, aucun changement du relais PC,
de Render, des projets Studio ou du système de mise à jour existant.
L'application ne promet pas de contourner un arrêt forcé Android.

## Validation

`Local2RecoveryIntegrationTest` : réponses HTTP simulées mais protocole et
limites réellement exercés ; service foreground Android réel avec Render
 désactivé et Local 2 activé ; idempotence ; reconnexion sans changer l'époque
 d'une tâche ; arrêt via notification et absence de reprise après cet arrêt.
Le nouveau workflow teste ces scénarios sur Android 15/API 35, avec les
régressions du navigateur partagé et du protocole Local 2. Le workflow de
livraison exécute en plus toute la suite sur API 30 puis signe l'APK avec la
clé persistante et publie une vraie GitHub Release.

Références officielles consultées :
- https://developer.android.com/develop/background-work/services
- https://developer.android.com/reference/android/app/ActivityManager#getHistoricalProcessExitReasons(java.lang.String,int,int)
- https://developer.android.com/reference/android/app/ApplicationExitInfo
