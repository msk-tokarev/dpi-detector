// Author: by Ivan Tokarev
package inetlookup

import (
	"net/netip"
	"os"
	"path/filepath"
	"testing"

	"github.com/hyperion-cs/dpi-checkers/ru/dpi-ch/config"
)

func TestReloadDefaultSuccessAndError(t *testing.T) {
	tmp := t.TempDir()
	cfgPath := filepath.Join(tmp, "config.yaml")
	if err := os.WriteFile(cfgPath, []byte("updater:\n  root-dir: data\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := config.Load(cfgPath); err != nil {
		t.Fatal(err)
	}

	// Missing files -> error
	if err := ReloadDefault(); err == nil {
		t.Fatal("expected error when CSV files do not exist")
	}

	asnPath, countryPath, namesPath := config.Get().GeoliteOutputPaths()
	for _, p := range []string{asnPath, countryPath, namesPath} {
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
	}
	_ = os.WriteFile(asnPath, []byte("network,autonomous_system_number,autonomous_system_organization\n10.20.0.0/16,64512,KarandashNet\n"), 0o644)
	_ = os.WriteFile(countryPath, []byte("network,geoname_id,registered_country_geoname_id,represented_country_geoname_id\n10.20.0.0/16,1,1,0\n"), 0o644)
	_ = os.WriteFile(namesPath, []byte("geoname_id,locale_code,continent_code,continent_name,country_iso_code,country_name\n1,en,EU,Europe,RU,Russia\n"), 0o644)

	if err := ReloadDefault(); err != nil {
		t.Fatalf("ReloadDefault failed: %v", err)
	}
	if !Inited() {
		t.Fatal("expected Inited() == true after ReloadDefault")
	}
	info := Default().IpInfo(netip.MustParseAddr("10.20.30.40"))
	if info.Org != "KarandashNet" || info.CountryIso != "RU" {
		t.Fatalf("unexpected IpInfo after reload: %+v", info)
	}
}
