package de.lino.cloud.api.utility;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared constants for {@code cloud-driver}, so consumers resolve
 * well-known values (e.g. local config file locations, file-extension to
 * content-type mappings) against one place instead of each hardcoding its
 * own copy.
 */
public final class Constraints {

    /** Not instantiable; all functionality is exposed through static fields. */
    private Constraints() {}

    /** The JVM's working directory ({@code user.dir}). */
    public static final Path WORKING_DIRECTORY = Path.of(System.getProperty("user.dir"));

    /**
     * Directory local configuration files are resolved against: a {@code cloud-driver}
     * subdirectory of {@link #WORKING_DIRECTORY}.
     */
    public static final Path CONFIGURATION_PATH = WORKING_DIRECTORY.resolve("cloud-driver");

    /** Default destination for {@code StoredFile#downloadToDevice()}: the user's Downloads folder. */
    public static final Path USER_DOWNLOADS_PATH = Path.of(System.getProperty("user.home"), "Downloads");

    /**
     * Directory {@code cloud-driver-plugin}'s extension-jar scanner looks in for {@code
     * *.jar} files - a top-level {@code extensions} subdirectory, sibling to
     * {@link #CONFIGURATION_PATH}.
     */
    public static final Path EXTENSIONS_PATH = WORKING_DIRECTORY.resolve("extensions");

    /**
     * Scratch directory {@code DefaultRestFactory#handleUploadFile} streams an in-progress
     * upload's request body to (a temp file per upload, deleted once the upload completes or
     * fails) rather than buffering it whole in JVM heap - a top-level {@code upload-scratch}
     * subdirectory, sibling to {@link #CONFIGURATION_PATH}, not the OS-global temp directory, so
     * an operator can see/clean it up the same way as every other named path here.
     */
    public static final Path UPLOAD_SCRATCH_PATH = WORKING_DIRECTORY.resolve("upload-scratch");

    /**
     * Scratch directory {@code cloud-driver-plugin}'s {@code PythonIcloudBridge}/{@code
     * DefaultIcloudImportService} use for one on-demand iCloud import job's transient state - a
     * per-job subdirectory (named by job id) holds the Apple session cookie file the bridge writes
     * and each file's plaintext bytes briefly in flight, deleted wholesale once that job reaches a
     * terminal state. A top-level {@code icloud-import-scratch} subdirectory, sibling to {@link
     * #CONFIGURATION_PATH}, following the same "operator-visible named path, not the OS-global temp
     * directory" reasoning as {@link #UPLOAD_SCRATCH_PATH}.
     */
    public static final Path ICLOUD_IMPORT_SCRATCH_PATH = WORKING_DIRECTORY.resolve("icloud-import-scratch");

    /** Timestamp the process started at, set once during bootstrap. */
    public static final AtomicReference<Long> CLOUD_START_TIME_STAMP = new AtomicReference<>();

    /** UnitParser labels {@link #resolveBytesToUnit(long)} scales through, smallest to largest. */
    private static final String[] BYTE_UNITS = {"B", "KB", "MB", "GB", "TB"};

    /** UnitParser labels {@link #resolveMilliSecondsToUnit(long)} scales through, smallest to largest. */
    private static final String[] TIME_UNITS = {"ms", "s", "min", "h", "d"};

    /** Divisor applied to move from each {@link #TIME_UNITS} entry to the next larger one. */
    private static final int[] TIME_UNIT_DIVISORS = {1000, 60, 60, 24};

    /**
     * Formats {@code bytes} in its largest whole unit (e.g. {@code 2048} -> {@code "2.00KB"}).
     *
     * @param bytes the byte count to format
     * @return the formatted string
     */
    public static String resolveBytesToUnit(final long bytes) {

        double value = bytes;
        int unit = 0;

        while (value >= 1024 && unit < BYTE_UNITS.length - 1) {
            value /= 1024;
            unit++;
        }

        return (unit == 0 ? String.valueOf((long) value) : String.format("%.2f", value).replace(",", ".")) + " " + BYTE_UNITS[unit];

    }

    /**
     * Formats {@code milliseconds} in its largest whole unit (e.g. {@code 90_000} -&gt;
     * {@code "1.50 min"}).
     *
     * @param milliseconds the duration to format
     * @return the formatted string
     */
    public static String resolveMilliSecondsToUnit(final long milliseconds) {

        double value = milliseconds;
        int unit = 0;

        while (unit < TIME_UNIT_DIVISORS.length && value >= TIME_UNIT_DIVISORS[unit]) {
            value /= TIME_UNIT_DIVISORS[unit];
            unit++;
        }

        return (unit == 0 ? String.valueOf((long) value) : String.format("%.2f", value).replace(",", ".")) + " " + TIME_UNITS[unit];

    }

    /** ASCII banner printed at startup. */
    public static final String CLOUD_DRIVER_BANNER =
            """
                    
                     _____ _                 _______      _               \s
                    /  __ \\ |               | |  _  \\    (_)              \s
                    | /  \\/ | ___  _   _  __| | | | |_ __ ___   _____ _ __\s
                    | |   | |/ _ \\| | | |/ _` | | | | '__| \\ \\ / / _ \\ '__|
                    | \\__/\\ | (_) | |_| | (_| | |/ /| |  | |\\ V /  __/ |  \s
                     \\____/_|\\___/ \\__,_|\\__,_|___/ |_|  |_| \\_/ \\___|_|  \s
                                                                          \s
                          https://github.com/linoalessio/cloud-driver       \s""";

