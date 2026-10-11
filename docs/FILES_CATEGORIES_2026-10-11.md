# Fichiers CHK — Accueil par catégories

## Interface
- Sur **Mes fichiers**, les six icônes **Images / Vidéos / Audio / Documents / Téléchargements / Récents** sont seules affichées (plus la barre de navigation et le bouton d'ajout). Les anciens dossiers ne sont plus listés directement en dessous.
- Appuyer sur une catégorie pour consulter ses dossiers et ses fichiers.
- **Créer / Importer** depuis l'accueil demande d'abord la catégorie de destination. Dans une catégorie ou dans un dossier, l'action crée directement à cet endroit.
- Les quatre catégories modifiables rangent les nouveaux éléments sous `Categories/Images`, `Categories/Videos`, `Categories/Audio` et `Categories/Documents`.
- Les vues **Téléchargements** et **Récents** servent à l'accès et à l'import, pas à stocker des dossiers.

## Compatibilité sans perte
- Les répertoires déjà présents (par exemple `CutVideo`, `ALPHA_OMEGA_...`, `musique`) ne sont **ni déplacés ni renommés**.
- Leur contenu est détecté par type MIME et leurs dossiers sont rendus visibles dans la/les catégorie(s) appropriée(s). Les dossiers vides ou non classables restent retrouvables dans Documents.
- Ouvrir un dossier historique donne accès à ses contenus réels, mêmes chemins MCP, mêmes liens de prévisualisation et mêmes projets vidéo.
- Les nouveaux dossiers de catégorie n'affectent pas le dossier d'export `CutVideo` du module Cut Studio.
- Recherche et tri restent disponibles à l'intérieur des vues.
- Suppression toujours via corbeille récupérable et gestes explicites.

## Technique
Les outils MCP `browser_files_*` ne sont pas modifiés : leur racine physique est conservée. `WorkspaceCategoryView` ne change que la présentation de l'interface Android ; le scan est borné à 5 000 entrées par rafraîchissement. Pour éviter les doublons, le dossier technique `Categories` n'apparaît pas comme une carte racine.

## Tests
`WorkspaceIntegrationTest` contrôle une racine visuelle vide, la conservation physique d'un dossier vidéo ancien, la présence des dossiers historiques inclassables dans Documents, la création d'un nouveau dossier dans Vidéos et le refus de Récents comme emplacement d'écriture.
