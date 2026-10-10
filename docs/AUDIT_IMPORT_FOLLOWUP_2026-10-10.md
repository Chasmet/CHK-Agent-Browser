# Suite de l'audit : import navigateur

Base : `d8038ee97bbf3b3df165e0116d9207f4203364fa`. Les ajouts existants relatifs aux téléchargements Grok sont conservés. Ce correctif ne modifie pas la branche `feature/cut-studio-20261010` ni ses nouveaux écrans.

## Blocage constaté

Les exécutions Actions 128 et 130 compilent et passent l'analyse Android, puis échouent uniquement sur `BrowserMediaUploadTest.uploadFromWorkspaceReachesHtmlFileInput` (28 tests réussis sur 29). La publication reste donc bloquée.

La page HTML synthétique utilise une base HTTPS mais un historique nul : `loadDataWithBaseURL` donne alors une adresse d'historique `about:blank`. Elle ne représente pas une page HTTPS normale et entre en conflit avec la vérification de destination ajoutée par l'audit. Le test fournit désormais la même adresse pour la base et l'historique, conformément à l'[exemple Android](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content). La vérification de destination reste active.

## Améliorations

- Chaque chargement principal d'une WebView reçoit une version de document, y compris un rechargement à la même adresse.
- Un import déjà en attente dans le moteur de transfert est refusé si ce document est remplacé avant l'injection ou pendant la lecture des fichiers.
- Les refus distinguent une page remplacée, un onglet fermé, un champ absent et un champ n'acceptant qu'un fichier. Le message indique comment reprendre l'action.
- Le nouveau test recharge réellement une WebView à la même adresse pendant qu'un import attend. Il exige un échec explicite, zéro fichier reçu par le nouveau document et aucun état temporaire restant.

## Validation

`git diff --check` est exécuté localement. La compilation, l'analyse Android et les 30 tests instrumentés sont exécutés sur la branche d'audit via le workflow existant. Leur résultat doit être vérifié dans GitHub Actions avant intégration ; aucun test ni garde de publication n'est retiré.
