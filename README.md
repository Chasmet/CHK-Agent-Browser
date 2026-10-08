# CHK Agent Browser

Navigateur Android Java sans frais d'API IA. Navigation WebView sur le téléphone, onglets, favoris, historique, copie du texte de la page après accord, téléchargements, mises à jour via GitHub Releases.

La liaison MCP est prévue pour la prochaine étape et n'est **pas activée** dans la V1.

Les versions Release nécessitent **une seule clé Android permanente** conservée dans GitHub Secrets. Aucune clé n'est demandée dans l'APK. Version, signature et procédure décrites dans le workflow .github/workflows/android.yml.

Installation: ouvrir l'onglet Actions du dépôt et récupérer l'APK Artifact. L'APK debug a un package séparé de l'APK Release. Une Release signée et installée peut être mise à jour sans perte de données si le certificat reste identique.
