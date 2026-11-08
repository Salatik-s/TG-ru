import java.io.*;
import org.apache.poi.hwpf.HWPFDocument;
public class MakeLegacyFixture {
 public static void main(String[] args)throws Exception{
  try(HWPFDocument document=new HWPFDocument(new FileInputStream(args[0]))){
   if(!document.getRange().text().trim().isEmpty())throw new IllegalStateException("Template must be blank");
   document.getRange().insertBefore("SAA_QA_DOC_BINARY\rSecond paragraph\r");
   document.createInformationProperties();document.getSummaryInformation().setAuthor("SAA QA");document.getSummaryInformation().setTitle("Synthetic document");
   try(OutputStream out=new FileOutputStream(args[1])){document.write(out);}
  }
  System.out.println("Generated synthetic legacy DOC fixture");
 }
}
