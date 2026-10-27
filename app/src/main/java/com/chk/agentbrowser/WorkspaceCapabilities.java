package com.chk.agentbrowser;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Versioned, paged discovery usable even through the Windows 1.3.0 android_action tool. */
final class WorkspaceCapabilities {
    static final int VERSION=5;
    private WorkspaceCapabilities(){}
    static JSONObject catalog(JSONObject args)throws Exception{
        List<JSONObject> all=new ArrayList<>();
        add(all,"files_browser_capabilities","Importation et formulaires web depuis Local 2 Windows 1.3 ou supérieur, via desktop tool browser.");
        add(all,"files_browser_upload_status","Journal persistant des sélections web : selected ne prouve pas le téléversement distant.","id:string");
        add(all,"files_open","Ouvre la visionneuse CHK ; succès seulement après chargement et fenêtre au premier plan.","path:string");
        add(all,"files_detect_format","Extension, MIME et signature ; capacités et avertissements réels.","path:string");
        add(all,"files_copy_content","Prépare le texte complet jusqu’à 200000 caractères, sans toucher au presse-papiers Android.","path:string");
        add(all,"files_archive_list","Liste paginée du ZIP sans extraction, 100 entrées par page, maximum 5000.","path:string","offset:integer?");
        add(all,"files_archive_read","Aperçu texte d’une entrée ZIP, maximum 1 Mo décompressé.","path:string","entry:string");
        add(all,"files_extract_archive","Extraction ZIP complète ou sélective dans un nouveau dossier, sans écrasement.","path:string","destination:string","entries:array?");
        add(all,"files_convert","Copie convertie : images vers png/jpg/webp ; texte vers txt/pdf/docx/pptx/json ; csv/tsv vers csv/xlsx. Cadres simples, pas de conservation d’une mise en page Office arbitraire.","path:string","destination:string","format:string","quality:integer?","max_dimension:integer?");
        add(all,"files_search_content","Recherche progressive, 2000 entrées et 32 Mo scannés maximum ; complete=false si limite atteinte.","folder:string?","query:string");
        add(all,"files_database","SQLite en lecture seule, choix table et pagination de 100 lignes.","path:string","table:string?","offset:integer?");
        add(all,"files_media_inspect","Aperçu universel selon signature, texte/PDF/Office/table/ZIP/SQLite/hex et propriétés médias.","path:string","offset:integer?","page:integer?","byte_offset:integer?");
        add(all,"files_verify","Présence, taille, SHA-256 ; erreur si l’empreinte attendue diffère.","path:string","expected_sha256:string?");
        add(all,"files_batch","Lot durable de 1 à 50 actions autorisées via le moteur de tâches, id stable.","id:string","operations:array");
        add(all,"files_stat","Présence, type, taille et date du fichier.","path:string");
        add(all,"files_self_test","Teste les fichiers synthétiques, archives et paquets documentaires ; aucun média personnel lu.");
        add(all,"files_hash","Calcule SHA-256 sur le fichier Android complet.","path:string");
        add(all,"files_list","Liste un dossier Android privé (racine : path vide).","path:string","query:string?","offset:integer?");
        add(all,"files_catalog","Recherche et tri dans le catalogue Android.","folder:string?","category:string?","query:string?","sort:string?","offset:integer?");
        add(all,"files_mkdir","Crée un dossier sans écrasement ; le parent doit exister.","path:string");
        add(all,"files_write_text","Écriture UTF-8 atomique, 1 Mo ; overwrite explicite.","path:string","text:string","overwrite:boolean?");
        add(all,"files_edit_text","Modifie le texte seulement si SHA-256 attendu correspond ; conserve l’ancienne version en corbeille.","path:string","text:string","expected_sha256:string");
        add(all,"files_read_text","Lecture progressive Unicode ou extraction des documents pris en charge.","path:string","offset:integer?","page:integer?","page_count:integer?");
        add(all,"files_read_bytes","Lecture Base64 par blocs de 8 Ko (compatibilité).","path:string","offset:integer?");
        add(all,"files_copy","Copie fichier ou dossier privé sans écraser, vérifie SHA-256 des fichiers ; 5 000 entrées / 6 Go maximum.","path:string","destination:string");
        add(all,"files_move","Déplace ou renomme sans écraser.","path:string","destination:string");
        add(all,"files_trash","Retrait récupérable dans la corbeille Android.","path:string");
        add(all,"files_trash_list","Liste les éléments restaurables.");
        add(all,"files_restore","Restaure avec contrôle de destination.","id:string");
        add(all,"files_zip","Sauvegarde fichier ou dossier en ZIP, 5 000 entrées / 6 Go maximum.","path:string","destination:string");
        add(all,"files_unzip","Extraction dans un nouveau dossier, chemins confinés et quota décompressé.","path:string","destination:string");
        add(all,"files_transfer_begin","Import binaire reprenable : id stable, taille 0..6000000000, SHA-256 minuscule ; pas d’écrasement.","id:string","path:string","size:integer","sha256:string");
        add(all,"files_transfer_write","Écrit 1..49152 octets à received ; un bloc identique rejoué est sans effet.","id:string","offset:integer","base64:string","chunk_sha256:string?");
        add(all,"files_transfer_status","Relit le point de reprise durable après interruption.","id:string");
        add(all,"files_transfer_commit","Vérifie taille et SHA-256 puis publie atomiquement.","id:string");
        add(all,"files_transfer_abort","Annule un transfert non publié et retire ses blocs temporaires.","id:string");
        add(all,"files_transfer_read","Export par blocs avec contrôle de version. Obtenir size/updated/sha256 via files_hash ; vérifier SHA-256 après assemblage.","path:string","offset:integer","size:integer","updated:integer");
        add(all,"files_external","Dossiers SAF autorisés : operation external_roots, external_request_access, external_list, external_import, external_export, external_read_text, external_rename, external_mkdir. root_id requis hors roots/request_access. Export vérifié par relecture SHA-256.","operation:string","root_id:string?","path:string?","source:string?","destination:string?","name:string?","offset:integer?");
        add(all,"files_task","Tâches persistantes de 1..50 étapes : submit/status/resume/cancel. Ne répète pas automatiquement une action incertaine.","id:string","operation:string","steps:array?","offset:integer?","retry_failed:boolean?");
        add(all,"files_capabilities","Ce catalogue de schémas, 8 actions par page.","offset:integer?","query:string?");
        add(all,"document_create","Crée sans écraser : pdf/docx avec title et text ; xlsx/csv avec rows ; pptx avec slides [{title,text}] ; json avec data ; txt avec text. Office : texte et valeurs simples, pas de macros, formules ou édition arbitraire d’un document existant.","path:string","format:string","title:string?","text:string?","rows:array?","slides:array?","data:object?");
        add(all,"document_read","Extraction PDF/DOCX/images, valeurs XLSX et texte PPTX (OCR local si nécessaire ; formules non calculées).","path:string","page:integer?","page_count:integer?","offset:integer?");
        add(all,"document_page","Aperçu d’une page PDF, indice à partir de zéro ; autorisation d’aperçus requise.","path:string","page:integer?");
        add(all,"files_preview","Aperçu d’image ou PDF ; autorisation d’aperçus requise.","path:string");
        add(all,"media_info","Durée, pistes, résolution et métadonnées locales.","path:string");
        add(all,"media_frame","Image vidéo à time_ms (image clé la plus proche).","path:string","time_ms:integer?");
        add(all,"media_codecs","Codecs réellement disponibles sur cet Android.");
        add(all,"media_transcribe","Transcription locale fr/en, modèle local requis.","path:string","language:string?","max_seconds:integer?");
        add(all,"video_editor_capabilities","Fonctions Studio réellement disponibles sur ce téléphone.");
        add(all,"video_editor_project_import","Valide puis ouvre un montage JSON depuis Fichiers, sans perdre le projet précédent.","path:string");
        add(all,"video_editor_timeline","Modifications atomiques du projet visible : add/update/move/delete/settings. Photos 1..60 s, maximum 100 plans.","expected_revision:integer","operations:array");
        add(all,"video_editor_history","Annuler ou rétablir, historique durable par projet.","expected_revision:integer","redo:boolean?");
        add(all,"video_editor_queue_add","Ajoute 1..10 projets indépendants, sans écraser de sortie ; request_id stable empêche les doubles exports.","request_id:string","projects:array");
        add(all,"video_editor_queue_status","Progression et vérification par export.");
        add(all,"video_editor_queue_pause","Arrête les prochains démarrages ; laisse finir le rendu actif.");
        add(all,"video_editor_queue_resume","Reprend la file, vérifie les exports interrompus avant de relancer.");
        add(all,"video_editor_project_read","Projet de montage actif avec révision.");
        add(all,"video_editor_project_save","Enregistre un projet validé, protège contre les révisions périmées.","project:object","expected_revision:integer?");
        add(all,"video_editor_project_list","Projets enregistrés ou corbeille.","trash:boolean?");
        add(all,"video_editor_project_new","Crée un projet vide.");
        add(all,"video_editor_project_open","Ouvre le projet enregistré.","id:string");
        add(all,"video_editor_export","Démarre l’export MP4 asynchrone ; attendre puis vérifier le résultat.","replace:boolean?");
        add(all,"video_editor_status","État et progression de l’export natif.");
        add(all,"video_editor_wait","Attend un export existant, maximum 120 s ; completed indique la fin, vérifier ensuite valid via verify_output.","timeout_ms:integer?");
        add(all,"video_editor_verify_output","Vérification MP4 locale, durée, pistes, images noires ; contrôler valid=true.");
        add(all,"video_editor_cancel","Arrête l’export natif.");
        add(all,"notes_list","Recherche des notes privées.","query:string?","deleted:boolean?","offset:integer?");
        add(all,"notes_read","Lit une note et sa révision.","id:string");
        add(all,"notes_save","Sauve une note avec contrôle de révision.","title:string","body:string","id:string?","revision:integer?","source_url:string?","pinned:boolean?");
        add(all,"workspace_status","États Android et version du protocole ; fichiers privés et autorisations externes séparés.");
        String query=args.optString("query","").toLowerCase(Locale.ROOT);List<JSONObject> selected=new ArrayList<>();for(JSONObject item:all)if(item.getString("action").contains(query))selected.add(item);
        int offset=args.optInt("offset",0);if(offset<0||offset>selected.size())throw new Exception("Pagination du catalogue invalide.");JSONArray page=new JSONArray();for(int i=offset;i<Math.min(offset+8,selected.size());i++)page.put(selected.get(i));
        return new JSONObject().put("workspace_version",VERSION).put("browser",BrowserAutomation.capabilities()).put("scope","workspace_and_studio").put("transport","android_action").put("actions",page).put("total",selected.size()).put("next_offset",offset+8<selected.size()?offset+8:-1);
    }
    private static void add(List<JSONObject> rows,String name,String description,String... fields)throws Exception{
        JSONObject properties=new JSONObject();JSONArray required=new JSONArray();
        for(String field:fields){String[] parts=field.split(":");boolean optional=parts[1].endsWith("?");String type=parts[1].replace("?","");JSONObject property=new JSONObject().put("type",type);if(type.equals("array"))property.put("items",new JSONObject());properties.put(parts[0],property);if(!optional)required.put(parts[0]);}
        if(name.equals("document_create"))properties.put("data",new JSONObject().put("type",new JSONArray().put("object").put("array")));
        rows.add(new JSONObject().put("action",name).put("description",description).put("inputSchema",new JSONObject().put("type","object").put("properties",properties).put("required",required).put("additionalProperties",false)));
    }
}
