package sanctuary.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/** Loads {@code sanctuary-agent.properties} from the working directory (or an explicit path). */
public final class Config {

    private Config() {
    }

    public static Properties load(String args) {
        Properties props = new Properties();
        Path path = Paths.get("sanctuary-agent.properties");
        if (args != null && !args.isBlank()) {
            for (String part : args.split(",")) {
                String[] kv = part.split("=", 2);
                if (kv.length == 2 && "config".equalsIgnoreCase(kv[0].trim())) {
                    path = Paths.get(kv[1].trim());
                }
            }
        }
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                props.load(in);
                System.out.println("[SanctuaryAgent] config loaded from " + path.toAbsolutePath());
            } catch (IOException e) {
                System.out.println("[SanctuaryAgent] config read failed: " + e);
            }
        } else {
            System.out.println("[SanctuaryAgent] no config at " + path.toAbsolutePath()
                    + " — using defaults");
            writeDefault(path, props);
        }
        return props;
    }

    private static void writeDefault(Path path, Properties props) {
        props.setProperty("protect_owned_golems", "true");
        props.setProperty("global_ban", "false");
        props.setProperty("patch_object_returns", "false");
        props.setProperty("verbose", "true");
        props.setProperty("violations", "sanctuary-violations.jsonl");
        try {
            try (java.io.OutputStream out = Files.newOutputStream(path)) {
                props.store(out, "Sanctuary agent policy");
            }
        } catch (IOException ignored) {
        }
    }
}
