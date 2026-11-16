import dev.gawel.dothome.core.AppFilter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Usage: AppFilterMain index.tsv drawable-dir appfilter.xml.
 * Checks column 2 against AppFilter.drawableNames and that each PNG exists in drawable-dir.
 */
public final class AppFilterMain {
    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("usage: AppFilterMain index.tsv drawable-dir appfilter.xml");
            System.exit(2);
        }
        List<String> components = new ArrayList<String>();
        List<String> declared = new ArrayList<String>();
        for (String line : Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) continue;
            String[] cols = line.split("\t", -1);
            if (cols.length < 2) fail("bad index line: " + line);
            components.add(cols[0]);
            declared.add(cols[1]);
        }
        // Names are assigned over the exported set; a gap (failed icon) can shift suffixes, so only
        // require agreement for the full list the exporter saw, in order.
        List<String> expected = AppFilter.drawableNames(components);
        for (int i = 0; i < components.size(); i++) {
            if (!expected.get(i).equals(declared.get(i))) {
                fail("drawable name mismatch for " + components.get(i) + ": index says "
                    + declared.get(i) + ", AppFilter says " + expected.get(i));
            }
            if (!new File(args[1], declared.get(i) + ".png").isFile()) {
                fail("missing PNG " + args[1] + "/" + declared.get(i) + ".png");
            }
        }
        Files.write(Paths.get(args[2]), AppFilter.xml(components).getBytes(StandardCharsets.UTF_8));
    }

    private static void fail(String msg) {
        System.err.println("AppFilterMain: " + msg);
        System.exit(1);
    }
}
