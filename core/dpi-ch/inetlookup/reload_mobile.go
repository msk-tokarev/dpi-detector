// Additive file for the DPI Detector Android app (not part of upstream).
package inetlookup

import (
	"fmt"

	"github.com/hyperion-cs/dpi-checkers/ru/dpi-ch/config"
)

// ReloadDefault reloads GeoLite CSV databases from disk and atomically replaces the default lookup.
func ReloadDefault() error {
	cfg := config.Get().InetlookupGeolitecsv
	lookup, err := loadGeoliteCsv(GeoliteCsvOpt{
		CidrAsPath:           cfg.CidrAs,
		CidrCountryPath:      cfg.CidrCountry,
		GeonameidCountryPath: cfg.GeonameidCountry,
	})
	if err != nil {
		return fmt.Errorf("inetlookup/geolite: %w", err)
	}
	mu.Lock()
	def = lookup
	inited.Store(true)
	mu.Unlock()
	return nil
}
