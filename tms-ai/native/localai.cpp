#include <jni.h>
#include "llama.h"
#include "sentencepiece_processor.h"
#include <atomic>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
std::atomic<bool> cancelled{false};
std::mutex generate_mutex, sp_mutex, index_mutex;
std::unique_ptr<sentencepiece::SentencePieceProcessor> sp;
std::string sp_path, vector_path;
std::vector<int8_t> vectors;
std::vector<float> scales;
std::once_flag backend_once;
std::string utf8(JNIEnv *e, jbyteArray data) {
    if (!data) throw std::runtime_error("Missing UTF-8 input");
    jsize n=e->GetArrayLength(data);
    std::string out(n,'\0');
    if(n)e->GetByteArrayRegion(data,0,n,reinterpret_cast<jbyte*>(&out[0]));
    return out;
}
std::string path(JNIEnv *e, jstring data) {
    if (!data) throw std::runtime_error("Missing path");
    const char *p=e->GetStringUTFChars(data,nullptr);
    if (!p) throw std::runtime_error("Path allocation failed");
    std::string out(p); e->ReleaseStringUTFChars(data,p); return out;
}
void fail(JNIEnv *e,const std::exception &x) {
    if (!e->ExceptionCheck()) e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),x.what());
}
bool abort_callback(void*) { return cancelled.load(std::memory_order_relaxed); }
void stage(JNIEnv *e,jobject cb,jmethodID method,const char *s) {
    jstring text=e->NewStringUTF(s); e->CallVoidMethod(cb,method,text); e->DeleteLocalRef(text);
    if(e->ExceptionCheck()) throw std::runtime_error("Generation listener failed");
}
struct ModelDelete { void operator()(llama_model *p)const{if(p)llama_model_free(p);} };
struct ContextDelete { void operator()(llama_context *p)const{if(p)llama_free(p);} };
struct SamplerDelete { void operator()(llama_sampler *p)const{if(p)llama_sampler_free(p);} };
}
extern "C" JNIEXPORT void JNICALL Java_org_tms_offline_NativeEngine_cancel(JNIEnv*,jclass) {
    cancelled.store(true,std::memory_order_relaxed);
}
extern "C" JNIEXPORT jintArray JNICALL Java_org_tms_offline_NativeEngine_tokenizeE5(JNIEnv *e,jclass,jstring model_path,jbyteArray text,jint limit) {
    try {
        if(limit<4||limit>512)throw std::runtime_error("Invalid tokenizer limit");
        std::lock_guard<std::mutex> lock(sp_mutex);
        std::string requested=path(e,model_path);
        if(!sp||sp_path!=requested){
            auto next=std::make_unique<sentencepiece::SentencePieceProcessor>();
            auto status=next->Load(requested);
            if(!status.ok())throw std::runtime_error("Cannot load SentencePiece vocabulary");
            sp=std::move(next);sp_path=requested;
        }
        std::vector<int> raw;
        auto status=sp->Encode(utf8(e,text),&raw);
        if(!status.ok())throw std::runtime_error("SentencePiece encoding failed");
        std::vector<jint> ids;ids.reserve(limit);ids.push_back(0);
        for(int id:raw){if((int)ids.size()>=limit-1)break;ids.push_back(id==0?3:id+1);}
        ids.push_back(2);
        jintArray result=e->NewIntArray(ids.size());
        e->SetIntArrayRegion(result,0,ids.size(),ids.data());return result;
    }catch(const std::exception &x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT jfloatArray JNICALL Java_org_tms_offline_NativeEngine_scoreIndex(JNIEnv *e,jclass,jstring vec_file,jstring scale_file,jfloatArray query) {
    try {
        if(e->GetArrayLength(query)!=384)throw std::runtime_error("Embedding dimension mismatch");
        std::lock_guard<std::mutex> lock(index_mutex);
        std::string requested=path(e,vec_file);
        if(vector_path!=requested||vectors.empty()){
            std::ifstream a(requested,std::ios::binary|std::ios::ate);
            if(!a)throw std::runtime_error("Semantic index missing");
            auto n=a.tellg();if(n<=0||n%384||n>256*1024*1024)throw std::runtime_error("Invalid semantic index length");
            std::vector<int8_t> nv((size_t)n);a.seekg(0);a.read((char*)nv.data(),nv.size());
            if(!a)throw std::runtime_error("Incomplete semantic index");
            std::ifstream b(path(e,scale_file),std::ios::binary|std::ios::ate);
            if(!b||b.tellg()!=(std::streamoff)((nv.size()/384)*sizeof(float)))throw std::runtime_error("Semantic scale length mismatch");
            std::vector<float> ns(nv.size()/384);b.seekg(0);b.read((char*)ns.data(),ns.size()*sizeof(float));
            if(!b)throw std::runtime_error("Incomplete semantic scales");
            vectors.swap(nv);scales.swap(ns);vector_path=requested;
        }
        float q[384];e->GetFloatArrayRegion(query,0,384,q);
        std::vector<float> out(scales.size());
        for(size_t row=0;row<out.size();++row){
            float dot=0;const int8_t *v=vectors.data()+row*384;
            for(int k=0;k<384;++k)dot+=q[k]*v[k];
            out[row]=dot*scales[row];
        }
        jfloatArray result=e->NewFloatArray(out.size());e->SetFloatArrayRegion(result,0,out.size(),out.data());return result;
    }catch(const std::exception &x){fail(e,x);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_org_tms_offline_NativeEngine_releaseIndex(JNIEnv*,jclass){
    std::lock_guard<std::mutex> lock(index_mutex);std::vector<int8_t>().swap(vectors);std::vector<float>().swap(scales);vector_path.clear();
}
extern "C" JNIEXPORT jint JNICALL Java_org_tms_offline_NativeEngine_generate(JNIEnv *e,jclass,jstring file,jbyteArray prompt,jint output_limit,jint context_limit,jint threads,jobject callback) {
    try {
        std::unique_lock<std::mutex> lock(generate_mutex,std::try_to_lock);
        if(!lock.owns_lock())throw std::runtime_error("Another generation is active");
        cancelled.store(false,std::memory_order_relaxed);
        if(output_limit<16||output_limit>768||context_limit<1024||context_limit>8192)throw std::runtime_error("Invalid generation bounds");
        jclass c=e->GetObjectClass(callback);
        jmethodID on_bytes=e->GetMethodID(c,"onBytes","([B)V");
        jmethodID on_stage=e->GetMethodID(c,"onStage","(Ljava/lang/String;)V");
        if(!on_bytes||!on_stage)throw std::runtime_error("Invalid generation listener");
        std::call_once(backend_once,[]{llama_backend_init();});
        stage(e,callback,on_stage,"loading_model");
        llama_model_params mp=llama_model_default_params();mp.n_gpu_layers=0;
        std::unique_ptr<llama_model,ModelDelete> model(llama_model_load_from_file(path(e,file).c_str(),mp));
        if(!model)throw std::runtime_error("Cannot load the local GGUF model");
        if(cancelled.load())return 1;
        const llama_vocab *vocab=llama_model_get_vocab(model.get());
        std::string p=utf8(e,prompt);
        int required=-llama_tokenize(vocab,p.data(),p.size(),nullptr,0,true,true);
        if(required<=0||required+output_limit+8>context_limit)throw std::runtime_error("Evidence exceeds model context; reduce source excerpts");
        std::vector<llama_token> tokens(required);
        int count=llama_tokenize(vocab,p.data(),p.size(),tokens.data(),tokens.size(),true,true);
        if(count<1)throw std::runtime_error("Prompt tokenization failed");
        tokens.resize(count);
        llama_context_params cp=llama_context_default_params();
        cp.n_ctx=context_limit;cp.n_batch=128;cp.n_ubatch=64;
        cp.n_threads=std::clamp((int)threads,1,6);cp.n_threads_batch=cp.n_threads;cp.no_perf=true;
        cp.abort_callback=abort_callback;cp.abort_callback_data=nullptr;
        std::unique_ptr<llama_context,ContextDelete> ctx(llama_init_from_model(model.get(),cp));
        if(!ctx)throw std::runtime_error("Not enough memory for model context");
        auto sampler_params=llama_sampler_chain_default_params();sampler_params.no_perf=true;
        std::unique_ptr<llama_sampler,SamplerDelete> sampler(llama_sampler_chain_init(sampler_params));
        llama_sampler_chain_add(sampler.get(),llama_sampler_init_penalties(llama_vocab_n_tokens(vocab),64,1.08f,0.0f,0.0f));
        llama_sampler_chain_add(sampler.get(),llama_sampler_init_top_k(20));
        llama_sampler_chain_add(sampler.get(),llama_sampler_init_top_p(0.8f,1));
        llama_sampler_chain_add(sampler.get(),llama_sampler_init_temp(0.35f));
        llama_sampler_chain_add(sampler.get(),llama_sampler_init_dist(42));
        stage(e,callback,on_stage,"reading_evidence");
        for(int pos=0;pos<count;){
            if(cancelled.load())return 1;
            int take=std::min(128,count-pos);
            llama_batch batch=llama_batch_get_one(tokens.data()+pos,take);
            if(llama_decode(ctx.get(),batch)){if(cancelled.load())return 1;throw std::runtime_error("Prompt evaluation failed");}
            pos+=take;
        }
        stage(e,callback,on_stage,"generating");
        for(int i=0;i<output_limit;++i){
            if(cancelled.load())return 1;
            llama_token token=llama_sampler_sample(sampler.get(),ctx.get(),-1);
            if(llama_vocab_is_eog(vocab,token))return 0;
            std::vector<char> piece(256);
            int n=llama_token_to_piece(vocab,token,piece.data(),piece.size(),0,false);
            if(n<0){piece.resize(-n);n=llama_token_to_piece(vocab,token,piece.data(),piece.size(),0,false);}
            if(n<0)throw std::runtime_error("Token rendering failed");
            if(n){jbyteArray bytes=e->NewByteArray(n);e->SetByteArrayRegion(bytes,0,n,reinterpret_cast<const jbyte*>(piece.data()));e->CallVoidMethod(callback,on_bytes,bytes);e->DeleteLocalRef(bytes);if(e->ExceptionCheck())return -1;}
            llama_batch next=llama_batch_get_one(&token,1);
            if(llama_decode(ctx.get(),next)){if(cancelled.load())return 1;throw std::runtime_error("Generation evaluation failed");}
        }
        return 2;
    }catch(const std::exception &x){fail(e,x);return -1;}
}
