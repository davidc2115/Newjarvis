// Pont JNI entre Kotlin (NativeLlama.kt) et llama.cpp.
// Basé sur l'exemple officiel examples/simple/simple.cpp du dépôt llama.cpp
// (API vérifiée sur le tag b4753), adapté pour un usage mobile CPU-only
// avec un modèle chargé une fois et réutilisé entre les appels.

#include <jni.h>
#include <string>
#include <vector>
#include <thread>
#include <algorithm>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "JarvisLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
    llama_model* g_model = nullptr;
    llama_context* g_ctx = nullptr;
    std::string g_loaded_path;
    bool g_backend_initialized = false;

    void unload_internal() {
        if (g_ctx != nullptr) {
            llama_free(g_ctx);
            g_ctx = nullptr;
        }
        if (g_model != nullptr) {
            llama_model_free(g_model);
            g_model = nullptr;
        }
        g_loaded_path.clear();
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jarvis_assistant_NativeLlama_loadModel(JNIEnv* env, jobject /* this */, jstring modelPathJ) {
    const char* pathChars = env->GetStringUTFChars(modelPathJ, nullptr);
    std::string pathStr(pathChars);
    env->ReleaseStringUTFChars(modelPathJ, pathChars);

    if (g_model != nullptr && g_loaded_path == pathStr) {
        return JNI_TRUE; // déjà chargé, rien à faire
    }

    unload_internal();

    if (!g_backend_initialized) {
        ggml_backend_load_all();
        g_backend_initialized = true;
    }

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // CPU uniquement sur mobile

    LOGI("Chargement du modèle : %s", pathStr.c_str());
    g_model = llama_model_load_from_file(pathStr.c_str(), model_params);
    if (g_model == nullptr) {
        LOGE("Échec du chargement du modèle");
        return JNI_FALSE;
    }

    llama_context_params ctx_params = llama_context_default_params();
    // BUG RÉEL CORRIGÉ (signalement utilisateur : "quand je télécharge une IA locale comme
    // Qwen, la conversation est trop longue pour le modèle local") : n_ctx=2048 rendait
    // l'échec SYSTÉMATIQUE et immédiat, dès le premier message -- le seul prompt système de
    // JARVIS (ApiClient.SYSTEM_PROMPT, ~6700 tokens) dépasse déjà 2048 tokens à lui seul, avant
    // même d'ajouter l'historique de conversation. Ce n'était donc pas "la conversation devient
    // trop longue avec le temps" mais un plafond trop bas pour fonctionner ne serait-ce qu'une
    // fois. 4096 laisse une vraie marge pour un prompt système allégé (voir LOCAL_SYSTEM_PROMPT
    // dans ApiClient.kt, utilisé spécifiquement par sendLocal()) + plusieurs tours de
    // conversation, tout en restant raisonnable en RAM pour un modèle quantifié 1-4B sur
    // téléphone (le cache KV grandit avec n_ctx).
    ctx_params.n_ctx = 4096;
    ctx_params.n_batch = 512;
    unsigned int hw = std::thread::hardware_concurrency();
    int n_threads = hw > 0 ? std::min<unsigned int>(hw, 6) : 4;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;

    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (g_ctx == nullptr) {
        LOGE("Échec de la création du contexte");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    g_loaded_path = pathStr;
    LOGI("Modèle chargé avec succès (%d threads)", n_threads);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jarvis_assistant_NativeLlama_generate(JNIEnv* env, jobject /* this */, jstring promptJ, jint maxTokens) {
    if (g_model == nullptr || g_ctx == nullptr) {
        return env->NewStringUTF("[ERREUR] Aucun modèle chargé.");
    }

    const char* promptChars = env->GetStringUTFChars(promptJ, nullptr);
    std::string prompt(promptChars);
    env->ReleaseStringUTFChars(promptJ, promptChars);

    const llama_vocab* vocab = llama_model_get_vocab(g_model);

    const int n_prompt = -llama_tokenize(
        vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);

    std::vector<llama_token> prompt_tokens(n_prompt);
    if (llama_tokenize(
            vocab, prompt.c_str(), (int32_t) prompt.size(),
            prompt_tokens.data(), (int32_t) prompt_tokens.size(), true, true) < 0) {
        return env->NewStringUTF("[ERREUR] Échec de la tokenisation du prompt.");
    }

    const uint32_t n_ctx = llama_n_ctx(g_ctx);
    if ((uint32_t) n_prompt >= n_ctx) {
        return env->NewStringUTF(
            "[ERREUR] La conversation est trop longue pour le contexte du modèle local. "
            "Démarre une nouvelle conversation.");
    }

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(1234));

    std::string result;
    llama_batch batch = llama_batch_get_one(prompt_tokens.data(), (int32_t) prompt_tokens.size());

    int generated = 0;
    int limit = std::min((int) maxTokens, (int) (n_ctx - (uint32_t) n_prompt) - 1);

    while (generated < limit) {
        if (llama_decode(g_ctx, batch)) {
            LOGE("Échec de llama_decode");
            break;
        }

        llama_token new_token = llama_sampler_sample(smpl, g_ctx, -1);

        if (llama_vocab_is_eog(vocab, new_token)) {
            break;
        }

        char buf[128];
        int n = llama_token_to_piece(vocab, new_token, buf, sizeof(buf), 0, true);
        if (n < 0) break;
        result.append(buf, n);

        batch = llama_batch_get_one(&new_token, 1);
        generated++;
    }

    llama_sampler_free(smpl);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_jarvis_assistant_NativeLlama_unload(JNIEnv* /* env */, jobject /* this */) {
    unload_internal();
}
