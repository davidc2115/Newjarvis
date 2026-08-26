// Pont JNI entre Kotlin (NativeLlama.kt) et llama.cpp.
// Basé sur l'exemple officiel examples/simple/simple.cpp du dépôt llama.cpp
// (API vérifiée sur le tag b4753), adapté pour un usage mobile CPU-only
// avec un modèle chargé une fois et réutilisé entre les appels.

#include <jni.h>
#include <string>
#include <vector>
#include <thread>
#include <algorithm>
#include <exception>
#include <stdexcept>
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
    // REVERT (signalement utilisateur : après ce changement, le chargement du modèle ÉCHOUE
    // ou dépasse le délai -- "❌ Échec du chargement du modèle .gguf (ou délai dépassé)" observé
    // en conditions réelles, alors que le chargement fonctionnait avant). use_mlock avait été
    // activé pour empêcher l'éviction des pages du modèle en cours d'inférence (RAM Plus /
    // extension mémoire par le stockage) -- mais l'utilisateur a désactivé cette extension RAM
    // ET le blocage persistait, prouvant que ce n'était pas la cause réelle de SES symptômes.
    // En pratique, mlock() force la lecture SYNCHRONE de tout le fichier modèle (~1-2 Go) en RAM
    // dès le chargement (au lieu du mmap paresseux par défaut, qui ne lit que ce qui est
    // réellement utilisé, au fur et à mesure) -- sur un stockage de téléphone plus lent qu'un
    // SSD, ce chargement d'un coup peut largement dépasser le délai de chargement, ce qui
    // explique précisément l'échec observé. Revient donc au comportement par défaut (mmap
    // paresseux, use_mlock=false) : chargement rapide, quitte à ce que des pages soient
    // éventuellement rechargées à la demande pendant la génération si nécessaire.
    // model_params.use_mlock = true; -- désactivé, voir commentaire ci-dessus.

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
    // REVERT (signalement utilisateur juste après cet ajout : "même Llama 1B très lent/ne
    // répond pas") : flash_attn avait été activé pour "des modèles ultra rapides", mais son
    // implémentation CPU dans llama.cpp cible surtout le traitement PARALLÈLE d'un long prompt
    // (prefill), pas la génération token-par-token où le principal coût est déjà la lecture des
    // poids -- sans pouvoir mesurer sur un vrai téléphone depuis cet environnement, le risque
    // qu'il ajoute de la latence (voire un chemin de code moins optimisé sur certains cœurs ARM)
    // sans bénéfice réel en CPU-only n'est pas justifié. Redevient la valeur par défaut (false)
    // tant qu'un gain mesurable n'est pas confirmé.
    // ctx_params.flash_attn = true; -- désactivé, voir commentaire ci-dessus.

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

    // BUG RÉEL CORRIGÉ (contribue au signalement "très lent voire ne répond pas") : ce pont
    // JNI suit le modèle "simple.cpp" (un seul échange, tout le prompt décodé d'un coup), mais
    // g_ctx est réutilisé TEL QUEL d'un message à l'autre (rechargé seulement si le CHEMIN du
    // modèle change) sans jamais vider son cache KV. Chaque nouveau message renvoie pourtant le
    // system prompt + historique COMPLET depuis zéro (voir buildPromptFromHistory côté Kotlin) :
    // sans ce clear, ces tokens s'empilaient dans le cache à la suite de ceux déjà décodés lors
    // des messages précédents, au lieu d'être réutilisés ou remplacés -- le cache se remplissait
    // donc bien plus vite que ne le laissait croire la seule longueur du prompt courant, avec un
    // ralentissement progressif au fil de la conversation pouvant aller jusqu'à un blocage.
    llama_kv_cache_clear(g_ctx);

    try {
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
    } catch (const std::exception& e) {
        // BUG RÉEL CORRIGÉ : une exception C++ (ex: llama_decode qui reçoit d'un coup plus de
        // tokens que n_batch, cache KV saturé...) traversant la frontière JNI sans être
        // interceptée termine le processus natif ENTIER (std::terminate), donc toute l'app --
        // ce qui, côté utilisateur, se voit exactement comme "JARVIS ne répond pas" (silence
        // total, pas de message d'erreur). Toute exception est désormais convertie en réponse
        // d'erreur normale, affichée dans le chat au lieu de faire planter l'app.
        LOGE("Exception pendant generate() : %s", e.what());
        std::string msg = std::string("[ERREUR] Le moteur local a rencontré un problème : ") + e.what();
        return env->NewStringUTF(msg.c_str());
    } catch (...) {
        LOGE("Exception inconnue pendant generate()");
        return env->NewStringUTF("[ERREUR] Le moteur local a rencontré un problème inattendu.");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_jarvis_assistant_NativeLlama_unload(JNIEnv* /* env */, jobject /* this */) {
    unload_internal();
}
