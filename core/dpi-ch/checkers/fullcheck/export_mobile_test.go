// Author: by Ivan Tokarev
package fullcheck

import (
	"context"
	"errors"
	"net/netip"
	"testing"
	"time"

	"github.com/hyperion-cs/dpi-checkers/ru/dpi-ch/checkers/cidrwhitelist"
	"github.com/hyperion-cs/dpi-checkers/ru/dpi-ch/checkers/dns"
	"github.com/hyperion-cs/dpi-checkers/ru/dpi-ch/checkers/webhost"
	"github.com/hyperion-cs/dpi-checkers/ru/dpi-ch/checkers/whoami"
	"github.com/hyperion-cs/dpi-checkers/ru/dpi-ch/inetlookup"
	"github.com/hyperion-cs/dpi-checkers/ru/dpi-ch/inetutil"
)

func TestMobileExports(t *testing.T) {
	r := NewReport()
	if r.Version != ReportVersion {
		t.Fatalf("expected version %d, got %d", ReportVersion, r.Version)
	}

	wOk := ToWhoamiDto(whoami.Result{Ip: "1.1.1.1", Ttlb: 25 * time.Millisecond}, nil)
	if wOk.Status.Code != "OK" || wOk.TtlbMs != 25 {
		t.Fatalf("unexpected whoami ok: %+v", wOk)
	}
	wErr := ToWhoamiDto(whoami.Result{}, errors.New("boom"))
	if wErr.Status.Code != "ERR" {
		t.Fatalf("unexpected whoami err: %+v", wErr)
	}

	if ToCidrWhitelistDto(nil).Status.Code != "NOT_DETECTED" {
		t.Fatal("expected NOT_DETECTED")
	}
	if ToCidrWhitelistDto(cidrwhitelist.ErrDetected).Status.Code != "DETECTED" {
		t.Fatal("expected DETECTED")
	}
	if ToCidrWhitelistDto(cidrwhitelist.ErrNoInetAccess).Status.Code != "NO_INTERNET_ACCESS" {
		t.Fatal("expected NO_INTERNET_ACCESS")
	}

	leak := ToDnsLeakDto([]dns.LeakResult{{Items: []inetlookup.IpInfoStrings{{Ip: "8.8.8.8"}}}})
	if leak.Status.Code != "OK" || len(leak.Items) != 1 {
		t.Fatalf("unexpected leak dto: %+v", leak)
	}

	verdicts := []dns.Verdict{
		{Provider: "CF", Verdict: nil},
		{Provider: "Slow", Verdict: dns.ErrProviderTimeout},
	}
	rep := ToDnsReportDto(verdicts)
	if len(rep.Providers) != 2 {
		t.Fatalf("unexpected dns report: %+v", rep)
	}
	if DnsStateOf(verdicts) != StateTimeout {
		t.Fatal("expected StateTimeout")
	}
	if DnsStateOf([]dns.Verdict{{Provider: "CF", Verdict: nil}}) != StateOk {
		t.Fatal("expected StateOk")
	}

	wh := ToWebhostItemDto(webhost.Item{
		Group: "Test",
		Result: webhost.SingleResult{
			IpInfo:  inetlookup.IpInfo{Ip: netip.MustParseAddr("1.1.1.1"), Asn: 13335, Org: "CF", CountryIso: "US"},
			Alive:   nil,
			Tcp1620: inetutil.ErrTcpReadTimeout,
			Siberian: webhost.ErrSkip,
		},
	})
	if wh.Alive.Code != "OK" || wh.Tcp1620.Code != "DETECTED" || wh.Siberian.Code != "SKIP" {
		t.Fatalf("unexpected webhost dto: %+v", wh)
	}

	if ErrorStateOf(nil) != StateOk ||
		ErrorStateOf(context.DeadlineExceeded) != StateTimeout ||
		ErrorStateOf(errors.New("fail")) != StateError {
		t.Fatal("unexpected ErrorStateOf mapping")
	}
}
