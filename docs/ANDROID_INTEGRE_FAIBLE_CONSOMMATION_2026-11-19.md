# CHK Agent Browser — Android intégré, Play Store réel et faible consommation
Date : 19 novembre 2026 — statut : **prototype intégré en branche de test, non validé sur téléphone réel**.

## Demande utilisateur et critères non négociables

1. L'accueil de **la même APK CHK Agent Browser** propose deux entrées : **Navigateur classique** et **Android intégré**. L'accès aux sections Tor, Fichiers, Notes, CHK Studio et Cut reste inchangé.
2. Dans « Android intégré », afficher un **véritable écran Android interactif dans CHK**, avec accueil Android, navigation Retour/Accueil et accès à des **applications Android réelles** et au **vrai Google Play Store**. Ne pas simuler des icônes ni ouvrir les applications hors de CHK en prétendant les intégrer.
3. Permettre à l'agent Local 3 d'inspecter une capture **réelle**, de cliquer, balayer, saisir et vérifier les résultats, avec consentement et traçabilité.
4. Éviter la surconsommation : aucune VM active au démarrage, aucun keep-screen-on permanent, arrêt du rendu/capture en arrière-plan, libération explicite de l'écran, limite des captures et surveillance de consommation.
5. Conserver le même `applicationId`, les données, le mécanisme `UpdateManager` et la signature de mise à jour. Ne toucher ni aux fichiers ni au comportement de CHK Studio/Cut, Tor, Fichiers et Notes.

## Constat établi dans le dépôt

- `main` au commit `587e51bd9418ad0f15ff30825403b9088d31b216` a retiré **QEMU et l'ancien espace Android**, ainsi que le mode « Android direct » Shizuku, le 19/11/2026, à la demande de l'utilisateur en raison de la batterie.
- Le code historique `11da0056653dcb7dede1e893fef64311034fca6d` disposait d'un deuxième écran Android via `VirtualDisplay` + Shizuku et de commandes `native_*`. Il lançait les applications **du système hôte** et n'était pas un Android invité indépendant.
- Ce mode ancien imposait `FLAG_KEEP_SCREEN_ON` dans `NativeAndroidActivity` et possédait un wakelock dans `DisplayEngine`, avec capture JPEG périodique (`refreshFrame` à environ 850 ms). **Il ne faut pas le réintroduire tel quel**.
- `MainActivity` (WebView) ne peut pas exécuter des APK ni constituer un système Android certifié avec Play Store.
- La CI `.github/workflows/android.yml` vérifie déjà la signature de release, compile les APK et refuse les anciennes bibliothèques QEMU. Le mécanisme de mise à jour signé existe ; ne pas remplacer ou contourner sa clé.

## Faisabilité et architecture retenue pour le prototype

### Option A : deuxième système Android embarqué (QEMU / ROM AOSP)

C'est la seule voie locale qui fournisse un OS invité complet distinct, mais elle demande une image système, RAM/CPU/stockage et charge plus la batterie. **Play Store non garanti** : les images AOSP ordinaires n'incluent pas les applications Google et une image « Google Play » certifiée de l'émulateur Android Studio n'est pas une image générique librement embarquable dans une APK. Cette voie est **écartée pour la version basse consommation**.

### Option B : véritable deuxième écran du système + profil séparé

Meilleur compromis *à valider sur le HONOR Android 16 avant intégration* :

- Utiliser un `VirtualDisplay` Android créé **uniquement sur demande** et un service local de contrôle autorisé explicitement par l'utilisateur (par ex. Shizuku via débogage sans fil). Les applications doivent être **visibles dans CHK**, et jamais simplement lancées sur l'écran principal.
- Pour un Play Store et des connexions distincts, utiliser si le fabricant le permet un **profil de travail / autre profil Android** géré par le système. Le Play Store dans ce profil doit être **réel et fonctionnel**, non une reproduction graphique. La création du profil, son activation et son compte Google exigent des actions de l'utilisateur ; le programme ne peut pas s'arroger ces droits.
- Les autorisations de lancement dans le profil, de rendu sur écran secondaire et de saisie sont soumises à Android/MagicOS. Ne pas déclarer le mode compatible ni « avec Play Store » avant un test réel sur l'appareil.
- Le moteur Live est isolé dans `com.chk.agentbrowser.androidspace`, les modules existants sont laissés intacts. Les commandes MCP sont dédiées, bornées et soumises au consentement.
- La capture utilisée pour Local 3 est **à la demande**, pas une boucle vidéo permanente ; affichage direct dans une `Surface` si supporté, sinon capture limitée au premier plan uniquement.
- Pas de `FLAG_KEEP_SCREEN_ON`, pas de wakelock CPU permanent. Stopper les captures au `onPause`. Arrêter et détruire le `VirtualDisplay` au départ de l'espace, sauf session agent explicitement autorisée et limitée dans le temps.
- Pas d'emplacement ou fichier de données CHK Studio modifié, pas d'accès transversal au stockage personnel des autres applications.

Cette option est **un affichage Android natif**, pas une émulation d'un OS distinct. Un profil Android séparé peut assurer une isolation au niveau système, mais pas une compatibilité universelle ni la disponibilité de Play Store. C'est la seule approche locale plausible permettant un faible surcoût CPU sans installer une seconde ROM.

