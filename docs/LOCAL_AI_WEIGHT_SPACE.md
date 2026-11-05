# CHK Local AI — amélioration sans entraînement

## Deux couches distinctes

1. **Programmation immédiate**, dans l'APK : \`LocalAiPlaybook.java\` détecte
   un contexte YouTube, Suno, CHK Studio, TikTok, Instagram ou navigateur et
   produit exactement trois contrôles dont chacun exige une preuve réelle MCP.
   \`LocalAiController\` fait une génération courte avec SmolLM2-135M, puis
   vérifie les répétitions, les échos et les contradictions simples. Toute
   réponse douteuse reçoit un plan de secours clairement indiqué comme
   \`local_deterministic_rules\`, et non comme une réponse du réseau de neurones.
   Aucun clic, téléchargement ou publication ne peut être déclaré réussi
   par l'IA seule. Aucun appel réseau, nouvelle API ou service PC.
2. **Weight-space programming expérimental**, hors de l'APK : script Python
   \`research/weight_space_edit.py\` pour inspecter une matrice de poids
   d'un checkpoint SmolLM2 en format **safetensors** et proposer une
   projection de direction sans gradients ni entraînement, selon
   l'idée de Takehi et coll. (MIT CSAIL, 2 novembre 2026).
   **Aucun poids modifié n'est encore livré dans CHK**.

L'étude source : https://rikiyat.github.io/blog/weight-space-programming/
et les logs publics : https://github.com/RikiyaT/lm_understand-logs

Les chercheurs ont réparé un comportement de répétition de Qwen3.5-0.8B ;
ils n'ont pas prouvé qu'une modification de poids ajoutait de nouvelles
capacités, ni que la même intervention fonctionne sur SmolLM2. Un petit
modèle peut manquer des compétences nécessaires à la tâche.
Contrairement à une modification de prompt, une édition directe **change**
les paramètres numériques du modèle : il n'y a pas de descente de gradient,
mais il faut toujours mesurer les régressions.

## Appeler via le MCP Local 3 existant

Le connecteur doit transmettre \`desktop\` et ses paramètres :

\`\`\`json
{"tool":"local_ai","operation":"plan","workflow":"youtube","prompt":"Programmer une vidéo pour samedi"}
\`\`\`

\`\`\`json
{"tool":"local_ai","operation":"check","expected":"Programmée","observed":"Statut affiché: Programmée"}
\`\`\`

Le champ \`text_match\` vérifie uniquement la comparaison textuelle ;
\`actual_action_confirmed\` est **toujours false**. Il faut une lecture de
l'interface via les outils MCP réels pour confirmer.

\`\`\`json
{"tool":"local_ai","operation":"ask","prompt":"Quelle est la prochaine étape ?","max_tokens":96}
\`\`\`

Les réponses \`ask\` contiennent \`quality\` et un \`fallback_plan\` en cas
d'écho, de répétitions ou de contradiction grossière.

## Édition des poids : protocole de recherche

**Pas sur le téléphone et jamais sur le GGUF quantifié en production.**
Sur un environnement de travail isolé, avec un checkpoint safetensors
original, les dépendances \`numpy\` et \`safetensors\`, et une copie
de sauvegarde :

1. Constituer deux petits jeux de séquences représentatifs, un normal
   et un où l'on observe le défaut **réel**. Capturer les activations à
   la même couche de SmolLM2, dans les deux groupes. Les fichiers
   \`normal.npy\` / \`repeating.npy\` doivent avoir la forme
   \`[N, hidden_size]\`. Il ne s'agit pas de données de formation.
2. Calculer la différence normalisée des activations moyennes sans
   rétropropagation :
   \`\`\`bash
   python research/weight_space_edit.py direction --normal normal.npy \
     --repeating repeating.npy --output direction.npy
   \`\`\`
3. Inspecter les tenseurs du checkpoint :
   \`\`\`bash
   python research/weight_space_edit.py inspect --checkpoint model.safetensors \
     --contains mlp.down_proj
   \`\`\`
4. Tester **à blanc** un patch limité et reproductible (exemple, valeurs
   à adapter après avoir identifié la couche et les colonnes impliquées) :
   \`\`\`bash
   python research/weight_space_edit.py patch --checkpoint model.safetensors \
      --output trial.safetensors --tensor model.layers.0.mlp.down_proj.weight \
      --columns 0,1 --direction direction.npy \
      --expected-sha256 <SHA256_DU_CHECKPOINT>
   \`\`\`
5. Uniquement après analyse, ajouter \`--write\` pour créer un **nouveau**
   safetensors. Le fichier original est conservé ; le script refuse de
   dépasser son budget de modification, limite la norme du patch et
   enregistre le SHA-256.
6. Faire des tests indépendants avant/après **sur des données non vues
   pendant la recherche** : français, anglais, non-répétition, consignes
   à respecter, additions, listes, YouTube/Suno/Studio. Mesurer le
   taux de réussite, les hallucinations, les répétitions et la RAM.
   N'accepter le modèle édité **que si les tests démontrent un gain
   significatif sans régression**.
7. Seulement ensuite convertir en GGUF, quantifier, signer et contrôler
   le hash final. Publier en version distincte, avec retour immédiat
   à l'ancien modèle en cas de problème. Cette étape n'est pas automatisée
   ici afin de ne jamais injecter des poids non validés dans l'APK.

Cette expérimentation ne modifie ni les connexions MCP, ni Render, ni le
serveur PC ni le modèle actuellement installé. Elle consomme de la mémoire
sur le poste de **recherche uniquement**, pas en RAM sur le téléphone.

## Tests

- \`LocalAiPlaybookTest\` : classification, plans non exécutables,
  contrôle de réponse et exigences de preuve.
- \`python -m unittest discover -s research -p 'test_*.py'\` après
  \`pip install numpy safetensors\` : vérifie sur des tenseurs synthétiques
  la création de direction, le mode dry-run, la modification de colonnes
  ciblées, la conservation de l'original et le refus d'un SHA-256 erroné.
- \`./gradlew assembleDebug lintDebug\` et tests Android via GitHub Actions.
- Tests en direct sur le téléphone seulement après publication de la
  nouvelle Release signée.
