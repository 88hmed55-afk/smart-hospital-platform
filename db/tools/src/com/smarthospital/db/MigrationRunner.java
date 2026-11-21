package com.smarthospital.db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Minimal forward-only migration runner.
 *
 * <p>Why not Flyway/Liquibase: the project standard is Ant and explicit SQL.
 * This runner keeps the same guarantees we actually need: strict ordering,
 * SHA-256 checksum of every applied file, and an append-only history.
 *
 * <p>Commands:
 * <pre>
 *   java com.smarthospital.db.MigrationRunner migrate
 *   java com.smarthospital.db.MigrationRunner seed
 *   java com.smarthospital.db.MigrationRunner verify
 *   java com.smarthospital.db.MigrationRunner status
 * </pre>
 *
 * <p>Connection settings come from {@code db/db.properties} (or
 * {@code -Ddb.properties=...}), with environment variables as fallback.
 */
public final class MigrationRunner {

    private static final Logger LOG = Logger.getLogger(MigrationRunner.class.getName());
    private static final Pattern MIGRATION_FILE = Pattern.compile("^V(\\d{3})__.+\\.sql$");
    private static final Pattern SEED_FILE = Pattern.compile("^S(\\d{3})__.+\\.sql$");
    private static final Pattern VERIFY_FILE = Pattern.compile("^Q(\\d{3})__.+\\.sql$");

    private final Path root;
    private final Properties dbConfig;

    private MigrationRunner(Path root, Properties dbConfig) {
        this.root = root;
        this.dbConfig = dbConfig;
    }

    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "status" : args[0].toLowerCase();
        Path projectRoot = Paths.get(System.getProperty("db.root", ".")).toAbsolutePath().normalize();
        Properties config = loadConfig(projectRoot);
        MigrationRunner runner = new MigrationRunner(projectRoot, config);

