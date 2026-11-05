package com.chk.agentbrowser;

import org.json.JSONArray;
import org.json.JSONObject;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Offline, deterministic playbooks usable by ChatGPT through Local 3.
 * No model training, network requests, credentials, or direct Android actions.
 * These are proposals only: each step requires real evidence from MCP tools.
 */
final class LocalAiPlaybook {
    static final int VERSION = 1;
    private LocalAiPlaybook() {}

    static String normalize(String value) {
        String plain = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD);
        return plain.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }
    static String category(String explicit, String prompt) {
        String w = normalize(explicit);
        if (w.equals("youtube") || w.equals("suno") || w.equals("studio")
                || w.equals("instagram") || w.equals("tiktok")
                || w.equals("browser")) return w;
        // Recognize exact product/domain names, not implied words or arbitrary URLs.
        String text = " " + normalize(prompt) + " ";
        if (text.contains(" youtube ") || text.contains(" youtu be ")) return "youtube";
        if (text.contains(" suno ")) return "suno";
        if (text.contains(" chk studio ") || text.contains(" studio chk ")
                || text.contains(" montage ") || text.contains(" sous titres ")) return "studio";
        if (text.contains(" instagram ")) return "instagram";
        if (text.contains(" tiktok ")) return "tiktok";
        return "browser";
    }
    static String[] steps(String category) {
        switch (category) {
            case "youtube": return new String[]{
                "Lire la page YouTube Studio et identifier la vidéo et sa chaîne.",
                "Vérifier le titre, la visibilité, la date, l'heure et le fuseau souhaités.",
                "Après action autorisée, relire la ligne de programmation et comparer la date et l'heure."};
            case "suno": return new String[]{
                "Lire l'interface Suno, la session et la sélection réelle de voix.",
                "Vérifier le texte, le mode, la voix choisie et le nombre de créations.",
                "Après génération, vérifier les nouveaux morceaux et leurs liens ou identifiants."};
            case "studio": return new String[]{
                "Lire le projet CHK Studio et la liste réelle des médias.",
                "Vérifier les durées, les pistes, les sous-titres et la configuration de l'export.",
                "Après export, contrôler le MP4 avec media_info et video_editor_verify_output."};
            case "instagram":
            case "tiktok": return new String[]{
                "Identifier le compte connecté et vérifier que le fichier vidéo est accepté.",
                "Contrôler légende, visibilité, date, heure et fuseau avant toute validation.",
                "Après confirmation, rechercher une preuve visible de publication programmée."};
            default: return new String[]{
                "Observer la page ou le fichier réel avant de proposer un geste.",
                "Choisir un seul geste autorisé avec une condition de réussite mesurable.",
                "Relire l'état et comparer avec le résultat attendu ; ne jamais inventer de succès."};
        }
    }
    static String[] proofs(String category) {
        switch (category) {
            case "youtube": return new String[]{
                "browser_inspect ou browser_get_page_status : bon compte et vidéo",
                "browser_verify_fields : valeurs réellement affichées",
                "browser_get_page_status ou browser_inspect : statut programmé et date/heure"};
            case "suno": return new String[]{
                "browser_inspect : écran et voix réellement affichés",
                "browser_verify_fields : paramètres et voix confirmés",
                "browser_inspect : morceaux nouveaux présents dans la liste"};
            case "studio": return new String[]{
                "video_editor_project_read : fichier projet réel",
                "video_editor_project_read : timeline et réglages",
                "video_editor_verify_output + media_info : MP4 créé et inspecté"};
            default: return new String[]{
                "browser_inspect ou files_stat",
                "browser_verify_fields ou video_editor_project_read",
                "browser_get_page_status ou media_info : preuve indépendante"};
        }
    }
    private static JSONObject step(int order, String action, String evidence) throws Exception {
        return new JSONObject().put("id", "verify-" + order)
            .put("instruction", action).put("evidence_required", evidence)
            .put("verified", false).put("executed", false);
    }
    static JSONObject plan(String explicit, String prompt) {
        String kind = category(explicit, prompt);
        JSONObject result = new JSONObject();
        try {
            String[] actions = steps(kind), evidence = proofs(kind);
            JSONArray arr = new JSONArray();
            for (int i=0;i<actions.length;i++) arr.put(step(i+1,actions[i],evidence[i]));
            result.put("kind", "code_playbook");
            result.put("category",kind).put("version",VERSION);
            result.put("steps",arr).put("verified",false)
                .put("actions_executed",false)
                .put("source","local_deterministic_rules")
                .put("requires_chatgpt_mcp_verification",true)
                .put("confidence","not_applicable");
        } catch (Exception ignored) {}
        return result;
    }
    static JSONObject verifyReport(JSONObject request) {
        JSONObject response = new JSONObject();
        try {
            // Only externally observed, explicitly supplied evidence is evaluated.
            // This is a structural check, never proof that a site actually accepted an action.
            String expected = request.optString("expected","").trim();
            String observed = request.optString("observed","").trim();
            if (expected.length()>512 || observed.length()>4096)
                throw new IllegalArgumentException("Preuve trop volumineuse.");
            boolean supplied = !expected.isEmpty() && !observed.isEmpty();
            String a = normalize(expected), b = normalize(observed);
            boolean match = supplied && !a.isEmpty() && b.contains(a);
            response.put("kind","code_evidence_comparison")
                .put("evidence_supplied",supplied)
                .put("text_match",match)
                .put("actual_action_confirmed",false)
                .put("status",!supplied?"missing_evidence":match?"text_matches_check_real_state":"mismatch")
                .put("requires_independent_mcp_check",true);
        } catch (Exception error) {
            try {response.put("error",error.getMessage()).put("actual_action_confirmed",false);}catch(Exception ignored){}
        }
        return response;
    }
    static JSONObject quality(String question, String answer) {
        JSONObject result = new JSONObject();
        try {
            String q = normalize(question), a = normalize(answer);
            boolean empty = a.isEmpty();
            boolean echo = q.length()>15 && (a.contains(q) || q.contains(a) && a.length()>=20);
            String[] chunks = (answer == null ? "" : answer).split("[\\n.!?;]+");
            Set<String> unique = new HashSet<>();
            int repeats = 0;
            for (String chunk : chunks) {
                String normalized = normalize(chunk).replaceFirst("^[0-9]+ ", "");
                if (normalized.length()>12 && !unique.add(normalized)) repeats++;
            }
            String padded = " " + a + " ";
            boolean contrad = (padded.contains(" is not scheduled ") && padded.contains(" is scheduled "))
                || (padded.contains(" est programme ") && padded.contains(" est pas programme "));
            // A short refusal or a genuine "I don't know" is honest but cannot
            // supply a plan. Return the deterministic, evidence-based fallback.
            boolean noAnswer = a.equals("je ne sais pas")
                || a.equals("je ne sais pas vraiment")
                || a.equals("i do not know")
                || a.equals("i don t know")
                || a.equals("unknown")
                || a.equals("impossible de savoir")
                || a.equals("je ne peux pas verifier")
                || a.equals("i cannot verify");
            boolean ok = !empty && !echo && repeats==0 && !contrad && !noAnswer;
            result.put("passed",ok).put("echo",echo).put("repetitions",repeats)
                .put("possible_contradiction",contrad)
                .put("non_answer",noAnswer)
                .put("unverified",true).put("actions_executed",false);
            if (!ok) result.put("fallback_required",true);
        } catch(Exception ignored){}
        return result;
    }
}
