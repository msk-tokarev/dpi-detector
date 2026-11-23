// Additive file for the DPI Detector Android app (not part of upstream).
// Exposes the report DTO converters so the mobile bridge can stream
// per-item results while still producing an upstream-compatible ALL report.

package fullcheck

import (
	"github.com/hyperion-cs/dpi-checkers/ru/dpi-ch/checkers/dns"
	"github.com/hyperion-cs/dpi-checkers/ru/dpi-ch/checkers/webhost"
	"github.com/hyperion-cs/dpi-checkers/ru/dpi-ch/checkers/whoami"
)

// ReportVersion is the ALL report format version (compatible with `dpich --import-all`).
const ReportVersion = version

func NewReport() *ReportDto { return &ReportDto{Version: version} }

func ToWhoamiDto(r whoami.Result, err error) WhoamiDto { return whoamiDto(r, err) }

func ToCidrWhitelistDto(err error) CidrWhitelistDto { return cidrWhitelistDto(err) }

func ToDnsLeakDto(items []dns.LeakResult) DnsLeakDto { return dnsLeakDto(items) }

func ToDnsReportDto(items []dns.Verdict) DnsReportDto { return dnsReportDto(items) }

func ToWebhostItemDto(o webhost.Item) WebhostItemDto { return webhostItemDto(o) }

func DnsStateOf(items []dns.Verdict) State { return dnsState(items) }

func ErrorStateOf(err error) State { return errorState(err) }
