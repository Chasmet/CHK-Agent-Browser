#include <jni.h>
#include <llama.h>
#include <algorithm>
#include <string>
#include <vector>

// Native resources live only for one inference. No persistent context/model.
// This deliberately does not expose Android actions to the model.
static void fail(JNIEnv * env, const char * message) {
    jclass error = env->FindClass("java/lang/IllegalStateException");
    if (error) env->ThrowNew(error, message);
}
static void put_tokens(llama_batch_ext * batch, const llama_token * tokens,
                       int count, llama_pos start, bool logits) {
    llama_batch_ext_clear(batch);
    for (int i = 0; i < count; ++i) {
        int idx = llama_batch_ext_add_token(batch, 0, tokens[i]);
        const llama_pos pos = start + i;
        llama_batch_ext_set_pos(batch, idx, &pos);
    }
    if (logits && count > 0)
        llama_batch_ext_set_output_logits(batch, count - 1, true);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_chk_agentbrowser_LocalAiController_nativeAsk(
    JNIEnv * env, jclass, jstring modelPath, jstring promptText,
    jint requestedTokens, jint requestedThreads) {
    if (!modelPath || !promptText) { fail(env, "Paramètres IA absents."); return nullptr; }
    const char * rawPath = env->GetStringUTFChars(modelPath, nullptr);
    if (!rawPath) return nullptr;
    const std::string path(rawPath);
    env->ReleaseStringUTFChars(modelPath, rawPath);
    const char * rawPrompt = env->GetStringUTFChars(promptText, nullptr);
    if (!rawPrompt) return nullptr;
    const std::string prompt(rawPrompt);
    env->ReleaseStringUTFChars(promptText, rawPrompt);
    if (prompt.empty() || prompt.size() > 8000 || path.empty()) {
        fail(env, "Prompt IA invalide ou trop long."); return nullptr;
    }
    const int maxTokens = std::max(16, std::min(160, (int) requestedTokens));
    const int threads = std::max(1, std::min(2, (int) requestedThreads));
    llama_backend_init();

    llama_model_params modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = 0;
    // Pinned llama.cpp uses load_mode instead of the former mmap/mlock flags.
    modelParams.load_mode = LLAMA_LOAD_MODE_MMAP;
    llama_model * model = llama_model_load_from_file(path.c_str(), modelParams);
    if (!model) { fail(env, "Modèle GGUF impossible à charger."); return nullptr; }
    const llama_vocab * vocab = llama_model_get_vocab(model);
    int promptCount = -llama_tokenize(vocab, prompt.data(), prompt.size(),
                                       nullptr, 0, true, true);
    if (promptCount <= 0 || promptCount > 768) {
        llama_model_free(model);
        fail(env, "Prompt trop long pour le contexte local de 1024 tokens.");
        return nullptr;
    }
    std::vector<llama_token> ids(promptCount);
    if (llama_tokenize(vocab, prompt.data(), prompt.size(), ids.data(),
                       ids.size(), true, true) != promptCount) {
        llama_model_free(model); fail(env, "Tokenisation impossible."); return nullptr;
    }
    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx = 1024;
    ctxParams.n_batch = 256;
    ctxParams.n_ubatch = 128;
    ctxParams.n_threads = threads;
    ctxParams.n_threads_batch = threads;
    llama_context * ctx = llama_init_from_model(model, ctxParams);
    if (!ctx) { llama_model_free(model); fail(env, "Mémoire insuffisante pour l'IA locale."); return nullptr; }

    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // Decode-time steering: small repetition penalty, no parameter modification.
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(
        llama_vocab_n_tokens(vocab), 64, 1.10f, 0.0f, 0.0f));
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    llama_batch_ext * batch = llama_batch_ext_init(ctx);
    bool ok = batch != nullptr;
    std::string answer;

    if (ok) {
        // Bounded 256-token chunks; only the final prefill batch requests logits.
        for (int offset = 0; offset < promptCount && ok; offset += 256) {
            int n = std::min(256, promptCount - offset);
            put_tokens(batch, ids.data() + offset, n, offset, offset + n == promptCount);
            ok = llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch) == 0;
        }
    }
    for (int i = 0; i < maxTokens && ok; ++i) {
        const llama_token token = llama_sampler_sample(sampler, ctx, -1);
        llama_sampler_accept(sampler, token);
        if (llama_vocab_is_eog(vocab, token)) break;
        char piece[512];
        int n = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, true);
        if (n < 0) { ok = false; break; }
        answer.append(piece, n);
        if (answer.size() > 12000) break;
        put_tokens(batch, &token, 1, promptCount + i, true);
        ok = llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch) == 0;
    }
    if (batch) llama_batch_ext_free(batch);
    llama_sampler_free(sampler);
    llama_free(ctx);
    llama_model_free(model);
    if (!ok) { fail(env, "Échec du décodage llama.cpp."); return nullptr; }
    return env->NewStringUTF(answer.c_str());
}
