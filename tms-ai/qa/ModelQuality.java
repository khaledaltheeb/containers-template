import org.tms.offline.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class ModelQuality {
 public static void main(String[] args)throws Exception{
  if(!NativeEngine.AVAILABLE)throw new AssertionError(NativeEngine.ERROR);
  String model=args[0],sp=args[1];
  String[] tokenTexts={"query: hydraulic pressure","passage: Stop the pump before inspection.","query: ما هو ضغط الزيت؟","query: فحص الطائرة والصيانة الدورية","query: 25 hours ١٢٣ café 😀"};
  try(PrintWriter p=new PrintWriter("quality/tokenizer.tsv","UTF-8")){for(String t:tokenTexts)p.println(Base64.getEncoder().encodeToString(t.getBytes(StandardCharsets.UTF_8))+"\t"+Arrays.toString(NativeEngine.tokenizeE5(sp,t.getBytes(StandardCharsets.UTF_8),256)));}
  String[][] tests={{"What is the filter replacement interval?","The filter replacement interval is 25 operating hours. Inspect the filter before installation.","25"},{"ما مدة استبدال الفلتر حسب المرجع؟","The filter replacement interval is 25 operating hours. Inspect the filter before installation.","25"},{"ما الضغط المرجعي المذكور؟","The approved reference pressure is 60 psi. Check the complete procedure before performing maintenance.","60"},{"What is the color of the housing?","The filter replacement interval is 25 operating hours. Inspect the filter before installation.",""}};
  boolean all=true;
  for(int i=0;i<tests.length;i++){
   Store.Hit h=new Store.Hit();h.text=tests[i][1];List<Store.Hit> src=Arrays.asList(h);ByteArrayOutputStream output=new ByteArrayOutputStream();long start=System.currentTimeMillis();
   int status=NativeEngine.generate(model,Grounding.prompt(tests[i][0],src).getBytes(StandardCharsets.UTF_8),384,4096,3,new NativeEngine.Listener(){public void onBytes(byte[] b){output.write(b,0,b.length);}public void onStage(String s){System.out.println(s);}});
   String text=Grounding.clean(output.toString("UTF-8")),issue=Grounding.validate(text,src);boolean ok=status==0&&(tests[i][2].isEmpty()||issue.isEmpty()&&Store.normalize(text).contains(tests[i][2]));
   if(i==1||i==2)ok &= java.util.regex.Pattern.compile("[\\u0600-\\u06ff]{2,}").matcher(text).find();
   String report="QUESTION: "+tests[i][0]+"\nSTATUS: "+status+"\nMILLISECONDS: "+(System.currentTimeMillis()-start)+"\nPASSED: "+ok+"\nVALIDATOR: "+issue+"\nANSWER:\n"+text+"\n";
   Files.write(Paths.get("quality","case-"+i+".txt"),report.getBytes(StandardCharsets.UTF_8));System.out.println(report);all &= ok;
  }
  Files.write(Paths.get("quality","outcome.txt"),(all?"ALL_TARGETED_CHECKS_PASSED":"SOME_TARGETED_CHECKS_FAILED").getBytes(StandardCharsets.UTF_8));
 }
}
