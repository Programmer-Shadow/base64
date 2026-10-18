import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Base64 encoding, not encryption. Requires Java 8 or newer. */
public class Base64FileTool {
    /** IDEA 直接运行 main 时处理此目录，修改成你的目标文件夹即可。Windows 路径可使用 /。 */
    private static final String DIRECTORY = "F:\\1";
    /** auto：自动编码/解码；encode：只编码；decode：只解码。 */
    private static final String MODE = "auto";
    /** 每个编码输出文件的字节上限（包含头信息），默认 500 KB，可按需修改。 */
    static long maxEncodedFileSize = 100L * 1024;

    private static final String FORMAT_PREFIX = "BASE64FILETOOL:";
    private static final String MAGIC = FORMAT_PREFIX + "1";
    private static final String FRAGMENT_MAGIC = FORMAT_PREFIX + "2";
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
                    .sorted().collect(Collectors.toList());
        }
        int success = 0, skipped = 0, failed = 0;
        Set<String> processedBatches = new HashSet<>();
        for (Path file : files) {
            try {
                boolean encoded = isEncodedFile(file);
                if (!encoded && !isTargetSuffix(file.getFileName().toString())) continue;
                if (mode.equals("encode") && !isTargetSuffix(file.getFileName().toString())) continue;
                byte[] data = Files.readAllBytes(file);
                Path output;
                if (mode.equals("encode") || (mode.equals("auto") && !encoded)) {
                    output = encodeFile(file, data);
                } else if (encoded) {
                    String batch = fragmentBatch(data);
                    if (batch != null && !processedBatches.add(batch)) continue;
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

    /** Only read the prefix when classifying unrelated directory entries. */
    private static boolean isEncodedFile(Path file) throws IOException {
        byte[] prefix = new byte[FORMAT_PREFIX.length()];
        try (InputStream stream = Files.newInputStream(file)) {
            int read = 0;
            while (read < prefix.length) {
                int count = stream.read(prefix, read, prefix.length - read);
                if (count < 0) return false;
                read += count;
            }
        }
        return hasFormatPrefix(prefix);
    }

    static Path encodeFile(Path input, byte[] data) throws IOException {
        String name = input.getFileName().toString();
        validateFileName(name);
        long limit = maxEncodedFileSize; // Snapshot the mutable setting for this entire batch.
        String encodedName = Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getEncoder().encodeToString(data);
        String batch = UUID.randomUUID().toString();
        String hash = sha256(data);
        // Fixed-width numeric fields make this header exactly the same size for every part.
        int overhead = fragmentHeader(encodedName, batch, 1, 1, data.length, hash, hash)
                .getBytes(StandardCharsets.US_ASCII).length + 1; // Payload's final LF.
        if (limit < overhead || (limit - overhead < 4 && !payload.isEmpty())) {
            throw new IOException("File size limit is too small for the format header and payload");
        }
        int capacity = (int) (Math.min(limit - overhead, Integer.MAX_VALUE - 3L) / 4 * 4);
        int count = payload.isEmpty() ? 1 : (int) ((payload.length() + (long) capacity - 1) / capacity);
        List<Path> created = new ArrayList<>();
        try {
            for (int index = 1; index <= count; index++) {
                int start = (int) ((long) (index - 1) * capacity);
                int end = (int) Math.min(payload.length(), (long) start + capacity);
                String chunk = payload.substring(start, end);
                String text = fragmentHeader(encodedName, batch, index, count, data.length, hash,
                        sha256(chunk.getBytes(StandardCharsets.US_ASCII))) + chunk + "\n";
                String suffix = count == 1 ? ENCODED_SUFFIX
                        : String.format(Locale.ROOT, ".part%010d-of-%010d%s", index, count, ENCODED_SUFFIX);
                Path output = input.resolveSibling(name + suffix);
                writeNew(output, text.getBytes(StandardCharsets.US_ASCII));
                created.add(output);
            }
        } catch (IOException e) {
            for (Path output : created) {
                try { Files.deleteIfExists(output); }
                catch (IOException cleanup) { e.addSuppressed(cleanup); }
            }
            throw e;
        }
        return created.get(0);
    }

    private static String fragmentHeader(String encodedName, String batch, int index, int count,
                                         long length, String hash, String chunkHash) {
        return FRAGMENT_MAGIC + "\n" + encodedName + "\n" + batch + "\n"
                + String.format(Locale.ROOT, "%010d\n%010d\n%020d\n", index, count, length)
                + hash + "\n" + chunkHash + "\n";
    }

    static Path decodeFile(Path input, byte[] data) throws IOException {
        // Decode strictly: malformed UTF-8 must not silently change the stored name.
        String text = strictUtf8(data);
        String[] parts = text.split("\n", 3);
        if (parts.length > 0 && stripCR(parts[0]).equals(FRAGMENT_MAGIC)) {
            return decodeFragments(input, parseFragment(data));
        }
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

    /** Read the batch identity without parsing an unrelated batch's payload. */
    private static String fragmentBatch(byte[] data) {
        int start = 0;
        String[] first = new String[3];
        int field = 0;
        for (int i = 0; i < data.length && field < 3; i++) {
            if (data[i] == '\n') {
                first[field++] = stripCR(new String(data, start, i - start, StandardCharsets.US_ASCII));
                start = i + 1;
            }
        }
        return field == 3 && FRAGMENT_MAGIC.equals(first[0]) ? first[2] : null;
    }

    private static Fragment parseFragment(byte[] data) throws IOException {
        String[] fields = strictUtf8(data).split("\n", 9);
        if (fields.length != 9) throw new IOException("Incomplete fragment header");
        for (int i = 0; i < 8; i++) fields[i] = stripCR(fields[i]);
        if (!FRAGMENT_MAGIC.equals(fields[0])) throw new IOException("Invalid fragment version");
        try {
            String name = strictUtf8(Base64.getDecoder().decode(fields[1]));
            validateFileName(name);
            if (!fields[2].matches("[A-Za-z0-9-]{1,64}")
                    || !fields[3].matches("[0-9]{10}") || !fields[4].matches("[0-9]{10}")
                    || !fields[5].matches("[0-9]{20}")
                    || !fields[6].matches("[0-9a-f]{64}") || !fields[7].matches("[0-9a-f]{64}")) {
                throw new IOException("Invalid fragment metadata");
            }
            int index = Integer.parseInt(fields[3]);
            int count = Integer.parseInt(fields[4]);
            long length = Long.parseLong(fields[5]);
            if (index < 1 || count < 1 || index > count || length > Integer.MAX_VALUE) {
                throw new IOException("Fragment index/count or original length out of range");
            }
            String payload = fields[8].replaceAll("[\\r\\n\\t ]", "");
            if (payload.length() % 4 != 0 || !payload.matches("[A-Za-z0-9+/]*={0,2}")
                    || !sha256(payload.getBytes(StandardCharsets.US_ASCII)).equals(fields[7])) {
                throw new IOException("Fragment payload checksum or encoding mismatch");
            }
            return new Fragment(name, fields[2], index, count, length, fields[6], payload);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid fragment metadata or Base64", e);
        }
    }

    private static Path decodeFragments(Path input, Fragment seed) throws IOException {
        Path absolute = input.toAbsolutePath().normalize();
        Map<Integer, Fragment> fragments = new TreeMap<>();
        fragments.put(seed.index, seed);
        try (Stream<Path> stream = Files.list(absolute.getParent())) {
            List<Path> files = stream.filter(Files::isRegularFile).collect(Collectors.toList());
            for (Path file : files) {
                if (file.toAbsolutePath().normalize().equals(absolute) || !isEncodedFile(file)) continue;
                byte[] data = Files.readAllBytes(file);
                if (!seed.batch.equals(fragmentBatch(data))) continue;
                Fragment part = parseFragment(data);
                if (!seed.sameFile(part)) throw new IOException("Inconsistent fragment metadata");
                if (fragments.put(part.index, part) != null) throw new IOException("Duplicate fragment index: " + part.index);
            }
        }
        if (fragments.size() != seed.count) {
            throw new IOException("Missing fragments: expected " + seed.count + ", found " + fragments.size());
        }
        long expectedEncodedLength = ((seed.length + 2) / 3) * 4;
        long mergedLength = 0;
        int expectedIndex = 1;
        for (Fragment part : fragments.values()) {
            if (part.index != expectedIndex++) throw new IOException("Non-contiguous fragment sequence");
            mergedLength += part.payload.length();
        }
        if (mergedLength != expectedEncodedLength || mergedLength > Integer.MAX_VALUE) {
            throw new IOException("Merged encoded length mismatch or unsupported size");
        }
        StringBuilder merged = new StringBuilder((int) mergedLength);
        for (Fragment part : fragments.values()) merged.append(part.payload);
        byte[] decoded;
        try { decoded = Base64.getDecoder().decode(merged.toString()); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid merged Base64", e); }
        if (decoded.length != seed.length || !sha256(decoded).equals(seed.hash)
                || !Base64.getEncoder().encodeToString(decoded).contentEquals(merged)) {
            throw new IOException("Merged content length, order or checksum mismatch");
        }
        Path output = input.resolveSibling(seed.name);
        writeNew(output, decoded);
        return output;
    }

    private static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by this Java runtime", e);
        }
    }

    /** Immutable metadata for one validated v2 fragment. */
    private static final class Fragment {
        final String name, batch, hash, payload;
        final int index, count;
        final long length;

        Fragment(String name, String batch, int index, int count, long length, String hash, String payload) {
            this.name = name;
            this.batch = batch;
            this.index = index;
            this.count = count;
            this.length = length;
            this.hash = hash;
            this.payload = payload;
        }

        boolean sameFile(Fragment other) {
            return name.equals(other.name) && batch.equals(other.batch) && count == other.count
                    && length == other.length && hash.equals(other.hash);
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
