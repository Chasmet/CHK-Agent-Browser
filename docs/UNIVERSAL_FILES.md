# Fichiers intégrés : formats réellement pris en charge

Le gestionnaire ouvre les fichiers dans CHK, en lecture seule. Un appui sur `META_A_COPIER.txt` affiche immédiatement le texte avec sélection et **Copier tout**. Aucun lecteur externe n’est requis pour ces métas. **Modifier** ouvre l’éditeur volontaire ; **Enregistrer une copie** crée un nouveau fichier UTF-8. Le remplacement explicite contrôle le SHA-256 d’origine et conserve une sauvegarde en corbeille.

## Modules disponibles

| Module | Prise en charge et limites |
| --- | --- |
| Textes, code, Markdown, HTML, sous-titres, configuration, EML/VCF/ICS | Lecture Unicode progressive, sélection, copie et recherche. Markdown : titres mis en valeur ; HTML/scripts restent du texte et ne s’exécutent pas. Édition UTF-8 jusqu’à 1 Mo, copie intégrale jusqu’à 200 000 caractères. |
| JSON / JSONL | Validation stricte, erreurs explicites, texte sélectionnable. Validation jusqu’à 1 Mo et profondeur 64. |
| CSV / TSV | Tableau avec guillemets et séparateurs, texte brut conservé quand une portion est incomplète. Affichage de 100 lignes à la fois au maximum. |
| PDF | Pages rendues localement, zoom, texte sélectionnable et copie ; OCR local pour les scans. Extraction de texte bornée aux pages demandées. Pas encore d’annotations ou d’édition de mise en page. |
| DOCX, XLSX, PPTX | Extraction du texte, cellules/valeurs enregistrées et diapositives ; pas de calcul de formules ni d’édition fidèle des mises en page. |
| OpenDocument ODT/ODS/ODP | Texte de content.xml, lecture seule ; pas de conservation visuelle de la mise en page. |
| Images | Formats que le décodeur Android reconnaît, aperçu réduit et zoom. Conversion locale en PNG/JPG/WEBP, dimension 16..4096, qualité 1..100, fond blanc pour transparence vers JPG. |
| Audio / vidéo | Lecteur Media3 avec commandes natives ; compatibilité selon les codecs du téléphone. Aucune promesse de décodage universel. |
| ZIP, paquets EPUB/APK | Liste sans extraction et lecture d’une entrée texte ; extraction ZIP complète ou sélective. L’EPUB est parcouru comme un paquet, pas encore comme une liseuse. L’APK n’est pas installé par cet écran. |
| SQLite | Tables et lignes paginées, ouverture en lecture seule, aucun SQL fourni par le client exécuté. Pas d’export complet de base dans ce lot. |
| Inconnus, binaires, formats propriétaires | Taille, format détecté, SHA-256, avertissement et aperçu hexadécimal de 512 octets par page. Ouvrir avec… et Partager restent disponibles. |

La détection utilise d’abord les signatures, puis les paquets ZIP, l’encodage et l’extension. Elle signale une extension trompeuse. Un binaire connu ne devient pas un texte modifiable parce qu’il finit par `.txt`. Un format chiffré inconnu n’est pas arbitrairement déclaré corrompu.

Les fichiers restent dans le stockage CHK ou sont importés depuis les accès SAF déjà autorisés. Ce lot n’ajoute aucun accès Android sans permission. Les lecteurs ne chargent pas arbitrairement plusieurs gigaoctets en mémoire : les textes sont lus par portions, les images sont sous-échantillonnées et les paquets documentaires sont bornés. Les ZIP refusent chemins traversants, destinations occupées, plus de 5 000 entrées, profondeur supérieure à 24, répertoire central supérieur à 8 Mo ou plus de 512 Mo décompressés. ZIP64, archives chiffrées et méthodes non reconnues sont signalés, sans extraction partielle publiée.

## Local 2 : utiliser le relais actuel

Le catalogue natif passe à `workspace_version=5`. Il reste accessible avec Windows 1.3.0/1.4.x par l’outil existant :

```json
{"action":"android_action","args":{"action":"files_open","args":{"path":"Categories/Videos/dans la brume 2/01_LA_VILLE_FANTOME/META_A_COPIER.txt"}}}
```

`files_open` est confirmé seulement lorsque la visionneuse a chargé le fichier et possède le focus. Si Android bloque le démarrage depuis l’arrière-plan, la réponse demande de mettre CHK au premier plan ; aucun affichage invisible n’est déclaré réussi.

| Action | Paramètres |
| --- | --- |
| files_detect_format | path |
| files_preview | path ; view=text pour le texte PDF, sinon aperçu JPEG pour PDF/images avec partage d’aperçus autorisé |
| files_media_inspect | path, offset, page, byte_offset : même moteur universel, propriétés multimédias |
| files_read_text | path, offset : Unicode progressif ou extraction du document reconnu |
| files_copy_content | path : texte complet borné, clipboard_written=false ; le bouton Copier de l’écran utilise le presse-papiers |
| files_edit_text | path, text, expected_sha256 ; sauvegarde récupérable et vérification |
| files_archive_list | path, offset : 100 entrées par réponse |
| files_archive_read | path, entry : texte d’une entrée, maximum 1 Mo décompressé |
| files_extract_archive | path, destination, entries facultatif : noms exacts, nouveau dossier obligatoire |
| files_database | path, table facultatif, offset : 100 lignes par réponse |
| files_convert | path, destination, format, quality/max_dimension facultatifs pour images |
| files_search_content | folder, query : 2 000 entrées, 32 Mo scannés, fichiers de 8 Mo maximum, 10 secondes et 100 résultats ; complete=false si une limite est atteinte |
| files_verify | path, expected_sha256 facultatif : présence, taille et SHA-256 |
| files_batch | id stable, operations=[{action,args}] : tâche durable de 1 à 50 étapes autorisées |

Les réponses trop grandes pour le relais sont conservées dans AgentResults par le mécanisme existant, avec chemin et SHA-256. La limite du transport JSON demeure distincte de celle du lecteur local ; pour les gros contenus, utiliser les transferts binaires reprenables.

Les conversions n’écrasent jamais un fichier. Images vers PNG/JPG/WEBP ; textes vers TXT/PDF/DOCX/PPTX ; JSON vers JSON formaté après validation ; CSV/TSV vers CSV/XLSX, avec limites explicites. Ce n’est pas un convertisseur Office arbitraire conservant la mise en page ni un transcodeur audio/vidéo.

Les fonctions 3D/CAD, PSD/RAW avancé, archives 7Z/RAR/TAR, mise en page Office modifiable, PDF professionnel complet, double panneau et extensions de lecteurs installables restent à développer. L’architecture sépare détection, lecture progressive, modules et interface ; ajouter du code natif nécessite encore une mise à jour APK.
