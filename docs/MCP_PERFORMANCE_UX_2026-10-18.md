# Mise à jour MCP / fluidité — 18 octobre 2026

## Périmètre livré
- Le navigateur visible conserve ses onglets et n'est jamais redirigé silencieusement vers la page autonome.
- « Reprendre la page MCP » ouvre un nouvel onglet, sélectionne l'onglet correspondant ou demande une confirmation si les douze emplacements sont occupés.
- Les onglets restaurés sont initialisés sans requête réseau, à l'exception de l'onglet sélectionné.
- La grille d'onglets affiche une favicon si la WebView en fournit une ; le menu et les autres espaces sont préservés.
- La barre de navigation mobile se masque et réapparaît lors du défilement sur le Web uniquement.
- Les sélections de fichiers modifient uniquement la carte concernée au lieu de vider et reconstruire la liste.
- Le polling reste compatible avec le relais Render actuel : plus rapide à l'avant-plan, normal en arrière-plan, rétrogradation automatique lors des erreurs.
- Le diagnostic expose des mesures réelles sans imprimer les jetons, cookies ou URL privée.
- Le mode éco est opt-in, car la limitation du verrou de veille peut retarder les commandes lorsque l'écran est éteint.

## Architecture et compatibilité
- Aucune modification des routes Render existantes (/agentbrowser/*).
- Aucun changement des contrats MCP distants ou du package Android com.chk.agentbrowser.
- L'application conserve le mécanisme GitHub Releases/UpdateManager, la clé de signature persistante et les données dans le stockage privé.
- Les contrôles de sécurité de l'agent, notamment la vérification de la page et le consentement aux captures, sont conservés.

## Vérifications requises avant publication
1. bash validation/check.sh
2. ./gradlew --no-daemon assembleDebug lintDebug
3. ./gradlew --no-daemon connectedDebugAndroidTest (émulateur Android API 30)
4. Signature de l'APK Release et dépôt automatique dans GitHub Releases.
5. Vérification sur téléphone : reprise d'onglets, passage de premier plan à arrière-plan, veille, sélection de fichiers, défilement, import volumineux.

## Limitations
- La migration de la liste Fichiers vers RecyclerView + DiffUtil reste à planifier séparément : le changement est structurel et exige des tests sur grands volumes.
- SSE/WebSocket/long polling n'a pas été activé car cela modifierait le relais Render partagé ; un déploiement serveur isolé et versionné est nécessaire.
- Cette mise à jour n'affirme pas de performances mesurées sur téléphone réel tant qu'un essai n'a pas eu lieu.
