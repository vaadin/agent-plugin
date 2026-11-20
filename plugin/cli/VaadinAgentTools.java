/*
 * vaadin-agent-tools: Vaadin tools for AI agents (and humans).
 *
 * This one file is the whole CLI. The plugin ships it as source, and the
 * launchers next to it (vaadin-agent-tools, vaadin-agent-tools.bat) run it with
 * `java VaadinAgentTools.java`: Java's source-file mode (JEP 330), which compiles
 * it in memory on every run. Nothing compiled is shipped, and nothing has to be
 * built.
 *
 * Keep it that way:
 *
 *   - One file. Source-file mode runs a program spread over several files only
 *     from Java 22 (JEP 458), and this one has to run on Java 17, the oldest JDK
 *     that Vaadin 24 supports. The tests (test/VaadinAgentToolsTest.java) are
 *     compiled together with this file instead.
 *   - Java 17 language and APIs, and nothing but the JDK: there is no build step
 *     to fetch a dependency. CI compiles the file with JDK 17.
 *   - ASCII only. JDK 17 reads a source file in the platform's charset, so a
 *     non-ASCII character breaks the compile under a C or POSIX locale. Use
 *     Unicode escapes in string literals instead.
 *   - No network access. vaadin-create-project downloads the skeleton with curl
 *     and hands the ZIP to create-project.
 *
 * The command line is the contract the plugin's skills and hook rely on: tool
 * names, arguments, the { "tool", "ok", ... } envelope that --json prints, and the
 * exit codes 0 (no error-level findings), 1 (findings, or a failure), and 2
 * (usage error).
 */

