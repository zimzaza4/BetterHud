package kr.toxicity.hud.api.manager;

import kr.toxicity.hud.api.BetterHudAPI;
import lombok.Getter;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;

/**
 * Shader manager
 */
public interface ShaderManager {
    /**
     * Defines constant (#Define)
     * @param key name
     * @param value value
     */
    void addConstant(@NotNull String key, @NotNull String value);

    /**
     * Adds shader tag to some shader file.
     * Default: #GenerateOtherMainMethod with an empty list in all files.
     * @param type type of shader
     * @param supplier tag function
     */
    void addTagSupplier(@NotNull ShaderType type, @NotNull ShaderManager.ShaderTagSupplier supplier);

    /**
     * Empty tag.
     */
    ShaderTag EMPTY_TAG = newTag()
            .add("GenerateOtherMainMethod", Collections.emptyList())
            .add("GenerateOtherDefinedMethod", Collections.emptyList());
    /**
     * Empty tag supplier.
     */
    ShaderTagSupplier EMPTY_SUPPLIER = () -> EMPTY_TAG;


    /**
     * A supplier of shader tag.
     */
    @FunctionalInterface
    interface ShaderTagSupplier extends Supplier<ShaderTag> {
        /**
         * Pluses supplier with others.
         * @param other another supplier
         * @return new supplier
         */
        default ShaderTagSupplier plus(@NotNull ShaderManager.ShaderTagSupplier other) {
            return () -> get().plus(other.get());
        }
    }

    /**
     * Creates new shader tag
     * @return new tag
     */
    static @NotNull ShaderTag newTag() {
        return new ShaderTag();
    }

    /**
     * Shader tag.
     */
    class ShaderTag {
        private final Map<String, List<String>> lines = new HashMap<>();

        /**
         * Private initializer.
         */
        private ShaderTag() {
        }

        /**
         * Adds to tag
         * newTag()
         *  .add("GenerateOtherMainMethod", Collections.emptyList())
         *  .add("OtherYouWant", someList);
         * @param tag tag name
         * @param line tag list
         * @return self
         */
        public @NotNull ShaderTag add(@NotNull String tag, @NotNull List<String> line) {
            Objects.requireNonNull(tag);
            Objects.requireNonNull(line);
            var get = lines.get(tag);
            if (get == null) lines.put(tag, line);
            else {
                var list = new ArrayList<String>(get.size() + line.size());
                list.addAll(get);
                list.addAll(line);
                lines.put(tag, list);
            }
            return this;
        }

        /**
         * Sums two different tags to new one.
         * @param tag another tag
         * @return new merged tag
         */
        public @NotNull ShaderTag plus(@NotNull ShaderTag tag) {
            Objects.requireNonNull(tag);
            var newTag = new ShaderTag();
            lines.forEach(newTag::add);
            tag.lines.forEach(newTag::add);
            return newTag;
        }

        /**
         * Gets a list from name
         * @param tagName name
         * @return tag list or null
         */
        @ApiStatus.Internal
        @Nullable
        public List<String> get(@NotNull String tagName) {
            return lines.get(tagName);
        }
    }

    /**
     * Represents BetterHud's shader files.
     */
    @Getter
    enum ShaderType {
        /**
         * text vsh
         */
        TEXT_VERTEX("text.vsh", "rendertype_text.vsh", "text.vsh"),
        /**
         * text fsh
         */
        TEXT_FRAGMENT("text.fsh", "rendertype_text.fsh", "text.fsh")

        ;
        /**
         * Fingerprints of the bundled defaults this folder was last compared against.
         */
        private static final @NotNull String RECORD_FILE = ".bundled";

        private static final @NotNull DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

        private final @NotNull String fileName;
        private final @NotNull String[] shadersCoreNames;

        ShaderType(@NotNull String fileName, @NotNull String... shadersCoreNames) {
            this.fileName = fileName;
            this.shadersCoreNames = shadersCoreNames;
        }

        /**
         * What to do with the copy of a shader file in the data folder.
         */
        @ApiStatus.Internal
        public enum Bundled {
            /**
             * the copy is missing: extract the bundled default
             */
            EXTRACT,
            /**
             * the copy is an untouched extraction of an older default: refresh it
             */
            REFRESH,
            /**
             * the copy is up to date, or was already compared against this default: keep it
             */
            KEEP,
            /**
             * the copy differs from the bundled default: keep it, warn once
             */
            KEEP_AND_WARN
        }

        /**
         * Compares the copy in the data folder against the bundled default.
         *
         * @param recorded the bundled default this file was last compared against, or null if unknown
         * @param copyHash the copy's fingerprint, or null if the copy is missing
         * @param bundledHash the bundled default's fingerprint
         * @return what to do with the copy
         */
        @ApiStatus.Internal
        public static @NotNull Bundled decide(@Nullable String recorded, @Nullable String copyHash, @NotNull String bundledHash) {
            if (copyHash == null) return Bundled.EXTRACT;
            if (bundledHash.equals(copyHash)) return Bundled.KEEP;
            if (bundledHash.equals(recorded)) return Bundled.KEEP;
            if (copyHash.equals(recorded)) return Bundled.REFRESH;
            return Bundled.KEEP_AND_WARN;
        }

