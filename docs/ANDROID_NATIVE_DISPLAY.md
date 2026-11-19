# CHK Browser — Android direct (écran virtuel Shizuku) — 19 novembre 2026

## But
Exécuter une application **déjà installée sur le téléphone** sur un second écran Android
rendu dans **la même application CHK Agent Browser**, sans nouveau serveur PC/Render,
sans créer de nouveau processus Android émulé et sans dupliquer les données des applications.

Ce n'est **PAS** une machine virtuelle Android ni un système Android isolé.
Les applications utilisent le véritable système Android et leurs comptes existants.
Un programme peut choisir de basculer sur l'écran principal, refuser l'écran secondaire,
masquer du contenu protégé ou exiger une autorisation supplémentaire.
Aucune compatibilité spécifique à HONOR / MagicOS n'est annoncée sans essai sur téléphone.

## Architecture
- `NativeAndroidActivity` : interface Java CHK, aperçus JPEG réels et gestes tactiles, dans l'APK existant.
- `com.chk.agentbrowser.nativeview.EngineClient` : bridge Binder Shizuku, connexion après autorisation du propriétaire.
- `DisplayService` : `UserService` shell Shizuku, possède un `VirtualDisplay` et un `ImageReader`.
- `DisplayEngine` : cycles de vie, liste d'applications existantes, lancement `am start --display <id>`.
- `InputBridge` : événements tactiles/clavier ciblés sur l'écran virtuel (pas le téléphone physique).
- `NativeDisplayMcp` : commandes Local 3 via le canal déjà existant `desktop / tool=android_emulator`
  avec préfixe `native_`, après **Autoriser Local 3 pendant 20 min**.
- Aucune route Windows, Render ou Supabase modifiée. QEMU reste disponible dans son ancien écran, sans effacer son disque.

## Configuration sur le téléphone
1. Installer Shizuku via le site officiel `https://shizuku.rikka.app/`.
2. Sur Android 11+, activer les options développeur et **Débogage sans fil**, puis démarrer
   Shizuku depuis le téléphone et suivre son jumelage sans fil. Ce service peut nécessiter
   un redémarrage manuel après un redémarrage du téléphone.
3. Ouvrir CHK Browser → **Android direct · rapide (Shizuku)**.
4. Appuyer sur **Autoriser Shizuku** puis **Créer l'écran**.
5. **Applications** → choisir une application déjà installée. Elle doit apparaître dans
   l'écran affiché *dans CHK*. Vérifier d'abord avec une application non sensible.
6. Pour ChatGPT : **Autoriser Local 3 à piloter l'écran pendant 20 min**.
7. **Arrêter** ne supprime aucune application et libère le second écran.

## Contrôle Local 3
`desktop` → `tool=android_emulator` et l'une des opérations :
- `native_status` (lecture des capacités, sans autorisation)
- `native_create` (écran 720 × 1280, 320 dpi)
- `native_list_apps` (liste des applications déjà installées)
- `native_launch` avec `package_name`
- `native_observe` retourne le vrai JPEG et `frame_id`, jusqu'à 40 Ko via encodeur adaptatif
- `native_tap` avec `frame_id, x, y`
- `native_swipe` avec `frame_id, x, y, end_x, end_y`
- `native_key` avec `key=home|back|enter`
- `native_text` avec `text`
- `native_release` libère l'écran

Les coordonnées visent la résolution originale de l'écran virtuel. Les gestes sont
refusés si le `frame_id` a expiré (30 s). La capture ne doit pas être déclarée
réussie sans une image transmise et décodée.

## Sécurité et limites
- Shizuku octroie un accès de niveau shell avec l'accord explicite du propriétaire ;
  ne pas accorder cet accès à d'autres applications non fiables.
- Les applications externes ne sont **pas copiées, modifiées, resignées ni clonées**.
- Pas d'import d'APK dans ce mode ; installer les applications normalement sur le téléphone.
- L'écran virtuel est créé avec les indicateurs `PUBLIC` et `OWN_CONTENT_ONLY` requis par Android ; **il n'offre pas l'isolation d'un système d'exploitation invité**. Les applications partagent les mêmes comptes et données que sur l'écran principal. Il faut éviter les comptes sensibles avant vérification sur le téléphone. Les actions MCP restent réelles.
- Le contenu DRM, les protections Play Integrity, les authentifications fortes et certaines
  applications sociales peuvent bloquer l'affichage ou le contrôle.
- Test réel du secondaire HONOR Android 16, du clavier, des captures et des gestes requis
  avant d'annoncer la fonctionnalité pleinement compatible. L'installation d'APK ne
  doit pas être confondue avec une preuve d'exécution.
- L'utilisateur ne doit pas activer QEMU et Shizuku simultanément pour éviter une
  surconsommation mémoire/batterie.

## Code open source utilisé
Composants Java de l'affichage virtuel, du bridge input et du service Binder adaptés de :
- `HakaI963/headless-android` (Apache-2.0) :
  https://github.com/HakaI963/headless-android
- Moteur d'origine : `HakaI963/ShadowAuto` (Apache-2.0)
- Shizuku SDK et app : https://github.com/RikkaApps/Shizuku (API MIT)
- Copie de la licence : `third_party/HeadlessAndroid-APACHE-2.0-LICENSE.txt`.

Les classes de clonage, de repackaging, de renommage, de ressignature et de
réinstallation de paquets du projet source ont été **exclues**.
