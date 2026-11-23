// Package dpich is a gomobile bridge between the Android app and the
// dpi-ch (hyperion-cs/dpi-checkers) checkers core.
//
// All exported functions use gomobile-compatible types only. Results are
// streamed to the app as JSON events through EventListener.
package dpich

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/netip"
	"os"
	"path/filepath"
	"runtime/debug"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/hyperion-cs/dpi-checkers/ru/dpi-ch/checkers/cidrwhitelist"
	"github.com/hyperion-cs/dpi-checkers/ru/dpi-ch/checkers/dns"
	"github.com/hyperion-cs/dpi-checkers/ru/dpi-ch/checkers/fullcheck"
	"github.com/hyperion-cs/dpi-checkers/ru/dpi-ch/checkers/webhost"
	"github.com/hyperion-cs/dpi-checkers/ru/dpi-ch/checkers/whoami"
	"github.com/hyperion-cs/dpi-checkers/ru/dpi-ch/config"
	"github.com/hyperion-cs/dpi-checkers/ru/dpi-ch/inetlookup"
	"github.com/hyperion-cs/dpi-checkers/ru/dpi-ch/updater"
)

// EventListener receives JSON-encoded events. It may be called from any thread.
type EventListener interface {
	OnEvent(json string)
}

var (
	mu       sync.Mutex
	cancelFn context.CancelFunc
	running  bool
	baseDir  string
	inited   bool
	emitMu   sync.Mutex
	logFile  *os.File
)

// ---------------------------------------------------------------------------
// Init / config
// ---------------------------------------------------------------------------

// Setup prepares the environment and loads configuration.
//
//	filesDir – app private files dir (Context.getFilesDir()).
//	cacheDir – app cache dir (Context.getCacheDir()).
//	userYaml – optional user overrides in dpi-ch config.yaml format ("" = defaults).
func Setup(filesDir, cacheDir, userYaml string) (err error) {
	defer recoverTo(&err)
	mu.Lock()
	defer mu.Unlock()
	if running {
		return errors.New("checks are running; stop them first")
	}

	baseDir = filepath.Join(filesDir, "dpich")
	dataHome := filepath.Join(baseDir, "data")
	for _, d := range []string{baseDir, dataHome, cacheDir, reportsDir()} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			return err
		}
	}
	// Go on Android has no sane defaults for these.
	os.Setenv("HOME", baseDir)
	os.Setenv("TMPDIR", cacheDir)
	os.Setenv("XDG_DATA_HOME", dataHome)
	os.Setenv("XDG_CONFIG_HOME", baseDir)

	cfgPath := filepath.Join(baseDir, "config.yaml")
	content := strings.TrimSpace(userYaml)
	if content == "" {
		content = "# user overrides (dpi-ch config.yaml format)"
	}
	if err := os.WriteFile(cfgPath, []byte(content+"\n"), 0o600); err != nil {
		return err
	}
	if err := config.Load(cfgPath); err != nil {
		return fmt.Errorf("config: %w", err)
	}

	if logFile != nil {
		logFile.Close()
		logFile = nil
	}
	if config.Get().Debug {
		f, ferr := os.OpenFile(config.Get().DataPath("debug.log"), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
		if ferr == nil {
			logFile = f
			log.SetOutput(f)
		}
	} else {
		log.SetOutput(io.Discard)
	}
	inited = true
	return nil
}

// DefaultConfigYaml returns the built-in dpi-ch default.yaml for reference.
func DefaultConfigYaml() string {
	return config.DefaultYaml()
}

// ValidateConfig checks that the given yaml merges cleanly with the defaults.
// Returns "" if valid, otherwise the error text.
func ValidateConfig(userYaml string) string {
	dir, err := os.MkdirTemp("", "cfgcheck-")
	if err != nil {
		return err.Error()
	}
	defer os.RemoveAll(dir)
	f := filepath.Join(dir, "config.yaml")
	content := strings.TrimSpace(userYaml)
	if content == "" {
		return ""
	}
	if err := os.WriteFile(f, []byte(content+"\n"), 0o600); err != nil {
		return err.Error()
	}
	mu.Lock()
	isRunning := running
	mu.Unlock()
	if isRunning {
		return "checks are running"
	}
	cur, _ := config.ConfigPath()
	verr := config.Load(f)
	if cur != "" {
		_ = config.Load(cur)
	}
	if verr != nil {
		return verr.Error()
	}
	return ""
}

