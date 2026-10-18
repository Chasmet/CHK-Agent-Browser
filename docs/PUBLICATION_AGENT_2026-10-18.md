# CHK Publication Agent — navigateur et campagnes (18 octobre 2026)

## Fonctions opérationnelles

Version 1 de l'assistant de publication sur le **téléphone**. Elle conserve la session de navigateur connectée, ses outils MCP et les médias dans l'espace privé CHK. Elle ne récupère ni identifiants, ni cookies, ni mots de passe pour le relais.

- **Préparer** `publication_prepare` : manifeste persistant avec titre, légende, hashtags, médias, compte visé, plateforme, mode promotion et programmation souhaitée.
- **Suivre** `publication_status`, `publication_list`, `publication_cancel` : états consultables sans ouvrir un autre service. Bouton dans **Réglages → Publications et campagnes CHK**.
- **Naviguer / préparer le formulaire** : outils MCP existants `browser_open_url`, `browser_read_page`, `browser_get_form`, `browser_fill_fields`, `browser_type`, `browser_select_option`, `browser_check`, `browser_upload_workspace_file`.
- **Publier** `publication_submit` : **un seul clic** ciblant la bonne plateforme HTTPS, avec condition observable (`expected_text` ou `expected_url_contains`) et journal anti-doublon conservé avant l'action. Fonctionne aussi pour une programmation demandée (`mode=schedule`).
- **Auditer** : jamais de « publié » simplement parce qu'un clic ou une sélection de fichier a réussi. Le résultat distingue `prepared`, `click_pending`, `browser_confirmed`, `schedule_browser_confirmed`, `uncertain`, `cancelled`.

Plateformes validées comme destinations HTTPS : **YouTube / Studio, TikTok, Instagram, Facebook, Threads, X, LinkedIn, SoundCloud, Bluesky, Pinterest, Reddit, Bandcamp**. Aucun sélecteur CSS ou URL de bouton final n'est codé en dur : l'agent les lit sur l'interface réelle, connectée, et confirme une condition propre à cette publication.

### Exemple d'une préparation avec l'outil MCP `browser_task_submit`

```json
{
  "id": "chk-chanson-001",
  "steps": [
    {
      "id": "plan",
      "action": "publication_prepare",
      "args": {
        "id": "chk-chanson-001",
        "platform": "youtube",
        "destination_url": "https://studio.youtube.com/",
        "account": "CHK",
        "title": "Titre de mon clip",
        "caption": "Description du clip",
        "hashtags": ["#CHK", "#RapFrancais"],
        "media_paths": ["CutVideo/clip.mp4"],
        "promotion_mode": "organic"
      }
    }
  ]
}
```

Après cette préparation, l'agent doit **lire et vérifier le formulaire courant**, charger le vrai fichier CHK via son outil d'import, remplir les champs, vérifier la présence des médias/du traitement et choisir les options de visibilité. La création du manifeste ne téléverse rien et ne publie rien.

La soumission finale utilise une **nouvelle tâche** avec l'action `publication_submit` et un `id` de manifeste existant :

```json
{
  "id": "chk-chanson-001-confirmation",
  "steps": [
    {
      "id": "confirmation",
      "action": "publication_submit",
      "args": {
        "id": "chk-chanson-001",
        "expected_host": "studio.youtube.com",
        "account_selector": "SELECTEUR_COMPTE_CHK_OBSERVE_SUR_LA_PAGE",
        "selector": "SELECTEUR_DU_BOUTON_VERIFIE_SUR_CETTE_PAGE",
        "expected_text": "TEXTE_DE_CONFIRMATION_OBSERVABLE_ET_SPECIFIQUE",
        "mode": "publish"
      }
    }
  ]
}
```

Les valeurs `selector`, `account_selector` (si le plan nomme un compte) et `expected_text` doivent provenir d'une inspection du site courant (pas d'une supposition). L'identité du compte doit être lisible dans l'élément désigné, sinon aucun clic n'a lieu. Une vérification réellement impossible donne `uncertain` et interdit tout re-clic automatique. Après une interruption, vérifier manuellement le statut du site avant de créer une nouvelle opération.

### Publications programmées et promotion

`schedule_at` : date ISO-8601 avec décalage horaire, par exemple `2026-11-01T19:00:00+01:00`. La programmation réelle **n'a lieu que si le formulaire du site est rempli et confirmé** par `publication_submit` en `mode=schedule`. Aucune planification serveur locale n'est prétendue.

**Promotion organique** : préparer des descriptions adaptées, publications croisées et preuves par plateforme, en créant une campagne par destination. **Publicité payante** : préparation autorisée avec `promotion_mode=paid`, mais tout clic de validation d'achat / budget est bloqué ici. Les dépenses exigent un accord spécifique du propriétaire.

Le navigateur ne contourne ni CAPTCHA, ni MFA, ni restrictions d'accès des plateformes. Aucun relais Render, aucune ancienne route, aucun Cut Vidéo externe n'a été modifié.

### Limites connues

- Les interfaces de plateformes évoluent : l'agent doit réinspecter la page avant chaque mise en ligne.
- Une confirmation dans le navigateur n'est pas une preuve indépendante de la diffusion ou des statistiques publicitaires ; le journal conserve l'URL et le message observés.
- Les très gros téléversements ou ceux qui s'appuient sur des dialogues propriétaires peuvent nécessiter une sélection assistée par le propriétaire.
- Les outils de haut niveau `publication_*` transitent par **`browser_task_submit`**, déjà présent dans le serveur MCP : aucune nouvelle route Render n'est requise.
