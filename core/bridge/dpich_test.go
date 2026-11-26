// Author: by Ivan Tokarev
package dpich

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/hyperion-cs/dpi-checkers/ru/dpi-ch/checkers/fullcheck"
	"golang.org/x/net/dns/dnsmessage"
)

type testListener struct {
	mu     sync.Mutex
	events []map[string]any
	doneCh chan struct{}
	once   sync.Once
}

func newTestListener() *testListener {
	return &testListener{doneCh: make(chan struct{})}
}

func (l *testListener) OnEvent(raw string) {
	var m map[string]any
	if err := json.Unmarshal([]byte(raw), &m); err != nil {
		return
	}
	l.mu.Lock()
	l.events = append(l.events, m)
	l.mu.Unlock()
	if m["type"] == "done" {
		l.once.Do(func() { close(l.doneCh) })
	}
}

func (l *testListener) types() map[string]int {
	l.mu.Lock()
	defer l.mu.Unlock()
	out := map[string]int{}
	for _, ev := range l.events {
		if tp, ok := ev["type"].(string); ok {
			out[tp]++
		}
	}
	return out
}

func selfSignedCert(t *testing.T, dnsNames []string) tls.Certificate {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: dnsNames[0]},
		DNSNames:     dnsNames,
		IPAddresses:  []net.IP{net.ParseIP("127.0.0.1"), net.ParseIP("::1")},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}
}

// startMockHttpsHost spins up a local TLS server simulating normal or TCP-16-20 DPI behavior.
func startMockHttpsHost(t *testing.T, simulateTcp1620 bool) (int, func()) {
	t.Helper()
	cert := selfSignedCert(t, []string{"mock.local", "example.test"})
	ln, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{
		Certificates: []tls.Certificate{cert},
		MinVersion:   tls.VersionTLS12,
	})
	if err != nil {
		t.Fatal(err)
	}
	srv := &http.Server{
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Method == http.MethodPost && simulateTcp1620 {
				time.Sleep(3 * time.Second)
				return
			}
			_, _ = io.Copy(io.Discard, r.Body)
			w.Header().Set("Content-Type", "text/plain")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("ok"))
		}),
	}
	go srv.Serve(ln)
	port := ln.Addr().(*net.TCPAddr).Port
	return port, func() {
		_ = srv.Close()
		_ = ln.Close()
	}
}

// startMockUdpDns answers every A query with 1.1.1.1 (so subnetfilter org("cloudflare") matches).
func startMockUdpDns(t *testing.T) (string, func()) {
	t.Helper()
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		buf := make([]byte, 512)
		for {
			n, addr, rerr := pc.ReadFrom(buf)
			if rerr != nil {
				return
			}
			var msg dnsmessage.Message
			if err := msg.Unpack(buf[:n]); err != nil || len(msg.Questions) == 0 {
				continue
			}
			q := msg.Questions[0]
			resp := dnsmessage.Message{
				Header: dnsmessage.Header{
					ID:                 msg.ID,
					Response:           true,
					RecursionDesired:   msg.RecursionDesired,
					RecursionAvailable: true,
				},
				Questions: msg.Questions,
				Answers: []dnsmessage.Resource{
					{
						Header: dnsmessage.ResourceHeader{
							Name:  q.Name,
							Type:  dnsmessage.TypeA,
							Class: dnsmessage.ClassINET,
							TTL:   60,
						},
						Body: &dnsmessage.AResource{A: [4]byte{1, 1, 1, 1}},
					},
				},
			}
			if out, err := resp.Pack(); err == nil {
				_, _ = pc.WriteTo(out, addr)
			}
		}
	}()
	return pc.LocalAddr().String(), func() { _ = pc.Close() }
}