// Sections returns JSON list of configured webhost sections.
func Sections() string {
	type sec struct {
		Index   int      `json:"index"`
		Name    string   `json:"name"`
		Desc    string   `json:"desc"`
		Targets []string `json:"targets"`
	}
	out := []sec{}
	for i, s := range config.Get().Checkers.Webhost.Sections {
		names := make([]string, 0, len(s.Targets))
		for _, t := range s.Targets {
			names = append(names, t.Name)
		}
		out = append(out, sec{Index: i, Name: s.Name, Desc: s.Desc, Targets: names})
	}
	b, _ := json.Marshal(out)
	return string(b)
}

// DnsProviders returns JSON list of configured DNS provider names.
func DnsProviders() string {
	names := []string{}
	for _, p := range config.Get().Checkers.Dns.Resolve.Providers {
		names = append(names, p.Name)
	}
	b, _ := json.Marshal(names)
	return string(b)
}

// ReportsDir returns the directory where finished reports are stored.
func ReportsDir() string { return reportsDir() }

func reportsDir() string { return filepath.Join(baseDir, "reports") }

// ---------------------------------------------------------------------------
// GeoLite database (needed by whoami / webhost / dns checkers)
// ---------------------------------------------------------------------------

// GeolitePaths returns the three CSV destinations separated by '\n':
// cidr->asn, cidr->country, geonameid->country.
func GeolitePaths() string {
	a, c, n := config.Get().GeoliteOutputPaths()
	return a + "\n" + c + "\n" + n
}

// MarkBundledGeoTimestamp writes the timestamp of bundled GeoLite CSVs if none exists yet.
func MarkBundledGeoTimestamp(unixSec int64) {
	if !inited || unixSec <= 0 {
		return
	}
	cfg := config.Get()
	tsPath := cfg.DataPath(cfg.Updater.InetlookupTsFile)
	if _, err := os.Stat(tsPath); err == nil {
		return
	}
	_ = os.MkdirAll(filepath.Dir(tsPath), 0o755)
	_ = os.WriteFile(tsPath, []byte(strconv.FormatInt(unixSec, 10)), 0o644)
}

// GeoStatus returns JSON: {"loaded":bool,"due":bool,"updatedAt":unix}.
func GeoStatus() string {
	cfg := config.Get()
	var ts int64
	if b, err := os.ReadFile(cfg.DataPath(cfg.Updater.InetlookupTsFile)); err == nil {
		fmt.Sscan(strings.TrimSpace(string(b)), &ts)
	}
	b, _ := json.Marshal(map[string]any{
		"loaded":    inetlookup.Inited(),
		"due":       updater.GeoliteDue(),
		"updatedAt": ts,
	})
	return string(b)
}

// PrepareGeo updates (if due or forced) and loads the GeoLite database.
// Blocking; call it from a background thread. Emits "log" events.
func PrepareGeo(force bool, allowOnlineUpdate bool, l EventListener) (err error) {
	defer recoverTo(&err)
	if !inited {
		return errors.New("not initialized")
	}
	ctx, done := begin()
	if ctx == nil {
		return errors.New("checks are already running")
	}
	defer done()

	cfg := config.Get()
	updated := false
	if force {
		config.ForceInetlookupUpdateFlag()
		defer func() { config.Get().Updater.ForceInetlookupUpdate = false }()
	}
	if allowOnlineUpdate && (force || (cfg.Updater.Enabled && updater.GeoliteDue())) {
		emit(l, map[string]any{"type": "log", "msg": "geoip: проверка обновлений базы GeoLite…"})
		if uerr := updater.GeoliteUpdate(ctx); uerr != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			emit(l, map[string]any{"type": "log", "msg": "geoip: онлайн-обновление недоступно (" + uerr.Error() + "), работаем по локальной базе"})
		} else {
			updated = true
			emit(l, map[string]any{"type": "log", "msg": "geoip: база обновлена"})
		}
	}
	if updated {
		emit(l, map[string]any{"type": "log", "msg": "geoip: перезагрузка базы в память…"})
		if lerr := inetlookup.ReloadDefault(); lerr != nil {
			return lerr
		}
	} else if !inetlookup.Inited() {
		emit(l, map[string]any{"type": "log", "msg": "geoip: загрузка базы в память…"})
		if lerr := inetlookup.InitDefault(); lerr != nil {
			return lerr
		}
	}
	emit(l, map[string]any{"type": "log", "msg": "geoip: база готова к работе"})
	return nil
}

