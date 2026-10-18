# CHK Agent Browser — formulaires et tâches MCP vérifiées

Mise à jour du 18 octobre 2026.

## Moteur local de tâches

Les 50 étapes maximum peuvent désormais utiliser `get_form`, `wait_for_element`,
`type`, `select_option`, `check`, `click_verified` et `upload_file`, en plus des actions précédentes.

Les changements de champs, les choix, les clics et les importations **nécessitent toujours**
une étape `verify` distincte et en lecture seule. Un clic vérifié doit aussi définir
`expected_selector`, `expected_text` ou `expected_url_contains`.

Les importations depuis les fichiers CHK demandent `expected_host`, `selector`,
une liste de 1 à 8 fichiers explicitement autorisés et une `verify` en lecture seule.
Le sélecteur natif confirme uniquement que le champ HTML a reçu les fichiers.
L'acceptation par le serveur doit être vérifiée séparément.

Les délais du moteur sont adaptés : 45 s pour les actions ordinaires, 35 s pour
`wait_for_element` et les vérifications, 180 s pour la préparation/l'envoi de fichier.
Une opération dont le résultat reste inconnu devient `uncertain` : aucune nouvelle
exécution automatique ne doit avoir lieu sans intervention explicite.

## Formulaires dynamiques

`get_form` inclut `forms`, `dynamic_controls` et `validation_errors`.
Les éléments retournés possèdent un `selector` CSS quand disponible, y compris
les boutons sans identifiant HTML, pour alimenter les actions suivantes.
Les éléments des iframes accessibles et Shadow DOM ouverts sont pris en charge,
mais les iframes d'autres origines et les dialogues natifs ne peuvent pas être inspectés
arbitrairement depuis JavaScript.

## Validation

Le script `bash validation/check.sh` vérifie les scripts JavaScript.
Le workflow GitHub Actions construit l'APK et exécute les tests Android instrumentés,
dont `AgentTaskValidationTest`.

**Ne pas confondre sélection de fichier, upload accepté et publication Google Play.**
Aucune déclaration ou soumission Google Play n'est réalisée sans vérifier l'état réel
de la console et le contenu de l'application.

La branche `backup/browser-before-upload-forms-20261018` conserve l'état antérieur.
Aucune route Render existante n'est modifiée.
