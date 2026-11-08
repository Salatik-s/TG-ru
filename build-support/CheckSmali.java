import brut.androlib.smali.SmaliBuilder;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.writer.builder.DexBuilder;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import java.io.File;

/** Syntax/DEX assembly check only; this does not perform Android ART verification. */
public final class CheckSmali {
    public static void main(String[] args) throws Exception {
        DexBuilder dex = new DexBuilder(new Opcodes(35));
        SmaliBuilder assembler = new SmaliBuilder(35);
        File[] files = new File(args[0]).listFiles((dir, name) -> name.endsWith(".smali"));
        if (files == null || files.length == 0) throw new IllegalArgumentException("No fixtures");
        for (File file : files) if (!assembler.buildFile(file, dex)) throw new IllegalStateException(file.getName());
        dex.writeTo(new FileDataStore(new File(args[1])));
        System.out.println("Assembled " + files.length + " patched smali fixtures into DEX; ART runtime not tested.");
    }
}