// ---------------------------------------------------------------------------
// Quick single-host probe
// ---------------------------------------------------------------------------

// CheckCustomHost probes a single domain or IPv4 address using dpi-ch's webhost engine
// (Alive / TLS / SNI block / TCP 16-20 / Siberian / Burst throughput).
// Returns JSON-encoded WebhostItemDto.
func CheckCustomHost(target string, sni string, port int32) (resJson string, err error) {
	defer recoverTo(&err)
	if !inited {
		return "", errors.New("not initialized")
	}
	if !inetlookup.Inited() {
		return "", errors.New("база GeoLite не загружена")
	}
	target = strings.TrimSpace(target)
	target = strings.TrimPrefix(target, "https://")
	target = strings.TrimPrefix(target, "http://")
	if idx := strings.IndexByte(target, '/'); idx >= 0 {
		target = target[:idx]
	}
	if target == "" {
		return "", errors.New("укажите домен или IPv4")
	}
	if port <= 0 || port > 65535 {
		port = 443
	}

	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()

	var ip netip.Addr
	hostHeader := target
	sniVal := strings.TrimSpace(sni)

	if parsed, perr := netip.ParseAddr(target); perr == nil {
		if !parsed.Is4() {
			return "", errors.New("поддерживаются только IPv4-адреса")
		}
		ip = parsed
		if sniVal != "" {
			hostHeader = sniVal
		}
	} else {
		ips, lerr := inetlookup.LookupIpViaDefault(ctx, target)
		if lerr != nil {
			return "", fmt.Errorf("DNS не смог разрешить %s: %w", target, lerr)
		}
		for _, raw := range ips {
			if v4 := raw.To4(); v4 != nil {
				ip, _ = netip.AddrFromSlice(v4)
				break
			}
		}
		if !ip.IsValid() {
			return "", fmt.Errorf("у %s не найдено IPv4-записей", target)
		}
		if sniVal == "" {
			sniVal = target
		}
	}

	r := webhost.Single(webhost.SingleOpt{
		Ctx:  ctx,
		Ip:   ip,
		Port: int(port),
		Sni:  sniVal,
		Host: hostHeader,
	})
	dto := fullcheck.ToWebhostItemDto(webhost.Item{Group: target, Result: r})
	b, merr := json.Marshal(dto)
	if merr != nil {
		return "", merr
	}
	return string(b), nil
}

// ---------------------------------------------------------------------------
// Checks
// ---------------------------------------------------------------------------

type startOpts struct {
	Checkers []string `json:"checkers"` // whoami, cidrwhitelist, dns, webhost
	Sections []int    `json:"sections"` // webhost section indexes; empty = all
}

// Start runs the selected checkers asynchronously.
// optsJson: {"checkers":["whoami","cidrwhitelist","dns","webhost"],"sections":[0,1]}
func Start(optsJson string, l EventListener) (err error) {
	defer recoverTo(&err)
	if !inited {
		return errors.New("not initialized")
	}
	var opts startOpts
	if optsJson != "" {
		if err := json.Unmarshal([]byte(optsJson), &opts); err != nil {
			return err
		}
	}
	if len(opts.Checkers) == 0 {
		opts.Checkers = []string{"whoami", "cidrwhitelist", "dns", "webhost"}
	}
	needsGeo := slices.ContainsFunc(opts.Checkers, func(s string) bool { return s != "cidrwhitelist" })
	if needsGeo && !inetlookup.Inited() {
		return errors.New("база GeoLite не загружена")
	}

	ctx, done := begin()
	if ctx == nil {
		return errors.New("checks are already running")
	}

	go func() {
		defer done()
		defer func() {
			if r := recover(); r != nil {
				emit(l, map[string]any{"type": "error", "msg": fmt.Sprintf("panic: %v\n%s", r, debug.Stack())})
				emit(l, map[string]any{"type": "done", "cancelled": true})
			}
		}()
		run(ctx, opts, l)
	}()
	return nil
}