        /**
         * Reads the all line of shader file.
         * @return all line.
         */
        public @NotNull List<String> lines() {
            var bootstrap = BetterHudAPI.inst().bootstrap();
            var dataFolder = bootstrap.dataFolder();
            var shaderLocation = new File(dataFolder, "shaders");
            if (!shaderLocation.exists() && !shaderLocation.mkdirs()) {
                bootstrap.logger().warn("Unable to create folder BetterHud/shaders.");
            }
            var dataFile = new File(shaderLocation, fileName);
            var bundled = bundled();
            var bundledHash = hash(bundled);
            var recordFile = new File(shaderLocation, RECORD_FILE);
            var record = readRecord(recordFile);
            var decision = decide(record.get(fileName), dataFile.exists() ? hash(dataFile) : null, bundledHash);
            if (decision == Bundled.REFRESH) {
                // untouched copy of an older default: drop it so that the extraction below reads the new one
                bootstrap.logger().info("Refreshed BetterHud/shaders/" + fileName + ": it had not been edited and the bundled default has changed.");
                if (!dataFile.delete()) bootstrap.logger().warn("Unable to delete an outdated BetterHud/shaders/" + fileName + ".");
            } else if (decision == Bundled.KEEP_AND_WARN) {
                bootstrap.logger().warn("BetterHud/shaders/" + fileName + " differs from the bundled default. If you did not edit it yourself, it may be left behind by an older BetterHud: /bh shaders restores the bundled files.");
            }
            record.put(fileName, bundledHash);
            writeRecord(recordFile, record);
            var lines = new ArrayList<String>();
            if (!dataFile.exists()) {
                try (
                        var fileStream = new FileOutputStream(dataFile);
                        var bufferedFileStream = new BufferedOutputStream(fileStream)
                ) {
                    bufferedFileStream.write(bundled);
                    for (var line : read(bundled)) {
                        lines.add(line);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("plugin jar file has a problem.");
                }
            } else {
                String line;
                try (var read = new BufferedReader(new FileReader(dataFile, StandardCharsets.UTF_8))) {
                    while ((line = read.readLine()) != null) {
                        lines.add(line);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Unable to read a lines of stream.");
                }
            }
            return lines;
        }

        /**
         * Writes the bundled default back into the data folder.
         * A copy that differs from the default is backed up as {@code <file>.bak-<time>} first.
         * @return whether the file was written
         */
        @ApiStatus.Internal
        public boolean restore() {
            var bootstrap = BetterHudAPI.inst().bootstrap();
            var shaderLocation = new File(bootstrap.dataFolder(), "shaders");
            if (!shaderLocation.exists() && !shaderLocation.mkdirs()) {
                bootstrap.logger().warn("Unable to create folder BetterHud/shaders.");
                return false;
            }
            var target = new File(shaderLocation, fileName);
            try {
                var bytes = bundled();
                if (target.isFile() && !Arrays.equals(Files.readAllBytes(target.toPath()), bytes)) {
                    var backup = new File(shaderLocation, fileName + ".bak-" + LocalDateTime.now().format(BACKUP_TIME));
                    Files.copy(target.toPath(), backup.toPath());
                    bootstrap.logger().info("Backed up the previous BetterHud/shaders/" + fileName + " as " + backup.getName() + ".");
                }
                Files.write(target.toPath(), bytes);
                return true;
            } catch (IOException e) {
                bootstrap.logger().warn("Unable to restore BetterHud/shaders/" + fileName + ": " + e.getMessage());
                return false;
            }
        }

        private @NotNull byte[] bundled() {
            var bootstrap = BetterHudAPI.inst().bootstrap();
            try (var resourceStream = Objects.requireNonNull(bootstrap.resource(fileName), "Unknown resource: " + fileName)) {
                return resourceStream.readAllBytes();
            } catch (IOException e) {
                throw new RuntimeException("plugin jar file has a problem.");
            }
        }

        private static @NotNull List<String> read(@NotNull byte[] bytes) {
            var lines = new ArrayList<String>();
            try (var read = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
                String line;
                while ((line = read.readLine()) != null) {
                    lines.add(line);
                }
            } catch (IOException e) {
                throw new RuntimeException("Unable to read a lines of stream.");
            }
            return lines;
        }

        private static @NotNull String hash(@NotNull byte[] bytes) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }

        private static @NotNull String hash(@NotNull File file) {
            try {
                return hash(Files.readAllBytes(file.toPath()));
            } catch (IOException e) {
                throw new RuntimeException("Unable to read " + file.getName() + ": " + e.getMessage());
            }
        }

        private static @NotNull Map<String, String> readRecord(@NotNull File file) {
            var record = new LinkedHashMap<String, String>();
            if (!file.isFile()) return record;
            try {
                for (var line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                    var split = line.indexOf('=');
                    if (split > 0) record.put(line.substring(0, split), line.substring(split + 1));
                }
            } catch (IOException e) {
                BetterHudAPI.inst().bootstrap().logger().warn("Unable to read BetterHud/shaders/" + RECORD_FILE + ": " + e.getMessage());
            }
            return record;
        }

        private static void writeRecord(@NotNull File file, @NotNull Map<String, String> record) {
            var builder = new StringBuilder();
            record.forEach((key, value) -> builder.append(key).append('=').append(value).append('\n'));
            try {
                Files.writeString(file.toPath(), builder.toString(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                BetterHudAPI.inst().bootstrap().logger().warn("Unable to write BetterHud/shaders/" + RECORD_FILE + ": " + e.getMessage());
            }
        }
    }
}
