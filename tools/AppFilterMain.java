import dev.gawel.dothome.core.AppFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Usage: AppFilterMain index.tsv out/appfilter.xml. Reads component column, emits appfilter.xml. */
public final class AppFilterMain {
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: AppFilterMain index.tsv appfilter.xml");
            System.exit(2);
        }
        List<String> components = new ArrayList<String>();
        for (String line : Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) continue;
            components.add(line.split("\t", -1)[0]);
        }
        Files.write(Paths.get(args[1]), AppFilter.xml(components).getBytes(StandardCharsets.UTF_8));
    }
}
