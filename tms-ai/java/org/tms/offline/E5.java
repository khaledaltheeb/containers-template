package org.tms.offline;
import android.content.Context;
import ai.onnxruntime.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class E5 implements AutoCloseable {
 private final OrtEnvironment env;
 private final OrtSession session;
 private final String tokenizer;
 private final Set<String> inputs;
 public E5(Context c)throws Exception{
  if(!NativeEngine.AVAILABLE)throw new IOException("Native AI is unavailable on this device: "+NativeEngine.ERROR);
  File dir=new File(c.getFilesDir(),"runtime");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create model directory");
  File model=provision(c,"models/e5/model_quantized.onnx",new File(dir,"e5.onnx"));
  tokenizer=provision(c,"models/e5/sentencepiece.bpe.model",new File(dir,"sentencepiece.bpe.model")).getAbsolutePath();
  env=OrtEnvironment.getEnvironment();
  try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()){
   options.setIntraOpNumThreads(Math.max(1,Math.min(3,Runtime.getRuntime().availableProcessors())));
   options.setInterOpNumThreads(1);
   options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
   options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
   session=env.createSession(model.getAbsolutePath(),options);
  }
  inputs=session.getInputNames();
 }
 public static synchronized File provision(Context c,String asset,File target)throws Exception{
  JSONObject catalog=new JSONObject(Store.read(c.getAssets().open("asset-hashes.json"),512*1024));
  JSONObject metadata=catalog.getJSONObject(asset);
  if(target.isFile()&&target.length()==metadata.getLong("bytes"))return target;
  File temp=new File(target.getAbsolutePath()+".tmp");target.getParentFile().mkdirs();
  try(InputStream in=c.getAssets().open(asset);FileOutputStream out=new FileOutputStream(temp)){
   byte[] b=new byte[1024*1024];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.getFD().sync();
  }
  if(temp.length()!=metadata.getLong("bytes")||!Store.sha(temp).equals(metadata.getString("sha256"))){temp.delete();throw new IOException("Bundled model failed integrity verification");}
  if(target.exists()&&!target.delete())throw new IOException("Cannot replace incomplete model");
  if(!temp.renameTo(target))throw new IOException("Cannot activate local model");return target;
 }
 public float[] embed(String text,boolean query)throws Exception{
  int[] ids=NativeEngine.tokenizeE5(tokenizer,((query?"query: ":"passage: ")+text).getBytes(StandardCharsets.UTF_8),256);
  long[][] input=new long[1][ids.length],mask=new long[1][ids.length],types=new long[1][ids.length];
  for(int i=0;i<ids.length;i++){input[0][i]=ids[i];mask[0][i]=1;}
  Map<String,OnnxTensor> feed=new HashMap<>();
  try{
   feed.put("input_ids",OnnxTensor.createTensor(env,input));
   if(inputs.contains("attention_mask"))feed.put("attention_mask",OnnxTensor.createTensor(env,mask));
   if(inputs.contains("token_type_ids"))feed.put("token_type_ids",OnnxTensor.createTensor(env,types));
   try(OrtSession.Result result=session.run(feed)){
    Object value=result.get(0).getValue();float[] pooled=new float[384];
    if(value instanceof float[][][]){float[][] token=((float[][][])value)[0];if(token.length!=ids.length||token[0].length!=384)throw new IOException("E5 output shape mismatch");for(float[] row:token)for(int k=0;k<384;k++)pooled[k]+=row[k]/ids.length;}
    else if(value instanceof float[][]){float[] row=((float[][])value)[0];if(row.length!=384)throw new IOException("E5 dimension mismatch");System.arraycopy(row,0,pooled,0,384);}
    else throw new IOException("Unsupported E5 output tensor");
    double norm=0;for(float x:pooled)norm+=x*x;norm=Math.sqrt(norm);if(norm<1e-10)throw new IOException("Invalid E5 vector");for(int k=0;k<384;k++)pooled[k]/=norm;return pooled;
   }
  }finally{for(OnnxTensor t:feed.values())t.close();}
 }
 @Override public void close(){try{session.close();}catch(Exception ignored){}}
}