        switch (command) {
            case "migrate" -> runner.migrate();
            case "seed" -> runner.runScripts("seed", SEED_FILE);
            case "verify" -> runner.runScripts("verify", VERIFY_FILE);
            case "status" -> runner.status();
            default -> {
                System.err.println("Unknown command: " + command);
                System.err.println("Use: migrate | seed | verify | status");
                System.exit(2);
            }
        }
    }

    private static Properties loadConfig(Path projectRoot) throws IOException {
        Properties properties = new Properties();
        String explicit = System.getProperty("db.properties");
        Path file = explicit != null && !explicit.isBlank()
                ? Paths.get(explicit)
                : projectRoot.resolve("db.properties");
        if (Files.isRegularFile(file)) {
            try (var in = Files.newInputStream(file)) {
                properties.load(in);
            }
        }
        override(properties, "db.url", "DB_URL");
        override(properties, "db.user", "DB_USER");
        override(properties, "db.password", "DB_PASSWORD");
        if (properties.getProperty("db.url") == null) {
            throw new IllegalStateException(
                    "No database configuration. Copy db/db.properties.example to db/db.properties.");
        }
        return properties;
    }

    private static void override(Properties properties, String key, String env) {
        String value = System.getenv(env);
        if (value != null && !value.isBlank()) {
            properties.setProperty(key, value);
        }
    }

    private Connection connect() throws SQLException {
        String url = dbConfig.getProperty("db.url");
        if (!url.contains("allowMultiQueries=true")) {
            url = url + (url.contains("?") ? "&" : "?") + "allowMultiQueries=true";
        }
        return DriverManager.getConnection(
                url,
                dbConfig.getProperty("db.user"),
                dbConfig.getProperty("db.password"));
    }

    private void migrate() throws Exception {
        try (Connection connection = connect()) {
            ensureHistoryTable(connection);
            List<Path> files = list(root.resolve("migrations"), MIGRATION_FILE);
            for (Path file : files) {
                String version = versionOf(file, MIGRATION_FILE);
                String checksum = sha256(file);
                String applied = appliedChecksum(connection, version);
                if (applied != null) {
                    if (!applied.equals(checksum)) {
                        throw new IllegalStateException(
                                "Checksum mismatch for " + file.getFileName()
                                        + ". Applied files must never change.");
                    }
                    LOG.info(() -> "skip " + version + " (already applied)");
                    continue;
                }
                apply(connection, file, version, checksum);
            }
            LOG.info("Migrations up to date.");
        }
    }

    private void apply(Connection connection, Path file, String version, String checksum)
            throws SQLException, IOException {
        String sql = Files.readString(file, StandardCharsets.UTF_8);
        long started = System.nanoTime();
        LOG.info(() -> "apply " + version + " — " + file.getFileName());
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
        int elapsedMs = (int) ((System.nanoTime() - started) / 1_000_000L);
        String description = file.getFileName().toString().replaceFirst("^V\\d{3}__", "");
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO schema_version (version, description, checksum, applied_at, applied_by, execution_ms) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, version);
            insert.setString(2, description);
            insert.setString(3, checksum);
            insert.setString(4, Instant.now().toString().replace("T", " ").replace("Z", ""));
            insert.setString(5, System.getProperty("user.name", "unknown"));
            insert.setInt(6, elapsedMs);
            insert.executeUpdate();
        }
    }

    private void runScripts(String directory, Pattern pattern) throws Exception {
        try (Connection connection = connect()) {
            List<Path> files = list(root.resolve(directory), pattern);
            for (Path file : files) {
                LOG.info(() -> "run " + file.getFileName());
                try (Statement statement = connection.createStatement()) {
                    boolean result = statement.execute(Files.readString(file, StandardCharsets.UTF_8));
                    while (true) {
                        if (result) {
                            try (ResultSet rs = statement.getResultSet()) {
                                printResultSet(rs);
                            }
                        } else if (statement.getUpdateCount() == -1) {
                            break;
                        }
                        result = statement.getMoreResults();
                    }
                }
            }
            LOG.info(() -> directory + " scripts completed: " + files.size());
        }
    }

    private static void printResultSet(ResultSet rs) throws SQLException {
        int columns = rs.getMetaData().getColumnCount();
        while (rs.next()) {
            StringBuilder line = new StringBuilder();
            for (int i = 1; i <= columns; i++) {
                if (i > 1) {
                    line.append(" | ");
                }
                line.append(rs.getString(i));
            }
            System.out.println(line);
        }
    }

    private void status() throws Exception {
        try (Connection connection = connect()) {
            ensureHistoryTable(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT version, description, applied_at FROM schema_version ORDER BY version")) {
                System.out.println("version | applied_at | description");
                while (rs.next()) {
                    System.out.println(rs.getString(1) + " | " + rs.getString(3) + " | " + rs.getString(2));
                }
            }
        }
    }

    private static void ensureHistoryTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_version (
                      version      VARCHAR(20) PRIMARY KEY,
                      description  VARCHAR(200) NOT NULL,
                      checksum     CHAR(64) NOT NULL,
                      applied_at   DATETIME(6) NOT NULL,
                      applied_by   VARCHAR(80) NOT NULL,
                      execution_ms INT NOT NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                    """);
        }
    }

    private static String appliedChecksum(Connection connection, String version) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT checksum FROM schema_version WHERE version = ?")) {
            query.setString(1, version);
            try (ResultSet rs = query.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static List<Path> list(Path directory, Pattern pattern) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("Directory not found: " + directory);
        }
        try (Stream<Path> stream = Files.list(directory)) {
            List<Path> files = new ArrayList<>(stream
                    .filter(Files::isRegularFile)
                    .filter(p -> pattern.matcher(p.getFileName().toString()).matches())
                    .toList());
            files.sort(Comparator.comparing(p -> p.getFileName().toString()));
            return files;
        }
    }

    private static String versionOf(Path file, Pattern pattern) {
        Matcher matcher = pattern.matcher(file.getFileName().toString());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Bad migration name: " + file);
        }
        return "V" + matcher.group(1);
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
