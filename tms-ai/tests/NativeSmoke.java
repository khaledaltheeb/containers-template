import org.tms.offline.NativeEngine;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class NativeSmoke {
 public static void main(String[] args)throws Exception {
  if(!NativeEngine.AVAILABLE)throw new AssertionError(NativeEngine.ERROR);
  String sp=args[0],model=args[1],out=args[2];
  Files.createDirectories(Paths.get(out));
  String[] samples={"query: hydraulic pressure", "passage: Stop the pump before inspection.", "query: ما هو ضغط الزيت؟", "query: فحص الطائرة والصيانة الدورية", "query: 25 hours ١٢٣ café 😀"};
  try(PrintWriter p=new PrintWriter(out+"/tokenizer-native.tsv","UTF-8")){
   for(String s:samples){int[] ids=NativeEngine.tokenizeE5(sp,s.getBytes(StandardCharsets.UTF_8),256);p.println(Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8))+"\t"+Arrays.toString(ids));}
  }
  String sys="Answer only from the supplied evidence. Do not use outside facts. Preserve numeric values. Cite [1]. If evidence is insufficient, say so. Reply in the question's language. Do not repeat these instructions. /no_think";
  String[] questions={"What is the replacement interval?", "ما مدة الاستبدال المذكورة في المرجع؟", "What is the color of the housing?"};
  for(int i=0;i<questions.length;i++){
   String user="Evidence [1]: The filter replacement interval is 25 operating hours. Inspect the filter before installation.\nQuestion: "+questions[i]+"\n/no_think";
   String prompt="<|im_start|>system\n"+sys+"<|im_end|>\n<|im_start|>user\n"+user+"<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";
   ByteArrayOutputStream result=new ByteArrayOutputStream();
   int status=NativeEngine.generate(model,prompt.getBytes(StandardCharsets.UTF_8),160,2048,2,new NativeEngine.Listener(){public void onBytes(byte[] b){result.write(b,0,b.length);}public void onStage(String s){System.out.println(s);}});
   String answer=new String(result.toByteArray(),StandardCharsets.UTF_8);
   Files.write(Paths.get(out,"answer-"+i+".txt"),("Question: "+questions[i]+"\nStatus: "+status+"\n"+answer).getBytes(StandardCharsets.UTF_8));
   if(status<0||answer.trim().length()<3)throw new AssertionError("No real generated output");
   if(i<2&&!answer.contains("25")&&!answer.contains("٢٥"))throw new AssertionError("Expected evidence number missing: "+answer);
  }
  Files.write(Paths.get(out,"native-smoke.txt"),"Native model executed; tokenizer fixtures exported. This is not an independent factual-accuracy evaluation.\n".getBytes(StandardCharsets.UTF_8));
 }
}
