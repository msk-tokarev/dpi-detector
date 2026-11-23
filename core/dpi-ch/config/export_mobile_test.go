// Author: by Ivan Tokarev
package config

import (
	"strings"
	"testing"
)

func TestDefaultYamlNotEmpty(t *testing.T) {
	raw := DefaultYaml()
	if !strings.Contains(raw, "checkers:") || !strings.Contains(raw, "webhost:") {
		t.Fatalf("DefaultYaml missing expected keys")
	}
}
