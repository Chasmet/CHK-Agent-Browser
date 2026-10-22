# Studio Android et Local 2

Le rendu et les fichiers restent sur Android. Windows 1.3.0 peut appeler les actions ci-dessous par son outil existant, sans installer un second serveur :

```json
{"action":"android_action","args":{"action":"video_editor_capabilities","args":{}}}
```

Avec le relais 1.4.1, `android_media(operation=nom_action, ...)` expose aussi ces actions. Consulter les capacités réellement retournées par l’APK avant de les appeler.

## Modifier le montage visible

1. `video_editor_project_read` retourne le projet et sa `revision`.
2. `video_editor_project_import` prend `path` (projet JSON dans Fichiers CHK) ou `project` (objet complet). Il valide tous les médias avant de créer un projet indépendant et de l’ouvrir ; le précédent reste dans la bibliothèque.
3. `video_editor_timeline` prend `expected_revision` obligatoire et `operations`. Le lot est validé et sauvegardé entièrement, ou refusé sans modification. L’écran Studio actualise ce même projet.

```json
{
  "expected_revision": 12,
  "operations": [
    {"op":"add","track":"clips","clip":{"path":"Categories/Images/photo.png","duration_ms":8000}},
    {"op":"update","track":"clips","index":0,"values":{"filter":"cinema","zoom":1,"zoom_end":1.12,"text":"Dans la brume"}},
    {"op":"move","track":"clips","index":0,"to":1},
    {"op":"settings","values":{"aspect_ratio":"16:9","resolution":1080,"fps":30,"mute_original":true}}
  ]
}
```

`add` accepte un index d’insertion facultatif. `update` modifie un plan ; `delete` le supprime. Les pistes prises en charge sont `clips` et `audio`, toutes deux séquentielles. Les photos ont `start_ms=0` et une durée de 1000 à 60000 ms. Maximum 100 plans et 20 minutes, projet JSON limité à 64000 caractères. L’interface importe jusqu’à 100 médias et donne 5 secondes à une photo. Le découpage permet de changer cette durée. Appui long sur un plan pour le déplacer ; le zoom de la timeline reste disponible.

Les filtres restent `aucun`, `noir`, `vintage`, `chaud`, `froid`, `cinema`, `contraste`, `nuit`, `vibrant`, `doux`, `desature`, `sepia`. `fade_ms` est un passage par le noir, pas un fondu croisé. `zoom`/`zoom_end` (1..4), `pan_x`/`pan_x_end` et `pan_y`/`pan_y_end` (-1..1) définissent le mouvement linéaire ; l’étalonnage accepte aussi valeurs de début/fin. Les effets sont calculés à l’export. L’aperçu reste celui des sources avec textes ; il ne simule pas tous les effets du rendu.

`video_editor_history(expected_revision, redo=false)` annule ; `redo=true` rétablit. Les 30 dernières sauvegardes sont conservées par projet. Chaque sauvegarde est atomique ; le brouillon le plus récent est récupéré après interruption.

## Exporter quatre projets existants

`video_editor_queue_add` exige un `request_id` stable (lettres, chiffres, tirets et soulignés) et 1 à 10 projets. Une nouvelle tentative avec le même identifiant retourne la série existante. Chaque projet accepte `path` ou un objet `project` complet. Aucun changement du projet visible n’est requis.

```json
{
  "request_id":"dans-la-brume-2-v1",
  "projects":[
    {"path":"Categories/Videos/dans la brume 2/01_LA_VILLE_FANTOME/montage.json"},
    {"path":"Categories/Videos/dans la brume 2/02_LES_OMBRES/montage.json"},
    {"path":"Categories/Videos/dans la brume 2/03_TRAVERSER_LA_BRUME/montage.json"},
    {"path":"Categories/Videos/dans la brume 2/04_LA_LUMIERE/montage.json"}
  ]
}
```

La file valide toutes les destinations et sources avant ajout, conserve les instructions et SHA-256 des médias, puis exporte successivement sans écraser les fichiers existants. Les chemins relatifs d’un JSON sont résolus depuis son dossier quand la source n’existe pas à la racine CHK. Un `output` sans dossier est placé près du JSON.

Lire `video_editor_queue_status` pour chaque état (`queued`, `running`, `completed`, `failed`, `interrupted`), destination et rapport de vérification. Une réponse à `queue_add` signifie que les travaux sont enregistrés, pas que les MP4 sont terminés. Seul `completed` accompagné de `verification.valid=true` confirme un export. `video_editor_queue_pause` laisse finir le rendu actif puis suspend la suite. `video_editor_queue_resume` reprend les travaux interrompus après inspection de leur éventuelle sortie ; un fichier existant non validé n’est jamais écrasé. Après arrêt du processus, la reprise est explicite depuis Studio ou MCP. La file conserve jusqu’à 40 entrées.

Un export direct peut aussi prendre `video_editor_export(project=objet)` sans modifier le projet ouvert, et `video_editor_verify_output(project=objet)` vérifie cette sortie précise. Sans projet, les commandes antérieures gardent leur comportement. La notification Android protège le rendu et les exports suivants ; son bouton Annuler suspend également la file.

La vérification contrôle la durée, la piste audio demandée et une image décodée au milieu de chaque plan. Elle compare la luminosité à la source et refuse un plan noir inattendu. Cela ne remplace pas une vérification visuelle de chaque transition : `media_frame` permet d’inspecter d’autres instants avec le partage d’aperçus autorisé. La vérification d’un MP4 n’est jamais une preuve que les 35 fonctions de la feuille de route sont implémentées.

## Limites de ce lot

1080p maximum, cadence 24/30/60 selon l’encodeur du téléphone. Pas encore de vidéo superposée, pistes audio parallèles, masques, transitions croisées, LUT, transcription locale automatique, proxies ou 4K. Les erreurs d’encodeur sont remontées ; les projets restent enregistrés. Aucune génération vidéo et aucun rendu sur Windows.
