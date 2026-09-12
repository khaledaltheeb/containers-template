package org.tms.offline;

public final class NativeEngine {
    public static final boolean AVAILABLE;
    public static final String ERROR;
    static {
        boolean ok=false;String error="";
        try { System.loadLibrary("tms_local_ai");ok=true; }
        catch(Throwable e){error=e.toString();}
        AVAILABLE=ok;ERROR=error;
    }
    private NativeEngine(){}
    public interface Listener {
        void onBytes(byte[] bytes);
        void onStage(String stage);
    }
    public static native int[] tokenizeE5(String sentencepiecePath,byte[] text,int limit);
    public static native float[] scoreIndex(String vectorPath,String scalePath,float[] query);
    public static native void releaseIndex();
    public static native int generate(String modelPath,byte[] prompt,int maxTokens,int contextTokens,int threads,Listener listener);
    public static native void cancel();
}
