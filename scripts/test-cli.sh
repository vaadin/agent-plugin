#!/bin/sh
# Test the vaadin-agent-tools CLI: compile plugin/cli/VaadinAgentTools.java with
# test/VaadinAgentToolsTest.java and run the tests, then run the launcher the
# way the skills and the hook do.
#
#   sh scripts/test-cli.sh [test-name-filter]
#
# Uses the javac and java in JAVA_HOME, or else on the PATH. CI runs this with
# JDK 17, the oldest the CLI supports, so the source can't drift past it.
set -e
cd "$(dirname "$0")/.." # repo root

if [ -n "$JAVA_HOME" ]; then
  jdk="$JAVA_HOME"
  # Under Git Bash, JAVA_HOME is a Windows path, whose drive colon would split
  # the PATH entry.
  if command -v cygpath >/dev/null 2>&1; then jdk="$(cygpath -u "$jdk")"; fi
  PATH="$jdk/bin:$PATH"
fi
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

echo "== Unit tests ($(javac -version 2>&1))"
# -Werror: the launcher compiles the CLI on every run, and a warning would be
# printed to every caller.
javac -Xlint:all -Werror -encoding UTF-8 -d "$OUT/classes" plugin/cli/VaadinAgentTools.java
javac -encoding UTF-8 -cp "$OUT/classes" -d "$OUT/classes" test/VaadinAgentToolsTest.java
java -cp "$OUT/classes" VaadinAgentToolsTest "$@"
[ $# -eq 0 ] || exit 0

echo
echo "== Launcher"
CLI=plugin/cli/vaadin-agent-tools
failures=0
fail() {
  echo "FAIL  $1"
  failures=$((failures + 1))
}
pass() {
  echo "ok    $1"
}

# expect_exit <code> <description> <command...>: runs the command, keeping its
# stdout and stderr in $OUT/stdout and $OUT/stderr.
expect_exit() {
  want="$1" what="$2"
  shift 2
  set +e
  "$@" >"$OUT/stdout" 2>"$OUT/stderr"
  got=$?
  set -e
  if [ "$got" = "$want" ]; then pass "$what"; else fail "$what: exit code $got, want $want"; cat "$OUT/stderr"; fi
}

expect_exit 0 "a clean project passes" sh "$CLI" check-theme-mixing test/fixtures/clean --json
grep -q '"ok": true' "$OUT/stdout" || fail "a clean project prints ok: true"
expect_exit 1 "a mixed project fails" sh "$CLI" check-theme-mixing test/fixtures/mixed
expect_exit 2 "a missing project is a usage error" sh "$CLI" check-aura-usage test/fixtures/does-not-exist

# Without Java, a skill gets a clear message and exit code 3, and the hook stays
# silent. JAVA_HOME takes precedence over the PATH, as for Maven and Gradle.
expect_exit 3 "a missing JDK is reported" env JAVA_HOME="$OUT/no-jdk" sh "$CLI" check-theme-mixing .
grep -q 'JAVA_HOME' "$OUT/stderr" || fail "the missing-JDK message names JAVA_HOME"
STYLING='{"tool_input":{"file_path":"/p/App.java","new_string":"btn.getStyle().set(\"color\", \"red\");"}}'
PLAIN='{"tool_input":{"file_path":"/p/App.java","new_string":"int total = a + b;"}}'
printf '%s' "$STYLING" >"$OUT/styling.json"
printf '%s' "$PLAIN" >"$OUT/plain.json"
expect_exit 0 "the hook is silent without a JDK" \
  env JAVA_HOME="$OUT/no-jdk" sh "$CLI" hook post-tool-use <"$OUT/styling.json"
[ ! -s "$OUT/stdout" ] && [ ! -s "$OUT/stderr" ] || fail "the hook printed something without a JDK"

# fake_jdk <dir> <version> <modules>: a stand-in JDK whose release file says
# what is given, and whose java only records that it was asked to run the CLI.
fake_jdk() {
  mkdir -p "$1/bin"
  printf 'JAVA_VERSION="%s"\nMODULES="%s"\n' "$2" "$3" >"$1/release"
  printf '#!/bin/sh\ncat >/dev/null\ncase "$*" in *VaadinAgentTools.java*) touch "%s" ;; esac\n' \
    "$OUT/java-ran" >"$1/bin/java"
  chmod +x "$1/bin/java"
  rm -f "$OUT/java-ran"
}

# The pre-filter.
fake_jdk "$OUT/fake-jdk" 17.0.2 "java.base java.compiler jdk.compiler"
expect_exit 0 "the hook skips Java for a non-styling edit" \
  env JAVA_HOME="$OUT/fake-jdk" sh "$CLI" hook post-tool-use <"$OUT/plain.json"
[ ! -e "$OUT/java-ran" ] || fail "the pre-filter let a non-styling edit through"
expect_exit 0 "the hook starts Java for a styling edit" \
  env JAVA_HOME="$OUT/fake-jdk" sh "$CLI" hook post-tool-use <"$OUT/styling.json"
[ -e "$OUT/java-ran" ] || fail "the pre-filter dropped a styling edit"

# A Java that can't run the CLI: the skills get exit code 3 and a message
# instead of compiler errors or a stack trace, and the hook stays silent.
# expect_unsupported <java home> <description> <message text>
expect_unsupported() {
  rm -f "$OUT/java-ran"
  expect_exit 3 "$2 is reported" env JAVA_HOME="$1" sh "$CLI" check-theme-mixing .
  grep -q "$3" "$OUT/stderr" || fail "the message for $2 lacks \"$3\": $(cat "$OUT/stderr")"
  ! grep -q 'Exception' "$OUT/stderr" || fail "$2 printed a stack trace"
  expect_exit 0 "the hook is silent with $2" \
    env JAVA_HOME="$1" sh "$CLI" hook post-tool-use <"$OUT/styling.json"
  [ ! -s "$OUT/stdout" ] && [ ! -s "$OUT/stderr" ] || fail "the hook printed something with $2"
  [ ! -e "$OUT/java-ran" ] || fail "the launcher ran the CLI with $2"
}
fake_jdk "$OUT/jdk-11" 11.0.2 "java.base java.compiler jdk.compiler"
expect_unsupported "$OUT/jdk-11" "a JDK 11 (from its release file)" "is Java 11"
if [ -n "$OLD_JDK" ]; then
  expect_unsupported "$OLD_JDK" "a real JDK older than 17" "needs Java 17 or later"
fi
# A runtime without the compiler, like a JRE: one made with jlink, both with
# its release file and, so that java is asked instead, without it.
jlink --add-modules java.base --output "$OUT/jre"
expect_unsupported "$OUT/jre" "a runtime without the compiler" "jdk.compiler"
rm "$OUT/jre/release"
expect_unsupported "$OUT/jre" "a runtime without the compiler or a release file" "jdk.compiler"

case "$(uname -s)" in
  MINGW* | MSYS* | CYGWIN*) windows=1 ;;
  *) windows="" ;;
