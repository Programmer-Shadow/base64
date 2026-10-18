import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Base64 encoding, not encryption. Requires Java 8 or newer. */
public class Base64FileTool {
    /** IDEA 直接运行 main 时处理此目录，修改成你的目标文件夹即可。Windows 路径可使用 /。 */
    private static final String DIRECTORY = "F:\\1";
    /** auto：自动编码/解码；encode：只编码；decode：只解码。 */
    private static final String MODE = "auto";

    private static final String FORMAT_PREFIX = "BASE64FILETOOL:";
    private static final String MAGIC = FORMAT_PREFIX + "1";
    private static final String ENCODED_SUFFIX = ".b64.txt";

    /** Usage: java Base64FileTool [directory] [auto|encode|decode] */
    public static void main(String[] args) throws IOException {
        if (args.length > 2) {
            throw new IllegalArgumentException("Usage: java Base64FileTool [directory] [auto|encode|decode]");
        }
        Path dir = Paths.get(args.length > 0 ? args[0] : DIRECTORY).toAbsolutePath().normalize();
        String mode = (args.length > 1 ? args[1] : MODE).toLowerCase(Locale.ROOT);
        if (!mode.equals("auto") && !mode.equals("encode") && !mode.equals("decode")) {
            throw new IllegalArgumentException("Mode must be auto, encode or decode");
        }
        if (!Files.isDirectory(dir)) {
            throw new IOException("Directory does not exist: " + dir);
        }
        System.out.println("Directory: " + dir);
        System.out.println("Mode: " + mode);
        processDirectory(dir, mode);
    }

    static void processDirectory(Path dir, String mode) throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(p -> isTargetSuffix(p.getFileName().toString()))
                    .sorted().collect(Collectors.toList());
        }
        int success = 0, skipped = 0, failed = 0;
        for (Path file : files) {
            try {
                byte[] data = Files.readAllBytes(file);
                boolean encoded = hasFormatPrefix(data);
                Path output;
                if (mode.equals("encode") || (mode.equals("auto") && !encoded)) {
                    output = encodeFile(file, data);
                } else if (encoded) {
                    output = decodeFile(file, data);
                } else {
                    skipped++;
                    System.out.println("SKIP (no format header): " + file.getFileName());
                    continue;
                }
                success++;
                System.out.println("OK: " + file.getFileName() + " -> " + output.getFileName());
            } catch (java.nio.file.FileAlreadyExistsException e) {
                skipped++;
                System.out.println("SKIP (output exists): " + file.getFileName());
            } catch (Exception e) {
                failed++;
                System.err.println("FAILED: " + file.getFileName() + " - " + e.getMessage());
            }
        }
        System.out.println("Success: " + success + ", skipped: " + skipped + ", failed: " + failed);
    }

    private static boolean isTargetSuffix(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".txt") || lower.endsWith(".md")
                || lower.endsWith(".7z") || lower.endsWith(".java");
    }

    private static boolean hasFormatPrefix(byte[] data) {
        byte[] prefix = FORMAT_PREFIX.getBytes(StandardCharsets.US_ASCII);
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }

    static Path encodeFile(Path input, byte[] data) throws IOException {
        String name = input.getFileName().toString();
        validateFileName(name);
        String text = MAGIC + "\n"
                + Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8)) + "\n"
                + Base64.getEncoder().encodeToString(data) + "\n";
        Path output = input.resolveSibling(name + ENCODED_SUFFIX);
        writeNew(output, text.getBytes(StandardCharsets.UTF_8));
        return output;
    }

    static Path decodeFile(Path input, byte[] data) throws IOException {
        // Decode strictly: malformed UTF-8 must not silently change the stored name.
        String text = strictUtf8(data);
        String[] parts = text.split("\n", 3);
        if (parts.length != 3 || !stripCR(parts[0]).equals(MAGIC)) {
            throw new IOException("Invalid or unsupported format header");
        }
        try {
            String name = strictUtf8(Base64.getDecoder().decode(stripCR(parts[1])));
            validateFileName(name);
            // Accept wrapped Base64, but reject non-whitespace garbage unlike the MIME decoder.
            String payload = parts[2].replaceAll("[\\r\\n\\t ]", "");
            byte[] decoded = Base64.getDecoder().decode(payload);
            Path output = input.resolveSibling(name);
            writeNew(output, decoded);
            return output;
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid Base64 data", e);
        }
    }

    private static String stripCR(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static String strictUtf8(byte[] data) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data)).toString();
    }

    private static void validateFileName(String name) throws IOException {
        if (name.isEmpty() || name.equals(".") || name.equals("")
                || name.endsWith(".") || name.endsWith(" ")) {
            throw new IOException("Unsafe original filename");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || "<>:\"/\\|?*".indexOf(c) >= 0) {
                throw new IOException("Unsafe original filename");
            }
        }
        String stem = name.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        if (stem.matches("CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³]")) {
            throw new IOException("Reserved original filename");
        }
    }

    private static void writeNew(Path output, byte[] data) throws IOException {
        // CREATE_NEW also refuses existing symbolic links. Never truncate an existing file.
        OutputStream stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        try (OutputStream owned = stream) {
            owned.write(data);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(output);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }
}