func writeMinimalGeo(t *testing.T) {
	t.Helper()
	paths := strings.Split(strings.TrimSpace(GeolitePaths()), "\n")
	if len(paths) != 3 {
		t.Fatalf("expected 3 geolite paths, got %d", len(paths))
	}
	for _, p := range paths {
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
	}
	_ = os.WriteFile(paths[0], []byte("network,autonomous_system_number,autonomous_system_organization\n127.0.0.0/8,64512,MockLabISP\n1.1.1.0/24,13335,Cloudflare\n"), 0o644)
	_ = os.WriteFile(paths[1], []byte("network,geoname_id,registered_country_geoname_id,represented_country_geoname_id\n127.0.0.0/8,2017370,2017370,0\n1.1.1.0/24,6252001,6252001,0\n"), 0o644)
	_ = os.WriteFile(paths[2], []byte("geoname_id,locale_code,continent_code,continent_name,country_iso_code,country_name\n2017370,en,EU,Europe,RU,Russia\n6252001,en,NA,North America,US,United States\n"), 0o644)
}

func setupBridgeWithMinimalGeo(t *testing.T, extraYaml string) string {
	t.Helper()
	tmp := t.TempDir()
	filesDir := filepath.Join(tmp, "files")
	cacheDir := filepath.Join(tmp, "cache")

	if err := Setup(filesDir, cacheDir, extraYaml); err != nil {
		t.Fatalf("Setup failed: %v", err)
	}
	writeMinimalGeo(t)
	MarkBundledGeoTimestamp(time.Now().Unix())
	if err := PrepareGeo(false, false, nil); err != nil {
		t.Fatalf("PrepareGeo offline failed: %v", err)
	}
	return tmp
}

func TestGuardsBeforeInitAndDebugMode(t *testing.T) {
	// Reset inited flag temporarily to exercise pre-init guard paths
	prevInited := inited
	inited = false
	MarkBundledGeoTimestamp(12345)
	if err := PrepareGeo(false, false, nil); err == nil {
		t.Fatal("PrepareGeo must fail when not initialized")
	}
	if _, err := CheckCustomHost("1.1.1.1", "", 443); err == nil {
		t.Fatal("CheckCustomHost must fail when not initialized")
	}
	if err := Start("", nil); err == nil {
		t.Fatal("Start must fail when not initialized")
	}
	inited = prevInited

	// Test Setup with debug: true (creates & rotates debug.log) and invalid yaml
	tmp := t.TempDir()
	if err := Setup(filepath.Join(tmp, "f"), filepath.Join(tmp, "c"), "debug: true\n"); err != nil {
		t.Fatalf("Setup debug=true failed: %v", err)
	}
	// Second Setup closes previous logFile cleanly
	if err := Setup(filepath.Join(tmp, "f"), filepath.Join(tmp, "c"), "debug: false\n"); err != nil {
		t.Fatalf("Setup debug=false failed: %v", err)
	}
	if err := Setup(filepath.Join(tmp, "f"), filepath.Join(tmp, "c"), ":::broken: [yaml"); err == nil {
		t.Fatal("Setup must fail on malformed yaml")
	}

	// MarkBundledGeoTimestamp edge cases: <=0 and already existing file
	MarkBundledGeoTimestamp(0)
	MarkBundledGeoTimestamp(time.Now().Unix())
	MarkBundledGeoTimestamp(time.Now().Unix() + 100) // no-op since file exists
}

func TestConfigValidationAndIntrospection(t *testing.T) {
	setupBridgeWithMinimalGeo(t, "debug: false\n")

	if y := DefaultConfigYaml(); !strings.Contains(y, "checkers:") {
		t.Fatal("DefaultConfigYaml must contain checkers section")
	}
	if msg := ValidateConfig(""); msg != "" {
		t.Fatalf("empty config must be valid, got: %s", msg)
	}
	if msg := ValidateConfig("checkers:\n  webhost:\n    workers: 4\n"); msg != "" {
		t.Fatalf("valid yaml rejected: %s", msg)
	}
	if msg := ValidateConfig(":::invalid: [yaml"); msg == "" {
		t.Fatal("invalid yaml must return an error message")
	}

	var secs []map[string]any
	if err := json.Unmarshal([]byte(Sections()), &secs); err != nil || len(secs) < 4 {
		t.Fatalf("Sections invalid: err=%v len=%d", err, len(secs))
	}
	var dnsProvs []string
	if err := json.Unmarshal([]byte(DnsProviders()), &dnsProvs); err != nil || len(dnsProvs) < 5 {
		t.Fatalf("DnsProviders invalid: err=%v len=%d", err, len(dnsProvs))
	}
	if ReportsDir() == "" {
		t.Fatal("ReportsDir must not be empty")
	}

	var geo map[string]any
	if err := json.Unmarshal([]byte(GeoStatus()), &geo); err != nil {
		t.Fatal(err)
	}
	if geo["loaded"] != true {
		t.Fatalf("expected geo loaded=true, got %v", geo)
	}
}

