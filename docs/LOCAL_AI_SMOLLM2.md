# IA locale CHK — SmolLM2 135M (version expérimentale)

## Objectif
Fournir à ChatGPT, via l'outil `desktop` déjà disponible dans CHK Browser Local 3,
un mini-modèle open source *consultatif* pour préparer de courtes étapes de navigation,
publication et vérification. **Le modèle ne contrôle jamais les gestes du téléphone** :
seul ChatGPT déclenche les outils MCP existants, sous les permissions déjà accordées.

## Choix technique
- SmolLM2-135M-Instruct, licence Apache-2.0 (Hugging FaceTB).
- Quantification GGUF Q4_K_M, environ 105 Mo, hébergée sur Hugging Face / QuantFactory.
- Révision de modèle figée : `476854d00ede130660aba430d15f9347ad2e7d0e`.
- SHA-256 du GGUF : `8030f04528538d47bda434f6f0bdf3952c40a58123e4d5e755332f23731a8684`.
- Runtime natif : llama.cpp, commit `620d2225cc436ab0b001e3200e590e3b286ef9ab`.
- Aucune formation / aucun fine-tuning : comportement adapté seulement par le **prompt système**, les limites, le code Java et les règles de délégation.
- CPU seulement, 1 génération à la fois, 2 threads maximum, 1 024 tokens de contexte,
  16–160 tokens en sortie. Modèle chargé et libéré à chaque demande ; stockage dans
  `getNoBackupFilesDir()` ; `mmap` activé, `mlock` désactivé.
- Aucune connexion réseau pour l'inférence. Seul le téléchargement initial accède à Hugging Face.
- **Limites** : très petit modèle, principalement anglophone, peu fiable sur des parcours Web complexes.
  Ses suggestions ne prouvent pas que les actions ont été faites.

## Installer et utiliser
1. Installer une Release APK **arm64-v8a** générée par GitHub Actions avec la même
   signature que les Releases précédentes.
2. Dans Réglages CHK, activer **Autoriser ChatGPT à interroger le modèle local**.
3. Choisir **Télécharger le mini-modèle** et accepter le transfert initial.
   Vérifier l'état affiché, ou interroger le statut depuis le connecteur.
4. Via Local 3, employer le transport MCP `desktop` déjà enregistré
   (ne nécessite **aucune modification du serveur PC**).

Exemple : `desktop`, paramètres JSON :
```json
{"tool":"local_ai","operation":"status"}
```
```json
{"tool":"local_ai","operation":"ask","prompt":"Propose deux vérifications avant de programmer une vidéo sur YouTube.","max_tokens":96}
```
Autres opérations : `install` (téléchargement asynchrone après opt-in),
`delete` (supprime seulement le modèle), `disable`.
`ask` ne déclenche aucune commande MCP. **Toujours vérifier** un résultat réel
avec `browser_inspect` / `browser_get_page_status` après l'action correspondante.

## Compilation GitHub / tests
Le fichier `scripts/build_local_ai_android.sh` prépare le runtime C++ **sur le
runner GitHub**, produit une bibliothèque ARM64 et la place dans
`app/src/main/jniLibs/arm64-v8a/libchk_local_ai.so` avant Gradle.
`./gradlew assembleDebug` fonctionne également sans le runtime optionnel,
mais dans ce cas le statut signale `native_available: false` et le modèle n'est pas exécutable.

La Release utilise le workflow existant `.github/workflows/android.yml`,
la même signature et le système de mise à jour déjà présent. Les fichiers
téléchargés restent dans l'espace privé de l'application, préservé à la mise à jour.
Le serveur Windows/PC, les routes Render, Tor et les données CHK ne sont pas modifiés.

## Sécurité et mémoire
Le modèle n'est pas doté d'outils, de comptes ou de secrets. Prompts bornés à
2 800 caractères ; aucun texte de page partagé automatiquement. Les sorties
sont marquées `local_model_unverified_text`. Le CPU, la RAM et le temps
restent **à mesurer sur le téléphone réel** ; la taille du fichier (105 Mo)
ne garantit **pas** une consommation RAM égale à 105 Mo. Le processus Android
peut être tué si les ressources système sont insuffisantes.
