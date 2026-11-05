# CHK — IA locale guidée + expérimentation Weight-Space Programming

## Ce que nous avons réellement livré
La même IA SmolLM2-135M-Instruct quantifiée Q4_K_M (environ 105 Mo) reste sur le
téléphone, sans nouvel entraînement ni changement de modèle. La v1.0.664 donne
déjà accès au moteur natif via CHK Browser Local 3.

La couche Java **LocalAiPlaybook** ajoute :
- `plan` : 3 étapes avec la preuve attendue, adaptées à YouTube, Suno, Studio,
  Instagram, TikTok ou navigation. Déterministe, même si le modèle répète la question.
- `quality` : détection simple de non-réponse, écho, répétition et contradictions
  explicites. Un signal heuristique, pas une garantie de vérité.
- `check` : comparaison textuelle de deux observations fournies. **Ne confirme
  jamais une publication sans vérification indépendante.**
- `ask` : un exemple compact dans le prompt, vérification de la réponse et
  `fallback_plan` séparé si le texte est de mauvaise qualité.
- `status` : annonce le moteur, l'absence d'entraînement et si les poids ont été
  modifiés.

### Appels au MCP Local 3
Toujours utiliser l'outil `desktop` existant ; exemples d'arguments :
```json
{"tool":"local_ai","operation":"plan","workflow":"youtube","prompt":"programmer un clip"}
```
```json
{"tool":"local_ai","operation":"check","expected":"21:15","observed":"Heure programmée : 21:15"}
```
```json
{"tool":"local_ai","operation":"quality","prompt":"Vérifications YouTube","answer":"Video is scheduled. Video is not scheduled."}
```
```json
{"tool":"local_ai","operation":"ask","prompt":"Combien font 2 + 3 ?"}
```
Ces opérations ne déclenchent **aucun clic ni publication**. ChatGPT reste
responsable de l'utilisation des outils MCP réels et de leurs autorisations.

## Programme de recherche WSP (isolé, non déployé)
L'article du 2 novembre 2026 des chercheurs MIT CSAIL
(https://rikiyat.github.io/blog/weight-space-programming/) décrit une édition
ciblée de 0,016 % des poids de **Qwen3.5-0.8B** ayant diminué ses boucles de
raisonnement. Le résultat ne s'applique **pas automatiquement à SmolLM2**.

La commande expérimentale `research/weight_space_edit.py` :
1. Inspecte un checkpoint **safetensors en précision flottante** connu.
2. Calcule un axe de contraste à partir d'activations de comportements « correct »
   et « répétitif ». Les activations doivent être recueillies et validées
   indépendamment ; cette collecte n'est **pas** implémentée sur Android.
3. Propose une projection soustractive sur quelques colonnes d'un
   `mlp.down_proj.weight` explicite, sans gradients.
4. Vérifie le SHA-256 du checkpoint original, la forme des tenseurs, la proportion
   modifiée, le delta relatif et l'absence de NaN. Le mode par défaut est
   **simulation sans écriture** ; `--write` crée un nouveau fichier distinct.
5. Enregistre un rapport `evaluation_passed: false` et
   `activated_in_CHK: false` : aucune amélioration n'est présumée.

**ATTENTION :** ce script est un mécanisme de modification directe et
conservatrice des poids, *inspiré* du Weight-Space Programming. Il ne reproduit
pas l'agent exploratoire des chercheurs et ne dispose pas d'un diagnostic
confirmé des circuits de SmolLM2. Le checkpoint **GGUF** déjà installé sur le
téléphone n'est jamais modifié. Aucun modèle modifié n'est inclus dans l'APK.

### Conditions avant déploiement de poids modifiés
- Diagnostiquer un échec reproductible sur SmolLM2 ; constituer une collection
  de requêtes d'essai et une collection indépendante pour validation.
- Instrumenter les activations, identifier précisément les couches et directions,
  définir les colonnes candidates. Sans évaluation : **pas de patch en production**.
- Mesurer les résultats base vs patch avec les mêmes prompts, tokens et réglages :
  répétition, exactitude, français, refus injustifiés, sécurité, Suno, YouTube,
  Studio, régressions sur questions simples.
- Recréer un GGUF quantifié depuis le checkpoint validé, avec nouveau nom, hash
  et mécanisme de retour à l'original ; vérifier les performances RAM/CPU sur
  le téléphone. La couche Java ne contourne pas ces étapes.
- Ne jamais déployer un patch expérimental automatiquement ; l'original est
  toujours récupérable.

## Tests
Les tests Android `LocalAiPlaybookTest` vérifient la sélection des procédures,
la détection de l'écho, la comparaison de preuve et l'absence de faux succès.

Le workflow `local-ai-research.yml` exécute des tests sur tenseurs synthétiques
sans télécharger de modèle : vérification de l'empreinte et de la non-destruction
du checkpoint. Il ne démontre **aucun gain d'intelligence**.

## Architecture et isolation
- Aucun changement du serveur PC Universal Control ni des routes Render.
- Aucune nouvelle permission Android et pas de service chargé en permanence.
- L'IA se charge à la demande, au maximum 2 threads CPU et 160 tokens en sortie.
- Même nom de package, même mécanisme de signature et d'actualisation APK.
- Le modèle et les données CHK ne sont pas supprimés à la mise à jour.