func TestPrepareGeoOnlineFallback(t *testing.T) {
	// Point updater to an unreachable local repo with short timeout so online update fails
	// gracefully and falls back to local GeoLite CSV.
	yaml := `
updater:
  enabled: true
  timeout: 200ms
  geolite:
    owner: nonexistent-local-owner-000
    repo: nonexistent-local-repo-000
`
	setupBridgeWithMinimalGeo(t, yaml)
	l := newTestListener()
	if err := PrepareGeo(true, true, l); err != nil {
		t.Fatalf("PrepareGeo should fall back to local DB on network/404 error, got: %v", err)
	}
	if l.types()["log"] == 0 {
		t.Fatal("expected log events from PrepareGeo")
	}
}

func TestCheckCustomHostAllBranches(t *testing.T) {
	setupBridgeWithMinimalGeo(t, "checkers:\n  webhost:\n    tcp-conn-timeout: 1s\n    tls-handshake-timeout: 1s\n    tcp-read-timeout: 2s\n    tcp-write-timeout: 2s\n")

	if _, err := CheckCustomHost("   ", "", 443); err == nil {
		t.Fatal("expected error for empty target")
	}
	if _, err := CheckCustomHost("nonexistent.invalid.local.test", "", 0); err == nil {
		t.Fatal("expected DNS resolution error for nonexistent domain")
	}

	cleanPort, stopClean := startMockHttpsHost(t, false)
	defer stopClean()

	// IPv4 target with URL prefix + path + custom SNI
	rawClean, err := CheckCustomHost("https://127.0.0.1/some/path", "mock.local", int32(cleanPort))
	if err != nil {
		t.Fatalf("CheckCustomHost clean failed: %v", err)
	}
	var cleanDto fullcheck.WebhostItemDto
	if err := json.Unmarshal([]byte(rawClean), &cleanDto); err != nil {
		t.Fatal(err)
	}
	if cleanDto.Alive.Code != "OK" || cleanDto.Tcp1620.Code != "OK" {
		t.Fatalf("unexpected clean result: alive=%+v tcp1620=%+v", cleanDto.Alive, cleanDto.Tcp1620)
	}
	if cleanDto.Tls == nil || cleanDto.Tls.Cn != "mock.local" {
		t.Fatalf("expected cert CN=mock.local, got %+v", cleanDto.Tls)
	}

	// host:port extraction when port <= 0
	rawWithPort, err := CheckCustomHost(fmt.Sprintf("127.0.0.1:%d", cleanPort), "mock.local", 0)
	if err != nil {
		t.Fatalf("CheckCustomHost host:port failed: %v", err)
	}
	var portDto fullcheck.WebhostItemDto
	if err := json.Unmarshal([]byte(rawWithPort), &portDto); err != nil || portDto.Alive.Code != "OK" {
		t.Fatalf("unexpected host:port result: err=%v dto=%+v", err, portDto)
	}

	// IPv6 target ([::1]:port and raw ::1) if IPv6 loopback is available on runner
	if v6Ln, v6Err := tls.Listen("tcp6", "[::1]:0", &tls.Config{
		Certificates: []tls.Certificate{selfSignedCert(t, []string{"mock.local"})},
		MinVersion:   tls.VersionTLS12,
	}); v6Err == nil {
		v6Srv := &http.Server{
			Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				_, _ = io.Copy(io.Discard, r.Body)
				w.WriteHeader(http.StatusOK)
				_, _ = w.Write([]byte("ok6"))
			}),
		}
		go v6Srv.Serve(v6Ln)
		defer v6Srv.Close()
		defer v6Ln.Close()
		v6Port := v6Ln.Addr().(*net.TCPAddr).Port

		rawV6, err := CheckCustomHost(fmt.Sprintf("https://[::1]:%d/v6path", v6Port), "mock.local", 0)
		if err != nil {
			t.Fatalf("CheckCustomHost IPv6 bracketed failed: %v", err)
		}
		var v6Dto fullcheck.WebhostItemDto
		if err := json.Unmarshal([]byte(rawV6), &v6Dto); err != nil || v6Dto.Alive.Code != "OK" || v6Dto.IP != "::1" {
			t.Fatalf("unexpected IPv6 result: err=%v dto=%+v", err, v6Dto)
		}

		// Raw IPv6 without SNI (verifies RFC 3986 [::1] Host header branch)
		rawV6NoSni, err := CheckCustomHost("::1", "", int32(v6Port))
		if err != nil {
			t.Fatalf("CheckCustomHost raw ::1 failed: %v", err)
		}
		if err := json.Unmarshal([]byte(rawV6NoSni), &v6Dto); err != nil || v6Dto.IP != "::1" {
			t.Fatalf("unexpected raw ::1 result: err=%v dto=%+v", err, v6Dto)
		}
	}

	// Hostname resolution path ("localhost" -> 127.0.0.1)
	rawHost, err := CheckCustomHost("http://localhost/test", "", int32(cleanPort))
	if err != nil {
		t.Fatalf("CheckCustomHost localhost failed: %v", err)
	}
	var hostDto fullcheck.WebhostItemDto
	if err := json.Unmarshal([]byte(rawHost), &hostDto); err != nil {
		t.Fatal(err)
	}
	if hostDto.IP != "127.0.0.1" {
		t.Fatalf("expected resolved 127.0.0.1, got %s", hostDto.IP)
	}

	// Simulated TCP 16-20 (L4-25) blackhole mock host
	dpiPort, stopDpi := startMockHttpsHost(t, true)
	defer stopDpi()

	rawDpi, err := CheckCustomHost("127.0.0.1", "mock.local", int32(dpiPort))
	if err != nil {
		t.Fatalf("CheckCustomHost dpi mock failed: %v", err)
	}
	var dpiDto fullcheck.WebhostItemDto
	if err := json.Unmarshal([]byte(rawDpi), &dpiDto); err != nil {
		t.Fatal(err)
	}
	if dpiDto.Alive.Code != "OK" || dpiDto.Tcp1620.Code != "DETECTED" {
		t.Fatalf("expected Alive=OK & Tcp1620=DETECTED, got alive=%+v tcp1620=%+v", dpiDto.Alive, dpiDto.Tcp1620)
	}
}

