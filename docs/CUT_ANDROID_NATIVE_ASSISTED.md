# CHK Cut — partage Android natif assisté (prototype)

## Constat vérifié
La vidéo reçue montre le partage réussi du MP4 de « Dans la brume » dans TikTok officiel. TikTok s'ouvre ensuite dans sa propre activité Android : le contrôle limité à CHK est alors perdu.

L'intégration inter-applications Android 13+ exige que l'application cible autorise l'hôte (certificat connu ou allowUntrustedActivityEmbedding). CHK ne peut pas forcer l'affichage de TikTok, YouTube ou Instagram dans la fenêtre de Cut. Le prototype est donc une expérience centralisée avec panneau CHK flottant, pas une vraie application embarquée.

## Parcours utilisateur
1. Cut > Mes vidéos > Partager + métadonnées > réseau > Partager Android.
2. Sans autorisation, proposition « Activer dans Android », « Partager sans pilotage », « Annuler ». L'utilisateur seul active le service CHK dans les réglages Android.
3. Avec le service connecté, l'utilisateur autorise une session ciblée de 20 minutes.
4. ACTION_SEND fournit le MP4 et les métadonnées à l'application officielle. Le presse-papiers CHK contient le titre, la description et les hashtags.
5. Le panneau « CHK · Retour / Arrêter » reste visible si le système accepte les overlays d'accessibilité.
6. Les actions MCP Local 3 sont transportées par android_action(action="desktop", args={tool:"social_native", operation:"..."}). Opérations : status, observe, screenshot, act (click, replace_text, tap, back), stop. Les actes exigent une observation récente et l'application choisie au premier plan.
7. Les mots de passe sont masqués. Aucun résultat d'import ni de publication n'est inventé. La dernière confirmation de publication reste contrôlée par l'utilisateur.
8. Après arrêt ou expiration, aucune inspection de réseau social n'est autorisée.

## Confidentialité et garde-fous
- Désactivé par défaut, permission Android via l'utilisateur uniquement, accès autorisé au seul réseau de la session.
- Aucun scan permanent de l'écran, aucune collecte de mots de passe, pas de contournement de CAPTCHA ou de protection native.
- Les services d'accessibilité non destinés à un handicap peuvent être soumis à des limites importantes de politique Google Play. Ce prototype nécessite une revue de conformité avant une distribution sur Play.
- Aucun changement du serveur PC Universal Control 1.0.4, des vidéos d'origine ni du partage Android classique.
- État exact du relais dans CutVideo/native_handoff.json, toujours platform_publication_confirmed=false jusqu'à preuve distante.

## Validation
Tester la compilation Android, lint, tests de non-régression et puis, sur téléphone, TikTok / YouTube / Instagram : import effectif MP4, copie des métadonnées, overlay, capture et inspection accessibles, activation/révocation du service, expiration à 20 min, absence de fuite vers un autre paquet, retour CHK et sauvegarde Cut. Les limites réseau ou système doivent être clairement rapportées.