esac

if [ -z "$windows" ]; then
  # Without JAVA_HOME, the java on the PATH leads to its JDK through links,
  # as /usr/bin/java -> /etc/alternatives/java -> ... does on Linux.
  mkdir -p "$OUT/linked"
  ln -s "$(command -v java)" "$OUT/linked/java"
  expect_exit 0 "java on the PATH is followed through links" \
    env -u JAVA_HOME PATH="$OUT/linked:$PATH" sh "$CLI" check-theme-mixing test/fixtures/clean

  # create-project gives each file the archive's permissions minus the umask,
  # as creating it with them does: with umask 017, 0755 becomes 0740.
  if command -v zip >/dev/null 2>&1; then
    mkdir -p "$OUT/skeleton/my-app"
    printf '#!/bin/sh\n' >"$OUT/skeleton/my-app/mvnw"
    chmod 755 "$OUT/skeleton/my-app/mvnw"
    (cd "$OUT/skeleton" && zip -qr ../skeleton.zip my-app)
    expect_exit 0 "create-project extracts a skeleton" \
      sh -c 'umask 017 && exec sh "$@"' sh "$CLI" create-project "$OUT/created" --skeleton="$OUT/skeleton.zip"
    mode="$(ls -l "$OUT/created/mvnw" | cut -c1-10)"
    [ "$mode" = "-rwxr-----" ] || fail "with umask 017, a 0755 entry became $mode, not -rwxr-----"
    mode="$(ls -ld "$OUT/created" | cut -c1-10)"
    [ "$mode" = "drwxr-----" ] || fail "with umask 017, the project directory became $mode, not drwxr-----"
  else
    echo "skip  create-project permissions (no zip command)"
  fi
fi

# And the real thing: a styling edit in a project that mixes themes.
printf '<project/>' >test/fixtures/mixed/pom.xml
trap 'rm -rf "$OUT" test/fixtures/mixed/pom.xml' EXIT
# Java reads this path, so under Git Bash it has to be a Windows one (pwd -W).
printf '{"tool_input":{"file_path":"%s","new_string":"addClassName(\\"x\\")"}}' \
  "$(pwd -W 2>/dev/null || pwd)/test/fixtures/mixed/src/main/java/com/example/Application.java" >"$OUT/mixed.json"
expect_exit 0 "the hook reports a mixed project" sh "$CLI" hook post-tool-use <"$OUT/mixed.json"
grep -q 'MULTIPLE_BASE_THEMES' "$OUT/stdout" || fail "the hook output lacks MULTIPLE_BASE_THEMES"
# Evidence paths are relative to the project the hook found: the fixture, not
# the repository (which mixes themes too).
! grep -q 'fixtures' "$OUT/stdout" || fail "the hook scanned more than the edited file's project"
rm -f test/fixtures/mixed/pom.xml

if [ "$failures" -ne 0 ]; then
  echo "$failures launcher checks failed"
  exit 1
fi
echo "launcher checks passed"