func TestFullPipelineAllCheckersWithLocalMocks(t *testing.T) {
	// 1. Mock whoami Mail API server
	whoamiSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`(none)({"IpAddress":"1.1.1.1"})`))
	}))
	defer whoamiSrv.Close()

	// 2. Mock CIDR whitelisted endpoint (OK) & dead regular endpoint -> DETECTED
	wlSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer wlSrv.Close()
	deadLn, _ := net.Listen("tcp", "127.0.0.1:0")
	deadAddr := deadLn.Addr().String()
	_ = deadLn.Close()

	// 3. Mock HTTPS webhost target
	webPort, stopWeb := startMockHttpsHost(t, false)
	defer stopWeb()

	// 4. Mock local UDP DNS server
	dnsAddr, stopDns := startMockUdpDns(t)
	defer stopDns()

	yaml := fmt.Sprintf(`
inetlookup:
  mail-api-url: %s/
  yandex-api-url: http://%s/
  ripe-api-url: http://%s/
checkers:
  whoami:
    timeout: 3s
  cidrwhitelist:
    timeout: 2s
    whitelisted:
      - %s/
    regular:
      - http://%s/
  dns:
    leak:
      timeout: 300ms
      times: 1
      workers: 1
      parent-domain: invalid.local
    resolve:
      plain-opt:
        query-timeout: 1s
        provider-timeout: 3s
        workers: 1
      doh-opt:
        query-timeout: 500ms
        provider-timeout: 2s
        workers: 1
      whoami:
        host: whoami.local
      targets:
        - host: target.local
          filter: org("cloudflare")
      providers:
        - name: MockDNS
          plain:
            filter: org("cloudflare")
            hosts:
              - %s
          doh:
            filter: org("cloudflare")
            hosts: []
  webhost:
    exclude-default-sections: true
    workers: 2
    farm-timeout: 10s
    tcp-conn-timeout: 1s
    tls-handshake-timeout: 1s
    tcp-read-timeout: 2s
    tcp-write-timeout: 2s
    sections:
      - name: MockSection
        desc: local mock host
        targets:
          - name: LocalTLS
            filter: subnet("127.0.0.1/32")
            count: 1
            port: %d
            sni: mock.local
            host: mock.local
            siberian-skip: true
`, whoamiSrv.URL, deadAddr, deadAddr, wlSrv.URL, deadAddr, dnsAddr, webPort)

	setupBridgeWithMinimalGeo(t, yaml)

	l := newTestListener()
	// Empty optsJson runs all 4 default checkers: whoami, cidrwhitelist, dns, webhost
	if err := Start("", l); err != nil {
		t.Fatalf("Start all failed: %v", err)
	}

	// While running: concurrent Start, Setup, ValidateConfig, PrepareGeo must refuse cleanly
	if IsRunning() {
		if err := Start("", l); err == nil {
			t.Error("expected error on concurrent Start")
		}
		if err := Setup(t.TempDir(), t.TempDir(), ""); err == nil {
			t.Error("expected error on Setup while running")
		}
		if msg := ValidateConfig("debug: true"); msg == "" {
			t.Error("expected refusal from ValidateConfig while running")
		}
		if err := PrepareGeo(false, false, nil); err == nil {
			t.Error("expected error on PrepareGeo while running")
		}
	}

	select {
	case <-l.doneCh:
	case <-time.After(15 * time.Second):
		Cancel()
		t.Fatal("timed out waiting for full mock pipeline")
	}

	types := l.types()
	for _, req := range []string{"started", "whoami", "cidr", "dnsLeak", "dnsPlain", "dnsDoh", "webhostItem", "webhostDone", "task", "done"} {
		if types[req] == 0 {
			t.Errorf("missing expected event %q; got %v", req, types)
		}
	}
	if IsRunning() {
		t.Fatal("IsRunning must be false after completion")
	}
}

