package tools

import (
	"archive/zip"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/vaadin/agent-plugin/internal/tool"
)

// CreateProject extracts a Vaadin project skeleton from start.vaadin.com into a
// directory. It mirrors the unpacking half of create-vaadin (`npm init vaadin`):
// unzip with the top-level directory stripped. The download itself is left to
// the caller (the vaadin-create-project skill runs curl), so the CLI never
// touches the network.
var CreateProject = tool.Descriptor{
	Name:    "create-project",
	Summary: "Extract a Vaadin project skeleton from start.vaadin.com.",
	Usage: `vaadin-agent-tools create-project <targetDir> --skeleton=<file.zip> [flags]

Extracts a Vaadin project skeleton, downloaded beforehand from
https://start.vaadin.com/skeleton, into <targetDir>, without the archive's
top-level directory. Mirrors ` + "`npm init vaadin`" + ` (create-vaadin). This tool
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
     without --overwrite)`,
	Run: runCreateProject,
}

// createReport is the typed result. The json-tagged fields form the JSON
// payload; OK/UsageError are CLI control fields.
type createReport struct {
	OK         bool   `json:"-"`
	UsageError string `json:"-"`

	Directory string `json:"directory"`
	Skeleton  string `json:"skeleton"`
	Files     int    `json:"files"`
	Error     string `json:"error,omitempty"`
}

func runCreateProject(args tool.Args) tool.Result {
	r := createProject(args)
	if r.UsageError != "" {
		return tool.Result{UsageError: r.UsageError}
	}
	return tool.Result{OK: r.OK, Payload: r, Human: renderCreateHuman(r)}
}

func createProject(args tool.Args) createReport {
	positionals, flags := splitFlags(args.Positionals)
	if len(positionals) == 0 {
		return createReport{UsageError: "Missing target directory. Usage: create-project <targetDir> --skeleton=<file.zip>"}
	}
	skeleton := flags["skeleton"]
	if skeleton == "" || skeleton == "true" {
		return createReport{UsageError: "Missing --skeleton=<file.zip>. Download it from https://start.vaadin.com/skeleton first."}
	}

	target := positionals[0]
	if !filepath.IsAbs(target) {
		target = filepath.Join(args.Cwd, target)
	}
	if !filepath.IsAbs(skeleton) {
		skeleton = filepath.Join(args.Cwd, skeleton)
	}
	overwrite := isSet(flags, "overwrite")

	// Target directory precondition (mirrors create-vaadin's overwrite handling).
	var existing []os.DirEntry
	if info, err := os.Stat(target); err == nil {
		if !info.IsDir() {
			return createReport{UsageError: "Target exists and is not a directory: " + target}
		}
		existing, _ = os.ReadDir(target)
		if len(existing) > 0 && !overwrite {
			return createReport{UsageError: "Target directory already exists and is not empty: " + target + " (pass --overwrite to replace its contents)"}
		}
	}

	base := createReport{Directory: target, Skeleton: skeleton}

	// Open the archive before clearing anything, so a failed download (an
	// HTML error page, an empty file) leaves an existing target untouched.
	zr, err := zip.OpenReader(skeleton)
	if err != nil {
		base.Error = "could not read the skeleton (check that the download succeeded): " + err.Error()
		return base
	}
	defer zr.Close()

	for _, e := range existing {
		if err := os.RemoveAll(filepath.Join(target, e.Name())); err != nil {
			base.Error = "could not clear target directory: " + err.Error()
			return base
		}
	}
	if err := os.MkdirAll(target, 0o755); err != nil {
		base.Error = "could not create target directory: " + err.Error()
		return base
	}
	count, err := extractZip(&zr.Reader, target)
	if err != nil {
		base.Error = "extraction failed: " + err.Error()
		return base
	}

	base.OK = true
	base.Files = count
	return base
}

// --- helpers ----------------------------------------------------------------

// stripFirst removes the leading path component (the archive's top-level
// directory), matching create-vaadin's decompress `strip: 1`. Returns "" when
// nothing remains (e.g. the top-level directory entry itself).
func stripFirst(name string) string {
	name = strings.TrimPrefix(name, "./")
	i := strings.IndexByte(name, '/')
	if i < 0 {
		return ""
	}
	return name[i+1:]
}

// extractZip writes the archive into target with the top-level directory
// stripped, and returns the number of files written. It rejects entries that
// would escape target (zip-slip) and preserves file modes (so mvnw stays
// executable) and empty files (so an empty theme styles.css survives).
func extractZip(zr *zip.Reader, target string) (int, error) {
	absTarget, err := filepath.Abs(target)
	if err != nil {
		return 0, err
	}

	count := 0
	for _, f := range zr.File {
		rel := stripFirst(f.Name)
		if rel == "" {
			continue
		}
		dest := filepath.Join(target, filepath.FromSlash(rel))

		absDest, err := filepath.Abs(dest)
		if err != nil {
			return count, err
		}
		if absDest != absTarget && !strings.HasPrefix(absDest, absTarget+string(os.PathSeparator)) {
			return count, fmt.Errorf("unsafe path in archive: %s", f.Name)
		}

		if f.FileInfo().IsDir() {
			if err := os.MkdirAll(dest, 0o755); err != nil {
				return count, err
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
			return count, err
		}
		if err := writeZipFile(f, dest); err != nil {
			return count, err
		}
		count++
	}
	return count, nil
}

func writeZipFile(f *zip.File, dest string) error {
	rc, err := f.Open()
	if err != nil {
		return err
	}
	defer rc.Close()

	mode := f.Mode().Perm()
	if mode == 0 {
		mode = 0o644
	}
	out, err := os.OpenFile(dest, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, mode)
	if err != nil {
		return err
	}
	defer out.Close()

	_, err = io.Copy(out, rc)
	return err
}

// splitFlags separates "--key=value" / "--flag" tokens from positional
// arguments. Bare flags map to "true".
func splitFlags(args []string) (positionals []string, flags map[string]string) {
	flags = map[string]string{}
	for _, a := range args {
		if strings.HasPrefix(a, "--") {
			kv := strings.TrimPrefix(a, "--")
			if i := strings.IndexByte(kv, '='); i >= 0 {
				flags[kv[:i]] = kv[i+1:]
			} else {
				flags[kv] = "true"
			}
			continue
		}
		positionals = append(positionals, a)
	}
	return positionals, flags
}

func isSet(flags map[string]string, key string) bool {
	v, ok := flags[key]
	return ok && v != "false"
}

func renderCreateHuman(r createReport) string {
	if r.Error != "" {
		return "# create-project\n\n✗ Failed to create project: " + r.Error +
			"\n  Skeleton: " + r.Skeleton
	}
	var b strings.Builder
	fmt.Fprintf(&b, "# create-project\n\n")
	fmt.Fprintf(&b, "✓ Project created in %s\n", r.Directory)
	fmt.Fprintf(&b, "  files written: %d\n\n", r.Files)
	fmt.Fprintf(&b, "Next: cd %s && mvn", r.Directory)
	return b.String()
}