import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public final class VaadinAgentTools {

    private VaadinAgentTools() {
    }

    public static void main(String[] argv) {
        // UTF-8 whatever the platform default is: the output is JSON, or text with
        // check marks in it, and what reads it expects UTF-8.
        PrintStream out = new PrintStream(new BufferedOutputStream(new FileOutputStream(FileDescriptor.out)),
                false, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        int code = run(argv, System.in, out, err, Path.of("").toAbsolutePath());
        out.flush();
        System.exit(code);
    }

    // ===================================================================== CLI

    /** The available tools. To add one, write its class below and list it here. */
    static final List<Tool> TOOLS = List.of(
            new Tool(AuraUsage.NAME, AuraUsage.SUMMARY, AuraUsage.USAGE, AuraUsage::run),
            new Tool(ThemeMixing.NAME, ThemeMixing.SUMMARY, ThemeMixing.USAGE, ThemeMixing::run),
            new Tool(CreateProject.NAME, CreateProject.SUMMARY, CreateProject.USAGE, CreateProject::run));

    /** A tool the CLI can run. */
    record Tool(String name, String summary, String usage, Function<Args, Result> run) {
    }

    /**
     * The invocation a tool runs with. The positionals include any tool-specific
     * flags (such as --overwrite), which the tool parses itself; the CLI has
     * already consumed the global ones (--json, --help).
     */
    record Args(List<String> positionals, Path cwd) {
    }

    /**
     * What a tool returns. A non-null usageError means a usage error (exit code
     * 2), and the rest is ignored. Otherwise --json prints { "tool", "ok",
     * ...payload }, with the payload's fields in their own order, and plain
     * output prints human.
     */
    record Result(boolean ok, String usageError, Map<String, Object> payload, String human) {
        static Result usageError(String message) {
            return new Result(false, message, Map.of(), "");
        }
    }

    /** Runs the CLI and returns its exit code. */
    static int run(String[] argv, InputStream stdin, PrintStream out, PrintStream err, Path cwd) {
        boolean json = false;
        boolean help = false;
        List<String> pos = new ArrayList<>();
        for (String a : argv) {
            switch (a) {
                case "--json" -> json = true;
                case "--help", "-h" -> help = true;
                default -> pos.add(a);
            }
        }
        String command = pos.isEmpty() ? "" : pos.remove(0);

        // `hook` reads a hook event on stdin and answers in that hook's own JSON
        // contract, not in the { tool, ok, ... } envelope, so it is not a tool.
        if (command.equals("hook")) {
            return Hook.run(pos, stdin, out, err, cwd);
        }

        if (command.isEmpty() || command.equals("help") || command.equals("list")) {
            if (json) {
                List<Object> tools = new ArrayList<>();
                for (Tool t : TOOLS) {
                    tools.add(obj("name", t.name(), "summary", t.summary()));
                }
                println(out, Json.write(obj("tools", tools)));
            } else {
                println(out, topLevelHelp());
            }
            return 0;
        }

        Tool tool = TOOLS.stream().filter(t -> t.name().equals(command)).findFirst().orElse(null);
        if (tool == null) {
            err.print("Unknown tool: " + command + "\n\n" + topLevelHelp() + "\n");
            return 2;
        }
        if (help) {
            println(out, tool.usage().isEmpty() ? tool.summary() : tool.usage());
            return 0;
        }

        Result res = tool.run().apply(new Args(pos, cwd));
        if (res.usageError() != null) {
            if (json) {
                println(out, Json.write(obj("tool", tool.name(), "ok", false, "usageError", res.usageError())));
            } else {
                err.print("Error: " + res.usageError() + "\n");
            }
            return 2;
        }
        println(out, json ? Json.write(envelope(tool.name(), res.ok(), res.payload())) : res.human());
        return res.ok() ? 0 : 1;
    }

    static String topLevelHelp() {
        List<String> lines = new ArrayList<>(List.of(
                "vaadin-agent-tools \u2014 Vaadin tools for AI agents and humans",
                "",
                "Usage:",
                "  vaadin-agent-tools <tool> [args] [--json]",
                "",
                "Tools:"));
        for (Tool t : TOOLS) {
            lines.add(String.format("  %-22s %s", t.name(), t.summary()));
        }
        lines.addAll(List.of(
                "",
                "Hooks (read a Claude Code or Codex hook event on stdin):",
                "  hook post-tool-use     Run the styling checks after a styling edit",
                "",
                "Global flags:",
                "  --json        Emit machine-readable JSON (recommended for agents)",
                "  -h, --help    Show help (use with a tool for tool-specific help)"));
        return String.join("\n", lines);
    }

    /** { "tool": name, "ok": ok, ...payload }, keeping the payload's field order. */
    static Map<String, Object> envelope(String name, boolean ok, Map<String, Object> payload) {
        Map<String, Object> m = obj("tool", name, "ok", ok);
        m.putAll(payload);
        return m;
    }

    // ================================================================ findings

    /**
     * One finding from a check.
     *
     * @param level      "error", "warning", or "info"
     * @param code       stable machine-readable identifier, e.g. MULTIPLE_BASE_THEMES
     * @param message    human-readable one-liner
     * @param confidence "high", "medium", or "low": how sure the heuristic is
     * @param evidence   the file:line snippets that triggered the finding
     */
    record Finding(String level, String code, String message, String confidence, List<Evidence> evidence) {
        Map<String, Object> toJson() {
            return obj("level", level, "code", code, "message", message, "confidence", confidence,
                    "evidence", evidence.stream().map(Evidence::toJson).collect(Collectors.toList()));
        }
    }

    /** Where a finding was seen. The snippet is the source line, trimmed. */
    record Evidence(String file, int line, String snippet) {
        Evidence {
            snippet = snippet.strip();
        }

        Map<String, Object> toJson() {
            return obj("file", file, "line", line, "snippet", snippet);
        }
    }

    static List<Object> findingsJson(List<Finding> findings) {
        return findings.stream().map(Finding::toJson).collect(Collectors.toList());
    }

    static boolean hasErrors(List<Finding> findings) {
        return findings.stream().anyMatch(f -> f.level().equals("error"));
    }

    /** The plain-text rendering of a finding list, shared so every tool reads the same. */
    static List<String> renderFindings(List<Finding> findings) {
        if (findings.isEmpty()) {
            return List.of("\u2713 No issues found.");
        }
        List<String> out = new ArrayList<>();
        for (Finding f : findings) {
            String marker = switch (f.level()) {
                case "error" -> "\u2717";
                case "warning" -> "\u26a0";
                default -> "\u2139";
            };
            out.add(marker + " [" + f.level() + "] " + f.code() + " (confidence: " + f.confidence() + ")");
            out.add("  " + f.message());
            for (Evidence e : f.evidence()) {
                out.add("    " + e.file() + ":" + e.line() + "  " + e.snippet());
            }
            out.add("");
        }
        return out;
    }

    /** The plain-text report of a scanning tool: a header, then the findings. */
    static String renderScan(String tool, List<String> themesLoaded, int filesScanned, List<Finding> findings) {
        List<String> out = new ArrayList<>();
        out.add("# " + tool);
        out.add("themes loaded: " + (themesLoaded.isEmpty() ? "(none detected)" : String.join(", ", themesLoaded)));
        out.add("files scanned: " + filesScanned);
        out.add("");
        out.addAll(renderFindings(findings));
        return trimTrailingNewlines(String.join("\n", out));
    }

    // ================================================================= helpers

    /** An ordered JSON object built from alternating keys and values. */
    static Map<String, Object> obj(Object... keysAndValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            m.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return m;
    }

    /** Prints a line ending in \n on every platform, as JSON consumers expect. */
    static void println(PrintStream s, String text) {
        s.print(text);
        s.print('\n');
    }

    static String trimTrailingNewlines(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '\n') {
            end--;
        }
        return s.substring(0, end);
    }

    /** The extension of the last element of path, with its dot, or "". */
    static String ext(String path) {
        for (int i = path.length() - 1; i >= 0 && !isSeparator(path.charAt(i)); i--) {
            if (path.charAt(i) == '.') {
                return path.substring(i);
            }
        }
        return "";
    }

    private static boolean isSeparator(char c) {
        return c == '/' || (c == '\\' && java.io.File.separatorChar == '\\');
    }

    /** The 1-based line that index falls on. */
    static int lineOf(String content, int index) {
        int line = 1;
        for (int i = 0; i < index && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** The whole line that index falls on, without its line terminator. */
    static String snippetAt(String content, int index) {
        int start = content.lastIndexOf('\n', index - 1) + 1;
        int end = content.indexOf('\n', index);
        return content.substring(start, end < 0 ? content.length() : end);
    }

    static Evidence evidenceAt(String file, String content, int index) {
        return new Evidence(file, lineOf(content, index), snippetAt(content, index));
    }

    /** Reads a file as UTF-8, replacing malformed input rather than failing. */
    static String readText(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /** arg resolved against cwd, or null if it is not a valid path at all. */
    static Path resolve(Path cwd, String arg) {
        try {
            return cwd.resolve(arg).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** The project directory a scanning tool was pointed at (default: cwd). */
    static Path projectDir(Args args) {
        String arg = args.positionals().isEmpty() ? "." : args.positionals().get(0);
        Path target = resolve(args.cwd(), arg);
        return target != null ? target : Path.of(arg.replace('\0', '?'));
    }

    // ==================================================================== walk

    /** Directory names that are never descended into. */
    static final Set<String> IGNORED_DIRS = Set.of("node_modules", "target", "build", "dist", "out", ".git",
            ".idea", ".vscode", ".mvn", "frontend-generated");

    /**
     * The regular files under root whose extension (compared case-insensitively,
     * with its dot) is in exts, sorted for deterministic output. Symbolic links
     * are not followed, and an unreadable directory is skipped rather than failing
     * the whole walk.
     */
    static List<Path> walk(Path root, Set<String> exts) {
        List<String> results = new ArrayList<>();
        Deque<Path> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Path dir = stack.pop();
            List<Path> entries = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path p : ds) {
                    entries.add(p);
                }
            } catch (IOException | DirectoryIteratorException e) {
                continue; // unreadable directory: skip it
            }
            for (Path p : entries) {
                BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException e) {
                    continue;
                }
                String name = p.getFileName().toString();
                if (attrs.isDirectory()) {
                    if (!IGNORED_DIRS.contains(name)) {
                        stack.push(p);
                    }
                } else if (attrs.isRegularFile() && exts.contains(ext(name).toLowerCase(Locale.ROOT))) {
                    results.add(p.toString());
                }
            }
        }
        Collections.sort(results);
        return results.stream().map(Path::of).collect(Collectors.toList());
    }

    // ===================================================================== CSS

    /**
     * A deliberately small CSS reader: enough structure to answer "which custom
     * property is assigned, to what, and under which selector" without a real CSS
     * parser. It understands comments, strings, parentheses, at-rules and nesting,
     * which is all the checks need, and never fails on input it does not
     * understand; an unparseable region simply yields no declarations.
     *
     * Every pass works on the source with comments (and, for Java, text blocks)
     * blanked to spaces, which keeps the length and the line terminators, so an
     * offset into the blanked text is still an offset into the original.
     */
    static final class Css {
        private Css() {
        }

        /**
         * One custom-property declaration. selectors is the chain of enclosing rule
         * preludes, outermost first, without at-rule preludes (@media, @supports,
         * ...), which select no elements. offset is where the property name starts.
         */
        record Declaration(String property, String value, int offset, List<String> selectors) {
        }

        /** One var(--name) reference. */
        record VarRead(String name, int offset) {
        }

        record Parsed(List<Declaration> declarations, List<VarRead> reads) {
        }

        /**
         * Replaces every block comment with spaces. String literals are skipped, so
         * a comment marker inside one (content: "/*") does not start a comment, and
         * quotes inside a comment do not start a string.
         */
        static String blankComments(String css) {
            return blankComments(css, false);
        }

        /**
         * blankComments for Java sources: it also blanks line comments, and skips
         * char literals and text blocks as well as strings.
         */
        static String blankJavaComments(String src) {
            return blankComments(src, true);
        }

        /**
         * Replaces the contents of every Java text block with spaces. Code quoted
         * in a text block is documentation, not code: a snippet shown in a string
         * never reaches the browser. Pass the output of blankJavaComments, so a
         * triple quote written inside a comment cannot open a block.
         */
        static String blankJavaTextBlocks(String src) {
            char[] out = src.toCharArray();
            for (int i = 0; i < out.length;) {
                if (isTextBlockDelimiter(out, i)) {
                    int[] end = javaTextBlock(out, i);
                    blankRange(out, i + 3, end[0]);
                    i = end[1];
                } else if (out[i] == '"' || out[i] == '\'') {
                    // Step over an ordinary string or char literal, so a quote inside
                    // one cannot look like the start of a text block.
                    char quote = out[i];
                    int j = i + 1;
                    while (j < out.length && out[j] != quote) {
                        if (out[j] == '\\') {
                            j++;
                        }
                        j++;
                    }
                    i = j + 1;
                } else {
                    i++;
                }
            }
            return new String(out);
        }

        /** Whether buf has the three quotes that open or close a text block at i. */
        private static boolean isTextBlockDelimiter(char[] buf, int i) {
            return i + 2 < buf.length && buf[i] == '"' && buf[i + 1] == '"' && buf[i + 2] == '"';
        }

        /**
         * Locates the end of the text block opening at i: { contentEnd, blockEnd },
         * where contentEnd is where its contents stop (the closing delimiter, or
         * EOF when the block is unterminated) and blockEnd is where scanning
         * resumes after it.
         *
         * Both Java passes call this, so they can never disagree about where a block
         * ends. A disagreement would not be harmless: a comment pass that ran past
         * the closing delimiter would leave the text-block pass to blank the real
         * code after it, and the assignments there would go unreported.
         */
        private static int[] javaTextBlock(char[] buf, int i) {
            int j = i + 3;
            while (j < buf.length && !isTextBlockDelimiter(buf, j)) {
                if (buf[j] == '\\') {
                    j++; // an escaped quote does not close the block
                }
                j++;
            }
            if (j >= buf.length) {
                return new int[] {buf.length, buf.length};
            }
            return new int[] {j, j + 3};
        }

        /**
         * Overwrites buf[from, to) with spaces, keeping line terminators so line
         * numbers computed against the original still hold. Java and CSS both count
         * a bare CR as one, so neither may be blanked away.
         */
        private static void blankRange(char[] buf, int from, int to) {
            for (int k = from; k < to && k < buf.length; k++) {
                if (buf[k] != '\n' && buf[k] != '\r') {
                    buf[k] = ' ';
                }
            }
        }

        /**
         * Walks comments and string literals in one pass so the two cannot be
         * confused for one another. lineComments enables // comments: Java only,
         * since in CSS // is ordinary text, as in url(https://example.com).
         */
        private static String blankComments(String src, boolean lineComments) {
            char[] out = src.toCharArray();
            for (int i = 0; i < out.length;) {
                char c = out[i];
                if (c == '/' && i + 1 < out.length && out[i + 1] == '*') {
                    int j = i + 2;
                    while (j + 1 < out.length && !(out[j] == '*' && out[j + 1] == '/')) {
                        j++;
                    }
                    int end = Math.min(j + 2, out.length); // unterminated: blank to EOF
                    blankRange(out, i, end);
                    i = end;
                } else if (lineComments && c == '/' && i + 1 < out.length && out[i + 1] == '/') {
                    int j = i;
                    while (j < out.length && out[j] != '\n' && out[j] != '\r') {
                        j++;
                    }
                    blankRange(out, i, j);
                    i = j;
                } else if (lineComments && isTextBlockDelimiter(out, i)) {
                    // A text block is one literal, however many quotes it contains.
                    // Lexing it as a run of ordinary strings desynchronizes everything
                    // after it: the contents can end up scanned as code, and the
                    // closing delimiter erased as a comment.
                    i = javaTextBlock(out, i)[1];
                } else if (c == '"' || c == '\'') {
                    // Step over the literal so its contents are never read as structure.
                    int j = i + 1;
                    while (j < out.length && out[j] != c) {
                        if (out[j] == '\\') {
                            j++;
                        }
                        j++;
                    }
                    i = j + 1;
                } else {
                    i++;
                }
            }
            return new String(out);
        }

        /**
         * The custom-property declarations (property names starting with --) in a
         * stylesheet, and every var() reference in it. Pass the output of
         * blankComments so commented-out code is ignored.
         */
        static Parsed parse(String css) {
            return new Parser(css).parse();
        }

        private static final class Parser {
            private final String css;
            private final List<Declaration> decls = new ArrayList<>();
            private final List<VarRead> reads = new ArrayList<>();
            // Enclosing rule preludes, outermost first; "" marks an at-rule level.
            private final List<String> selectors = new ArrayList<>();
            private final StringBuilder buf = new StringBuilder();
            private int bufStart = -1;
            private int blockDepth; // { } nesting

            Parser(String css) {
                this.css = css;
            }

            Parsed parse() {
                int n = css.length();
                int depth = 0; // parenthesis depth
                for (int i = 0; i < n; i++) {
                    char c = css.charAt(i);

                    // Record var(--name) here rather than in a separate pass, so a
                    // reference that only looks like one inside a string literal is
                    // not counted: the string branch below skips past those.
                    if ((c == 'v' || c == 'V') && startsWithIgnoreCase(css, i, "var(")
                            && (i == 0 || !isIdentChar(css.charAt(i - 1)))) {
                        int j = i + 4;
                        while (j < n && (css.charAt(j) == ' ' || css.charAt(j) == '\t' || css.charAt(j) == '\n'
                                || css.charAt(j) == '\r')) {
                            j++;
                        }
                        if (css.startsWith("--", j)) {
                            int k = j;
                            while (k < n && isIdentChar(css.charAt(k))) {
                                k++;
                            }
                            reads.add(new VarRead(css.substring(j, k), j));
                        }
                    }

                    if (c == '"' || c == '\'') {
                        // Consume the whole string literal so its contents cannot be
                        // read as structure. It stays in buf: a var() inside a string
                        // is not a read, but a quoted value is still the value.
                        int j = i + 1;
                        while (j < n && css.charAt(j) != c) {
                            if (css.charAt(j) == '\\') {
                                j++;
                            }
                            j++;
                        }
                        if (j >= n) {
                            j = n - 1;
                        }
                        if (bufStart < 0) {
                            bufStart = i;
                        }
                        buf.append(css, i, j + 1);
                        i = j;
                        continue;
                    }
                    if (c == '(') {
                        depth++;
                    } else if (c == ')') {
                        if (depth > 0) {
                            depth--;
                        }
                    } else if (c == '{' && depth == 0) {
                        String prelude = buf.toString().strip();
                        buf.setLength(0);
                        bufStart = -1;
                        blockDepth++;
                        // An at-rule (or an unreadable prelude) pushes a placeholder,
                        // so the matching } pops it and nothing else.
                        selectors.add(!prelude.isEmpty() && !prelude.startsWith("@") ? prelude : "");
                        continue;
                    } else if (c == '}' && depth == 0) {
                        flushDeclaration(); // a last declaration may omit its semicolon
                        if (blockDepth > 0) {
                            blockDepth--;
                            selectors.remove(selectors.size() - 1);
                        }
                        continue;
                    } else if (c == ';' && depth == 0) {
                        flushDeclaration();
                        continue;
                    }

                    if (bufStart < 0 && c != ' ' && c != '\t' && c != '\n' && c != '\r' && c != '\f') {
                        bufStart = i;
                    }
                    if (bufStart >= 0) {
                        buf.append(c);
                    }
                }
                return new Parsed(decls, reads);
            }

            private void flushDeclaration() {
                String text = buf.toString();
                buf.setLength(0);
                int start = bufStart;
                bufStart = -1;
                if (blockDepth == 0 || start < 0) {
                    return;
                }
                int colon = text.indexOf(':');
                if (colon < 0) {
                    return;
                }
                String prop = text.substring(0, colon).strip();
                if (!prop.startsWith("--")) {
                    return;
                }
                List<String> chain = selectors.stream().filter(s -> !s.isEmpty()).collect(Collectors.toList());
                decls.add(new Declaration(prop, text.substring(colon + 1).strip(), start, chain));
            }
        }

        /** Whether s has prefix (lowercase ASCII) at i, ignoring ASCII case. */
        private static boolean startsWithIgnoreCase(String s, int i, String prefix) {
            if (s.length() - i < prefix.length()) {
                return false;
            }
            for (int k = 0; k < prefix.length(); k++) {
                char c = s.charAt(i + k);
                if (c >= 'A' && c <= 'Z') {
                    c += 'a' - 'A';
                }
                if (c != prefix.charAt(k)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Whether c can appear in a CSS identifier. Every non-ASCII character
         * counts: CSS identifiers may contain them, and treating one as a boundary
         * would both truncate a custom property name and let one class name match
         * a longer one.
         */
        static boolean isIdentChar(char c) {
            return c == '-' || c == '_' || c >= 0x80 || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9');
        }

        /**
         * Whether any selector in chain mentions any of the tokens. A token with
         * identifier characters at its edges must match on an identifier boundary,
         * so "vaadin-tab" does not match "vaadin-tabs"; a token like
         * "::part(overlay)" matches anywhere, so it also matches
         * "vaadin-dialog::part(overlay)".
         */
        static boolean selectorChainMatches(List<String> chain, List<String> tokens) {
            List<String> normalized = tokens.stream().map(Css::normalizeSelector).collect(Collectors.toList());
            for (String sel : chain) {
                String hay = normalizeSelector(sel);
                for (String tok : normalized) {
                    if (containsSelectorToken(hay, tok)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Folds away the spellings CSS treats as equivalent, so a token written one
         * way still matches a selector written another: case, "double" vs 'single'
         * attribute quotes, and whitespace inside an attribute selector
         * ([theme~= "success"] is [theme~='success']).
         *
         * Whitespace outside brackets is left alone: there it is the descendant
         * combinator, and collapsing it would weld two identifiers into one, so
         * "vaadin-notification-card vaadin-button" would stop mentioning
         * vaadin-button.
         */
        static String normalizeSelector(String sel) {
            StringBuilder b = new StringBuilder(sel.length());
            int inBrackets = 0;
            char quote = 0; // the open string delimiter, or 0 outside a string
            for (int i = 0; i < sel.length(); i++) {
                char c = sel.charAt(i);

                // Inside a quoted value nothing is structure: a [ there does not open
                // a bracket, a space there is part of the value, and the case is
                // significant (attribute values are case-sensitive, unlike the rest
                // of a selector).
                if (quote != 0) {
                    if (c == '\\' && i + 1 < sel.length()) {
                        b.append(c).append(sel.charAt(++i));
                    } else if (c == quote) {
                        quote = 0;
                        b.append('\''); // both delimiters normalize to '
                    } else {
                        b.append(c);
                    }
                    continue;
                }

                if (c == '"' || c == '\'') {
                    quote = c;
                    b.append('\'');
                } else if (c == '[') {
                    inBrackets++;
                    b.append(c);
                } else if (c == ']') {
                    if (inBrackets > 0) {
                        inBrackets--;
                    }
                    b.append(c);
                } else if (inBrackets > 0 && (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f')) {
                    continue; // dropped
                } else if (c >= 'A' && c <= 'Z') {
                    b.append((char) (c + 'a' - 'A'));
                } else {
                    b.append(c);
                }
            }
            return b.toString();
        }

        private static boolean containsSelectorToken(String haystack, String token) {
            if (token.isEmpty()) {
                return false;
            }
            boolean needLeft = isIdentChar(token.charAt(0));
            boolean needRight = isIdentChar(token.charAt(token.length() - 1));
            for (int from = 0;;) {
                int start = haystack.indexOf(token, from);
                if (start < 0) {
                    return false;
                }
                int end = start + token.length();
                boolean okLeft = !needLeft || start == 0 || !isIdentChar(haystack.charAt(start - 1));
                boolean okRight = !needRight || end == haystack.length() || !isIdentChar(haystack.charAt(end));
                if (okLeft && okRight) {
                    return true;
                }
                from = start + 1;
            }
        }
    }

    // ==================================================================== JSON

    /**
     * JSON out and in. The JDK has no JSON support, and the CLI needs little: it
     * writes maps, lists, strings, ints and booleans, and reads the hook payload.
     */
    static final class Json {
        private Json() {
        }

        static final class ParseException extends Exception {
            private static final long serialVersionUID = 1L;

            ParseException(String message) {
                super(message);
            }
        }

        /**
         * value as JSON indented by two spaces, the same text Go's encoding/json
         * writes with SetIndent("", "  ") and SetEscapeHTML(false): <, > and &
         * stay readable in source-code snippets.
         */
        static String write(Object value) {
            StringBuilder sb = new StringBuilder();
            write(sb, value, "");
            return sb.toString();
        }

        private static void write(StringBuilder sb, Object value, String indent) {
            if (value == null) {
                sb.append("null");
            } else if (value instanceof String s) {
                quote(sb, s);
            } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
                sb.append(value);
            } else if (value instanceof Map<?, ?> map) {
                if (map.isEmpty()) {
                    sb.append("{}");
                    return;
                }
                String inner = indent + "  ";
                String sep = "{\n";
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    sb.append(sep).append(inner);
                    quote(sb, (String) e.getKey());
                    sb.append(": ");
                    write(sb, e.getValue(), inner);
                    sep = ",\n";
                }
                sb.append('\n').append(indent).append('}');
            } else if (value instanceof List<?> list) {
                if (list.isEmpty()) {
                    sb.append("[]");
                    return;
                }
                String inner = indent + "  ";
                String sep = "[\n";
                for (Object item : list) {
                    sb.append(sep).append(inner);
                    write(sb, item, inner);
                    sep = ",\n";
                }
                sb.append('\n').append(indent).append(']');
            } else {
                throw new IllegalArgumentException("not a JSON value: " + value.getClass());
            }
        }

        private static void quote(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    // Valid JSON, but not valid JavaScript inside a string literal.
                    case '\u2028', '\u2029' -> sb.append(String.format("\\u%04x", (int) c));
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            sb.append('"');
        }

        /**
         * Parses text into maps (in key order), lists, strings, doubles, booleans
         * and nulls. Anything that is not exactly one JSON value is an error.
         */
        static Object parse(String text) throws ParseException {
            Reader r = new Reader(text);
            Object value = r.value(0);
            r.skipWhitespace();
            if (r.pos < text.length()) {
                throw r.error("unexpected text after the value");
            }
            return value;
        }

        /** v as an object; null (or a missing member) reads as an empty one. */
        @SuppressWarnings("unchecked")
        static Map<String, Object> object(Object v, String what) throws ParseException {
            if (v == null) {
                return Map.of();
            }
            if (v instanceof Map) {
                return (Map<String, Object>) v; // the reader only makes Map<String, Object>
            }
            throw new ParseException(what + " is not an object");
        }

        /** v as an array; null (or a missing member) reads as an empty one. */
        static List<?> array(Object v, String what) throws ParseException {
            if (v == null) {
                return List.of();
            }
            if (v instanceof List<?> list) {
                return list;
            }
            throw new ParseException(what + " is not an array");
        }

        /** v as a string; null (or a missing member) reads as "". */
        static String string(Object v, String what) throws ParseException {
            if (v == null) {
                return "";
            }
            if (v instanceof String s) {
                return s;
            }
            throw new ParseException(what + " is not a string");
        }

        private static final class Reader {
            private static final int MAX_DEPTH = 1000;
            private final String s;
            int pos;

            Reader(String s) {
                this.s = s;
            }

            Object value(int depth) throws ParseException {
                if (depth > MAX_DEPTH) {
                    throw error("nested too deeply");
                }
                skipWhitespace();
                if (pos >= s.length()) {
                    throw error("unexpected end of input");
                }
                return switch (s.charAt(pos)) {
                    case '{' -> object(depth);
                    case '[' -> array(depth);
                    case '"' -> string();
                    case 't' -> literal("true", Boolean.TRUE);
                    case 'f' -> literal("false", Boolean.FALSE);
                    case 'n' -> literal("null", null);
                    default -> number();
                };
            }

            private Map<String, Object> object(int depth) throws ParseException {
                Map<String, Object> map = new LinkedHashMap<>();
                pos++;
                skipWhitespace();
                if (at('}')) {
                    pos++;
                    return map;
                }
                while (true) {
                    skipWhitespace();
                    if (!at('"')) {
                        throw error("expected a member name");
                    }
                    String key = string();
                    skipWhitespace();
                    expect(':');
                    map.put(key, value(depth + 1));
                    skipWhitespace();
                    if (at(',')) {
                        pos++;
                        continue;
                    }
                    expect('}');
                    return map;
                }
            }

            private List<Object> array(int depth) throws ParseException {
                List<Object> list = new ArrayList<>();
                pos++;
                skipWhitespace();
                if (at(']')) {
                    pos++;
                    return list;
                }
                while (true) {
                    list.add(value(depth + 1));
                    skipWhitespace();
                    if (at(',')) {
                        pos++;
                        continue;
                    }
                    expect(']');
                    return list;
                }
            }

            private String string() throws ParseException {
                pos++; // the opening quote
                StringBuilder sb = new StringBuilder();
                while (true) {
                    if (pos >= s.length()) {
                        throw error("unterminated string");
                    }
                    char c = s.charAt(pos++);
                    if (c == '"') {
                        return sb.toString();
                    }
                    if (c < 0x20) {
                        throw error("control character in a string");
                    }
                    if (c != '\\') {
                        sb.append(c);
                        continue;
                    }
                    if (pos >= s.length()) {
                        throw error("unterminated string");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"', '\\', '/' -> sb.append(e);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> sb.append(hex4());
                        default -> throw error("invalid escape");
                    }
                }
            }

            private char hex4() throws ParseException {
                if (pos + 4 > s.length()) {
                    throw error("invalid unicode escape");
                }
                int v = 0;
                for (int k = 0; k < 4; k++) {
                    int d = Character.digit(s.charAt(pos++), 16);
                    if (d < 0) {
                        throw error("invalid unicode escape");
                    }
                    v = v * 16 + d;
                }
                return (char) v;
            }

            private Double number() throws ParseException {
                int start = pos;
                if (at('-')) {
                    pos++;
                }
                if (at('0')) {
                    pos++;
                } else if (digits() == 0) {
                    throw error("unexpected character");
                }
                if (at('.')) {
                    pos++;
                    if (digits() == 0) {
                        throw error("invalid number");
                    }
                }
                if (at('e') || at('E')) {
                    pos++;
                    if (at('+') || at('-')) {
                        pos++;
                    }
                    if (digits() == 0) {
                        throw error("invalid number");
                    }
                }
                return Double.valueOf(s.substring(start, pos));
            }

            private int digits() {
                int start = pos;
                while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                    pos++;
                }
                return pos - start;
            }

            private Object literal(String word, Object value) throws ParseException {
                if (!s.startsWith(word, pos)) {
                    throw error("unexpected character");
                }
                pos += word.length();
                return value;
            }

            void skipWhitespace() {
                while (pos < s.length()) {
                    char c = s.charAt(pos);
                    if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                        return;
                    }
                    pos++;
                }
            }

            private boolean at(char c) {
                return pos < s.length() && s.charAt(pos) == c;
            }

            private void expect(char c) throws ParseException {
                if (!at(c)) {
                    throw error("expected '" + c + "'");
                }
                pos++;
            }

            ParseException error(String message) {
                return new ParseException(message + " at offset " + pos);
            }
        }
    }

    // ====================================================== check-theme-mixing

    /** Detects a project that loads both the Aura and Lumo base themes. */
    static final class ThemeMixing {
        private ThemeMixing() {
        }

        static final String NAME = "check-theme-mixing";
        static final String SUMMARY = "Detect whether a Vaadin project mixes the Aura and Lumo themes.";
        static final String USAGE = """
                vaadin-agent-tools check-theme-mixing [projectDir]

                Scans a Vaadin project for signs that both the Aura and Lumo base themes are in
                use at the same time, which leads to conflicting styles and unresolved CSS
                custom properties.

                Arguments:
                  projectDir   Path to the Vaadin project root (default: current directory)

                Detection signals:
                  - @StyleSheet(Aura.STYLESHEET) / @StyleSheet(Lumo.STYLESHEET) in Java sources
                  - @import of aura/aura.css or lumo/lumo.css in reusable-theme stylesheets
                  - Usage of --aura-* vs --lumo-* CSS custom properties
                  - Usage of the LumoUtility class in Java (only works under the Lumo theme)

                Exit codes:
                  0  no error-level findings
                  1  mixing detected (error-level finding)
                  2  usage error (e.g. projectDir does not exist)""";

        // @StyleSheet(Aura.STYLESHEET), or with the annotation or the theme class
        // fully qualified, e.g.
        // @com.vaadin.flow.server.StyleSheet(com.vaadin.flow.theme.aura.Aura.STYLESHEET).
        // The optional qualifiers require a trailing dot, so they match a package
        // prefix without matching a different annotation (e.g. @MyStyleSheet).
        static final Pattern JAVA_STYLESHEET = Pattern.compile(
                "@(?:[\\w.]*\\.)?StyleSheet\\s*\\(\\s*(?:[\\w.]*\\.)?(Aura|Lumo)\\.STYLESHEET");
        // The legacy Flow theming mechanism (Vaadin 24 and earlier), qualified or not.
        static final Pattern JAVA_LEGACY_THEME = Pattern.compile("@(?:[\\w.]*\\.)?Theme\\s*\\(");
        // LumoUtility emits Lumo-specific utility class names (e.g.
        // LumoUtility.Margin.MEDIUM) that the Aura theme does not define.
        static final Pattern JAVA_LUMO_UTILITY = Pattern.compile("(?<![A-Za-z0-9_])LumoUtility(?![A-Za-z0-9_])");
        // @import '.../aura/aura.css' or "lumo/lumo.css"
        static final Pattern CSS_IMPORT_AURA = Pattern.compile("@import\\s+[\"'][^\"']*/aura/aura\\.css[\"']");
        static final Pattern CSS_IMPORT_LUMO = Pattern.compile("@import\\s+[\"'][^\"']*/lumo/lumo\\.css[\"']");
        // Custom-property prefixes
        static final Pattern AURA_TOKEN = Pattern.compile("--aura-[\\w-]+");
        static final Pattern LUMO_TOKEN = Pattern.compile("--lumo-[\\w-]+");

        record Report(String usageError, boolean ok, List<String> themesLoaded, List<String> tokenPrefixesUsed,
                int filesScanned, List<Finding> findings) {
            Map<String, Object> toJson() {
                return obj("themesLoaded", themesLoaded, "tokenPrefixesUsed", tokenPrefixesUsed,
                        "filesScanned", filesScanned, "findings", findingsJson(findings));
            }
        }

        static Result run(Args args) {
            Report r = analyze(args);
            if (r.usageError() != null) {
                return Result.usageError(r.usageError());
            }
            return new Result(r.ok(), null, r.toJson(), renderScan(NAME, r.themesLoaded(), r.filesScanned(),
                    r.findings()));
        }

        static Report analyze(Args args) {
            Path target = projectDir(args);
            if (!Files.isDirectory(target)) {
                return new Report("Project directory not found: " + target, false, List.of(), List.of(), 0,
                        List.of());
            }

            List<Path> files = walk(target, Set.of(".java", ".css"));

            Map<String, List<Evidence>> loaded = new LinkedHashMap<>();
            Map<String, List<Evidence>> tokens = new LinkedHashMap<>();
            for (String theme : List.of("aura", "lumo")) {
                loaded.put(theme, new ArrayList<>());
                tokens.put(theme, new ArrayList<>());
            }
            List<Evidence> legacyTheme = new ArrayList<>();
            List<Evidence> lumoUtility = new ArrayList<>();

            for (Path file : files) {
                String content;
                try {
                    content = readText(file);
                } catch (IOException e) {
                    continue;
                }
                String rel = target.relativize(file).toString();

                // Detection runs over a copy with comments blanked, so commented-out
                // code is not mistaken for a loaded theme. Evidence still comes from
                // the original, at the same offsets.
                switch (ext(file.toString()).toLowerCase(Locale.ROOT)) {
                    case ".java" -> {
                        String code = Css.blankJavaComments(content);
                        Matcher m = JAVA_STYLESHEET.matcher(code);
                        while (m.find()) {
                            loaded.get(m.group(1).toLowerCase(Locale.ROOT)).add(evidenceAt(rel, content, m.start()));
                        }
                        m = JAVA_LEGACY_THEME.matcher(code);
                        while (m.find()) {
                            legacyTheme.add(evidenceAt(rel, content, m.start()));
                        }
                        // At most one LumoUtility hit per file, to keep the evidence tidy.
                        m = JAVA_LUMO_UTILITY.matcher(code);
                        if (m.find()) {
                            lumoUtility.add(evidenceAt(rel, content, m.start()));
                        }
                    }
                    case ".css" -> {
                        String code = Css.blankComments(content);
                        Matcher m = CSS_IMPORT_AURA.matcher(code);
                        while (m.find()) {
                            loaded.get("aura").add(evidenceAt(rel, content, m.start()));
                        }
                        m = CSS_IMPORT_LUMO.matcher(code);
                        while (m.find()) {
                            loaded.get("lumo").add(evidenceAt(rel, content, m.start()));
                        }
                        // Only the first token per file per prefix, to keep the evidence tidy.
                        m = AURA_TOKEN.matcher(code);
                        if (m.find()) {
                            tokens.get("aura").add(evidenceAt(rel, content, m.start()));
                        }
                        m = LUMO_TOKEN.matcher(code);
                        if (m.find()) {
                            tokens.get("lumo").add(evidenceAt(rel, content, m.start()));
                        }
                    }
                    default -> {
                    }
                }
            }

            List<String> themesLoaded = new ArrayList<>();
            List<String> tokenPrefixesUsed = new ArrayList<>();
            for (String theme : List.of("aura", "lumo")) {
                if (!loaded.get(theme).isEmpty()) {
                    themesLoaded.add(theme);
                }
                if (!tokens.get(theme).isEmpty()) {
                    tokenPrefixesUsed.add(theme);
                }
            }

            List<Finding> findings = new ArrayList<>();

            // 1. High confidence: both base themes are explicitly loaded.
            if (themesLoaded.size() > 1) {
                List<Evidence> ev = new ArrayList<>(loaded.get("aura"));
                ev.addAll(loaded.get("lumo"));
                findings.add(new Finding("error", "MULTIPLE_BASE_THEMES",
                        "Both the Aura and Lumo base themes are loaded. Load exactly one base theme.", "high", ev));
            }

            // The checks below depend on knowing which base theme is active, which is
            // only known when exactly one is explicitly loaded. With none loaded the
            // project may use a custom or base-styles theme, and correctness is out of
            // this tool's scope, so it is a no-op rather than a guess.
            if (themesLoaded.size() == 1) {
                String active = themesLoaded.get(0);
                String other = active.equals("lumo") ? "aura" : "lumo";

                // Tokens from a theme other than the active one will not resolve.
                if (!tokens.get(other).isEmpty()) {
                    findings.add(new Finding("warning", "MISMATCHED_THEME_TOKENS",
                            "The " + active + " theme is loaded, but --" + other + "-* CSS custom properties are "
                                    + "used. These will not resolve unless the " + other + " theme is also loaded.",
                            "medium", tokens.get(other)));
                }

                // LumoUtility emits Lumo-specific utility class names that Aura lacks.
                if (active.equals("aura") && !lumoUtility.isEmpty()) {
                    findings.add(new Finding("error", "LUMO_UTILITY_WITHOUT_LUMO_THEME",
                            "LumoUtility is used while the Aura theme is loaded. LumoUtility emits Lumo-specific "
                                    + "CSS utility classes that Aura does not define.",
                            "high", lumoUtility));
                }
            } else if (themesLoaded.isEmpty() && (!lumoUtility.isEmpty() || !tokenPrefixesUsed.isEmpty())) {
                // Theme-dependent usage exists but no base theme is explicitly loaded,
                // so whether Aura, Lumo, or a custom theme is active is unknown. Say so
                // as info, so it is clear the checks were skipped, not passed.
                List<Evidence> ev = new ArrayList<>(lumoUtility);
                ev.addAll(tokens.get("aura"));
                ev.addAll(tokens.get("lumo"));
                findings.add(new Finding("info", "THEME_INDETERMINATE",
                        "No base theme is explicitly loaded via @StyleSheet or a theme @import, so the active theme "
                                + "could not be determined (it may be a custom theme). Theme-dependent checks were "
                                + "skipped.",
                        "low", ev));
            }

            // Informational: a legacy @Theme alongside @StyleSheet-based theming.
            if (!legacyTheme.isEmpty() && !themesLoaded.isEmpty()) {
                findings.add(new Finding("info", "LEGACY_THEME_ANNOTATION",
                        "A legacy @Theme annotation is present alongside @StyleSheet-based theming. Confirm this is "
                                + "intentional when upgrading from Vaadin 24.",
                        "low", legacyTheme));
            }

            return new Report(null, !hasErrors(findings), themesLoaded, tokenPrefixesUsed, files.size(), findings);
        }
    }

    // ======================================================== check-aura-usage

    /**
     * Validates how a project uses the Aura theme's custom properties, in its
     * stylesheets and in the inline styles its Java sources set. Doc search can
     * tell an agent the rule; this tells it that the stylesheet, or the Flow view,
     * it just wrote breaks the rule.
     */
    static final class AuraUsage {
        private AuraUsage() {
        }

        static final String NAME = "check-aura-usage";
        static final String SUMMARY =
                "Validate Aura theme usage in a Vaadin project's stylesheets and Java inline styles.";
        static final String USAGE = """
                vaadin-agent-tools check-aura-usage [projectDir]

                Scans a Vaadin project's CSS, and the inline styles its Java sources set through
                Style.set / Style.bind, for misuse of the Aura theme's custom properties \u2014
                assignments that are silently discarded, or that break the light/dark color
                schemes.

                Arguments:
                  projectDir   Path to the Vaadin project root (default: current directory)

                Checks:
                  AURA_READONLY_PROPERTY_ASSIGNED             (error)   a computed Aura property is assigned
                  AURA_SURFACE_PROPERTY_WITHOUT_SURFACE_CLASS (warning) surface property on a non-surface selector
                  AURA_ACCENT_SURFACE_WITHOUT_ACCENT_CLASS    (warning) accent color on a non-accent selector
                  AURA_UNITLESS_LENGTH                        (error)   a length property given a unitless number
                  AURA_UNKNOWN_PROPERTY                       (warning) var() reads an --aura-* property nothing defines

                The two selector checks and AURA_UNKNOWN_PROPERTY are CSS-only: the first two
                need the class names on the element, which a Java Style.set call does not carry,
                and the last one fires on reads, which Style.set is not.

                Exit codes:
                  0  no error-level findings
                  1  error-level findings
                  2  usage error (e.g. projectDir does not exist)""";

        // The Java sources are read by two passes that look at the same string
        // literals for opposite purposes, so keep them apart when changing either:
        //
        //   - JAVA_CUSTOM_PROPERTY harvests NAMES the project itself uses, to
        //     suppress AURA_UNKNOWN_PROPERTY for a project's own tokens. It is
        //     deliberately broad: any --aura-* literal anywhere counts as "the
        //     project knows this name".
        //   - JAVA_STYLE_SET recognizes ASSIGNMENTS, which are then checked exactly
        //     like a CSS declaration. It is deliberately narrow: only a
        //     Style.set/bind call.
        //
        // The two never contradict each other: a read-only property is a known
        // property regardless, so harvesting its name suppresses nothing.

        /**
         * An --aura-* custom property named in a Java string literal, e.g.
         * getStyle().set("--aura-card-padding", "1rem"). Such a property is defined
         * by the project even though no stylesheet declares it, so it must not be
         * reported as unknown.
         */
        static final Pattern JAVA_CUSTOM_PROPERTY = Pattern.compile("\"(--aura-[\\w-]+)\"");

        /**
         * A custom property assigned from Java through com.vaadin.flow.dom.Style:
         * getStyle().set("--aura-app-layout-inset", "0"), or
         * getElement().getStyle().set(...). Style.set(String, String) is the only
         * Style member that can assign a custom property; the rest are typed
         * setters for standard properties. Style.bind(String, Signal) is matched
         * too: its value is not readable here, but the property name is.
         *
         * Whitespace is allowed after the dot: Java permits it, and it is also what
         * a blanked-out comment between the dot and the method name leaves behind.
         *
         * The value group is optional, and matches only a lone literal with no
         * escape sequences in it that closes the call. Group 2 therefore either is
         * the whole value verbatim, or did not participate at all, which is how a
         * non-literal value (a variable, a signal, a "0" + unit concatenation) is
         * told apart from a literal one, so the value-dependent check can stay
         * quiet rather than guess.
         *
         * Deliberately not matched:
         *
         *   - Style.remove("--aura-..."), which is not an assignment.
         *   - anything inside a text block, which is a snippet being quoted rather
         *     than code the browser will run (see the blanking pass at the call
         *     site).
         *   - setAttribute("style", "--aura-x: 0"), a raw style string: the first
         *     argument is "style", so recognizing it would mean CSS-parsing the
         *     second one for a spelling Flow code rarely uses. Its mistakes go
         *     unreported.
         *
         * Matched by accident: any non-Style API of the same shape, e.g.
         * config.set("--aura-...", "..."). Requiring the -- prefix on the first
         * argument keeps that rare, and such a line is worth a look either way.
         */
        static final Pattern JAVA_STYLE_SET = Pattern.compile(
                "\\.\\s*(?:set|bind)\\s*\\(\\s*\"(--[\\w-]+)\"\\s*,\\s*(?:\"([^\"\\\\]*)\"\\s*\\))?");

        /** A bare number with no CSS unit. */
        static final Pattern UNITLESS_NUMBER = Pattern.compile("[+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)");

        /**
         * A trailing !important. CSS lets it be written in any case and with
         * whitespace after the bang, so "0 ! IMPORTANT" must strip down to "0".
         */
        static final Pattern IMPORTANT = Pattern.compile("(?i)\\s*!\\s*important\\s*\\z");

        /**
         * One custom-property declaration with where it came from, so a finding can
         * point at it: a CSS declaration, or a Java Style.set/bind call (fromJava).
         * Not every check applies to both. selectors is empty for a Java call,
         * since the element's class names live in other statements. valueKnown is
         * false when the value could not be read: a Java call passing a variable or
         * a signal rather than a string literal. The name is still checkable.
         */
        record Decl(String property, String value, List<String> selectors, String file, int line, String snippet,
                boolean fromJava, boolean valueKnown) {
            Evidence evidence() {
                return new Evidence(file, line, snippet);
            }
        }

        record Report(String usageError, boolean ok, List<String> themesLoaded, int filesScanned,
                List<Finding> findings) {
            Map<String, Object> toJson() {
                return obj("themesLoaded", themesLoaded, "filesScanned", filesScanned,
                        "findings", findingsJson(findings));
            }
        }

        static Result run(Args args) {
            Report r = analyze(args);
            if (r.usageError() != null) {
                return Result.usageError(r.usageError());
            }
            return new Result(r.ok(), null, r.toJson(), renderScan(NAME, r.themesLoaded(), r.filesScanned(),
                    r.findings()));
        }

        static Report analyze(Args args) {
            Path target = projectDir(args);
            if (!Files.isDirectory(target)) {
                return new Report("Project directory not found: " + target, false, List.of(), 0, List.of());
            }

            List<Path> files = walk(target, Set.of(".java", ".css"));

            List<Decl> decls = new ArrayList<>();
            List<Decl> reads = new ArrayList<>(); // property is the var() name
            Set<String> loaded = new LinkedHashSet<>();
            // Custom properties the project itself defines, which are therefore
            // known names even when Aura does not define them.
            Set<String> projectDefined = new LinkedHashSet<>();

            for (Path file : files) {
                String content;
                try {
                    content = readText(file);
                } catch (IOException e) {
                    continue;
                }
                String rel = target.relativize(file).toString();

                switch (ext(file.toString()).toLowerCase(Locale.ROOT)) {
                    case ".java" -> {
                        // Scan with comments blanked, so a commented-out @StyleSheet
                        // does not look like a loaded theme.
                        String code = Css.blankJavaComments(content);
                        Matcher m = ThemeMixing.JAVA_STYLESHEET.matcher(code);
                        while (m.find()) {
                            loaded.add(m.group(1).toLowerCase(Locale.ROOT));
                        }
                        m = JAVA_CUSTOM_PROPERTY.matcher(code);
                        while (m.find()) {
                            projectDefined.add(m.group(1));
                        }
                        // Assignments are read from a second view with text blocks
                        // blanked too: a snippet quoted in a text block is
                        // documentation, and reporting it would flag a warning about
                        // wrong code as wrong code. The harvesting above deliberately
                        // still sees those names: a token named in a CSS string the
                        // project injects is one the project knows.
                        String stmts = Css.blankJavaTextBlocks(code);
                        m = JAVA_STYLE_SET.matcher(stmts);
                        while (m.find()) {
                            boolean valueKnown = m.start(2) >= 0; // the value group did not participate otherwise
                            decls.add(new Decl(m.group(1), valueKnown ? m.group(2) : "", List.of(), rel,
                                    lineOf(content, m.start(1)), snippetAt(content, m.start(1)), true, valueKnown));
                        }
                    }
                    case ".css" -> {
                        String css = Css.blankComments(content);
                        if (ThemeMixing.CSS_IMPORT_AURA.matcher(css).find()) {
                            loaded.add("aura");
                        }
                        if (ThemeMixing.CSS_IMPORT_LUMO.matcher(css).find()) {
                            loaded.add("lumo");
                        }
                        Css.Parsed parsed = Css.parse(css);
                        for (Css.Declaration d : parsed.declarations()) {
                            projectDefined.add(d.property());
                            decls.add(new Decl(d.property(), d.value(), d.selectors(), rel,
                                    lineOf(content, d.offset()), snippetAt(content, d.offset()), false, true));
                        }
                        for (Css.VarRead v : parsed.reads()) {
                            reads.add(new Decl(v.name(), "", List.of(), rel, lineOf(content, v.offset()),
                                    snippetAt(content, v.offset()), false, false));
                        }
                    }
                    default -> {
                    }
                }
            }

            List<String> themesLoaded = new ArrayList<>();
            for (String theme : List.of("aura", "lumo")) {
                if (loaded.contains(theme)) {
                    themesLoaded.add(theme);
                }
            }
            boolean auraIsActive = themesLoaded.equals(List.of("aura"));

            List<Finding> findings = new ArrayList<>();
            findings.addAll(readOnlyFindings(decls, auraIsActive));
            findings.addAll(surfaceFindings(decls));
            findings.addAll(accentFindings(decls));
            findings.addAll(unitlessFindings(decls));
            findings.addAll(unknownFindings(reads, projectDefined));

            return new Report(null, !hasErrors(findings), themesLoaded, files.size(), findings);
        }

        // --- check 1: a computed (read-only) property is assigned ------------

        static List<Finding> readOnlyFindings(List<Decl> decls, boolean auraIsActive) {
            // One finding per offending property, so each message can name the
            // customizable property to set in its place.
            Map<String, List<Evidence>> byProperty = new TreeMap<>();
            Map<String, AuraData.ReadOnly> spec = new HashMap<>();
            Map<String, String> lead = new HashMap<>();

            for (Decl d : decls) {
                AuraData.ReadOnly ro = AuraData.READ_ONLY.get(d.property());
                String what = "is a read-only Aura property";
                if (ro == null) {
                    // The --vaadin-* base-style properties are only read-only because
                    // Aura reassigns them, so they are only checked while Aura is the
                    // loaded theme.
                    if (!auraIsActive) {
                        continue;
                    }
                    ro = AuraData.REASSIGNED_VAADIN.get(d.property());
                    if (ro == null) {
                        continue;
                    }
                    what = "is a base-style property that Aura reassigns with a computed value, "
                            + "so it is read-only while Aura is the loaded theme";
                }
                spec.put(d.property(), ro);
                lead.put(d.property(), what);
                byProperty.computeIfAbsent(d.property(), k -> new ArrayList<>()).add(d.evidence());
            }

            List<Finding> out = new ArrayList<>();
            byProperty.forEach((prop, evidence) -> {
                AuraData.ReadOnly ro = spec.get(prop);
                out.add(new Finding(ro.level(), "AURA_READONLY_PROPERTY_ASSIGNED",
                        prop + " " + lead.get(prop) + ". " + ro.reason() + " Set " + ro.instead() + " instead.",
                        "high", evidence));
            });
            return out;
        }

        // --- check 2: a surface property on a selector that is not a surface ---

        static List<Finding> surfaceFindings(List<Decl> decls) {
            List<Evidence> evidence = new ArrayList<>();
            for (Decl d : decls) {
                if (d.fromJava()) {
                    // Both selector checks ask which class names the element carries,
                    // and Java puts that in a different statement from the assignment:
                    //
                    //   box.addClassNames("aura-surface", "recessed-box");
                    //   box.getStyle().set("--aura-surface-level", "-1");
                    //
                    // Answering it means following a variable through a method body.
                    // Until something does that, staying quiet beats warning about
                    // correct code.
                    continue;
                }
                if (!AuraData.SURFACE_PROPERTIES.contains(d.property())) {
                    continue;
                }
                if (Css.selectorChainMatches(d.selectors(), AuraData.SURFACE_RESPONDING_SELECTORS)) {
                    continue;
                }
                evidence.add(d.evidence());
            }
            if (evidence.isEmpty()) {
                return List.of();
            }
            return List.of(new Finding("warning", "AURA_SURFACE_PROPERTY_WITHOUT_SURFACE_CLASS",
                    "--aura-surface-level / --aura-surface-opacity only take effect on an element that uses the "
                            + "Aura surface color. Add the aura-surface (or aura-surface-solid) class name to the "
                            + "element, or set the property on one of the built-in components that use the surface "
                            + "color \u2014 otherwise the declaration does nothing.",
                    "medium", evidence));
        }

        // --- check 3: an accent color on a selector that does not recompute it --

        static List<Finding> accentFindings(List<Decl> decls) {
            List<Evidence> evidence = new ArrayList<>();
            for (Decl d : decls) {
                if (d.fromJava()) {
                    continue; // no selector to judge, as in check 2
                }
                if (!AuraData.ACCENT_INPUT_PROPERTIES.contains(d.property())) {
                    continue;
                }
                if (Css.selectorChainMatches(d.selectors(), AuraData.ACCENT_SELECTORS)) {
                    continue;
                }
                evidence.add(d.evidence());
            }
            if (evidence.isEmpty()) {
                return List.of();
            }
            return List.of(new Finding("warning", "AURA_ACCENT_SURFACE_WITHOUT_ACCENT_CLASS",
                    "Aura only recomputes the accent-derived colors (--aura-accent-surface, "
                            + "--aura-accent-text-color, --aura-accent-contrast-color, \u2026) on elements that carry "
                            + "an accent class name. Add one of " + AuraData.ACCENT_CLASS_NAMES + " to the element "
                            + "\u2014 otherwise changing --aura-accent-color-light / --aura-accent-color-dark here "
                            + "does nothing.",
                    "medium", evidence));
        }

        // --- check 4: a length property given a unitless number ----------------

        static List<Finding> unitlessFindings(List<Decl> decls) {
            Map<String, List<Evidence>> byProperty = new TreeMap<>();
            for (Decl d : decls) {
                if (!AuraData.LENGTH_PROPERTIES.contains(d.property())) {
                    continue;
                }
                if (!d.valueKnown()) {
                    continue; // a Java call passing a variable or a signal: nothing to inspect
                }
                String value = IMPORTANT.matcher(d.value()).replaceAll("").strip();
                if (!UNITLESS_NUMBER.matcher(value).matches()) {
                    continue;
                }
                byProperty.computeIfAbsent(d.property(), k -> new ArrayList<>()).add(d.evidence());
            }
            List<Finding> out = new ArrayList<>();
            byProperty.forEach((prop, evidence) -> out.add(new Finding("error", "AURA_UNITLESS_LENGTH",
                    prop + " is a CSS length that Aura feeds into calc()/clamp(), so it needs a unit even when the "
                            + "value is zero \u2014 write 0px, not 0.",
                    "high", evidence)));
            return out;
        }

        // --- check 5: var() reads an --aura-* property nothing defines ---------

        static List<Finding> unknownFindings(List<Decl> reads, Set<String> projectDefined) {
            Map<String, List<Evidence>> byProperty = new TreeMap<>();
            for (Decl r : reads) {
                String name = r.property();
                if (!name.startsWith("--aura-")) {
                    continue;
                }
                if (AuraData.KNOWN_PROPERTIES.contains(name) || projectDefined.contains(name)) {
                    continue;
                }
                byProperty.computeIfAbsent(name, k -> new ArrayList<>()).add(r.evidence());
            }
            List<Finding> out = new ArrayList<>();
            byProperty.forEach((prop, evidence) -> out.add(new Finding("warning", "AURA_UNKNOWN_PROPERTY",
                    prop + " is read here but the Aura theme does not define it and nothing in this project does "
                            + "either, so it resolves to nothing. Check the spelling against the Aura style property "
                            + "reference, or define the property.",
                    "high", evidence)));
            return out;
        }
    }

    // ================================================================ Aura data

    /**
     * Aura theme reference data for check-aura-usage.
     *
     * The property names are measured from @vaadin/aura (see
     * scripts/derive-aura-properties.sh, which re-derives them and diffs them
     * against KNOWN_PROPERTIES, so drift shows up per release).
     *
     * The write-safety classification is NOT derivable from the CSS: several
     * properties are computed from other properties and are still the documented,
     * intended override points. The split below follows the badges in the Vaadin
     * Aura reference pages: a property carrying `Read-only` or `light-dark()` is
     * read-only, everything else is customizable.
     *
     * Measured from @vaadin/aura@25.3.0-rc1: 74 defined properties + 3 that Aura
     * only reads through a fallback (customization hooks) = 77 known names.
     */
    static final class AuraData {
        private AuraData() {
        }

        /**
         * Every --aura-* custom property the theme defines or reads. A var() read of
         * a name outside this set, and not defined anywhere in the project, is a
         * typo or a hallucinated token.
         */
        static final Set<String> KNOWN_PROPERTIES = Set.of(
                "--aura-accent-border-color",
                "--aura-accent-color",
                "--aura-accent-color-dark",
                "--aura-accent-color-dark-initial",
                "--aura-accent-color-light",
                "--aura-accent-color-light-initial",
                "--aura-accent-contrast-color",
                "--aura-accent-contrast-color-dark",
                "--aura-accent-contrast-color-light",
                "--aura-accent-surface",
                "--aura-accent-text-color",
                "--aura-accent-text-color-dark",
                "--aura-accent-text-color-light",
                "--aura-app-background",
                "--aura-app-layout-border-width",
                "--aura-app-layout-inset",
                "--aura-app-layout-radius",
                "--aura-background-color",
                "--aura-background-color-dark",
                "--aura-background-color-light",
                "--aura-base-font-size",
                "--aura-base-line-height",
                "--aura-base-radius",
                "--aura-base-size",
                "--aura-blue",
                "--aura-blue-text",
                "--aura-color-scheme",
                "--aura-content-color-scheme",
                "--aura-contrast-level",
                "--aura-font-family",
                "--aura-font-family-instrument-sans",
                "--aura-font-family-system",
                "--aura-font-size-l",
                "--aura-font-size-m",
                "--aura-font-size-s",
                "--aura-font-size-xl",
                "--aura-font-size-xs",
                "--aura-font-smoothing",
                "--aura-font-weight-medium",
                "--aura-font-weight-regular",
                "--aura-font-weight-semibold",
                "--aura-green",
                "--aura-green-text",
                "--aura-item-overlay-padding-block",
                "--aura-item-overlay-padding-inline",
                "--aura-line-height-l",
                "--aura-line-height-m",
                "--aura-line-height-s",
                "--aura-line-height-xl",
                "--aura-line-height-xs",
                "--aura-master-detail-layout-detail-inset",
                "--aura-neutral",
                "--aura-neutral-dark",
                "--aura-neutral-light",
                "--aura-notification-color-scheme",
                "--aura-orange",
                "--aura-orange-text",
                "--aura-overlay-backdrop-filter",
                "--aura-overlay-inner-outline-color",
                "--aura-overlay-outline-color",
                "--aura-overlay-outline-shadow",
                "--aura-overlay-shadow",
                "--aura-overlay-surface-opacity",
                "--aura-purple",
                "--aura-purple-text",
                "--aura-red",
                "--aura-red-text",
                "--aura-shadow-color",
                "--aura-shadow-m",
                "--aura-shadow-s",
                "--aura-shadow-xs",
                "--aura-surface-color",
                "--aura-surface-color-solid",
                "--aura-surface-level",
                "--aura-surface-opacity",
                "--aura-yellow",
                "--aura-yellow-text");

        /**
         * A property Aura computes, which must not be assigned: reason explains what
         * the assignment breaks, instead names the customizable property to set in
         * its place, and level is the finding level.
         */
        record ReadOnly(String level, String reason, String instead) {
        }

        /**
         * Shared by every property Aura defines with the CSS light-dark() function:
         * assigning one value replaces the whole light-dark() pair, so the property
         * stops adapting to the active color scheme.
         */
        static final String LIGHT_DARK = "Aura computes it with light-dark(), so assigning a single value "
                + "discards both the light and the dark color-scheme value.";
        static final String FONT_SIZE = "Aura computes the font size scale from --aura-base-font-size.";
        static final String LINE_HEIGHT =
                "Aura computes the line height scale from --aura-base-line-height and the font sizes.";
        static final String FONT_STACK =
                "It is one of Aura's two font stacks, meant to be assigned to --aura-font-family rather than redefined.";

        /**
         * The 27 --aura-* properties documented as read-only. They are never safe to
         * assign; the customizable input is named in instead.
         */
        static final Map<String, ReadOnly> READ_ONLY = Map.ofEntries(
                error("--aura-accent-border-color", LIGHT_DARK,
                        "--aura-contrast-level, or the accent color via --aura-accent-color-light / "
                                + "--aura-accent-color-dark"),
                error("--aura-accent-color", LIGHT_DARK, "--aura-accent-color-light / --aura-accent-color-dark"),
                error("--aura-accent-contrast-color", LIGHT_DARK,
                        "--aura-accent-contrast-color-light / --aura-accent-contrast-color-dark"),
                error("--aura-accent-surface",
                        "Aura computes it by blending the effective accent color into the surface color.",
                        "the accent color (--aura-accent-color-light / --aura-accent-color-dark, or an "
                                + "aura-accent-* class name) together with --aura-surface-level / "
                                + "--aura-surface-opacity"),
                error("--aura-accent-text-color", LIGHT_DARK,
                        "--aura-accent-text-color-light / --aura-accent-text-color-dark"),
                error("--aura-background-color", LIGHT_DARK,
                        "--aura-background-color-light / --aura-background-color-dark"),
                error("--aura-blue-text", LIGHT_DARK, "--aura-blue"),
                error("--aura-green-text", LIGHT_DARK, "--aura-green"),
                error("--aura-orange-text", LIGHT_DARK, "--aura-orange"),
                error("--aura-purple-text", LIGHT_DARK, "--aura-purple"),
                error("--aura-red-text", LIGHT_DARK, "--aura-red"),
                error("--aura-yellow-text", LIGHT_DARK, "--aura-yellow"),
                error("--aura-neutral", LIGHT_DARK, "--aura-neutral-light / --aura-neutral-dark"),

                error("--aura-font-size-xs", FONT_SIZE, "--aura-base-font-size"),
                error("--aura-font-size-s", FONT_SIZE, "--aura-base-font-size"),
                error("--aura-font-size-m", FONT_SIZE, "--aura-base-font-size"),
                error("--aura-font-size-l", FONT_SIZE, "--aura-base-font-size"),
                error("--aura-font-size-xl", FONT_SIZE, "--aura-base-font-size"),

                error("--aura-line-height-xs", LINE_HEIGHT, "--aura-base-line-height"),
                error("--aura-line-height-s", LINE_HEIGHT, "--aura-base-line-height"),
                error("--aura-line-height-m", LINE_HEIGHT, "--aura-base-line-height"),
                error("--aura-line-height-l", LINE_HEIGHT, "--aura-base-line-height"),
                error("--aura-line-height-xl", LINE_HEIGHT, "--aura-base-line-height"),

                error("--aura-surface-color",
                        "Aura computes it from --aura-background-color, --aura-surface-level and "
                                + "--aura-surface-opacity.",
                        "--aura-surface-level / --aura-surface-opacity"),
                error("--aura-surface-color-solid",
                        "Aura computes it from --aura-background-color and --aura-surface-level.",
                        "--aura-surface-level"),

                // Milder case: redefining a font stack is wrong usage rather than a
                // color-scheme bug, so these are warnings.
                Map.entry("--aura-font-family-system", new ReadOnly("warning", FONT_STACK, "--aura-font-family")),
                Map.entry("--aura-font-family-instrument-sans",
                        new ReadOnly("warning", FONT_STACK, "--aura-font-family")));

        /**
         * The 7 base-style --vaadin-* properties Aura reassigns with its own
         * computed, color-scheme-adapted values. They are only read-only while Aura
         * is the loaded theme, so these are gated on that.
         */
        static final Map<String, ReadOnly> REASSIGNED_VAADIN = Map.ofEntries(
                error("--vaadin-background-container", LIGHT_DARK,
                        "--aura-background-color-light / --aura-background-color-dark, or --aura-contrast-level"),
                error("--vaadin-background-container-strong", LIGHT_DARK,
                        "--aura-background-color-light / --aura-background-color-dark, or --aura-contrast-level"),
                error("--vaadin-border-color", LIGHT_DARK,
                        "--aura-contrast-level, or --aura-background-color-light / --aura-background-color-dark"),
                error("--vaadin-border-color-secondary", LIGHT_DARK,
                        "--aura-contrast-level, or --aura-background-color-light / --aura-background-color-dark"),
                error("--vaadin-text-color", LIGHT_DARK, "--aura-neutral-light / --aura-neutral-dark"),
                error("--vaadin-text-color-secondary", LIGHT_DARK + " It is derived from the main text color.",
                        "--aura-contrast-level, or --aura-neutral-light / --aura-neutral-dark"),
                error("--vaadin-text-color-disabled", LIGHT_DARK + " It is derived from the main text color.",
                        "--aura-contrast-level, or --aura-neutral-light / --aura-neutral-dark"));

        private static Map.Entry<String, ReadOnly> error(String property, String reason, String instead) {
            return Map.entry(property, new ReadOnly("error", reason, instead));
        }

        /**
         * The Aura properties whose value is a CSS length that feeds a
         * calc()/clamp()/min()/max() expression. A unitless number (most often a
         * bare 0) makes the whole expression invalid, so the declaration is dropped
         * silently.
         */
        static final Set<String> LENGTH_PROPERTIES = Set.of("--aura-app-layout-inset", "--aura-app-layout-radius",
                "--aura-app-layout-border-width", "--aura-item-overlay-padding-inline",
                "--aura-item-overlay-padding-block");

        /** The two properties that only take effect where the Aura surface color is used. */
        static final List<String> SURFACE_PROPERTIES = List.of("--aura-surface-level", "--aura-surface-opacity");

        /**
         * The accent color inputs that only take effect on an element the accent
         * color is (re)computed on, i.e. one carrying an aura-accent-* class name.
         * (--aura-accent-color itself is read-only and is reported by the read-only
         * check instead.)
         */
        static final List<String> ACCENT_INPUT_PROPERTIES =
                List.of("--aura-accent-color-light", "--aura-accent-color-dark");

        /**
         * The selectors Aura defines the surface color on, and therefore the ones
         * that respond to --aura-surface-level / --aura-surface-opacity. Derived from
         * the selector list in @vaadin/aura/src/surface.css, plus the table in the
         * Aura color reference page.
         */
        static final List<String> SURFACE_SELECTORS = List.of(
                ":root", ":host", "html", "body",
                ".aura-surface", ".aura-surface-solid",
                "::part(input-field)", "::part(overlay)",
                "vaadin-accordion-panel", "vaadin-app-layout", "vaadin-avatar-group", "vaadin-button",
                "vaadin-card", "vaadin-checkbox", "vaadin-combo-box", "vaadin-confirm-dialog",
                "vaadin-context-menu", "vaadin-crud", "vaadin-dashboard-widget", "vaadin-date-picker",
                "vaadin-details", "vaadin-dialog", "vaadin-drawer-toggle", "vaadin-grid", "vaadin-grid-pro",
                "vaadin-login-overlay", "vaadin-master-detail-layout", "vaadin-menu-bar-button",
                "vaadin-menu-bar-submenu", "vaadin-message", "vaadin-message-input",
                "vaadin-multi-select-combo-box", "vaadin-notification-card", "vaadin-popover",
                "vaadin-radio-button", "vaadin-range-slider", "vaadin-rich-text-editor",
                "vaadin-rich-text-editor-popup", "vaadin-select", "vaadin-side-nav-item", "vaadin-slider",
                "vaadin-slider-bubble", "vaadin-tab", "vaadin-tabs", "vaadin-time-picker", "vaadin-tooltip",
                "vaadin-upload", "vaadin-upload-button", "vaadin-upload-file");

        /**
         * The selectors Aura recomputes the accent color and its derived colors
         * (--aura-accent-surface, --aura-accent-text-color, ...) on. Derived from the
         * selector list in @vaadin/aura/src/color.css.
         */
        static final List<String> ACCENT_SELECTORS = List.of(
                ":root", ":host", "html", "body",
                ".aura-accent-color", ".aura-accent-neutral", ".aura-accent-red", ".aura-accent-orange",
                ".aura-accent-yellow", ".aura-accent-green", ".aura-accent-blue", ".aura-accent-purple",
                ".aura-accent-surface", ".aura-accent-surface-solid",
                ".v-info", ".v-success", ".v-warning", ".v-error",
                "[theme~='danger']", "[theme~='error']", "[theme~='success']", "[theme~='warning']",
                "[theme~='info']",
                "vaadin-app-layout", "vaadin-avatar", "vaadin-badge", "vaadin-button", "vaadin-checkbox",
                "vaadin-confirm-dialog", "vaadin-context-menu-item", "vaadin-crud-edit", "vaadin-dialog",
                "vaadin-drawer-toggle", "vaadin-menu-bar-button", "vaadin-menu-bar-item", "vaadin-message",
                "vaadin-notification-card", "vaadin-notification-container", "vaadin-radio-button",
                "vaadin-select-item", "vaadin-side-nav-item", "vaadin-tab", "vaadin-upload-button",
                "vaadin-upload-file");

        /**
         * Every selector on which --aura-surface-level / --aura-surface-opacity have
         * an effect: the surface selectors plus the accent ones, since Aura computes
         * --aura-accent-surface from those same two properties, so an element it
         * recomputes the accent colors on (a vaadin-badge, an .aura-accent-surface
         * box) responds to them too.
         */
        static final List<String> SURFACE_RESPONDING_SELECTORS = concat(SURFACE_SELECTORS, ACCENT_SELECTORS);

        /**
         * The class names the docs say to apply for a different accent color on part
         * of the UI; named in the finding message.
         */
        static final String ACCENT_CLASS_NAMES = "aura-accent-neutral, aura-accent-red, aura-accent-orange, "
                + "aura-accent-yellow, aura-accent-green, aura-accent-blue, aura-accent-purple";

        private static List<String> concat(List<String> a, List<String> b) {
            List<String> all = new ArrayList<>(a);
            all.addAll(b);
            return List.copyOf(all);
        }
    }

    // ========================================================== create-project

    /**
     * Extracts a Vaadin project skeleton from start.vaadin.com into a directory:
     * the unpacking half of create-vaadin (`npm init vaadin`), unzipping with the
     * top-level directory stripped. The download itself is left to the caller
     * (the vaadin-create-project skill runs curl), so the CLI never touches the
     * network.
     */
    static final class CreateProject {
        private CreateProject() {
        }

        static final String NAME = "create-project";
        static final String SUMMARY = "Extract a Vaadin project skeleton from start.vaadin.com.";
        static final String USAGE = """
                vaadin-agent-tools create-project <targetDir> --skeleton=<file.zip> [flags]

                Extracts a Vaadin project skeleton, downloaded beforehand from
                https://start.vaadin.com/skeleton, into <targetDir>, without the archive's
                top-level directory. Mirrors `npm init vaadin` (create-vaadin). This tool
                doesn't access the network; download the skeleton first, for example:

                  curl -fsSL -o skeleton.zip "https://start.vaadin.com/skeleton?artifactId=my-app&frameworks=flow&ref=agent-plugin"

                Arguments:
                  targetDir          Directory to create the project in.

                Flags:
                  --skeleton=<file>  The downloaded skeleton ZIP (required)
                  --overwrite        If targetDir exists and is non-empty, replace its contents

                Exit codes:
                  0  project created
                  1  the skeleton could not be read or extracted
                  2  usage error (missing targetDir or --skeleton, or targetDir exists
                     without --overwrite)""";

        record Report(String usageError, boolean ok, String directory, String skeleton, int files, String error) {
            static Report usage(String message) {
                return new Report(message, false, "", "", 0, "");
            }

            Map<String, Object> toJson() {
                Map<String, Object> m = obj("directory", directory, "skeleton", skeleton, "files", files);
                if (!error.isEmpty()) {
                    m.put("error", error);
                }
                return m;
            }

            String human() {
                if (!error.isEmpty()) {
                    return "# create-project\n\n\u2717 Failed to create project: " + error + "\n  Skeleton: "
                            + skeleton;
                }
                return "# create-project\n\n\u2713 Project created in " + directory + "\n  files written: " + files
                        + "\n\nNext: cd " + directory + " && mvn";
            }
        }

        static Result run(Args args) {
            Report r = create(args);
            if (r.usageError() != null) {
                return Result.usageError(r.usageError());
            }
            return new Result(r.ok(), null, r.toJson(), r.human());
        }

        static Report create(Args args) {
            List<String> positionals = new ArrayList<>();
            Map<String, String> flags = new HashMap<>();
            splitFlags(args.positionals(), positionals, flags);
            if (positionals.isEmpty()) {
                return Report.usage("Missing target directory. Usage: create-project <targetDir> --skeleton=<file.zip>");
            }
            String skeletonArg = flags.getOrDefault("skeleton", "");
            if (skeletonArg.isEmpty() || skeletonArg.equals("true")) {
                return Report.usage(
                        "Missing --skeleton=<file.zip>. Download it from https://start.vaadin.com/skeleton first.");
            }
            Path target = resolve(args.cwd(), positionals.get(0));
            if (target == null) {
                return Report.usage("Invalid target directory: " + positionals.get(0));
            }
            Path skeleton = resolve(args.cwd(), skeletonArg);
            if (skeleton == null) {
                return Report.usage("Invalid --skeleton path: " + skeletonArg);
            }
            boolean overwrite = flags.containsKey("overwrite") && !flags.get("overwrite").equals("false");

            // The target directory precondition, as in create-vaadin's overwrite handling.
            List<Path> existing = List.of();
            if (Files.exists(target)) {
                if (!Files.isDirectory(target)) {
                    return Report.usage("Target exists and is not a directory: " + target);
                }
                existing = list(target);
                if (!existing.isEmpty() && !overwrite) {
                    return Report.usage("Target directory already exists and is not empty: " + target
                            + " (pass --overwrite to replace its contents)");
                }
            }

            String directory = target.toString();
            String skeletonName = skeleton.toString();
            // Open the archive before clearing anything, so a failed download (an
            // HTML error page, an empty file) leaves an existing target untouched.
            try (ZipFile zip = new ZipFile(skeleton.toFile())) {
                Map<String, Integer> modes = entryModes(skeleton);
                for (Path e : existing) {
                    try {
                        deleteRecursively(e);
                    } catch (IOException x) {
                        return failed(directory, skeletonName, "could not clear target directory: " + describe(x));
                    }
                }
                try {
                    Files.createDirectories(target);
                } catch (IOException x) {
                    return failed(directory, skeletonName, "could not create target directory: " + describe(x));
                }
                int count;
                try {
                    count = extract(zip, modes, target);
                } catch (IOException x) {
                    return failed(directory, skeletonName, "extraction failed: " + describe(x));
                }
                return new Report(null, true, directory, skeletonName, count, "");
            } catch (IOException e) {
                return failed(directory, skeletonName,
                        "could not read the skeleton (check that the download succeeded): " + describe(e));
            }
        }

        private static Report failed(String directory, String skeleton, String error) {
            return new Report(null, false, directory, skeleton, 0, error);
        }

        /**
         * Writes the archive into target with the top-level directory stripped, and
         * returns the number of files written. It rejects entries that would escape
         * target (zip slip), and keeps empty files (so an empty theme styles.css
         * survives) and permissions (so mvnw stays executable).
         */
        static int extract(ZipFile zip, Map<String, Integer> modes, Path target) throws IOException {
            Path root = target.toAbsolutePath().normalize();
            int count = 0;
            for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements();) {
                ZipEntry entry = en.nextElement();
                String rel = stripFirst(entry.getName());
                if (rel.isEmpty()) {
                    continue;
                }
                Path dest;
                try {
                    dest = root.resolve(rel).normalize();
                } catch (InvalidPathException e) {
                    throw new IOException("invalid path in archive: " + entry.getName());
                }
                if (!dest.startsWith(root)) {
                    throw new IOException("unsafe path in archive: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                    continue;
                }
                Files.createDirectories(dest.getParent());
                try (InputStream in = zip.getInputStream(entry); OutputStream out = Files.newOutputStream(dest)) {
                    in.transferTo(out);
                }
                applyMode(dest, modes.getOrDefault(entry.getName(), 0644));
                count++;
            }
            return count;
        }

        /**
         * Removes the leading path component (the archive's top-level directory),
         * like create-vaadin's decompress strip: 1. Returns "" when nothing remains,
         * as for the top-level directory entry itself.
         */
        static String stripFirst(String name) {
            if (name.startsWith("./")) {
                name = name.substring(2);
            }
            int i = name.indexOf('/');
            return i < 0 ? "" : name.substring(i + 1);
        }

        /**
         * The permission bits of each entry, by name. java.util.zip doesn't expose
         * them, so they are read from the archive's central directory: the host
         * system the entry was made on, and its external attributes. Best effort:
         * an archive this can't read (ZIP64, say) gets the default 0644.
         */
        static Map<String, Integer> entryModes(Path zip) {
            Map<String, Integer> modes = new HashMap<>();
            try {
                byte[] b = Files.readAllBytes(zip);
                int eocd = -1;
                for (int i = b.length - 22; i >= 0 && i >= b.length - 22 - 0xFFFF; i--) {
                    if (u32(b, i) == 0x06054b50L) {
                        eocd = i;
                        break;
                    }
                }
                if (eocd < 0) {
                    return modes;
                }
                int total = u16(b, eocd + 10);
                long offset = u32(b, eocd + 16);
                if (offset >= b.length) {
                    return modes;
                }
                int p = (int) offset;
                for (int n = 0; n < total && p + 46 <= b.length && u32(b, p) == 0x02014b50L; n++) {
                    int madeBy = u16(b, p + 4);
                    int nameLength = u16(b, p + 28);
                    int extraLength = u16(b, p + 30);
                    int commentLength = u16(b, p + 32);
                    long external = u32(b, p + 38);
                    if (p + 46 + nameLength > b.length) {
                        break;
                    }
                    String name = new String(b, p + 46, nameLength, StandardCharsets.UTF_8);
                    modes.put(name, permissions(madeBy >>> 8, external));
                    p += 46 + nameLength + extraLength + commentLength;
                }
            } catch (IOException | RuntimeException e) {
                // Permissions are a nicety; extraction goes ahead with the defaults.
            }
            return modes;
        }

        /** An entry's permission bits, read the way Go's archive/zip reads them. */
        static int permissions(int host, long external) {
            int perm = switch (host) {
                case 3, 19 -> (int) (external >>> 16) & 0777; // Unix, macOS
                case 0, 11, 14 -> { // MS-DOS, NTFS, VFAT: a directory bit and a read-only bit
                    int m = (external & 0x10) != 0 ? 0777 : 0666;
                    yield (external & 0x01) != 0 ? m & ~0222 : m;
                }
                default -> 0;
            };
            return perm == 0 ? 0644 : perm;
        }

        private static int u16(byte[] b, int i) {
            return (b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8;
        }

        private static long u32(byte[] b, int i) {
            return (u16(b, i) | (long) u16(b, i + 2) << 16) & 0xFFFFFFFFL;
        }

        /**
         * Gives a newly written file the archive's permission bits, minus what the
         * umask takes away, as creating it with those bits would have. Java creates
         * files with 0666 minus the umask, which shows the umask's read and write
         * bits; its execute bits are taken to follow its read bits, which holds for
         * the usual 022, 027 and 077. A file system without POSIX permissions
         * (Windows) keeps its defaults.
         */
        static void applyMode(Path file, int mode) throws IOException {
            PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);
            if (view == null) {
                return;
            }
            int created = bits(view.readAttributes().permissions());
            int allowed = created | (created & 0444) >> 2;
            int wanted = mode & allowed;
            if (wanted != created) {
                view.setPermissions(permissionSet(wanted));
            }
        }

        static int bits(Set<PosixFilePermission> permissions) {
            int m = 0;
            for (PosixFilePermission p : permissions) {
                m |= 0400 >> p.ordinal(); // OWNER_READ, OWNER_WRITE, ..., OTHERS_EXECUTE
            }
            return m;
        }

        static Set<PosixFilePermission> permissionSet(int mode) {
            Set<PosixFilePermission> set = EnumSet.noneOf(PosixFilePermission.class);
            for (PosixFilePermission p : PosixFilePermission.values()) {
                if ((mode & 0400 >> p.ordinal()) != 0) {
                    set.add(p);
                }
            }
            return set;
        }

        /** Separates --key=value and --flag tokens from positionals; a bare flag maps to "true". */
        static void splitFlags(List<String> args, List<String> positionals, Map<String, String> flags) {
            for (String a : args) {
                if (a.startsWith("--")) {
                    String kv = a.substring(2);
                    int i = kv.indexOf('=');
                    if (i >= 0) {
                        flags.put(kv.substring(0, i), kv.substring(i + 1));
                    } else {
                        flags.put(kv, "true");
                    }
                } else {
                    positionals.add(a);
                }
            }
        }

        private static List<Path> list(Path dir) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                List<Path> entries = new ArrayList<>();
                ds.forEach(entries::add);
                return entries;
            } catch (IOException | DirectoryIteratorException e) {
                return List.of();
            }
        }

        /** Deletes path and everything under it, without following symbolic links. */
        static void deleteRecursively(Path path) throws IOException {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                    if (e != null) {
                        throw e;
                    }
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        /** A one-line description of e, for the report's error field. */
        static String describe(IOException e) {
            if (e instanceof NoSuchFileException) {
                return e.getMessage() + ": no such file or directory";
            }
            if (e instanceof AccessDeniedException) {
                return e.getMessage() + ": permission denied";
            }
            if (e instanceof ZipException) {
                return "not a valid ZIP file (" + e.getMessage() + ")";
            }
            return e.getMessage() != null ? e.getMessage() : e.toString();
        }
    }

    // ==================================================================== hook

    /**
     * The `hook` command. It is kept out of the tool list because hooks read a JSON
     * event on stdin and answer in a hook-specific JSON contract on stdout, which
     * does not fit the { tool, ok, ... } envelope.
     *
     * One hook is implemented: `vaadin-agent-tools hook post-tool-use`. It reads a
     * Claude Code or Codex PostToolUse payload from stdin and, when the edit that
     * just happened actually touched Vaadin styling, runs the styling checks
     * (check-theme-mixing and check-aura-usage) against each edited file's own
     * project. Only when one of them reports an error-level finding does it print
     * a PostToolUse response asking the agent to fix it. In every other case it
     * stays completely silent. It always exits 0, so a styling edit is never
     * blocked by this hook.
     *
     * The launcher filters most events out before starting Java at all; see the
     * pre-filter in cli/vaadin-agent-tools, which must stay a superset of the
     * gates below.
     */
    static final class Hook {
        private Hook() {
        }

        /**
         * The text an edit introduced when that edit touches Vaadin styling. Kept
         * narrow on purpose, so ordinary business-logic .java edits never trigger
         * the check. The launcher's pre-filter looks for a case-insensitive
         * substring of each alternative; keep the two in step.
         */
        static final Pattern JAVA_STYLING = Pattern.compile(
                "getStyle\\("
                        + "|(?:add|remove|set|get)ClassNames?[(<]"
                        + "|@(?:[\\w.]*\\.)?CssImport"
                        + "|@(?:[\\w.]*\\.)?StyleSheet"
                        + "|@(?:[\\w.]*\\.)?Theme(?![A-Za-z0-9_])"
                        + "|(?<![A-Za-z0-9_])LumoUtility(?![A-Za-z0-9_])"
                        + "|--lumo-"
                        + "|--aura-"
                        + "|(?:add|set)ThemeNames?\\("
                        + "|getThemeList\\(");

        /** One file an edit touched, with the text the edit introduced there. */
        record FileEdit(String path, String introduced) {
        }

        /**
         * The part of the PostToolUse payload the hook reads. tool_input's shape
         * varies by tool. Claude Code's tools carry an absolute file_path: Write has
         * content, Edit has new_string, MultiEdit has edits[].new_string. Codex
         * edits through apply_patch, whose only input is command, the raw patch
         * text, with paths relative to cwd.
         */
        record Payload(String cwd, String filePath, String newString, String content, List<String> edits,
                String command) {
            static Payload from(Object json) throws Json.ParseException {
                Map<String, Object> root = Json.object(json, "the payload");
                Map<String, Object> in = Json.object(root.get("tool_input"), "tool_input");
                List<String> edits = new ArrayList<>();
                for (Object e : Json.array(in.get("edits"), "tool_input.edits")) {
                    edits.add(Json.string(Json.object(e, "an edit").get("new_string"), "new_string"));
                }
                return new Payload(Json.string(root.get("cwd"), "cwd"),
                        Json.string(in.get("file_path"), "file_path"),
                        Json.string(in.get("new_string"), "new_string"),
                        Json.string(in.get("content"), "content"),
                        edits,
                        Json.string(in.get("command"), "command"));
            }
        }

        static int run(List<String> args, InputStream stdin, PrintStream out, PrintStream err, Path cwd) {
            if (args.isEmpty() || !args.get(0).equals("post-tool-use")) {
                // Misconfiguration, not a normal edit: stay quiet rather than
                // cluttering the transcript on every later tool call.
                return 0;
            }
            try {
                postToolUse(stdin, out, cwd);
            } catch (Exception | StackOverflowError e) {
                // Never block or clutter an edit over a bug here. With exit code 0,
                // the agent does not see this; it is for whoever runs it by hand.
                err.print("vaadin-agent-tools hook: " + e + "\n");
            }
            return 0;
        }

        private static void postToolUse(InputStream stdin, PrintStream out, Path cwd) throws IOException {
            String raw = new String(stdin.readAllBytes(), StandardCharsets.UTF_8);
            Payload p;
            try {
                p = Payload.from(Json.parse(raw));
            } catch (Json.ParseException e) {
                return;
            }

            // Codex patch paths are relative to the session's directory, which the
            // payload names; it is also where Codex starts the hook, so this only
            // matters if the two ever differ.
            Path payloadCwd = absolutePath(p.cwd());
            if (payloadCwd != null) {
                cwd = payloadCwd;
            }

            // Gate 1: the file type, and for .java, gate 2: the edit touched styling.
            // Each check is scoped to the edited file's own project. One Codex patch
            // can touch files in several projects, so collect each project once.
            Set<Path> projects = new LinkedHashSet<>();
            for (FileEdit e : editsFrom(p)) {
                if (touchesStyling(e)) {
                    projects.add(projectRootFor(e.path(), cwd));
                }
            }

            // Gate 3: only speak when a check finds an error-level problem.
            List<String> sections = new ArrayList<>();
            for (Path project : projects) {
                List<String> found = checkProject(project, cwd);
                if (found.isEmpty()) {
                    continue;
                }
                if (projects.size() > 1) {
                    sections.add("Project: " + project);
                }
                sections.addAll(found);
            }
            if (sections.isEmpty()) {
                return;
            }

            String context = "Vaadin styling checks flagged issues after this change. Review the findings below "
                    + "and fix the error-level ones:\n" + String.join("\n", sections);
            println(out, Json.write(obj("hookSpecificOutput",
                    obj("hookEventName", "PostToolUse", "additionalContext", context))));
        }

        private static Path absolutePath(String path) {
            try {
                Path p = Path.of(path);
                return !path.isEmpty() && p.isAbsolute() ? p : null;
            } catch (InvalidPathException e) {
                return null;
            }
        }

        /** The files the tool call edited: one for Claude Code, any number for a Codex patch. */
        static List<FileEdit> editsFrom(Payload p) {
            if (!p.filePath().isEmpty()) {
                StringBuilder changed = new StringBuilder(p.newString()).append('\n').append(p.content());
                for (String e : p.edits()) {
                    changed.append('\n').append(e);
                }
                return List.of(new FileEdit(p.filePath(), changed.toString()));
            }
            if (!p.command().isEmpty()) {
                return parseApplyPatch(p.command());
            }
            return List.of();
        }

        /** Gates 1 and 2 for one edited file. */
        static boolean touchesStyling(FileEdit e) {
            return switch (ext(e.path()).toLowerCase(Locale.ROOT)) {
                case ".css" -> true; // any CSS edit is a styling change
                case ".java" -> JAVA_STYLING.matcher(e.introduced()).find();
                default -> false;
            };
        }

        /** Runs the styling checks on one project, returning the envelope of each that found an error. */
        static List<String> checkProject(Path project, Path cwd) {
            Args args = new Args(List.of(project.toString()), cwd);
            List<String> sections = new ArrayList<>();
            ThemeMixing.Report mixing = ThemeMixing.analyze(args);
            if (mixing.usageError() == null && !mixing.ok()) {
                sections.add(Json.write(envelope(ThemeMixing.NAME, false, mixing.toJson())));
            }
            AuraUsage.Report aura = AuraUsage.analyze(args);
            if (aura.usageError() == null && !aura.ok()) {
                sections.add(Json.write(envelope(AuraUsage.NAME, false, aura.toJson())));
            }
            return sections;
        }

        // The apply_patch envelope markers, as codex-rs/apply-patch/src/parser.rs
        // spells them.
        static final String PATCH_BEGIN = "*** Begin Patch";
        static final String PATCH_END = "*** End Patch";
        static final String PATCH_ADD = "*** Add File: ";
        static final String PATCH_DELETE = "*** Delete File: ";
        static final String PATCH_UPDATE = "*** Update File: ";
        static final String PATCH_MOVE_TO = "*** Move to: ";

        /**
         * The files a Codex apply_patch envelope adds or updates, each with the text
         * its + lines introduce. It reads the envelope the way Codex's own parser
         * does, including its leniency about whitespace around the markers and a
         * heredoc wrapper around the whole patch. Unlike that parser it never fails:
         * Codex has already applied the patch by the time this hook runs, so a line
         * it does not recognize is skipped. A deleted file introduces nothing and is
         * left out. A moved file is reported at its new path.
         */
        static List<FileEdit> parseApplyPatch(String patch) {
            final int outside = 0; // before the first hunk, or in a deleted file's hunk
            final int adding = 1;
            final int updating = 2;
            List<String> paths = new ArrayList<>();
            List<List<String>> added = new ArrayList<>();
            int mode = outside;
            boolean started = false;
            boolean expectMove = false;

            for (String line : patch.split("\n", -1)) {
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                if (!started) {
                    started = line.strip().equals(PATCH_BEGIN);
                    continue;
                }
                // Codex trims a header line on both sides, except inside an update
                // hunk, where leading whitespace marks a context line.
                String header = mode == updating ? line.stripTrailing() : line.strip();
                boolean atMove = expectMove;
                expectMove = false;

                if (header.equals(PATCH_END)) {
                    break;
                } else if (header.startsWith(PATCH_ADD)) {
                    paths.add(header.substring(PATCH_ADD.length()));
                    added.add(new ArrayList<>());
                    mode = adding;
                } else if (header.startsWith(PATCH_DELETE)) {
                    mode = outside;
                } else if (header.startsWith(PATCH_UPDATE)) {
                    paths.add(header.substring(PATCH_UPDATE.length()));
                    added.add(new ArrayList<>());
                    mode = updating;
                    expectMove = true;
                } else if (atMove && header.startsWith(PATCH_MOVE_TO)) {
                    paths.set(paths.size() - 1, header.substring(PATCH_MOVE_TO.length()));
                } else if (mode != outside && line.startsWith("+")) {
                    added.get(added.size() - 1).add(line.substring(1));
                }
            }

            List<FileEdit> edits = new ArrayList<>();
            for (int i = 0; i < paths.size(); i++) {
                edits.add(new FileEdit(paths.get(i), String.join("\n", added.get(i))));
            }
            return edits;
        }

        /**
         * The nearest ancestor of file that holds a build descriptor (pom.xml,
         * build.gradle[.kts]). It falls back to CLAUDE_PROJECT_DIR, then to cwd, so
         * a project without a recognized descriptor still gets scanned.
         */
        static Path projectRootFor(String file, Path cwd) {
            Path parent = Path.of(file).getParent();
            Path dir = (parent == null ? cwd : cwd.resolve(parent)).normalize();
            for (Path d = dir; d != null; d = d.getParent()) {
                for (String marker : List.of("pom.xml", "build.gradle", "build.gradle.kts")) {
                    if (Files.exists(d.resolve(marker))) {
                        return d;
                    }
                }
            }
            String env = System.getenv("CLAUDE_PROJECT_DIR");
            if (env != null && !env.isEmpty()) {
                return Path.of(env);
            }
            return cwd;
        }
    }
}