    /**
     * Fixed {@code fileId} {@code CloudBootstrap} uploads {@code SECURITY_REQUIREMENTS.md}
     * under on every startup - hardcoded so the upload is idempotent across restarts.
     */
    public static final UUID REQUIREMENTS_UUID = UUID.fromString("e54d8aab-cfa0-426c-b40a-1a703db293d4");

    /**
     * File extension (lowercase, no leading dot) to MIME content type, used by {@code
     * StoredFile} to infer content type from a file name. Not exhaustive; unlisted
     * extensions fall back to {@code StoredFile.DEFAULT_CONTENT_TYPE}.
     */
    public static final Map<String, String> CONTENT_TYPES = Map.<String, String>ofEntries(
            // text / documents
            Map.entry("txt", "text/plain"),
            Map.entry("csv", "text/csv"),
            Map.entry("tsv", "text/tab-separated-values"),
            Map.entry("html", "text/html"),
            Map.entry("htm", "text/html"),
            Map.entry("css", "text/css"),
            Map.entry("js", "text/javascript"),
            Map.entry("mjs", "text/javascript"),
            Map.entry("json", "application/json"),
            Map.entry("xml", "application/xml"),
            Map.entry("md", "text/markdown"),
            Map.entry("rtf", "application/rtf"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("odt", "application/vnd.oasis.opendocument.text"),
            Map.entry("ods", "application/vnd.oasis.opendocument.spreadsheet"),
            Map.entry("odp", "application/vnd.oasis.opendocument.presentation"),
            Map.entry("epub", "application/epub+zip"),
            Map.entry("ics", "text/calendar"),
            Map.entry("yaml", "application/yaml"),
            Map.entry("yml", "application/yaml"),
            Map.entry("toml", "application/toml"),
            Map.entry("ini", "text/plain"),
            Map.entry("properties", "text/x-java-properties"),
            Map.entry("log", "text/plain"),
            Map.entry("goodnotes", "text/goodnotes"),

            // images
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("png", "image/png"),
            Map.entry("gif", "image/gif"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("webp", "image/webp"),
            Map.entry("tif", "image/tiff"),
            Map.entry("tiff", "image/tiff"),
            Map.entry("ico", "image/vnd.microsoft.icon"),
            Map.entry("heic", "image/heic"),
            Map.entry("psd", "image/vnd.adobe.photoshop"),
            Map.entry("ai", "application/postscript"),
            Map.entry("eps", "application/postscript"),

            // audio
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/wav"),
            Map.entry("ogg", "audio/ogg"),
            Map.entry("oga", "audio/ogg"),
            Map.entry("flac", "audio/flac"),
            Map.entry("aac", "audio/aac"),
            Map.entry("m4a", "audio/mp4"),
            Map.entry("weba", "audio/webm"),
            Map.entry("mid", "audio/midi"),
            Map.entry("midi", "audio/midi"),

            // video
            Map.entry("mp4", "video/mp4"),
            Map.entry("m4v", "video/mp4"),
            Map.entry("avi", "video/x-msvideo"),
            Map.entry("mov", "video/quicktime"),
            Map.entry("wmv", "video/x-ms-wmv"),
            Map.entry("mkv", "video/x-matroska"),
            Map.entry("webm", "video/webm"),
            Map.entry("flv", "video/x-flv"),
            Map.entry("mpeg", "video/mpeg"),
            Map.entry("mpg", "video/mpeg"),
            Map.entry("3gp", "video/3gpp"),

            // archives
            Map.entry("zip", "application/zip"),
            Map.entry("rar", "application/vnd.rar"),
            Map.entry("7z", "application/x-7z-compressed"),
            Map.entry("tar", "application/x-tar"),
            Map.entry("gz", "application/gzip"),
            Map.entry("bz2", "application/x-bzip2"),
            Map.entry("xz", "application/x-xz"),

            // fonts
            Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),

            // executables / archives-of-code
            Map.entry("exe", "application/x-msdownload"),
            Map.entry("dll", "application/x-msdownload"),
            Map.entry("apk", "application/vnd.android.package-archive"),
            Map.entry("iso", "application/x-iso9660-image"),
            Map.entry("jar", "application/java-archive"),
            Map.entry("class", "application/java-vm"),
            Map.entry("wasm", "application/wasm"),

            // source / config, for callers that also treat source files as StoredFiles
            Map.entry("java", "text/x-java-source"),
            Map.entry("kt", "text/x-kotlin"),
            Map.entry("py", "text/x-python"),
            Map.entry("c", "text/x-c"),
            Map.entry("h", "text/x-c-header"),
            Map.entry("cpp", "text/x-c++src"),
            Map.entry("cs", "text/x-csharp"),
            Map.entry("go", "text/x-go"),
            Map.entry("rs", "text/rust"),
            Map.entry("rb", "text/x-ruby"),
            Map.entry("php", "application/x-httpd-php"),
            Map.entry("ts", "application/typescript"),
            Map.entry("sh", "application/x-sh"),
            Map.entry("bat", "application/bat"),
            Map.entry("sql", "application/sql"),
            Map.entry("gradle", "text/x-gradle")
    );

}
