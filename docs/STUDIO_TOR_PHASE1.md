# CHK Agent Browser — travaux Tor et Studio (branche de développement)

Date : 29 octobre 2026. **Non publié dans l'application.**

## Modifications implémentées dans cette première étape

- Navigation Tor : bouton séparé ouvrant **uniquement** le paquet Android officiel `org.torproject.torbrowser`. Le WebView CHK normal n'utilise pas de proxy Tor et ne partage pas les cookies du navigateur officiel. Si Tor Browser n'est pas installé, la fenêtre pointe vers sa fiche Play Store. **CHK ne peut pas confirmer, ni interrompre, la connexion Tor d'une autre application** : son état est marqué non vérifié, jamais connecté par supposition.
- Correctif de l'audio d'origine : `mute_original` vaut `false` par défaut même en présence d'une piste importée. Régler le volume d'un plan ne touche plus au mute de ses voisins.
- Niveaux sonores linéaires de 0 à 200 % dans le moteur, curseur Studio et commandes ci-dessous. `dB=20×log10(gain)`, silence exprimé `-Infinity`.
- Recadrage non destructif par clip : rectangle `{x,y,width,height}` normalisé et borné dans [0,1], inversion X/Y, rotation existante. Le même effet Media3 `Crop` est utilisé par le moteur de composition d'aperçu rendu et d'export. Interface numérique provisoire dans la barre d'outils.
- Contrôle des révisions, `project_id` exact, `request_id` stable et journal des 48 dernières mutations pour éviter une double application.

## Transport Local 3 sur serveur inchangé

Les nouveaux appels sont acheminables avec `android_action` et les noms `video_editor_studio_audio_*` / `video_editor_studio_crop_*` par le canal existant. L'appel direct de `studio_audio_*` ou `studio_crop_*` se traduit par un alias Android dans l'application. **Le serveur 1.0.4 ne publie pas pour autant de nouveaux outils MCP dédiés** : il faut réutiliser son transport générique tant qu'une évolution du schéma n'est pas autorisée.

- `studio_audio_tracks`: `{project_id:"UUID"}`
- `studio_audio_set_volume`: `{project_id:"UUID",expected_revision:4,request_id:"mix001",kind:"audio",track_index:0,source_path:"musique.wav",percent:15}` (ou `db:-16.478`).
- `studio_crop_open`: `{project_id:"UUID",clip_index:0}`
- `studio_crop_set`: `{project_id:"UUID",expected_revision:5,request_id:"crop001",clip_index:0,coordinates:"normalized",rect:{x:0.1,y:0.1,width:0.8,height:0.8}}`.
- `studio_crop_move`: `dx` et `dy` normalisés, identifiants et révision idem.
- `studio_crop_resize`: `width`, `height` normalisés, identifiants et révision idem.
- `studio_crop_rotate`: `degrees` entre -180 et 180, identifiants et révision idem.
- `studio_crop_flip`: `horizontal`, `vertical`, identifiants et révision idem.
- `studio_crop_apply`: applique et vérifie les paramètres actuels au projet, identifiants et révision idem.

## Non terminé : ne pas publier comme mise à jour globale

La gestion de l'audio **superposé en parallèle**, le ducking automatique, les keyframes réellement mixées, le limiteur de sortie, les quatre poignées tactiles, le recadrage animé et le diagnostic Tor **connecté** ne sont pas encore implémentés ni validés. Les appels `studio_audio_analyze`, `studio_audio_ducking`, `studio_audio_keyframes`, `studio_audio_mix`, `studio_audio_verify` et `studio_crop_preview` refusent explicitement l'exécution plutôt que de retourner un succès fictif.

Aucun essai sur le réseau Tor ou export MP4 comparatif n'a encore eu lieu pour ces ajouts. Ne pas déclencher de Release publique depuis cette branche sans tests d'émulateur, essais Android réels et vérification du rendu.
