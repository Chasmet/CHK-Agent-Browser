"""Specialized Android tools, preserving the existing android_action transport."""
from __future__ import annotations

FILES = {op: "files_" + op for op in ("stat", "hash", "self_test", "list", "catalog", "mkdir", "write_text", "edit_text", "read_text", "copy", "move", "trash", "trash_list", "restore", "zip", "unzip", "preview", "external")}
MEDIA = {op: op for op in ("media_info", "media_frame", "media_codecs", "media_model_status", "media_transcribe", "video_editor_project_read", "video_editor_project_save", "video_editor_project_list", "video_editor_project_new", "video_editor_project_open", "video_editor_export", "video_editor_status", "video_editor_wait", "video_editor_verify_output", "video_editor_cancel", "video_editor_capabilities", "video_editor_project_import", "video_editor_timeline", "video_editor_history", "video_editor_queue_add", "video_editor_queue_status", "video_editor_queue_pause", "video_editor_queue_resume", "document_read", "document_page")}
TRANSFERS = {op: "files_transfer_" + op for op in ("begin", "write", "read", "status", "commit", "abort")}


def route(name: str, args: dict) -> tuple[str, dict] | None:
    if name == "android_capabilities":
        return "files_capabilities", args
    if name == "android_document_create":
        return "document_create", args
    if name == "android_tasks":
        if args.get("operation") not in ("submit", "status", "resume", "cancel"):
            raise ValueError("Opération de tâche Android inconnue.")
        return "files_task", args
    table = {"android_files": FILES, "android_media": MEDIA, "android_transfer": TRANSFERS}.get(name)
    if table is not None:
        op = args.get("operation")
        if not isinstance(op, str) or op not in table:
            raise ValueError("Opération Android inconnue ; consulter android_capabilities.")
        payload = dict(args)
        payload.pop("operation")
        # SAF has a nested provider operation, keeping the outer tool's operation precise.
        if name == "android_files" and op == "external":
            payload["operation"] = payload.pop("external_operation", "external_roots")
        return table[op], payload
    return None


def catalog() -> list[dict]:
    def string(description=""):
        return dict(type="string", description=description)
    def enum(values):
        return dict(type="string", enum=list(values))
    integer = {"type": "integer"}
    def tool(name, description, fields, required=()):
        return dict(name=name, description=description, inputSchema=dict(type="object", properties=fields,
                    required=list(required), additionalProperties=False))
    path = string("Chemin relatif dans l'espace privé Android CHK, pas dans Windows.")
    return [
        tool("android_capabilities", "Catalogue réel et versionné des actions de l'APK Android, paginé. À consulter avant une action nouvelle.",
             dict(offset=integer, query=string())),
        tool("android_files", "Fichiers ANDROID : lecture/écriture, SHA-256, copie, renommage, corbeille, ZIP et dossiers SAF autorisés. Vérifier success et message ; pending se lit via android_result.",
             dict(operation=enum(FILES), path=path, destination=path, text=string(), expected_sha256=string(), overwrite={"type": "boolean"},
                  id=string(), query=string(), offset=integer, folder=path, category=string(), sort=string(),
                  external_operation=enum(("external_roots", "external_request_access", "external_list", "external_import", "external_export", "external_read_text", "external_rename", "external_mkdir")),
                  root_id=string(), source=path, name=string()), ("operation",)),
        tool("android_document_create", "Crée un document local Android sans écrasement, puis confirme fichier, taille et SHA-256. PDF/DOCX : title,text ; XLSX/CSV : rows ; PPTX : slides ; JSON : data. Office simple, sans macros ni calcul de formules.",
             dict(path=path, format=enum(("pdf", "docx", "xlsx", "pptx", "json", "csv", "txt")), title=string(), text=string(),
                  rows={"type": "array", "items": {"type": "array", "items": {"type": ["string", "number", "boolean", "null"]}}},
                  slides={"type": "array", "items": {"type": "object", "properties": {"title": string(), "text": string()}, "required": ["text"], "additionalProperties": False}},
                  data={"type": ["object", "array"]}), ("path", "format")),
        tool("android_media", "Lecture PDF/images/vidéos et studio natif Android. Export asynchrone : attendre video_editor_wait/status puis vérifier valid=true via video_editor_verify_output. L'aperçu nécessite l'autorisation Android.",
             dict(operation=enum(MEDIA), path=path, page=integer, page_count=integer, offset=integer, time_ms=integer,
                  language=enum(("fr", "en")), max_seconds=integer, project={"type": "object"}, expected_revision=integer,
                  id=string(), request_id=string(), operations={"type": "array", "items": {"type": "object"}}, projects={"type": "array", "items": {"type": "object"}}, redo={"type": "boolean"}, trash={"type": "boolean"}, replace={"type": "boolean"}, timeout_ms=integer), ("operation",)),
        tool("android_transfer", "Transfert binaire bas niveau Android : begin(id,path,size,sha256), write(id,offset,base64), status/commit/abort(id), read(path,offset,size,updated). 48 Ko par écriture, 8 Ko par lecture, SHA-256 final obligatoire.",
             dict(operation=enum(TRANSFERS), id=string(), path=path, size=integer, updated=integer, sha256=string(),
                  offset=integer, base64=string(), chunk_sha256=string()), ("operation",)),
        tool("android_tasks", "Tâches Android persistantes : submit/status/resume/cancel avec id stable. Maximum 50 étapes action/args, verify en lecture avec expect/contains. Ne pas réessayer une action incertaine avant inspection.",
             dict(operation=enum(("submit", "status", "resume", "cancel")), id=string(), steps={"type": "array", "items": {"type": "object"}},
                  offset=integer, retry_failed={"type": "boolean"}), ("operation", "id")),
        tool("android_transfer_file", "Copie un gros fichier Windows ↔ Android en arrière-plan, par blocs, reprise et SHA-256. Les chemins Windows restent dans CHK-Workspace. start(id,direction,source,destination), puis status ; resume après interruption, cancel pour arrêter. Réussite seulement state=completed et verified=true.",
             dict(operation=enum(("start", "status", "resume", "cancel")), id=string(), direction=enum(("to_android", "to_pc")),
                  source=string("Chemin relatif de la source."), destination=string("Nouvelle destination relative, sans écrasement.")), ("operation", "id")),
    ]