### Option C : appareil Android distant diffusé dans CHK

Un véritable Android distant avec image Google Play adaptée pourrait être diffusé par vidéo interactive dans CHK et piloté par Local 3, avec consommation locale réduite. Mais cela nécessite un hébergement compatible VM/accélération (généralement payant), distinct du simple relais Render actuel, et des garanties sur la protection des comptes. Il **ne tourne pas sur le téléphone** : ce n'est donc pas la solution retenue pour cette demande.

## Précision bloquante — TikTok reste affiché et pilotable DANS CHK

Exemple utilisateur non négociable : depuis l'écran d'accueil **Android intégré** dans CHK Agent Browser, toucher l'icône **TikTok** ouvre l'application Android TikTok réelle **dans la zone d'affichage intérieure de CHK**. Les écrans TikTok, menus, vidéos, champs, connexion, boutons de publication et navigation doivent rester visibles et actionnables **dans la même fenêtre CHK Agent Browser**, comme sur l'illustration d'un Android invité. Le téléphone ne doit **jamais basculer vers l'activité TikTok sur son écran principal**. Cela s'applique aussi à Play Store et aux autres applications que l'utilisateur veut piloter.

**Tests éliminatoires :**
- Lancer TikTok depuis l'environnement intérieur CHK ; vérifier visuellement que TikTok reste dedans, pendant l'ouverture, la connexion, le défilement et les dialogues d'upload.
- Vérifier la même chose en pilotant par Local 3 : capture réelle dans la zone CHK, clic, saisie, vérification, puis Retour et Accueil renvoyés à l'environnement Android intérieur.
- Tester les intents et liens externes : ils ne doivent pas provoquer de sortie silencieuse hors de CHK. Si Android interdit de les afficher sur l'écran secondaire, afficher **« application incompatible avec cet écran intégré »**, et ne jamais afficher une réussite fictive.
- La fermeture de l'environnement intégré restitue CHK navigateur classique et libère les ressources.
- Le fait que l'application TikTok existe déjà sur l'hôte ne constitue **pas une preuve** qu'elle se lance dans CHK : seul un rendu/contrôle réellement vérifié est une réussite.

## Plan d'intégration conditionné par la preuve

1. Créer un écran d'essai **dans le dépôt existant** avec arrêt immédiat, aucune activation au démarrage, *sans retirer* le navigateur classique.
2. Tester le rendu d'une application tierce **sans quitter CHK**, gestes, clavier, Retour/Accueil, reprise et arrêt, sur l'appareil HONOR Android 16 réel.
3. Tester ensuite un **profil Android distinct** et son **Play Store réel**, la connexion Google, l'installation et le lancement d'une application sous ce profil. Ne jamais déclarer « Play Store opérationnel » avant cette preuve.
4. Exposer `android_status`, `android_observe`, `android_tap`, `android_swipe`, `android_text`, `android_home` et `android_stop` dans Local 3 **uniquement** après une preuve de fonctionnement et une autorisation de contrôle dédiée.
5. Mesurer consommation et comportement sur écran éteint / changement d'espace. Pas de publication si l'affichage impose le maintien permanent de l'écran ou ne libère pas les ressources.
6. Exécuter `./gradlew assembleDebug`, lint, tests d'intégration et les CI de régression Studio/Tor. Publier uniquement une release signée avec l'ancienne clé et une vérification de mise à jour sans perte de données.

## Critères d'acceptation (preuve indispensable)

- L'utilisateur ouvre CHK et trouve **Web classique** et **Android intégré** ; Studio/Cut et les données existantes restent accessibles.
- Sur le téléphone réel, une application installée dans le bon profil est rendue **dans l'écran CHK**, avec clics et clavier fonctionnels sans basculer dans une autre application.
- Google Play est ouvert **dans cet écran**, charge son interface réelle, permet la connexion et l'installation dans le profil attendu. Sans cela, nommer le mode « Android direct » et **ne pas afficher de fausse icône Play Store**.
- Après fermeture, aucun `VirtualDisplay` ni rafraîchissement périodique ne tourne, aucun `FLAG_KEEP_SCREEN_ON` n'est maintenu.
- La commande MCP rapporte une capture de provenance réelle ; absence d'image = erreur, jamais succès présumé.
- Release APK réelle, même package/signature et version supérieure ; Studio et tous les modules régressions verts.

## Ce que **cette branche ne fait pas**

Cette branche **n'implémente pas** l'environnement Android demandé, **n'active pas** Play Store ni Shizuku, **ne compile pas** de nouvel APK et **ne prétend pas** avoir vérifié l'affichage sur le HONOR. Elle fixe la solution testable et les conditions qui empêchent de répéter les échecs du 18 et 19 novembre. La branche principale et CHK Studio sont inchangés.

Sources officielles à revalider lors de l'implémentation :
- [Android Virtualization Framework](https://source.android.com/docs/core/virtualization)
- [Images AVD compatibles Google Play](https://developer.android.com/studio/run/managing-avds)
- [Politique des applications dans les conteneurs Android](https://support.google.com/googleplay/android-developer/answer/18265346)
