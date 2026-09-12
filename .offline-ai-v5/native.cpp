#include <jni.h>
#include <llama.h>
#include <sentencepiece_processor.h>
#include <atomic>
#include <string>
#include <vector>
#include <memory>
#include <mutex>
#include <algorithm>
#include <stdexcept>

static std::atomic<bool> cancelled(false);
static std::once_flag initialized;
static void error(JNIEnv *env, const char *msg) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), msg);
}
static std::string utf8(JNIEnv *env, jbyteArray arr) {
    jsize n=env->GetArrayLength(arr); std::string s(n,'\0');
    if(n) env->GetByteArrayRegion(arr,0,n,reinterpret_cast<jbyte*>(&s[0])); return s;
}
static std::string path(JNIEnv *env,jstring s) {
    const char *p=env->GetStringUTFChars(s,nullptr); std::string out(p?p:"");
    if(p) env->ReleaseStringUTFChars(s,p); return out;
}
extern "C" JNIEXPORT void JNICALL Java_org_tms_offline_NativeAi_cancel(JNIEnv*,jclass){cancelled.store(true);}
extern "C" JNIEXPORT jlong JNICALL Java_org_tms_offline_NativeAi_openTokenizer(JNIEnv* e,jclass,jstring filename) {
    try { auto p=std::make_unique<sentencepiece::SentencePieceProcessor>();
        if(!p->Load(path(e,filename)).ok()) {error(e,"Cannot load the local tokenizer");return 0;}
        return reinterpret_cast<jlong>(p.release());
    } catch(const std::exception& ex) {error(e,ex.what());return 0;}
}
extern "C" JNIEXPORT void JNICALL Java_org_tms_offline_NativeAi_closeTokenizer(JNIEnv*,jclass,jlong handle) {
    delete reinterpret_cast<sentencepiece::SentencePieceProcessor*>(handle);
}
extern "C" JNIEXPORT jintArray JNICALL Java_org_tms_offline_NativeAi_tokenize(JNIEnv* e,jclass,jlong handle,jbyteArray text,jint limit) {
    if(!handle){error(e,"Tokenizer is not loaded");return nullptr;}
    try {std::vector<int> v;auto* p=reinterpret_cast<sentencepiece::SentencePieceProcessor*>(handle);
        if(!p->Encode(utf8(e,text),&v).ok()){error(e,"Tokenization failed");return nullptr;}
        if((int)v.size()>limit-2)v.resize(std::max(0,limit-2));
        std::vector<jint> ids;ids.reserve(v.size()+2);ids.push_back(0);
        for(int id:v)ids.push_back(id==0?3:id+1);ids.push_back(2);
        auto a=e->NewIntArray(ids.size());e->SetIntArrayRegion(a,0,ids.size(),ids.data());return a;
    }catch(const std::exception& ex){error(e,ex.what());return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_org_tms_offline_NativeAi_generate(JNIEnv* e,jclass,jstring filename,jbyteArray input,jint maxTokens,jint context,jint threads,jobject sink){
    cancelled.store(false);
    try{
        std::call_once(initialized,[]{llama_backend_init();});
        auto cls=e->GetObjectClass(sink);
        auto chunk=e->GetMethodID(cls,"onChunk","([B)V");
        auto progress=e->GetMethodID(cls,"onProgress","(II)V");
        if(!chunk||!progress)return;
        auto mp=llama_model_default_params();mp.n_gpu_layers=0;mp.use_mmap=true;mp.use_mlock=false;
        std::unique_ptr<llama_model,decltype(&llama_model_free)> model(llama_model_load_from_file(path(e,filename).c_str(),mp),llama_model_free);
        if(!model){error(e,"Local GGUF could not be loaded");return;}
        if(cancelled.load())return;
        const auto *vocab=llama_model_get_vocab(model.get());
        auto prompt=utf8(e,input);
        int n=-llama_tokenize(vocab,prompt.data(),prompt.size(),nullptr,0,true,true);
        if(n<=0 || n>12000){error(e,"Invalid or oversized prompt");return;}
        int ctxSize=std::max(1024,std::min(4096,(int)context));
        int predict=std::max(16,std::min(768,(int)maxTokens));
        if(n+predict+8>ctxSize){error(e,"Evidence exceeds context budget; shorten the question or evidence");return;}
        std::vector<llama_token> tokens(n);
        if(llama_tokenize(vocab,prompt.data(),prompt.size(),tokens.data(),tokens.size(),true,true)<0){error(e,"Prompt tokenization failed");return;}
        auto cp=llama_context_default_params();cp.n_ctx=ctxSize;cp.n_batch=128;cp.n_ubatch=64;
        cp.n_threads=std::max(1,std::min(4,(int)threads));cp.n_threads_batch=cp.n_threads;
        cp.abort_callback=[](void*){return cancelled.load();};cp.abort_callback_data=nullptr;
        std::unique_ptr<llama_context,decltype(&llama_free)> ctx(llama_init_from_model(model.get(),cp),llama_free);
        if(!ctx){error(e,"Insufficient memory for the local context");return;}
        auto sp=llama_sampler_chain_default_params();sp.no_perf=true;
        std::unique_ptr<llama_sampler,decltype(&llama_sampler_free)> sampler(llama_sampler_chain_init(sp),llama_sampler_free);
        llama_sampler_chain_add(sampler.get(),llama_sampler_init_penalties(96,1.12f,0.0f,0.0f));
        llama_sampler_chain_add(sampler.get(),llama_sampler_init_top_k(20));
        llama_sampler_chain_add(sampler.get(),llama_sampler_init_top_p(0.8f,1));
        llama_sampler_chain_add(sampler.get(),llama_sampler_init_temp(0.2f));
        llama_sampler_chain_add(sampler.get(),llama_sampler_init_dist(42));
        for(int i=0;i<n && !cancelled.load();i+=128){
            int count=std::min(128,n-i);auto batch=llama_batch_get_one(tokens.data()+i,count);
            int rc=llama_decode(ctx.get(),batch);
            if(rc){if(!cancelled.load())error(e,"Local prompt evaluation failed");return;}
            e->CallVoidMethod(sink,progress,i+count,n);if(e->ExceptionCheck())return;
        }
        for(int i=0;i<predict && !cancelled.load();i++){
            auto token=llama_sampler_sample(sampler.get(),ctx.get(),-1);
            if(llama_vocab_is_eog(vocab,token))break;
            char small[256];int len=llama_token_to_piece(vocab,token,small,sizeof(small),0,true);
            std::vector<char> large;
            const char* data=small;
            if(len<0){large.resize(-len);len=llama_token_to_piece(vocab,token,large.data(),large.size(),0,true);data=large.data();}
            if(len<0){error(e,"Token decoding failed");return;}
            auto a=e->NewByteArray(len);if(!a)return;
            if(len)e->SetByteArrayRegion(a,0,len,reinterpret_cast<const jbyte*>(data));
            e->CallVoidMethod(sink,chunk,a);e->DeleteLocalRef(a);if(e->ExceptionCheck())return;
            auto batch=llama_batch_get_one(&token,1);int rc=llama_decode(ctx.get(),batch);
            if(rc){if(!cancelled.load())error(e,"Local token evaluation failed");return;}
        }
        e->DeleteLocalRef(cls);
    }catch(const std::bad_alloc&){error(e,"Not enough memory for this model");}
    catch(const std::exception& ex){error(e,ex.what());}
    catch(...){error(e,"Native local inference error");}
}
