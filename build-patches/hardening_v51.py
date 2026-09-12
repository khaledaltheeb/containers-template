from pathlib import Path
import sys
R=Path(sys.argv[1] if len(sys.argv)>1 else 'tms-ai')
J=R/'java/org/tms/offline'
def change(path,old,new):
 s=path.read_text();assert old in s,(path,old[:80]);path.write_text(s.replace(old,new))
change(R/'tests/android/DeviceTests.java','store.search("seed reference",-1,null);record("bundled_source_mapping",!seed.isEmpty()&&seed.get(0).pageNo==0,','store.search("synthetic bundled importer fixture",-1,null);record("bundled_source_mapping",!seed.isEmpty()&&seed.get(0).pageNo==0&&store.page(seed.get(0).page).getString("text").contains("synthetic bundled"),')
change(J/'Grounding.java','Do not invent details.','Do not invent details. Preserve technical abbreviations and their original English expansions verbatim, even in an Arabic answer. Never guess or replace a technical expansion with an unverified translation.')
change(J/'Grounding.java','user.append("\\nQUESTION: ")','user.append(SourceTerms.guide(question,sources));\n  user.append("\\nQUESTION: ")')
change(J/'AiService.java','Grounding.validate(text,evidence)','Grounding.validate(text,evidence);if(issue.isEmpty())issue=SourceTerms.validate(q,text,evidence)')
# Preserve a previously valid vector pair when restoration is cancelled.
p=J/'Importer.java';s=p.read_text()
s=s.replace('boolean success=false;\n  Map<Integer,List<int[]>>','boolean success=false;List<File> createdVectors=new ArrayList<>();\n  Map<Integer,List<int[]>>')
old='for(String name:new String[]{"vectors.i8","scales.f32"}){ZipEntry entry=z.getEntry("assets/semantic/"+name);if(entry==null)throw new IOException("Missing semantic index");File f=new File(store.root("semantic"),name);copy(z.getInputStream(entry),f,256*MB);long expected=(long)mapping.length()*(name.endsWith("i8")?384:4);if(f.length()!=expected)throw new IOException("Semantic index size mismatch");}'
new='for(String name:new String[]{"vectors.i8","scales.f32"}){ZipEntry entry=z.getEntry("assets/semantic/"+name);if(entry==null)throw new IOException("Missing semantic index");File f=new File(store.root("semantic"),name);long expected=(long)mapping.length()*(name.endsWith("i8")?384:4);if(restore&&f.isFile()&&f.length()==expected)continue;File temp=new File(store.root("semantic"),name+".tmp");try{copy(z.getInputStream(entry),temp,256*MB);if(temp.length()!=expected)throw new IOException("Semantic index size mismatch");if(f.exists())throw new IOException("Existing vector file differs; refusing destructive replacement");if(!temp.renameTo(f))throw new IOException("Cannot commit semantic index");createdVectors.add(f);}finally{temp.delete();}}'
assert old in s;s=s.replace(old,new)
s=s.replace('new File(store.root("semantic"),"vectors.i8").delete();new File(store.root("semantic"),"scales.f32").delete();','for(File f:createdVectors)f.delete();')
old='try(FileOutputStream out=new FileOutputStream(assembled)){byte[] b='
s=s.replace(old,'try{try(FileOutputStream out=new FileOutputStream(assembled)){byte[] b=')
old='for(int i=0;i<parts;i++)new File(staging,i+".part").delete();staging.delete();return '
s=s.replace(old,'for(int i=0;i<parts;i++)new File(staging,i+".part").delete();staging.delete();}finally{assembled.delete();}return ')
p.write_text(s)
p=J/'MainActivity.java';s=p.read_text();s=s.replace('status.setText("\u0627\u0643\u062a\u0645\u0644\u062a \u0627\u0644\u0639\u0645\u0644\u064a\u0629 \u0627\u0644\u0645\u062d\u0644\u064a\u0629")','status.setText("\u0627\u0646\u062a\u0647\u062a \u0627\u0644\u0639\u0645\u0644\u064a\u0629\u061b \u0631\u0627\u062c\u0639 \u0627\u0644\u0646\u062a\u064a\u062c\u0629")');p.write_text(s)
(J/'SourceTerms.java').write_text(r'''package org.tms.offline;
import java.util.*;
import java.util.regex.*;
/** Exact source-derived acronym anchors. Not a translation or semantic truth verifier. */
public final class SourceTerms {
 private SourceTerms(){}
 private static boolean definition(String q){String n=Store.normalize(q);return n.contains("meaning")||n.contains("mean")||n.contains("stand for")||n.contains("abbreviat")||n.contains("\u0645\u0639\u0646")||n.contains("\u0627\u062e\u062a\u0635\u0627\u0631")||n.contains("\u064a\u0639\u0646");}
 private static Map<String,String> anchors(String q,List<Store.Hit> sources){
  Map<String,String> result=new LinkedHashMap<>();if(!definition(q))return result;
  Set<String> terms=new LinkedHashSet<>();Matcher words=Pattern.compile("\\b[A-Za-z][A-Za-z0-9]{1,9}\\b").matcher(q);while(words.find())terms.add(words.group().toUpperCase(Locale.ROOT));
  for(String term:terms){Set<String> expansions=new LinkedHashSet<>();
   for(Store.Hit h:sources){String t=Grounding.excerpt(h.text);Matcher m=Pattern.compile("\\(\\s*"+Pattern.quote(term)+"\\s*\\)").matcher(t);
    while(m.find()){String prefix=t.substring(Math.max(0,m.start()-150),m.start());Matcher tail=Pattern.compile("[A-Za-z][A-Za-z-]*(?:\\s+[A-Za-z][A-Za-z-]*)*\\s*$").matcher(prefix);if(!tail.find())continue;String[] tokens=tail.group().trim().split("\\s+");
     for(int count=2;count<=Math.min(9,tokens.length);count++){StringBuilder initials=new StringBuilder(),phrase=new StringBuilder();for(int k=tokens.length-count;k<tokens.length;k++){String token=tokens[k];initials.append(Character.toUpperCase(token.charAt(0)));if(phrase.length()>0)phrase.append(' ');phrase.append(token);}if(initials.toString().equals(term)){expansions.add(phrase.toString().toLowerCase(Locale.ROOT));break;}}
    }
   }
   if(expansions.size()==1)result.put(term,expansions.iterator().next());
  }return result;
 }
 public static String guide(String q,List<Store.Hit> sources){StringBuilder s=new StringBuilder();for(Map.Entry<String,String> e:anchors(q,sources).entrySet())s.append("\nEXACT SOURCE-DEFINED EXPANSION: ").append(e.getKey()).append(" = ").append(e.getValue()).append(". Preserve this exact English expansion in the answer, then cite its source. Do not invent an Arabic expansion.\n");return s.toString();}
 public static String validate(String q,String answer,List<Store.Hit> sources){String a=answer.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");for(Map.Entry<String,String> e:anchors(q,sources).entrySet())if(!a.contains(e.getValue()))return "\u0631\u0641\u0636\u062a \u0627\u0644\u0635\u064a\u0627\u063a\u0629 \u0644\u0623\u0646\u0647\u0627 \u0644\u0645 \u062a\u062d\u0627\u0641\u0638 \u0639\u0644\u0649 \u062a\u0639\u0631\u064a\u0641 \u0627\u0644\u0627\u062e\u062a\u0635\u0627\u0631 \u0627\u0644\u0648\u0627\u0631\u062f \u0641\u064a \u0627\u0644\u0645\u0635\u062f\u0631: "+e.getKey()+" = "+e.getValue();return "";}
}
''')
# Exact expansion test uses a synthetic reference, never private corpus content.
p=R/'tests/android/DeviceTests.java';s=p.read_text();key='  record("single_digit_query_preserved"'
extra='''  Store.Hit termSource=new Store.Hit();termSource.text="The auxiliary power unit (APU) supplies the stated system.";
  record("source_acronym_anchor",SourceTerms.guide("What does APU mean?",Arrays.asList(termSource)).contains("auxiliary power unit")&&!SourceTerms.validate("What does APU mean?","An unrelated expansion [1].",Arrays.asList(termSource)).isEmpty(),"A source-defined technical expansion cannot be replaced by a guessed translation");
'''
assert key in s;s=s.replace(key,extra+key);p.write_text(s)
print('Applied v5.1 hardening supplement')
