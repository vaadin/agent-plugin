import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Tests for plugin/cli/VaadinAgentTools.java, in plain Java: there is no build to
 * fetch a test framework with. Every static method named test* is a test. They
 * reach into the CLI's package-private classes, so the two files are compiled
 * together; run them from the repository root with
 *
 *   sh scripts/test-cli.sh
 *
 * The fixtures are the sample projects in test/fixtures.
 */
public final class VaadinAgentToolsTest {

    static final Path ROOT = Path.of("").toAbsolutePath();
    static final Path FIXTURES = ROOT.resolve("test").resolve("fixtures");
    static final Path CLI_DIR = ROOT.resolve("plugin").resolve("cli");

    public static void main(String[] args) throws Exception {
        if (!Files.isDirectory(FIXTURES)) {
            System.err.println("Run the tests from the repository root.");
            System.exit(2);
        }
        List<Method> tests = Arrays.stream(VaadinAgentToolsTest.class.getDeclaredMethods())
                .filter(m -> Modifier.isStatic(m.getModifiers()) && m.getName().startsWith("test")
                        && m.getParameterCount() == 0)
                .sorted(Comparator.comparing(Method::getName))
                .collect(Collectors.toList());
        String only = args.length > 0 ? args[0] : "";
        int run = 0;
        int failed = 0;
        for (Method m : tests) {
            if (!m.getName().contains(only)) {
                continue;
            }
            run++;
            try {
                m.invoke(null);
                System.out.println("ok    " + m.getName());
            } catch (InvocationTargetException e) {
                failed++;
                Throwable cause = e.getCause();
                System.out.println("FAIL  " + m.getName() + ": " + cause.getMessage());
                if (!(cause instanceof AssertionError)) {
                    cause.printStackTrace(System.out);
                }
            }
        }
        System.out.println(run + " tests, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ================================================================ helpers

    static void check(boolean condition, String message, Object... args) {
        if (!condition) {
            throw new AssertionError(String.format(message, args));
        }
    }

    static void checkEquals(Object expected, Object actual, String what) {
        check(expected.equals(actual), "%s = %s, want %s", what, actual, expected);
    }

    interface TempDirBody {
        void run(Path dir) throws Exception;
    }

    static void withTempDir(TempDirBody body) throws Exception {
        Path dir = Files.createTempDirectory("vaadin-agent-tools-test");
        try {
            body.run(dir);
        } finally {
            VaadinAgentTools.CreateProject.deleteRecursively(dir);
        }
    }

    // ===================================================== check-theme-mixing

    static VaadinAgentTools.ThemeMixing.Report mixing(String fixture) {
        return VaadinAgentTools.ThemeMixing.analyze(new VaadinAgentTools.Args(List.of(fixture), FIXTURES));
    }

    static VaadinAgentTools.Finding findByCode(List<VaadinAgentTools.Finding> findings, String code) {
        return findings.stream().filter(f -> f.code().equals(code)).findFirst().orElse(null);
    }

    static void testFlagsBothAuraAndLumo() {
        var r = mixing("mixed");
        check(!r.ok(), "expected ok=false");
        checkEquals(List.of("aura", "lumo"), r.themesLoaded(), "themesLoaded");
        var f = findByCode(r.findings(), "MULTIPLE_BASE_THEMES");
        check(f != null, "expected MULTIPLE_BASE_THEMES finding");
        checkEquals("error", f.level(), "level");
    }

    static void testDetectsFullyQualifiedAnnotations() {
        var r = mixing("qualified");
        check(!r.ok(), "expected ok=false for fully qualified Aura + Lumo");
        checkEquals(List.of("aura", "lumo"), r.themesLoaded(), "themesLoaded");
        check(findByCode(r.findings(), "MULTIPLE_BASE_THEMES") != null,
                "expected MULTIPLE_BASE_THEMES finding from qualified annotations");
    }

    static void testPassesSingleTheme() {
        var r = mixing("clean");
        check(r.ok(), "expected ok=true");
        checkEquals(List.of("aura"), r.themesLoaded(), "themesLoaded");
        check(r.findings().isEmpty(), "expected no findings, got %s", r.findings());
    }

    static void testWarnsOnMismatchedTokens() {
        var r = mixing("mismatch");
        check(r.ok(), "expected ok=true (warning only)");
        var f = findByCode(r.findings(), "MISMATCHED_THEME_TOKENS");
        check(f != null, "expected MISMATCHED_THEME_TOKENS finding");
        checkEquals("warning", f.level(), "level");
    }

    static void testFlagsLumoUtilityUnderAura() {
        var r = mixing("lumo-utility");
        check(!r.ok(), "expected ok=false");
        var f = findByCode(r.findings(), "LUMO_UTILITY_WITHOUT_LUMO_THEME");
        check(f != null, "expected LUMO_UTILITY_WITHOUT_LUMO_THEME finding");
        checkEquals("error", f.level(), "level");
        checkEquals("high", f.confidence(), "confidence");
    }

    static void testDoesNotFlagLumoUtilityUnderLumo() {
        var r = mixing("lumo-utility-ok");
        check(findByCode(r.findings(), "LUMO_UTILITY_WITHOUT_LUMO_THEME") == null,
                "LumoUtility is valid under the Lumo theme; should not be flagged");
    }

    static void testNoOpWhenThemeIndeterminate() {
        var r = mixing("indeterminate");
        check(r.ok(), "expected ok=true (correctness out of scope here)");
        check(r.themesLoaded().isEmpty(), "themesLoaded = %s, want empty", r.themesLoaded());
        var f = findByCode(r.findings(), "THEME_INDETERMINATE");
        check(f != null, "expected THEME_INDETERMINATE info finding");
        checkEquals("info", f.level(), "level");
        for (var x : r.findings()) {
            check(!x.level().equals("error") && !x.level().equals("warning"),
                    "unexpected %s finding when theme is unknown: %s", x.level(), x.code());
        }
    }

    static void testUsageErrorForMissingDir() {
        check(mixing("does-not-exist").usageError() != null, "expected a usage error for a missing directory");
    }

    // ======================================================= check-aura-usage

    static VaadinAgentTools.AuraUsage.Report aura(String fixture) {
        return VaadinAgentTools.AuraUsage.analyze(new VaadinAgentTools.Args(List.of(fixture), FIXTURES));
    }

    /** Every finding with the code: the read-only and unitless checks emit one per property. */
    static List<VaadinAgentTools.Finding> allByCode(List<VaadinAgentTools.Finding> findings, String code) {
        return findings.stream().filter(f -> f.code().equals(code)).collect(Collectors.toList());
    }

    /** Whether a finding's message names the property as its subject (messages start with it). */
    static boolean propertyReported(List<VaadinAgentTools.Finding> findings, String code, String property) {
        return allByCode(findings, code).stream().anyMatch(f -> f.message().startsWith(property + " "));
    }

    static String subjectOf(VaadinAgentTools.Finding f) {
        return f.message().split(" ", 2)[0];
    }

    static void testAuraCleanProjectHasNoFindings() {
        var r = aura("aura-clean");
        check(r.ok(), "expected ok=true");
        check(r.findings().isEmpty(), "expected no findings, got %s", r.findings());
        checkEquals(List.of("aura"), r.themesLoaded(), "themesLoaded");
    }

    static void testAuraFlagsReadOnlyPropertyAssignment() {
        var r = aura("aura-readonly");
        check(!r.ok(), "expected ok=false");
        for (String prop : List.of("--aura-background-color", "--aura-accent-color", "--aura-surface-color",
                "--aura-font-size-m", "--aura-line-height-m", "--aura-red-text", "--aura-neutral")) {
            check(propertyReported(r.findings(), "AURA_READONLY_PROPERTY_ASSIGNED", prop),
                    "expected %s to be reported as read-only", prop);
        }
        // The message must name the customizable property to set instead.
        for (var f : allByCode(r.findings(), "AURA_READONLY_PROPERTY_ASSIGNED")) {
            check(f.message().contains("Set --"), "finding for %s does not name a replacement property",
                    f.message());
            check(!f.evidence().isEmpty(), "finding %s carries no evidence", f.message());
        }
    }

    static void testAuraFontFamilyStacksAreWarningsNotErrors() {
        var r = aura("aura-readonly");
        var f = allByCode(r.findings(), "AURA_READONLY_PROPERTY_ASSIGNED").stream()
                .filter(x -> x.message().startsWith("--aura-font-family-system ")).findFirst().orElse(null);
        check(f != null, "expected --aura-font-family-system to be reported");
        checkEquals("warning", f.level(), "level for a font stack");
    }

    static void testAuraFlagsVaadinPropertiesReassignedByAura() {
        var r = aura("aura-readonly");
        for (String prop : List.of("--vaadin-text-color", "--vaadin-border-color")) {
            check(propertyReported(r.findings(), "AURA_READONLY_PROPERTY_ASSIGNED", prop),
                    "expected %s to be reported while Aura is loaded", prop);
        }
    }

    /** The --vaadin-* properties are only read-only because Aura reassigns them. */
    static void testAuraSkipsVaadinPropertiesWhenAuraIsNotLoaded() {
        var r = aura("mismatch"); // loads Lumo
        check(!propertyReported(r.findings(), "AURA_READONLY_PROPERTY_ASSIGNED", "--vaadin-text-color"),
                "--vaadin-* properties must only be checked while Aura is the loaded theme");
    }

    /** The computed-but-customizable properties are the documented override points. */
    static void testAuraDoesNotFlagCustomizableComputedProperties() {
        var r = aura("aura-customizable");
        check(r.ok(), "expected ok=true, got findings: %s", r.findings());
        check(r.findings().isEmpty(), "customizable properties must not be flagged, got: %s", r.findings());
    }

    static void testAuraFlagsSurfacePropertyWithoutSurfaceClass() {
        var r = aura("aura-surface");
        check(r.ok(), "expected ok=true (warning only)");
        var found = allByCode(r.findings(), "AURA_SURFACE_PROPERTY_WITHOUT_SURFACE_CLASS");
        checkEquals(1, found.size(), "surface findings");
        var f = found.get(0);
        checkEquals("warning", f.level(), "level");
        // Only the two offending declarations; the aura-surface-solid selector, the
        // built-in vaadin-card, and the nested rule under .aura-surface are all fine.
        checkEquals(2, f.evidence().size(), "evidence entries");
        for (var e : f.evidence()) {
            check(!e.snippet().contains("--aura-surface-level: 2") && !e.snippet().contains("--aura-surface-level: 0")
                    && !e.snippet().contains("--aura-surface-opacity: 1"), "flagged a valid surface selector: %s", e);
        }
    }

    static void testAuraFlagsAccentColorWithoutAccentClass() {
        var r = aura("aura-accent");
        check(r.ok(), "expected ok=true (warning only)");
        var found = allByCode(r.findings(), "AURA_ACCENT_SURFACE_WITHOUT_ACCENT_CLASS");
        checkEquals(1, found.size(), "accent findings");
        checkEquals("warning", found.get(0).level(), "level");
        // html and .aura-accent-green.status-tile are both valid places to set it.
        checkEquals(2, found.get(0).evidence().size(), "evidence entries");
    }

    static void testAuraFlagsUnitlessLength() {
        var r = aura("aura-unitless");
        check(!r.ok(), "expected ok=false");
        // One per offending property: inset, radius, border-width, and the two
        // item-overlay paddings.
        var found = allByCode(r.findings(), "AURA_UNITLESS_LENGTH");
        checkEquals(5, found.size(), "unitless findings");
        for (var f : found) {
            checkEquals("error", f.level(), "level");
        }
        // The number-valued properties in the same rule are unitless by design.
        for (String prop : List.of("--aura-base-size", "--aura-base-radius", "--aura-contrast-level",
                "--aura-surface-level", "--aura-surface-opacity", "--aura-font-weight-regular")) {
            check(!propertyReported(r.findings(), "AURA_UNITLESS_LENGTH", prop),
                    "%s takes a unitless number by design and must not be flagged", prop);
        }
    }

    static void testAuraFlagsUnknownPropertyReads() {
        var r = aura("aura-unknown");
        check(r.ok(), "expected ok=true (warning only)");
        checkEquals(2, allByCode(r.findings(), "AURA_UNKNOWN_PROPERTY").size(), "unknown-property findings");
        for (String prop : List.of("--aura-primary-color", "--aura-contrast-5pct")) {
            check(propertyReported(r.findings(), "AURA_UNKNOWN_PROPERTY", prop),
                    "expected %s to be reported as unknown", prop);
        }
        // A property the project defines itself is a known name.
        check(!propertyReported(r.findings(), "AURA_UNKNOWN_PROPERTY", "--aura-card-padding"),
                "a project-defined --aura-* property must not be reported as unknown");
    }

    /** An unknown name that is only assigned is how a project defines its own token. */
    static void testAuraDoesNotFlagUnknownPropertyAssignments() {
        var r = aura("clean"); // assigns --aura-primary-color, never reads it
        check(allByCode(r.findings(), "AURA_UNKNOWN_PROPERTY").isEmpty(),
                "assignments must not be reported as unknown reads: %s", r.findings());
    }

    static void testAuraUsageErrorForMissingDir() {
        check(aura("does-not-exist").usageError() != null, "expected a usage error for a missing directory");
    }

    /**
     * Every read-only property must name a replacement that is itself
     * customizable, or the tool would send an agent from one read-only property
     * to another.
     */
    static void testAuraReplacementAdviceNeverNamesAReadOnlyProperty() {
        Map<String, VaadinAgentTools.AuraData.ReadOnly> all = new LinkedHashMap<>(
                VaadinAgentTools.AuraData.READ_ONLY);
        all.putAll(VaadinAgentTools.AuraData.REASSIGNED_VAADIN);
        all.forEach((name, ro) -> {
            for (String word : ro.instead().split("[^-a-z]+")) {
                if (!word.startsWith("--")) {
                    continue;
                }
                check(!VaadinAgentTools.AuraData.READ_ONLY.containsKey(word)
                        && !VaadinAgentTools.AuraData.REASSIGNED_VAADIN.containsKey(word),
                        "%s: advice names the read-only property %s", name, word);
                check(VaadinAgentTools.AuraData.KNOWN_PROPERTIES.contains(word) || word.startsWith("--vaadin-"),
                        "%s: advice names %s, which Aura does not define", name, word);
            }
        });
    }

    /** The curated tables must stay inside the measured property set. */
    static void testAuraCuratedListsAreSubsetsOfTheKnownProperties() {
        Set<String> known = VaadinAgentTools.AuraData.KNOWN_PROPERTIES;
        List<String> curated = new ArrayList<>(VaadinAgentTools.AuraData.READ_ONLY.keySet());
        curated.addAll(VaadinAgentTools.AuraData.LENGTH_PROPERTIES);
        curated.addAll(VaadinAgentTools.AuraData.SURFACE_PROPERTIES);
        curated.addAll(VaadinAgentTools.AuraData.ACCENT_INPUT_PROPERTIES);
        for (String name : curated) {
            check(known.contains(name), "%s is in a curated table but is not a known Aura property", name);
        }
        checkEquals(77, known.size(), "known Aura properties");
        checkEquals(27, VaadinAgentTools.AuraData.READ_ONLY.size(), "read-only Aura properties");
        checkEquals(7, VaadinAgentTools.AuraData.REASSIGNED_VAADIN.size(), "reassigned --vaadin-* properties");
    }

    /** Commented-out theme loading must not activate the Aura-gated checks. */
    static void testAuraIgnoresCommentedOutThemeLoading() {
        var r = aura("aura-commented-theme");
        check(r.themesLoaded().isEmpty(),
                "themesLoaded = %s, want empty: the @StyleSheet/@import are commented out", r.themesLoaded());
        check(!propertyReported(r.findings(), "AURA_READONLY_PROPERTY_ASSIGNED", "--vaadin-text-color"),
                "--vaadin-* must not be reported when no base theme is loaded");
        check(r.ok(), "expected ok=true, got findings: %s", r.findings());
    }

    /** !important is case-insensitive and allows whitespace; a comment marker in a string hides nothing. */
    static void testAuraUnitlessSeesThroughImportantAndStrings() {
        var r = aura("aura-unitless");
        for (String prop : List.of(
                "--aura-app-layout-border-width", // 0 !IMPORTANT
                "--aura-item-overlay-padding-inline", // 0 ! important
                "--aura-item-overlay-padding-block")) { // after a content: "/*" string
            check(propertyReported(r.findings(), "AURA_UNITLESS_LENGTH", prop),
                    "expected %s to be reported as unitless", prop);
        }
    }

    // --- Java inline styles (Style.set / Style.bind) ---------------------------

    static void testAuraFlagsReadOnlyPropertyAssignedFromJava() throws IOException {
        var r = aura("aura-java-inline");
        check(!r.ok(), "expected ok=false");
        for (String prop : List.of(
                "--aura-background-color", // getStyle().set(...)
                "--vaadin-text-color", // getElement().getStyle().set(...), gated on Aura
                "--aura-accent-color", // non-literal value: the name still decides
                "--aura-surface-color", // bind(..., signal)
                "--aura-red-text", // whitespace between the dot and the method name
                "--aura-green-text", // a comment there, which blanks to whitespace
                "--aura-purple-text", // first of two chained set(...) calls
                "--aura-orange-text")) { // second of the same chain
            check(propertyReported(r.findings(), "AURA_READONLY_PROPERTY_ASSIGNED", prop),
                    "expected %s to be reported as read-only", prop);
        }
        // Each property is assigned exactly once in the fixture, so one evidence
        // entry apiece: a second would mean the same call was matched twice.
        for (var f : allByCode(r.findings(), "AURA_READONLY_PROPERTY_ASSIGNED")) {
            checkEquals(1, f.evidence().size(), "evidence entries for " + subjectOf(f));
            var e = f.evidence().get(0);
            check(e.file().endsWith("DashboardView.java"),
                    "evidence points at %s, but only DashboardView assigns anything", e.file());
            assertEvidenceMatchesSource("aura-java-inline", e, subjectOf(f));
        }
    }

    /**
     * The reported line must be the line the property is written on, and the
     * snippet must be that line. Reading the source beats hard-coding line
     * numbers, and it is what catches an off-by-one in the Java offsets.
     */
    static void assertEvidenceMatchesSource(String fixture, VaadinAgentTools.Evidence e, String property)
            throws IOException {
        String[] lines = Files.readString(FIXTURES.resolve(fixture).resolve(e.file())).split("\n", -1);
        check(e.line() >= 1 && e.line() <= lines.length, "%s: line %d is outside %s", property, e.line(), e.file());
        String source = lines[e.line() - 1];
        check(source.contains(property + "\""), "%s: reported at %s:%d, but that line is %s", property, e.file(),
                e.line(), source.strip());
        checkEquals(source.strip(), e.snippet(), property + " snippet");
    }

    /** Code quoted in a text block is a snippet being shown, not an assignment. */
    static void testAuraIgnoresAssignmentsQuotedInJavaTextBlocks() {
        var r = aura("aura-java-clean"); // WHAT_NOT_TO_DO quotes two bad calls
        check(r.ok(), "a quoted snippet must not be reported, got: %s", r.findings());
        for (String code : List.of("AURA_READONLY_PROPERTY_ASSIGNED", "AURA_UNITLESS_LENGTH")) {
            check(allByCode(r.findings(), code).isEmpty(), "%s fired on a text block", code);
        }
    }

    static void testAuraFlagsUnitlessLengthFromJava() {
        var r = aura("aura-java-inline");
        checkEquals(1, allByCode(r.findings(), "AURA_UNITLESS_LENGTH").size(), "unitless findings");
        check(propertyReported(r.findings(), "AURA_UNITLESS_LENGTH", "--aura-app-layout-inset"),
                "expected --aura-app-layout-inset to be reported as unitless");
        // set("--aura-app-layout-radius", radius) has no readable value to judge.
        check(!propertyReported(r.findings(), "AURA_UNITLESS_LENGTH", "--aura-app-layout-radius"),
                "a non-literal value must not be reported as unitless");
    }

    /** Requiring the -- prefix makes a non-Style .set(...) rare rather than impossible; this pins that. */
    static void testAuraReportsLookalikeSetCallsOnNonStyleObjects() {
        check(propertyReported(aura("aura-java-inline").findings(), "AURA_READONLY_PROPERTY_ASSIGNED",
                "--aura-font-size-m"), "expected config.set(\"--aura-font-size-m\", ...) to be reported");
    }

    static void testAuraCorrectJavaInlineStylesHaveNoFindings() {
        var r = aura("aura-java-clean");
        check(r.ok(), "expected ok=true, got findings: %s", r.findings());
        check(r.findings().isEmpty(), "expected no findings, got %s", r.findings());
    }

    /** The selector checks need the element's class names, which Java sets in another statement. */
    static void testAuraSelectorChecksSkipJavaInlineStyles() {
        for (String fixture : List.of("aura-java-clean", "aura-java-inline")) {
            var r = aura(fixture);
            for (String code : List.of("AURA_SURFACE_PROPERTY_WITHOUT_SURFACE_CLASS",
                    "AURA_ACCENT_SURFACE_WITHOUT_ACCENT_CLASS")) {
                check(allByCode(r.findings(), code).isEmpty(), "%s: %s must not fire on a Java inline style",
                        fixture, code);
            }
        }
    }

    /** Harvesting a project's own --aura-* names from Java suppresses AURA_UNKNOWN_PROPERTY. */
    static void testAuraJavaDefinedPropertiesStillSuppressUnknownReads() {
        var r = aura("aura-java-token");
        check(r.ok(), "expected ok=true (warning only), got findings: %s", r.findings());
        check(!propertyReported(r.findings(), "AURA_UNKNOWN_PROPERTY", "--aura-card-padding"),
                "a token the project sets from Java must count as defined");
        // The harvesting reads comment-blanked source, so a name that only ever
        // appears in a Java comment defines nothing.
        check(propertyReported(r.findings(), "AURA_UNKNOWN_PROPERTY", "--aura-card-margin"),
                "a name only mentioned in a Java comment must not count as defined");
    }

    // ==================================================================== CSS

    static VaadinAgentTools.Css.Parsed parse(String css) {
        return VaadinAgentTools.Css.parse(VaadinAgentTools.Css.blankComments(css));
    }

    static void testBlankCommentsPreservesOffsets() {
        checkEquals("a{    \n    --p: 1}", VaadinAgentTools.Css.blankComments("a{/* x\ny */--p: 1}"), "blanked");
    }

    static void testParseCssCapturesSelectorChain() {
        var decls = parse("\n@media (min-width: 900px) {\n  .card {\n    --a: 1px;\n    &:hover { --b: 2px }\n  }\n}")
                .declarations();
        checkEquals(2, decls.size(), "declarations");
        // The @media prelude is not a selector.
        checkEquals(List.of(".card"), decls.get(0).selectors(), "selectors");
        checkEquals(List.of(".card", "&:hover"), decls.get(1).selectors(), "nested selectors");
        // The last declaration in a block may omit its semicolon.
        checkEquals("--b: 2px", decls.get(1).property() + ": " + decls.get(1).value(), "declaration");
    }

    static void testParseCssIgnoresCommentsAndStrings() {
        var p = parse("\n.x {\n  /* --commented: var(--gone); */\n  content: \"} --not-a-decl: var(--nope)\";\n"
                + "  --real: var(--dep);\n}");
        checkEquals(List.of("--real"), p.declarations().stream().map(d -> d.property()).collect(Collectors.toList()),
                "declarations");
        checkEquals(List.of("--dep"), p.reads().stream().map(v -> v.name()).collect(Collectors.toList()), "reads");
    }

    static void testParseCssHandlesFunctionsInValues() {
        var p = parse(".x { --c: light-dark(var(--a), var(--b)); --d: 1 }");
        checkEquals(2, p.declarations().size(), "declarations");
        checkEquals("light-dark(var(--a), var(--b))", p.declarations().get(0).value(), "value");
        checkEquals(List.of("--a", "--b"), p.reads().stream().map(v -> v.name()).collect(Collectors.toList()),
                "reads");
    }

    static void testParseCssSkipsTopLevelText() {
        var decls = parse("@import url(https://example.com/a.css);\n.x { --p: 1px }").declarations();
        checkEquals(List.of("--p"), decls.stream().map(d -> d.property()).collect(Collectors.toList()),
                "declarations");
    }

    static void checkSelectorMatches(Object[][] cases) {
        for (Object[] c : cases) {
            @SuppressWarnings("unchecked")
            List<String> chain = (List<String>) c[0];
            boolean got = VaadinAgentTools.Css.selectorChainMatches(chain, List.of((String) c[1]));
            check(got == (Boolean) c[2], "selectorChainMatches(%s, %s) = %s, want %s", chain, c[1], got, c[2]);
        }
    }

    static void testSelectorChainMatchesRespectsIdentifierBoundaries() {
        checkSelectorMatches(new Object[][] {
            {List.of("vaadin-tabs"), "vaadin-tab", false},
            {List.of("vaadin-tab"), "vaadin-tab", true},
            {List.of("vaadin-tab:hover"), "vaadin-tab", true},
            {List.of(".aura-surface-solid"), ".aura-surface", false},
            {List.of(".hero.aura-surface"), ".aura-surface", true},
            {List.of("vaadin-text-field::part(input-field)"), "::part(input-field)", true},
            {List.of("vaadin-dialog::part(overlay)"), "::part(overlay)", true},
            {List.of(".my-card", "vaadin-card"), "vaadin-card", true},
            {List.of(".my-card"), "vaadin-card", false},
            {List.of("VAADIN-CARD"), "vaadin-card", true},
        });
    }

    /** A comment marker inside a string must not start a comment, nor a quote in a comment a string. */
    static void testBlankCommentsDoesNotConfuseStringsAndComments() {
        var decls = parse(".x {\n  content: \"/*\";\n  --a: 0;\n}\n/* it's a comment with an apostrophe */\n"
                + ".y { --b: 1 }").declarations();
        checkEquals(List.of("--a", "--b"), decls.stream().map(d -> d.property()).collect(Collectors.toList()),
                "declarations");
    }

    static void testBlankCommentsHandlesUnterminatedComment() {
        String in = "a{--p:1} /* never closed";
        checkEquals(in.length(), VaadinAgentTools.Css.blankComments(in).length(), "length");
    }

    /** CSS has no // comment: url(https://...) must survive blanking intact. */
    static void testBlankCommentsKeepsProtocolRelativeText() {
        String in = "@import url(https://example.com/a.css);\n.x { --p: 1px }";
        checkEquals(in, VaadinAgentTools.Css.blankComments(in), "blanked");
    }

    static void testBlankJavaCommentsBlanksLineAndBlockComments() {
        String in = "// @StyleSheet(Aura.STYLESHEET)\n/* @StyleSheet(Lumo.STYLESHEET) */\n"
                + "String s = \"// not a comment\";";
        String out = VaadinAgentTools.Css.blankJavaComments(in);
        checkEquals(in.length(), out.length(), "length");
        check(!out.contains("Aura") && !out.contains("Lumo"), "commented annotations survived: %s", out);
        check(out.contains("// not a comment"), "a comment marker inside a string literal was blanked: %s", out);
    }

    /** CSS identifiers may contain non-ASCII characters. */
    static void testParseCssHandlesNonAsciiIdentifiers() {
        var p = parse(".x { --aura-card-\u00e9: red; color: var(--aura-card-\u00e9); }");
        checkEquals(List.of("--aura-card-\u00e9"),
                p.declarations().stream().map(d -> d.property()).collect(Collectors.toList()), "declarations");
        checkEquals(List.of("--aura-card-\u00e9"), p.reads().stream().map(v -> v.name()).collect(Collectors.toList()),
                "reads (not truncated)");
    }

    static void testSelectorChainMatchesNormalizesEquivalentSpellings() {
        checkSelectorMatches(new Object[][] {
            {List.of("[theme~=\"success\"]"), "[theme~='success']", true},
            {List.of("[theme~= \"success\"]"), "[theme~='success']", true},
            // A selector matches case-insensitively, but an attribute VALUE is
            // case-sensitive in CSS, so these two are genuinely different.
            {List.of("[THEME~='SUCCESS']"), "[theme~='success']", false},
            {List.of("[THEME~='success']"), "[theme~='success']", true},
            {List.of("[theme~='danger']"), "[theme~='success']", false},
            // A non-ASCII suffix makes it a different class identifier.
            {List.of(".aura-surface\u00e9"), ".aura-surface", false},
        });
    }

    /** Whitespace outside brackets is the descendant combinator, an identifier boundary. */
    static void testSelectorChainMatchesKeepsDescendantCombinator() {
        checkSelectorMatches(new Object[][] {
            {List.of("vaadin-notification-card vaadin-button"), "vaadin-button", true},
            {List.of("vaadin-notification-card > vaadin-button"), "vaadin-button", true},
            {List.of(".card vaadin-tab"), "vaadin-tab", true},
            {List.of(".card vaadin-tabx"), "vaadin-tab", false},
        });
    }

    /** Brackets in a quoted value must not shift the depth, and whitespace in it must not fold. */
    static void testSelectorChainMatchesTreatsQuotedValuesAsOpaque() {
        checkSelectorMatches(new Object[][] {
            {List.of("[data-label=\"[\"] span vaadin-button"), "vaadin-button", true},
            {List.of("[data-label=\"]\"] vaadin-card"), "vaadin-card", true},
            // Folding whitespace inside a value used to forge a whitelisted class.
            {List.of("[data-label=\".aura- surface\"]"), ".aura-surface", false},
            {List.of("[data-label=\"a\\\"b\"] vaadin-card"), "vaadin-card", true},
            // Whitespace outside the value is still insignificant.
            {List.of("[theme~= \"success\"]"), "[theme~='success']", true},
        });
    }

    /** A Java line comment ends at any line terminator, and a bare CR is one. */
    static void testBlankJavaCommentsEndsLineCommentAtCarriageReturn() {
        String in = "// comment\r@StyleSheet(Aura.STYLESHEET)\rclass App {}";
        String out = VaadinAgentTools.Css.blankJavaComments(in);
        checkEquals(in.length(), out.length(), "length");
        check(out.contains("@StyleSheet(Aura.STYLESHEET)"), "code after a CR-terminated line comment was blanked");
        check(!out.contains("comment"), "the comment itself survived: %s", out);
        checkEquals(in.chars().filter(c -> c == '\r').count(), out.chars().filter(c -> c == '\r').count(),
                "carriage returns");
    }

    static void testBlankJavaTextBlocksBlanksOnlyTheContents() {
        String src = "String a = \"keep me\";\nString doc = \"\"\"\n"
                + "    box.getStyle().set(\"--aura-background-color\", \"#fff\");\n    \"\"\";\n"
                + "String b = \"keep me too\";\n";
        String got = VaadinAgentTools.Css.blankJavaTextBlocks(src);
        checkEquals(src.length(), got.length(), "length");
        checkEquals(src.chars().filter(c -> c == '\n').count(), got.chars().filter(c -> c == '\n').count(),
                "newlines");
        check(!got.contains("--aura-background-color"), "text-block contents were not blanked: %s", got);
        check(got.contains("\"keep me\"") && got.contains("\"keep me too\""), "a string literal was blanked: %s",
                got);
    }

    /** A quote inside an ordinary literal is not a block, and neither is "" (two quotes). */
    static void testBlankJavaTextBlocksSkipsOrdinaryLiterals() {
        String src = "String quote = \"\\\"\\\"\\\"\"; String empty = \"\"; String tail = \"--aura-red\";";
        checkEquals(src, VaadinAgentTools.Css.blankJavaTextBlocks(src), "blanked");
    }

    /** The comment pass and the text-block pass must agree about where a block ends. */
    static void testBlankJavaCommentsKeepsTextBlocksIntact() {
        String src = "String doc = \"\"\"\n\" // \"\"\";\nstyle.set(\"--aura-red-text\", \"#900\");\n";
        String got = VaadinAgentTools.Css.blankJavaTextBlocks(VaadinAgentTools.Css.blankJavaComments(src));
        check(got.contains("set(\"--aura-red-text\", \"#900\")"),
                "the statement after the text block was blanked away: %s", got);
    }

    /** An unterminated block blanks to EOF rather than looping or failing. */
    static void testBlankJavaTextBlocksHandlesUnterminatedBlock() {
        String src = "String doc = \"\"\"\n  set(\"--aura-red-text\", \"#900\");\n";
        String got = VaadinAgentTools.Css.blankJavaTextBlocks(src);
        checkEquals(src.length(), got.length(), "length");
        check(!got.contains("--aura-red-text"), "an unterminated block must be blanked to EOF: %s", got);
    }

    // ========================================================= create-project

    static void testStripFirst() {
        Map<String, String> cases = Map.of("proj/pom.xml", "pom.xml", "proj/src/main/App.java", "src/main/App.java",
                "proj/", "", "toplevel", "", "./proj/pom.xml", "pom.xml");
        cases.forEach((in, want) -> checkEquals(want, VaadinAgentTools.CreateProject.stripFirst(in),
                "stripFirst(" + in + ")"));
    }

    /** A ZIP of name -> content; a name ending in / is a directory entry. */
    static byte[] makeZip(Map<String, String> files) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buf)) {
            for (var e : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                if (!e.getKey().endsWith("/")) {
                    zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                }
                zip.closeEntry();
            }
        }
        return buf.toByteArray();
    }

    /**
     * Marks the named entry as made on Unix with the given mode, which
     * ZipOutputStream can't do: patches "version made by" and the external
     * attributes in its central directory header.
     */
    static byte[] withUnixMode(byte[] zip, String name, int mode) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        for (int p = 0; p + 46 <= zip.length; p++) {
            if (zip[p] == 0x50 && zip[p + 1] == 0x4b && zip[p + 2] == 0x01 && zip[p + 3] == 0x02) {
                int nameLength = (zip[p + 28] & 0xFF) | (zip[p + 29] & 0xFF) << 8;
                if (Arrays.equals(Arrays.copyOfRange(zip, p + 46, p + 46 + nameLength), nameBytes)) {
                    zip[p + 5] = 3; // made on Unix
                    long external = (long) (0100000 | mode) << 16;
                    for (int k = 0; k < 4; k++) {
                        zip[p + 38 + k] = (byte) (external >>> (8 * k));
                    }
                    return zip;
                }
            }
        }
        throw new AssertionError("no central directory header for " + name);
    }

    static Map<String, String> files(String... namesAndContents) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < namesAndContents.length; i += 2) {
            m.put(namesAndContents[i], namesAndContents[i + 1]);
        }
        return m;
    }

    static VaadinAgentTools.CreateProject.Report create(Path cwd, String... args) {
        return VaadinAgentTools.CreateProject.create(new VaadinAgentTools.Args(List.of(args), cwd));
    }

    static void testExtractZipStripsTopDirAndKeepsEmptyFiles() throws Exception {
        withTempDir(dir -> {
            Path zipFile = dir.resolve("skeleton.zip");
            Files.write(zipFile, makeZip(files("my-project/", "", "my-project/pom.xml", "<project/>",
                    "my-project/src/main/java/App.java", "class App {}",
                    "my-project/frontend/themes/x/styles.css", ""))); // an empty theme file must survive
            Path target = dir.resolve("out");
            Files.createDirectories(target);
            int count;
            try (ZipFile zip = new ZipFile(zipFile.toFile())) {
                count = VaadinAgentTools.CreateProject.extract(zip, Map.of(), target);
            }
            checkEquals(3, count, "files written");
            check(!Files.exists(target.resolve("my-project")), "expected the top-level directory to be stripped");
            for (String p : List.of("pom.xml", "src/main/java/App.java", "frontend/themes/x/styles.css")) {
                check(Files.exists(target.resolve(p)), "expected %s to exist", p);
            }
            checkEquals(0L, Files.size(target.resolve("frontend/themes/x/styles.css")), "styles.css size");
        });
    }

    static void testExtractZipRejectsZipSlip() throws Exception {
        withTempDir(dir -> {
            Path zipFile = dir.resolve("skeleton.zip");
            Files.write(zipFile, makeZip(files("proj/../../escape.txt", "pwned")));
            Path target = dir.resolve("a").resolve("b");
            Files.createDirectories(target);
            try (ZipFile zip = new ZipFile(zipFile.toFile())) {
                VaadinAgentTools.CreateProject.extract(zip, Map.of(), target);
                throw new AssertionError("expected the zip-slip path to be rejected");
            } catch (IOException expected) {
                check(expected.getMessage().contains("unsafe path"), "unexpected error: %s", expected);
            }
            check(!Files.exists(dir.resolve("escape.txt")), "the zip-slip entry was written");
        });
    }

    static void testCreateProjectUsageErrors() throws Exception {
        withTempDir(dir -> {
            Path skeleton = dir.resolve("skeleton.zip");
            Files.write(skeleton, makeZip(files("p/pom.xml", "<project/>")));
            check(create(dir, "--skeleton=" + skeleton).usageError() != null,
                    "expected a usage error when the target directory is missing");
            check(create(dir, "newproj").usageError() != null, "expected a usage error when --skeleton is missing");
            Files.createDirectories(dir.resolve("proj"));
            Files.writeString(dir.resolve("proj/keep.txt"), "x");
            check(create(dir, "proj", "--skeleton=" + skeleton).usageError() != null,
                    "expected a usage error for a non-empty target without --overwrite");
        });
    }

    static void testCreateProjectExtractsSkeleton() throws Exception {
        withTempDir(dir -> {
            Path skeleton = dir.resolve("skeleton.zip");
            Files.write(skeleton, makeZip(files("my-app/pom.xml", "<project/>", "my-app/src/main/java/App.java",
                    "class App {}")));
            var r = create(dir, "my-app", "--skeleton=" + skeleton);
            check(r.ok() && r.usageError() == null, "createProject failed: %s", r);
            checkEquals(2, r.files(), "files written");
            check(Files.exists(dir.resolve("my-app/pom.xml")), "expected my-app/pom.xml");
        });
    }

    static void testCreateProjectOverwritesExistingTarget() throws Exception {
        withTempDir(dir -> {
            Path skeleton = dir.resolve("skeleton.zip");
            Files.write(skeleton, makeZip(files("my-app/pom.xml", "<project/>")));
            Files.createDirectories(dir.resolve("proj/old"));
            Files.writeString(dir.resolve("proj/old/stale.txt"), "x");
            var r = create(dir, "proj", "--skeleton=" + skeleton, "--overwrite");
            check(r.ok(), "createProject failed: %s", r);
            check(!Files.exists(dir.resolve("proj/old")), "--overwrite must clear the old contents");
            check(Files.exists(dir.resolve("proj/pom.xml")), "expected proj/pom.xml");
        });
    }

    static void testCreateProjectKeepsTargetWhenSkeletonIsInvalid() throws Exception {
        withTempDir(dir -> {
            // What curl saves when the server answers with an error page.
            Path skeleton = dir.resolve("skeleton.zip");
            Files.writeString(skeleton, "<html>Service unavailable</html>");
            Path keep = dir.resolve("proj/keep.txt");
            Files.createDirectories(keep.getParent());
            Files.writeString(keep, "x");
            var r = create(dir, "proj", "--skeleton=" + skeleton, "--overwrite");
            check(!r.ok() && !r.error().isEmpty(), "expected an error for an invalid skeleton, got %s", r);
            check(Files.exists(keep), "an invalid skeleton must not clear the target");
        });
    }

    /** mvnw must stay executable, and nothing else may become so. */
    static void testCreateProjectKeepsExecutableBit() throws Exception {
        withTempDir(dir -> {
            if (Files.getFileAttributeView(dir, java.nio.file.attribute.PosixFileAttributeView.class) == null) {
                return; // no POSIX permissions here (Windows)
            }
            byte[] zip = makeZip(files("my-app/mvnw", "#!/bin/sh\n", "my-app/pom.xml", "<project/>"));
            Path skeleton = dir.resolve("skeleton.zip");
            Files.write(skeleton, withUnixMode(withUnixMode(zip, "my-app/mvnw", 0755), "my-app/pom.xml", 0644));
            check(create(dir, "my-app", "--skeleton=" + skeleton).ok(), "createProject failed");
            check(Files.getPosixFilePermissions(dir.resolve("my-app/mvnw")).contains(PosixFilePermission.OWNER_EXECUTE),
                    "mvnw lost its executable bit");
            check(!Files.getPosixFilePermissions(dir.resolve("my-app/pom.xml"))
                    .contains(PosixFilePermission.OWNER_EXECUTE), "pom.xml became executable");
        });
    }

    /** Each file gets the archive's mode, minus the umask: never more. */
    static void testCreateProjectNeverWidensPermissions() throws Exception {
        withTempDir(dir -> {
            if (Files.getFileAttributeView(dir, java.nio.file.attribute.PosixFileAttributeView.class) == null) {
                return; // no POSIX permissions here (Windows)
            }
            byte[] zip = makeZip(files("my-app/secret.txt", "s", "my-app/run.sh", "#!/bin/sh\n"));
            Path skeleton = dir.resolve("skeleton.zip");
            Files.write(skeleton, withUnixMode(withUnixMode(zip, "my-app/secret.txt", 0600), "my-app/run.sh", 0700));
            check(create(dir, "my-app", "--skeleton=" + skeleton).ok(), "createProject failed");
            for (String name : List.of("secret.txt", "run.sh")) {
                for (PosixFilePermission p : Files.getPosixFilePermissions(dir.resolve("my-app").resolve(name))) {
                    check(p.name().startsWith("OWNER_"), "%s got %s, which its archive mode doesn't have", name, p);
                }
            }
        });
    }

    /** A ZIP of one entry stored uncompressed, so its contents can be damaged in place. */
    static byte[] makeStoredZip(String name, String content) throws IOException {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        entry.setCompressedSize(data.length);
        entry.setCrc(crc.getValue());
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buf)) {
            zip.putNextEntry(entry);
            zip.write(data);
            zip.closeEntry();
        }
        return buf.toByteArray();
    }

    static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int k = 0; k < needle.length; k++) {
                if (haystack[i + k] != needle[k]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new AssertionError("not found");
    }

    /** ZipFile checks no checksums, so extraction must, or a damaged download passes as fine. */
    static void testCreateProjectRejectsDamagedEntries() throws Exception {
        // Damaged contents in a stored entry.
        byte[] stored = makeStoredZip("my-app/pom.xml", "<project>stored</project>");
        stored[indexOf(stored, "stored</".getBytes(StandardCharsets.UTF_8))] ^= 0x20;
        // A deflated entry whose checksum, in the central directory, doesn't match.
        byte[] deflated = makeZip(files("my-app/pom.xml", "<project>deflated</project>"));
        deflated[indexOf(deflated, new byte[] {0x50, 0x4b, 0x01, 0x02}) + 16] ^= (byte) 0xFF;

        for (byte[] zip : List.of(stored, deflated)) {
            withTempDir(dir -> {
                Path skeleton = dir.resolve("skeleton.zip");
                Files.write(skeleton, zip);
                var r = create(dir, "my-app", "--skeleton=" + skeleton);
                check(!r.ok(), "a damaged entry was extracted as if it were fine: %s", r);
                check(r.error().contains("my-app/pom.xml is damaged"), "unexpected error: %s", r.error());
                check(!Files.exists(dir.resolve("my-app/pom.xml")), "the damaged file was left behind");
            });
        }
    }

    static void testPermissionsFollowTheHostSystem() {
        checkEquals(0755, VaadinAgentTools.CreateProject.permissions(3, 0100755L << 16), "Unix 0755");
        checkEquals(0644, VaadinAgentTools.CreateProject.permissions(3, 0), "Unix without a mode");
        checkEquals(0444, VaadinAgentTools.CreateProject.permissions(0, 0x01), "MS-DOS read-only");
        checkEquals(0666, VaadinAgentTools.CreateProject.permissions(11, 0), "NTFS");
        checkEquals(0644, VaadinAgentTools.CreateProject.permissions(99, 0x01), "an unknown host");
    }

    // =================================================================== hook

    static String runHookProcess(InputStream stdin, Path cwd, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = VaadinAgentTools.Hook.run(List.of(args), stdin, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), cwd);
        checkEquals(0, code, "exit code");
        checkEquals("", err.toString(StandardCharsets.UTF_8), "stderr");
        return out.toString(StandardCharsets.UTF_8);
    }

    static InputStream stdin(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Feeds payload to `hook post-tool-use`. A build descriptor is dropped into the
     * fixture first, so the project-root resolver scopes the scan to that fixture
     * alone. $FILE becomes the fixture's stylesheet when the payload mentions a
     * custom property and the fixture has one, and its Application class otherwise.
     */
    static String runHook(String fixture, String payload) throws IOException {
        Path root = FIXTURES.resolve(fixture);
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, "<project/>");
        try {
            Path edited = root.resolve("src/main/java/com/example/Application.java");
            Path css = root.resolve("frontend/themes/my-theme/styles.css");
            if ((payload.contains("--aura-") || payload.contains("--lumo-")) && Files.exists(css)) {
                edited = css;
            }
            String json = payload.replace("$FILE", edited.toString().replace("\\", "\\\\"));
            return runHookProcess(stdin(json), root, "post-tool-use");
        } finally {
            Files.delete(pom);
        }
    }

    static void testHookFlagsStylingEditInMixedProject() throws IOException {
        String out = runHook("mixed",
                "{\"tool_input\":{\"file_path\":\"$FILE\",\"new_string\":\"btn.getStyle().set(\\\"color\\\",\\\"red\\\");\"}}");
        check(out.contains("MULTIPLE_BASE_THEMES"), "expected a mixing finding in the output, got: %s", out);
        check(out.contains("\"hookEventName\": \"PostToolUse\""), "expected a PostToolUse envelope, got: %s", out);
    }

    static void testHookSilentOnNonStylingJavaEdit() throws IOException {
        checkEquals("", runHook("mixed", "{\"tool_input\":{\"file_path\":\"$FILE\",\"new_string\":\"int total = a + b;\"}}"),
                "output for a non-styling edit");
    }

    static void testHookSilentOnCleanProject() throws IOException {
        checkEquals("", runHook("clean",
                "{\"tool_input\":{\"file_path\":\"$FILE\",\"new_string\":\"addClassName(\\\"foo\\\");\"}}"),
                "output for a clean project");
    }

    static void testHookDetectsStylingInMultiEdit() throws IOException {
        String out = runHook("mixed", "{\"tool_input\":{\"file_path\":\"$FILE\",\"edits\":[{\"new_string\":\"nope\"},"
                + "{\"new_string\":\"addClassName(\\\"x\\\")\"}]}}");
        check(out.contains("MULTIPLE_BASE_THEMES"), "expected MultiEdit styling to trigger the check, got: %s", out);
    }

    static void testHookIgnoresNonStyleableFiles() {
        checkEquals("", runHookProcess(stdin("{\"tool_input\":{\"file_path\":\"/x/readme.txt\",\"content\":\"getStyle()\"}}"),
                Path.of("/x").toAbsolutePath(), "post-tool-use"), "output for a .txt edit");
    }

    static void testHookSilentOnMalformedStdin() {
        for (String payload : List.of("not json", "", "[]", "\"x\"", "{\"tool_input\":{\"file_path\":5}}",
                "{\"tool_input\":{\"edits\":[\"x\"]}}", "{} {}")) {
            checkEquals("", runHookProcess(stdin(payload), ROOT, "post-tool-use"), "output for " + payload);
        }
    }

    static void testHookUnknownSubcommandIsSilent() {
        checkEquals("", runHookProcess(stdin("{}"), ROOT, "bogus"), "output for an unknown subcommand");
        checkEquals("", runHookProcess(stdin("{}"), ROOT), "output for no subcommand");
    }

    static void testHookFlagsAuraMisuseAfterCssEdit() throws IOException {
        // Any .css edit counts as a styling change, so gate 2 does not apply here.
        String out = runHook("aura-readonly",
                "{\"tool_input\":{\"file_path\":\"$FILE\",\"content\":\"--aura-background-color: #fff;\"}}");
        check(out.contains("AURA_READONLY_PROPERTY_ASSIGNED"), "expected the Aura usage check to run, got: %s", out);
        check(out.contains("check-aura-usage"), "expected a check-aura-usage envelope, got: %s", out);
    }

    static void testHookStaysSilentOnCleanAuraProject() throws IOException {
        checkEquals("", runHook("aura-clean",
                "{\"tool_input\":{\"file_path\":\"$FILE\",\"content\":\"--aura-base-size: 16;\"}}"),
                "output for a correctly themed project");
    }

    /** An inline style set from a Flow view reaches the hook through the .java gate. */
    static void testHookFlagsAuraMisuseAfterJavaInlineStyleEdit() throws IOException {
        String out = runHook("aura-java-inline", "{\"tool_input\":{\"file_path\":\"$FILE\",\"new_string\":"
                + "\"box.getStyle().set(\\\"--aura-background-color\\\", \\\"#fff\\\");\"}}");
        check(out.contains("AURA_READONLY_PROPERTY_ASSIGNED"), "expected the Aura usage check to speak, got: %s", out);
        check(out.contains("DashboardView.java"), "expected the finding to point at the Java source, got: %s", out);
    }

    /** Warnings are not worth interrupting the agent for. */
    static void testHookStaysSilentOnAuraWarningsOnly() throws IOException {
        checkEquals("", runHook("aura-surface",
                "{\"tool_input\":{\"file_path\":\"$FILE\",\"content\":\"--aura-surface-level: 3;\"}}"),
                "output for warning-only findings");
    }

    /** The envelope handed to the agent is the tool's own --json output, field order included. */
    static void testHookEnvelopeMatchesToolJson() throws Exception {
        String out = runHook("mixed",
                "{\"tool_input\":{\"file_path\":\"$FILE\",\"new_string\":\"addClassName(\\\"x\\\")\"}}");
        @SuppressWarnings("unchecked")
        Map<String, Object> hook = (Map<String, Object>) ((Map<String, Object>) VaadinAgentTools.Json.parse(out))
                .get("hookSpecificOutput");
        String context = (String) hook.get("additionalContext");
        String envelope = context.substring(context.indexOf('{'));

        ByteArrayOutputStream cli = new ByteArrayOutputStream();
        VaadinAgentTools.run(new String[] {"check-theme-mixing", FIXTURES.resolve("mixed").toString(), "--json"},
                stdin(""), new PrintStream(cli, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), ROOT);
        checkEquals(cli.toString(StandardCharsets.UTF_8).strip(), envelope.strip(), "envelope");
    }

    /**
     * Feeds a Codex apply_patch payload to the hook. The patch's relative paths
     * resolve against the payload's cwd, the fixtures directory; the hook itself
     * runs somewhere unrelated, which proves the payload's cwd is the one used.
     */
    static String runPatchHook(String patch, String... fixtures) throws IOException {
        List<Path> poms = new ArrayList<>();
        try {
            for (String fixture : fixtures) {
                Path pom = FIXTURES.resolve(fixture).resolve("pom.xml");
                Files.writeString(pom, "<project/>");
                poms.add(pom);
            }
            String payload = VaadinAgentTools.Json.write(VaadinAgentTools.obj("hook_event_name", "PostToolUse",
                    "cwd", FIXTURES.toString(), "tool_name", "apply_patch",
                    "tool_input", VaadinAgentTools.obj("command", patch)));
            return runHookProcess(stdin(payload), ROOT.resolve("plugin"), "post-tool-use");
        } finally {
            for (Path pom : poms) {
                Files.delete(pom);
            }
        }
    }

    static void testHookFlagsAuraMisuseAfterCodexCssPatch() throws IOException {
        String out = runPatchHook("*** Begin Patch\n*** Update File: aura-readonly/frontend/themes/my-theme/styles.css\n"
                + "@@ html {\n+  --aura-background-color: #fff;\n*** End Patch", "aura-readonly");
        check(out.contains("AURA_READONLY_PROPERTY_ASSIGNED"), "expected the Aura usage check to run, got: %s", out);
        check(!out.contains("Project: "), "a single-project patch should not label its sections, got: %s", out);
    }

    static void testHookFlagsStylingAfterCodexJavaPatch() throws IOException {
        String out = runPatchHook("*** Begin Patch\n*** Update File: mixed/src/main/java/com/example/Application.java\n"
                + "@@ public class Application {\n+    void style(Button btn) { btn.getStyle().set(\"color\", \"red\"); }\n"
                + "*** End Patch", "mixed");
        check(out.contains("MULTIPLE_BASE_THEMES"), "expected a mixing finding after a Codex styling patch, got: %s",
                out);
    }

    /** Only added lines count; styling code in context or removed lines must not wake the hook. */
    static void testHookSilentOnCodexNonStylingJavaPatch() throws IOException {
        checkEquals("", runPatchHook("*** Begin Patch\n*** Update File: mixed/src/main/java/com/example/Application.java\n"
                + "@@ public class Application {\n     btn.getStyle().set(\"color\", \"red\");\n"
                + "-    btn.addClassName(\"primary\");\n+    int total = a + b;\n*** End Patch", "mixed"),
                "output for a non-styling Codex patch");
    }

    /** Each project a patch touches is checked once, and labelled; apply_patch also takes absolute paths. */
    static void testHookChecksEveryProjectACodexPatchTouches() throws IOException {
        Path css = FIXTURES.resolve("aura-readonly/frontend/themes/my-theme/styles.css");
        String out = runPatchHook("*** Begin Patch\n*** Update File: mixed/src/main/java/com/example/Application.java\n"
                + "@@\n+    btn.addClassName(\"primary\");\n"
                + "*** Update File: mixed/src/main/java/com/example/Application.java\n@@\n+    int total = a + b;\n"
                + "*** Update File: " + css + "\n@@\n+  --aura-background-color: #fff;\n*** End Patch",
                "mixed", "aura-readonly");
        // The sections sit inside a JSON string, where a Windows path's backslashes are doubled.
        String mixed = "Project: " + FIXTURES.resolve("mixed").toString().replace("\\", "\\\\");
        String readonly = "Project: " + FIXTURES.resolve("aura-readonly").toString().replace("\\", "\\\\");
        for (String want : List.of("MULTIPLE_BASE_THEMES", "AURA_READONLY_PROPERTY_ASSIGNED", mixed, readonly)) {
            check(out.contains(want), "expected %s in the output, got: %s", want, out);
        }
        checkEquals(1, out.split(Pattern.quote(mixed), -1).length - 1, "sections for the mixed project");
    }

    /** Deleting a stylesheet introduces no styling, even in a project with error-level findings. */
    static void testHookSilentOnCodexDeleteOnlyPatch() throws IOException {
        checkEquals("", runPatchHook("*** Begin Patch\n*** Delete File: aura-readonly/frontend/themes/my-theme/styles.css\n"
                + "*** End Patch", "aura-readonly"), "output for a delete-only Codex patch");
    }

    static void testParseApplyPatch() {
        Object[][] cases = {
            {"added file", "*** Begin Patch\n*** Add File: a/styles.css\n+html {\n+  color: red;\n+}\n*** End Patch",
                List.of(edit("a/styles.css", "html {\n  color: red;\n}"))},
            {"update keeps only added lines",
                "*** Begin Patch\n*** Update File: A.java\n@@ class A {\n ctx();\n-old();\n+fresh();\n*** End of File\n"
                        + "*** End Patch",
                List.of(edit("A.java", "fresh();"))},
            {"move reports the new path", "*** Begin Patch\n*** Update File: old.txt\n*** Move to: new.css\n@@\n+x\n"
                    + "*** End Patch", List.of(edit("new.css", "x"))},
            {"late move marker is ignored", "*** Begin Patch\n*** Update File: a.txt\n@@\n+x\n*** Move to: b.css\n"
                    + "*** End Patch", List.of(edit("a.txt", "x"))},
            {"deleted file is left out", "*** Begin Patch\n*** Delete File: gone.css\n*** Add File: kept.css\n+a\n"
                    + "*** End Patch", List.of(edit("kept.css", "a"))},
            {"heredoc wrapper, padded markers and CRLF",
                "<<'EOF'\r\n  *** Begin Patch \r\n *** Add File: a.css\r\n+b\r\n*** End Patch\r\nEOF\r\n",
                List.of(edit("a.css", "b"))},
            {"nothing after the end marker",
                "*** Begin Patch\n*** Add File: a.css\n+b\n*** End Patch\n*** Add File: c.css\n+d",
                List.of(edit("a.css", "b"))},
            {"not a patch", "mvn package", List.of()},
        };
        for (Object[] c : cases) {
            checkEquals(c[2], VaadinAgentTools.Hook.parseApplyPatch((String) c[1]), "parseApplyPatch: " + c[0]);
        }
    }

    static VaadinAgentTools.Hook.FileEdit edit(String path, String introduced) {
        return new VaadinAgentTools.Hook.FileEdit(path, introduced);
    }

    /**
     * The launcher skips Java for events the hook would ignore, by looking for
     * words in the raw payload. Every string the CLI's styling gate matches must
     * contain one of them, or the launcher would hide a real finding.
     */
    static void testLauncherPrefilterCoversTheJavaStylingGate() throws IOException {
        String launcher = Files.readString(CLI_DIR.resolve("vaadin-agent-tools"));
        Matcher m = Pattern.compile("grep -qiE '([^']+)'").matcher(launcher);
        check(m.find(), "no grep -qiE pre-filter in the launcher");
        Pattern prefilter = Pattern.compile(m.group(1), Pattern.CASE_INSENSITIVE);
        check(launcher.contains("grep -qi '\\.css'") && launcher.contains("grep -qi '\\.java'"),
                "the launcher must let every .css and .java edit through to the word check");
        for (String styling : List.of("getStyle(", "addClassName(", "removeClassNames(", "setClassName(",
                "getClassNames<", "@CssImport", "@com.vaadin.flow.component.dependency.CssImport", "@StyleSheet",
                "@Theme", "@com.vaadin.flow.theme.Theme(", "LumoUtility.Margin", "--lumo-space-m", "--aura-red",
                "addThemeName(", "setThemeNames(", "getThemeList(")) {
            check(VaadinAgentTools.Hook.JAVA_STYLING.matcher(styling).find(), "the gate does not match %s", styling);
            check(prefilter.matcher(styling).find(), "the launcher's pre-filter would drop %s", styling);
        }
    }

    // ==================================================================== JSON

    static void testJsonWriteMatchesGoEncoding() {
        String got = VaadinAgentTools.Json.write(VaadinAgentTools.obj("a", List.of(), "b", Map.of(),
                "c", List.of("x", 1, true), "d", "q\"\\<&>\n\t\u0001\u2028\u00e9"));
        checkEquals("{\n  \"a\": [],\n  \"b\": {},\n  \"c\": [\n    \"x\",\n    1,\n    true\n  ],\n"
                + "  \"d\": \"q\\\"\\\\<&>\\n\\t\\u0001\\u2028\u00e9\"\n}", got, "JSON");
    }

    static void testJsonParse() throws Exception {
        Object v = VaadinAgentTools.Json.parse(
                " {\"a\": [1, -2.5e3, true, false, null], \"b\": \"\\u00e9\\ud83d\\ude00\\n\\/\", \"a\": {}} ");
        checkEquals("{a={}, b=\u00e9\ud83d\ude00\n/}", v.toString(), "parsed");
        for (String bad : List.of("", "not json", "{} x", "{'a': 1}", "[1,]", "{\"a\"}", "\"\u0001\"", "01", "-",
                "\"\\x\"", "\"\\u12\"", "[".repeat(5000))) {
            try {
                VaadinAgentTools.Json.parse(bad);
                throw new AssertionError("parsed invalid JSON: " + bad);
            } catch (VaadinAgentTools.Json.ParseException expected) {
                // ok
            }
        }
    }

    // ===================================================================== CLI

    record CliRun(int code, String out, String err) {
    }

    static CliRun cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = VaadinAgentTools.run(args, stdin(""), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), FIXTURES);
        return new CliRun(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    static void testCliHelpListsEveryTool() {
        for (String[] args : List.of(new String[0], new String[] {"help"}, new String[] {"--help"})) {
            CliRun r = cli(args);
            checkEquals(0, r.code(), "exit code");
            for (String tool : List.of("check-aura-usage", "check-theme-mixing", "create-project", "hook post-tool-use")) {
                check(r.out().contains(tool), "help does not mention %s", tool);
            }
        }
        check(cli("list", "--json").out().contains("\"name\": \"create-project\""), "list --json lacks create-project");
    }

    static void testCliToolHelp() {
        CliRun r = cli("check-aura-usage", "--help");
        checkEquals(0, r.code(), "exit code");
        check(r.out().startsWith("vaadin-agent-tools check-aura-usage [projectDir]\n"), "unexpected help: %s", r.out());
    }

    static void testCliExitCodes() {
        checkEquals(0, cli("check-theme-mixing", "clean").code(), "exit code for a clean project");
        checkEquals(1, cli("check-theme-mixing", "mixed").code(), "exit code for a mixed project");
        checkEquals(2, cli("check-theme-mixing", "does-not-exist").code(), "exit code for a missing project");
        CliRun r = cli("no-such-tool");
        checkEquals(2, r.code(), "exit code for an unknown tool");
        check(r.err().startsWith("Unknown tool: no-such-tool\n"), "unexpected stderr: %s", r.err());
    }

    static void testCliJsonEnvelope() throws Exception {
        CliRun r = cli("check-theme-mixing", "mixed", "--json");
        @SuppressWarnings("unchecked")
        Map<String, Object> json = (Map<String, Object>) VaadinAgentTools.Json.parse(r.out());
        checkEquals(List.of("tool", "ok", "themesLoaded", "tokenPrefixesUsed", "filesScanned", "findings"),
                new ArrayList<>(json.keySet()), "envelope fields");
        checkEquals("check-theme-mixing", json.get("tool"), "tool");
        checkEquals(false, json.get("ok"), "ok");

        r = cli("check-aura-usage", "does-not-exist", "--json");
        checkEquals(2, r.code(), "exit code");
        checkEquals("", r.err(), "stderr");
        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) VaadinAgentTools.Json.parse(r.out());
        checkEquals(List.of("tool", "ok", "usageError"), new ArrayList<>(usage.keySet()), "usage error fields");
    }

    static void testCliHumanOutput() {
        CliRun r = cli("check-theme-mixing", "clean");
        checkEquals("# check-theme-mixing\nthemes loaded: aura\nfiles scanned: 2\n\n\u2713 No issues found.\n",
                r.out(), "output");
    }

    // ================================================================ walking

    static void testWalkSkipsIgnoredDirectoriesAndLinks() throws Exception {
        withTempDir(dir -> {
            for (String f : List.of("a.css", "B.CSS", "c.txt", "node_modules/x.css", "target/y.css", "src/z.java")) {
                Files.createDirectories(dir.resolve(f).getParent());
                Files.writeString(dir.resolve(f), "");
            }
            try {
                Files.createSymbolicLink(dir.resolve("linked"), dir.resolve("src"));
            } catch (IOException | UnsupportedOperationException e) {
                // Creating links needs extra rights on Windows; the rest still holds.
            }
            List<String> found = VaadinAgentTools.walk(dir, Set.of(".css", ".java")).stream()
                    .map(p -> dir.relativize(p).toString().replace('\\', '/')).collect(Collectors.toList());
            checkEquals(List.of("B.CSS", "a.css", "src/z.java"), found, "walked files");
        });
    }

    // ================================================================== source

    /** JDK 17 reads source in the platform charset, so the CLI must stay ASCII. */
    static void testSourceIsAscii() throws IOException {
        byte[] src = Files.readAllBytes(CLI_DIR.resolve("VaadinAgentTools.java"));
        int line = 1;
        for (byte b : src) {
            check(b >= 0, "VaadinAgentTools.java has a non-ASCII byte on line %d", line);
            if (b == '\n') {
                line++;
            }
        }
    }
}