func TestCancelDuringSlowCheck(t *testing.T) {
	// Hang server for cidrwhitelist so we can cleanly exercise Cancel()
	blockCh := make(chan struct{})
	defer close(blockCh)
	hangSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-blockCh:
		case <-r.Context().Done():
		}
	}))
	defer hangSrv.Close()

	yaml := fmt.Sprintf(`
checkers:
  cidrwhitelist:
    timeout: 30s
    whitelisted:
      - %s/
    regular:
      - %s/
`, hangSrv.URL, hangSrv.URL)

	setupBridgeWithMinimalGeo(t, yaml)
	l := newTestListener()
	if err := Start(`{"checkers":["cidrwhitelist"]}`, l); err != nil {
		t.Fatal(err)
	}
	time.Sleep(150 * time.Millisecond)
	Cancel()

	select {
	case <-l.doneCh:
	case <-time.After(5 * time.Second):
		t.Fatal("Cancel did not terminate check in time")
	}

	var cancelled bool
	l.mu.Lock()
	for _, ev := range l.events {
		if ev["type"] == "done" && ev["cancelled"] == true {
			cancelled = true
		}
	}
	l.mu.Unlock()
	if !cancelled {
		t.Fatalf("expected done{cancelled:true}, events=%v", l.events)
	}
}

func TestHelpersAndErrors(t *testing.T) {
	setupBridgeWithMinimalGeo(t, "")
	if err := Start("{bad-json", nil); err == nil {
		t.Fatal("expected error for malformed optsJson")
	}
	if stateName(fullcheck.StateOk) != "ok" ||
		stateName(fullcheck.StateError) != "error" ||
		stateName(fullcheck.StateTimeout) != "timeout" ||
		stateName(fullcheck.StateRunning) != "running" {
		t.Fatal("unexpected stateName mapping")
	}
	emit(nil, map[string]any{"type": "log"}) // must be safe no-op

	var gotErr error
	func() {
		defer recoverTo(&gotErr)
		panic("boom")
	}()
	if gotErr == nil || !strings.Contains(gotErr.Error(), "boom") {
		t.Fatalf("recoverTo did not capture panic: %v", gotErr)
	}
}
