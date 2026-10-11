# Clavier agent — moteur de saisie MCP

Cet outil est réservé à l’agent. Il ne remplace pas Gboard et n’ajoute ni clavier visuel, ni service d’accessibilité, ni clavier Android système. Les autorisations MCP existantes et la connexion du propriétaire sont conservées.

## Ce que l’agent peut faire

- Inspecter les champs ordinaires de la page web ou du dernier écran CHK conservé en mémoire, y compris ses dialogues de création, renommage, Notes, Studio et Cut.
- Remplacer, insérer ou effacer du texte, sélectionner une partie du champ, déplacer le curseur, passer au champ suivant/précédent.
- Saisir des nombres ; une virgule décimale est convertie dans les champs numériques qui attendent un point. Les filtres et limites du champ sont respectés.
- Cocher, choisir une option et cliquer sur un bouton ou un élément de liste **natif CHK** inspecté.
- Remplir jusqu’à 25 champs par commande avec un bilan individuel. Un lot n’envoie pas automatiquement le formulaire.
- Annuler/rétablir les huit dernières modifications agent du champ, tant que sa valeur n’a pas été changée ailleurs. Historique en mémoire seulement, sans collecte du presse-papiers.
- Sur le web, contrôler la valeur de nouveau après 160 ms pour repérer les corrections ou refus des formulaires React/Vue et autres scripts.

Les mots de passe, champs cachés et fichiers ne sont pas exposés ni saisis. Utiliser les outils d’import existants pour les fichiers. Un écran natif détruit par Android doit être rouvert ; l’outil ne pilote pas les autres applications du téléphone.

## Outils du relais existant

| Outil | Usage |
|---|---|
| `browser_keyboard_inspect(target="web", offset=0)` | Jusqu’à 60 contrôles, contexte et sélecteurs uniques ; suivre `next_offset` pour un grand formulaire |
| `browser_keyboard_input(...)` | Une modification, un choix, une touche ou un clic natif |
| `browser_fill_fields(fields, target, expected_context, expected_host)` | Lot de 1 à 25 champs avec réussites et erreurs explicites |

`target` vaut `web`, `native` ou `auto`. Pour les écrans internes, préférer `native`. Les contrôles retournent `selector`, `context`, type, valeur limitée à un aperçu, sélection et longueur. Les positions du curseur comptent les points de code Unicode : un emoji simple compte comme un caractère.

Les sélecteurs uniques restent associés au contrôle observé. Après un changement de page, de dialogue ou de contrôle, inspecter de nouveau. Fournir `expected_context` pour un lot ou une action sur le champ actuellement ciblé ; `expected_host` peut également fixer le domaine web.

Opérations : `replace`, `insert`, `clear`, `select`, `key`, `check`, `select_option` et `click` (natif seulement). Pour `select_option`, `text` est la valeur HTML ; pour un Spinner natif, utiliser en priorité son index `options[].value`. Un libellé exact est également accepté s’il ne correspond pas déjà à un index valide.

Touches : `SELECT_ALL`, `BACKSPACE`, `DELETE`, `LEFT`, `RIGHT`, `HOME`, `END`, `TAB`, `SHIFT_TAB`, `ENTER`, `UNDO`, `REDO`.

Entrée insère un retour à la ligne dans un champ multiligne. Dans un champ natif simple, elle utilise l’action de l’éditeur déclarée par l’application. Dans un champ web simple, elle transmet une vraie touche Android au WebView. **Une touche transmise ou un bouton cliqué ne confirme pas l’envoi, l’enregistrement ou la publication** : relire le résultat. Après un résultat incertain, ne pas rejouer automatiquement l’action.

## Compatibilité avec le connecteur déjà installé

Le moteur est également accessible avec l’outil existant `browser_type`, sans changer l’adresse MCP :

```json
{
  "selector": "chk://keyboard",
  "text": "{\"target\":\"native\",\"operation\":\"inspect\"}"
}
```

Le champ `text` contient la requête JSON du clavier. Exemple de contenu après inspection, en remplaçant le contexte et le sélecteur par les valeurs réellement obtenues :

```json
{
  "target": "native",
  "operation": "replace",
  "expected_context": "native:contexte_observe",
  "selector": "native:contexte_observe:champ_observe",
  "text": "Dossier exemple"
}
```

Le transport de compatibilité conserve la limite de 8 000 caractères de `browser_type`. Les nouveaux outils négocient `keyboard_version=1` et demandent une mise à jour à un ancien APK avant de mettre une commande en file.

Un lot peut avoir un transport réussi et des champs en échec : lire `succeeded`, `failed` et chaque `results[].ok`. Les nouveaux outils renvoient ces résultats comme JSON structuré. Le transport ancien garde l’enveloppe habituelle `ok/result`, dont `result` est ce même JSON.

## Vérification

Quatorze nouveaux tests Android utilisent de vrais EditText/InputConnection, dialogues, listes, Spinner, sauvegarde SQLite de Notes et WebView. Ils couvrent Unicode, curseur, annulation/rétablissement, nombres et limites, écran conservé mais en pause, lots partiels, contrôles protégés/périmés, compatibilité avec Fichiers, annulation de commande, action Entrée, événements web, rejet différé, iframe de même origine, shadow DOM, texte enrichi et vraie touche Android.

Les tests du relais couvrent aussi la déclaration MCP, les limites de requête, l’isolation de la file, la négociation de version et le transport des résultats partiels. La compilation, le lint, les 59 tests Android et la signature Release doivent être attestés dans GitHub Actions avant publication.

Références : [InputConnection Android](https://developer.android.com/reference/android/view/inputmethod/InputConnection), [cycle de vie des activités](https://developer.android.com/reference/android/app/Application.ActivityLifecycleCallbacks).