// Cancel stops running checks (if any).
func Cancel() {
	mu.Lock()
	defer mu.Unlock()
	if cancelFn != nil {
		cancelFn()
	}
}

// IsRunning reports whether checks or geo update are in progress.
func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return running
}

func begin() (context.Context, func()) {
	mu.Lock()
	defer mu.Unlock()
	if running {
		return nil, nil
	}
	ctx, cancel := context.WithCancel(context.Background())
	running, cancelFn = true, cancel
	return ctx, func() {
		cancel()
		mu.Lock()
		running, cancelFn = false, nil
		mu.Unlock()
	}
}

func run(ctx context.Context, opts startOpts, l EventListener) {
	cfg := config.Get()
	has := func(name string) bool { return slices.Contains(opts.Checkers, name) }

	type secRef struct {
		Index int    `json:"index"`
		Name  string `json:"name"`
	}
	var sections []secRef
	if has("webhost") {
		for i, s := range cfg.Checkers.Webhost.Sections {
			if len(opts.Sections) == 0 || slices.Contains(opts.Sections, i) {
				sections = append(sections, secRef{i, s.Name})
			}
		}
	}
	emit(l, map[string]any{"type": "started", "checkers": opts.Checkers, "sections": sections})

	report := fullcheck.NewReport()
	var rmu sync.Mutex
	var wg sync.WaitGroup

	task := func(name string, index int, st fullcheck.State) {
		emit(l, map[string]any{"type": "task", "task": name, "index": index, "state": stateName(st)})
	}

	if has("whoami") {
		wg.Go(func() {
			emit(l, map[string]any{"type": "log", "msg": "whoami: определение внешнего IP и провайдера…"})
			res, werr := whoami.Check(ctx)
			dto := fullcheck.ToWhoamiDto(res, werr)
			rmu.Lock()
			report.Whoami = &dto
			rmu.Unlock()
			emit(l, map[string]any{"type": "whoami", "data": dto})
			task("whoami", 0, fullcheck.ErrorStateOf(werr))
		})
	}

	if has("cidrwhitelist") {
		wg.Go(func() {
			emit(l, map[string]any{"type": "log", "msg": "cidrwhitelist: проверка на «белые списки» подсетей…"})
			cerr := cidrwhitelist.Check(ctx)
			dto := fullcheck.ToCidrWhitelistDto(cerr)
			rmu.Lock()
			report.CidrWhitelist = &dto
			rmu.Unlock()
			st := fullcheck.ErrorStateOf(cerr)
			if cerr == cidrwhitelist.ErrDetected || cerr == cidrwhitelist.ErrNoInetAccess {
				st = fullcheck.StateOk
			}
			emit(l, map[string]any{"type": "cidr", "data": dto})
			task("cidrwhitelist", 0, st)
		})
	}

	if has("dns") {
		dnsDto := &fullcheck.DnsDto{}
		rmu.Lock()
		report.Dns = dnsDto
		rmu.Unlock()

		wg.Go(func() {
			emit(l, map[string]any{"type": "log", "msg": "dns/leak: проверка утечек DNS…"})
			items := []dns.LeakResult{}
			var leakErr error
			for v := range dns.LeakGochan(ctx) {
				items = append(items, v)
				if v.Err != nil {
					leakErr = v.Err
				}
			}
			dto := fullcheck.ToDnsLeakDto(items)
			rmu.Lock()
			dnsDto.Leak = &dto
			rmu.Unlock()
			emit(l, map[string]any{"type": "dnsLeak", "data": dto})
			task("dnsLeak", 0, fullcheck.ErrorStateOf(leakErr))
		})
		wg.Go(func() {
			items := []dns.Verdict{}
			for v := range dns.PlainGochan(ctx) {
				items = append(items, v)
				emit(l, map[string]any{"type": "log", "msg": "dns/plain: " + v.Provider + " — готово"})
			}
			dto := fullcheck.ToDnsReportDto(items)
			rmu.Lock()
			dnsDto.Plain = &dto
			rmu.Unlock()
			emit(l, map[string]any{"type": "dnsPlain", "data": dto})
			task("dnsPlain", 0, fullcheck.DnsStateOf(items))
		})
		wg.Go(func() {
			items := []dns.Verdict{}
			for v := range dns.DohGochan(ctx) {
				items = append(items, v)
				emit(l, map[string]any{"type": "log", "msg": "dns/doh: " + v.Provider + " — готово"})
			}
			dto := fullcheck.ToDnsReportDto(items)
			rmu.Lock()
			dnsDto.Doh = &dto
			rmu.Unlock()
			emit(l, map[string]any{"type": "dnsDoh", "data": dto})
			task("dnsDoh", 0, fullcheck.DnsStateOf(items))
		})
	}

	if len(sections) > 0 {
		report.Webhost = map[string]fullcheck.WebhostDto{}
		for _, sref := range sections {
			s := cfg.Checkers.Webhost.Sections[sref.Index]
			wg.Go(func() {
				gch := webhost.Run(ctx, s.Targets)
				go func() {
					for p := range gch.Progress {
						emit(l, map[string]any{"type": "log", "msg": "[" + s.Name + "] " + p})
					}
				}()
				rmu.Lock()
				report.Webhost[s.Name] = fullcheck.WebhostDto{Items: []fullcheck.WebhostItemDto{}}
				rmu.Unlock()
				for o := range gch.Out {
					dto := fullcheck.ToWebhostItemDto(o)
					rmu.Lock()
					cur := report.Webhost[s.Name]
					cur.Items = append(cur.Items, dto)
					report.Webhost[s.Name] = cur
					rmu.Unlock()
					emit(l, map[string]any{"type": "webhostItem", "section": s.Name, "index": sref.Index, "data": dto})
				}
				summary := <-gch.Summary
				st := fullcheck.ErrorStateOf(summary.Err)
				if summary.FarmTimeout {
					st = fullcheck.StateTimeout
					rmu.Lock()
					cur := report.Webhost[s.Name]
					cur.FarmTimeout = true
					report.Webhost[s.Name] = cur
					rmu.Unlock()
				}
				errText := ""
				if summary.Err != nil {
					errText = summary.Err.Error()
				}
				emit(l, map[string]any{
					"type": "webhostDone", "section": s.Name, "index": sref.Index,
					"farmTimeout": summary.FarmTimeout, "error": errText,
				})
				task("webhost", sref.Index, st)
			})
		}
	}

	wg.Wait()
	if ctx.Err() != nil {
		emit(l, map[string]any{"type": "done", "cancelled": true})
		return
	}

	path, serr := saveReport(report)
	if serr != nil {
		emit(l, map[string]any{"type": "error", "msg": "не удалось сохранить отчёт: " + serr.Error()})
	}
	emit(l, map[string]any{"type": "done", "path": path, "cancelled": false})
}

func saveReport(r *fullcheck.ReportDto) (string, error) {
	if err := os.MkdirAll(reportsDir(), 0o755); err != nil {
		return "", err
	}
	path := filepath.Join(reportsDir(), "results_"+time.Now().Format("2006-01-02_15-04-05")+".json")
	b, err := json.MarshalIndent(r, "", "  ")
	if err != nil {
		return "", err
	}
	return path, os.WriteFile(path, b, 0o644)
}

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

func stateName(s fullcheck.State) string {
	switch s {
	case fullcheck.StateOk:
		return "ok"
	case fullcheck.StateError:
		return "error"
	case fullcheck.StateTimeout:
		return "timeout"
	default:
		return "running"
	}
}

func emit(l EventListener, ev map[string]any) {
	if l == nil {
		return
	}
	b, err := json.Marshal(ev)
	if err != nil {
		return
	}
	emitMu.Lock()
	defer emitMu.Unlock()
	l.OnEvent(string(b))
}

func recoverTo(err *error) {
	if r := recover(); r != nil {
		*err = fmt.Errorf("panic: %v", r)
	}
}
