# Audit général — CHK Agent Browser — 10 octobre 2026

Base : `090472733dc9c192857daddfbc09fc8edf3825c7`, Release 1.0.127.
Sauvegarde : `backup/audit-general-20261010`.

L'audit couvre le navigateur visible et autonome, les transferts, Fichiers, Notes, Studio, le cycle de vie Android, les commandes MCP, les mises à jour et le workflow de publication. Il ne constitue pas une garantie d'absence de tout défaut sur tous les téléphones et sites.

## Défauts confirmés et corrections

| Domaine | Défaut constaté dans les sources | Correction |
|---|---|---|
| WebView | Absence de traitement de la disparition du processus de rendu | Remplacement de l'onglet affecté, destruction de la WebView inutilisable, conservation de l'adresse et action explicite pour rouvrir ; prise en charge dans les deux moteurs |
| Navigation | Échecs réseau affichés uniquement par la page d'erreur système | Message natif avec une action pour réessayer ; les erreurs de sous-ressources ne déclenchent pas ce message |
| Téléchargements | Même destination utilisée deux fois ; nom Content-Disposition ignoré | Noms distincts, prise en compte des fichiers existants et des téléchargements encore en cours, nom fourni par le site conservé |
| Transferts MCP | Vérification de la destination avant la préparation uniquement | Nouvelle vérification après préparation, transfert lié au document, identifiant de transfert et contrôle de durée de vie avant chaque injection |
| Commandes différées | Des continuations pouvaient agir après expiration ou arrêt | Callback avec état actif ; garde avant clic différé, import, attente, commande Fichiers/Notes et téléchargement |
| Réponses MCP | Coupure arbitraire à 11 000 caractères, y compris au milieu d'un JSON ; taille UTF-8 non contrôlée avant mise en file | JSON complet jusqu'aux limites réellement acceptées par le relais ; erreur explicite pour les dépassements, sans résultat corrompu ni file bloquée |
| Import local | Un fichier défaillant interrompait le lot ; noms longs impossibles ; destination perdue après recréation Android | Traitement indépendant par fichier, bilan avec réussites/échecs, noms compatibles Unicode avec extension, état du sélecteur sauvegardé |
| Notes | Une recréation pendant une première sauvegarde pouvait produire deux notes ou un conflit artificiel | Opération d'enregistrement conservée pendant la recréation, identité/révision transmises au nouvel éditeur, sauvegarde du dernier texte |
| Studio / sauvegarde | Appels de sauvegarde concurrents avec la même révision ; fermeture immédiate | File de sauvegarde du dernier état et attente de fin lors du retour |
| Studio / batterie | Vérifications régulières du projet après passage en arrière-plan | Arrêt du ticker et de ses relectures lorsque l'activité n'est plus visible |
| Studio / exports | Une préparation ou une vérification de rendu annulée pouvait finaliser l'export suivant via des champs partagés | Numéro d'export et fichiers capturés par tâche ; finalisation protégée, callbacks obsolètes ignorés |
| Studio / sources | La sortie pouvait désigner un MP4 utilisé comme source audio | Refus explicite de cette destination |
| Reconnexion | Toutes les variations de capacités réseau déclenchaient reconnexion et journalisation | Écoute du réseau par défaut lorsque disponible et déduplication des changements de transport/validation |
| Mise à jour | Téléchargements répétés d'une même version ; vérifications de signature répétées sur le fil UI | Réutilisation du téléchargement actif, vérification du fichier terminé hors UI, mémorisation du retour d'autorisation d'installation |

## Distribution et conservation des données

L'identifiant `com.chk.agentbrowser`, les espaces de stockage, SQLite, les préférences MCP et le mécanisme de signature permanent sont conservés. Aucune migration destructive ni suppression de données n'est ajoutée. Le dépôt conserve ses dépendances existantes : Java, minSdk 23, targetSdk 34 et compileSdk 36, requis par le Studio déjà présent.

Le workflow existant est conservé : compilation debug, lint, tests Android sur émulateur API 30, récupération de la signature existante par GitHub OIDC, compilation release, vérification de signature puis publication d'une véritable GitHub Release. Aucun secret ou PAT n'est ajouté dans l'APK. Aucun serveur Render supplémentaire n'est créé et aucune route d'une autre application n'est modifiée.

## Validation exigée avant publication

- `bash validation/check.sh` : scripts réellement embarqués, formulaires, sélecteurs, clic vérifié, reconnexion et chemins.
- `./gradlew --no-daemon assembleDebug lintDebug` : compilation et analyse Android.
- `./gradlew --no-daemon connectedDebugAndroidTest` : tests existants, dont import HTML réel et exports MP4 avec décodage des images, plus tests de non-régression de cet audit.
- `./gradlew --no-daemon assembleRelease` et `apksigner verify` avec la signature existante vérifiée par empreinte dans `signing/prepare_ci.py`.

Les nouveaux tests couvrent JSON long, dépassement UTF-8, noms Unicode et doublons, récupération d'onglet, état du sélecteur, note pendant recréation, comparaison de version, annulation/relance d'export et refus d'écraser une source audio. Le test de récupération d'onglet appelle le chemin de récupération sur une vraie WebView ; il ne simule pas une panne matérielle du téléphone.

La compilation locale a été tentée mais le réseau du conteneur ne permet pas le téléchargement de Gradle. La validation complète est donc exécutée dans GitHub Actions ; la publication reste conditionnée au succès de tous les contrôles.

## Limites restant à vérifier sur le téléphone

- Veille profonde et politiques batterie Honor/Huawei : le service Android existant reste nécessaire ; téléphone éteint ou arrêt forcé incompatibles avec le fonctionnement MCP.
- WebView visible et autonome : cookies partagés, mais états JavaScript distincts. Un formulaire non envoyé ne devient pas automatiquement identique dans les deux contextes.
- Certains sites refusent des événements de fichier synthétiques ; sélection manuelle CHK/Android disponible. Injection MCP limitée à 32 Mo par lot ; import local à 512 Mo par fichier.
- Une note dont la sauvegarde n'a pas encore atteint le stockage ne peut pas être garantie après un arrêt forcé du processus. La correction testée vise la recréation d'activité (rotation/configuration).
- Les filtres GPU du Studio restent appliqués à l'export ; l'aperçu direct conserve les limitations documentées de la version précédente.
- Installation finale et conservation effective des données sur l'appareil de l'utilisateur nécessitent son installation de l'APK. Ne pas désinstaller la version existante.

Références : [cycle de vie du moteur WebView](https://developer.android.com/develop/ui/views/layout/webapps/handle-termination), [DownloadManager](https://developer.android.com/reference/android/app/DownloadManager).
