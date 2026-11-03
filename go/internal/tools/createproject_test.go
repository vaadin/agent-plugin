package tools

import (
	"archive/zip"
	"bytes"
	"os"
	"path/filepath"
	"testing"

	"github.com/vaadin/agent-plugin/internal/tool"
)

func TestStripFirst(t *testing.T) {
	cases := map[string]string{
		"proj/pom.xml":           "pom.xml",
		"proj/src/main/App.java": "src/main/App.java",
		"proj/":                  "",
		"toplevel":               "",
		"./proj/pom.xml":         "pom.xml",
	}
	for in, want := range cases {
		if got := stripFirst(in); got != want {
			t.Errorf("stripFirst(%q) = %q, want %q", in, got, want)
		}
	}
}

// makeZip builds an in-memory zip from name->content. A name ending in "/" is a
// directory entry; an empty content string yields a zero-length file.
func makeZip(t *testing.T, files map[string]string) []byte {
	t.Helper()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for name, content := range files {
		if len(name) > 0 && name[len(name)-1] == '/' {
			if _, err := zw.Create(name); err != nil {
				t.Fatal(err)
			}
			continue
		}
		w, err := zw.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := w.Write([]byte(content)); err != nil {
			t.Fatal(err)
		}
	}
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func openZip(t *testing.T, data []byte) *zip.Reader {
	t.Helper()
	zr, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		t.Fatal(err)
	}
	return zr
}

// writeFile writes data to name in a fresh temp directory and returns its path.
func writeFile(t *testing.T, name string, data []byte) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), name)
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestExtractZipStripsTopDirAndKeepsEmptyFiles(t *testing.T) {
	data := makeZip(t, map[string]string{
		"my-project/":                             "",
		"my-project/pom.xml":                      "<project/>",
		"my-project/src/main/java/App.java":       "class App {}",
		"my-project/frontend/themes/x/styles.css": "", // empty theme file must survive
	})

	dir := t.TempDir()
	count, err := extractZip(openZip(t, data), dir)
	if err != nil {
		t.Fatalf("extractZip: %v", err)
	}
	if count != 3 {
		t.Fatalf("files written = %d, want 3", count)
	}
	// Top-level "my-project" directory was stripped.
	if _, err := os.Stat(filepath.Join(dir, "my-project")); !os.IsNotExist(err) {
		t.Error("expected top-level directory to be stripped")
	}
	// Files landed at the stripped paths.
	for _, p := range []string{"pom.xml", "src/main/java/App.java", "frontend/themes/x/styles.css"} {
		if _, err := os.Stat(filepath.Join(dir, filepath.FromSlash(p))); err != nil {
			t.Errorf("expected %s to exist: %v", p, err)
		}
	}
	// The empty styles.css must exist and be zero length.
	info, err := os.Stat(filepath.Join(dir, "frontend/themes/x/styles.css"))
	if err != nil {
		t.Fatalf("styles.css missing: %v", err)
	}
	if info.Size() != 0 {
		t.Errorf("styles.css size = %d, want 0", info.Size())
	}
}

func TestExtractZipRejectsZipSlip(t *testing.T) {
	data := makeZip(t, map[string]string{
		"proj/../../escape.txt": "pwned",
	})
	dir := t.TempDir()
	if _, err := extractZip(openZip(t, data), dir); err == nil {
		t.Fatal("expected zip-slip path to be rejected")
	}
}

func TestCreateProjectUsageErrors(t *testing.T) {
	skeleton := writeFile(t, "skeleton.zip", makeZip(t, map[string]string{"p/pom.xml": "<project/>"}))

	// No target directory.
	if r := createProject(tool.Args{Positionals: []string{"--skeleton=" + skeleton}, Cwd: t.TempDir()}); r.UsageError == "" {
		t.Error("expected usage error when target directory is missing")
	}

	// No --skeleton.
	if r := createProject(tool.Args{Positionals: []string{"newproj"}, Cwd: t.TempDir()}); r.UsageError == "" {
		t.Error("expected usage error when --skeleton is missing")
	}

	// Existing, non-empty target without --overwrite.
	dir := t.TempDir()
	existing := filepath.Join(dir, "proj")
	if err := os.MkdirAll(existing, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(existing, "keep.txt"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	if r := createProject(tool.Args{Positionals: []string{"proj", "--skeleton=" + skeleton}, Cwd: dir}); r.UsageError == "" {
		t.Error("expected usage error for a non-empty target without --overwrite")
	}
}

func TestCreateProjectExtractsSkeleton(t *testing.T) {
	skeleton := writeFile(t, "skeleton.zip", makeZip(t, map[string]string{
		"my-app/pom.xml":                "<project/>",
		"my-app/src/main/java/App.java": "class App {}",
	}))
	dir := t.TempDir()
	r := createProject(tool.Args{Positionals: []string{"my-app", "--skeleton=" + skeleton}, Cwd: dir})
	if !r.OK || r.UsageError != "" {
		t.Fatalf("createProject failed: %+v", r)
	}
	if r.Files != 2 {
		t.Errorf("files written = %d, want 2", r.Files)
	}
	if _, err := os.Stat(filepath.Join(dir, "my-app", "pom.xml")); err != nil {
		t.Errorf("expected my-app/pom.xml: %v", err)
	}
}

func TestCreateProjectKeepsTargetWhenSkeletonIsInvalid(t *testing.T) {
	// What curl saves when the server answers with an error page.
	skeleton := writeFile(t, "skeleton.zip", []byte("<html>Service unavailable</html>"))
	dir := t.TempDir()
	keep := filepath.Join(dir, "proj", "keep.txt")
	if err := os.MkdirAll(filepath.Dir(keep), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(keep, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}

	r := createProject(tool.Args{Positionals: []string{"proj", "--skeleton=" + skeleton, "--overwrite"}, Cwd: dir})
	if r.OK || r.Error == "" {
		t.Fatalf("expected an error for an invalid skeleton, got %+v", r)
	}
	if _, err := os.Stat(keep); err != nil {
		t.Errorf("an invalid skeleton must not clear the target: %v", err)
	}
}
