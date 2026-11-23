// Author: by Ivan Tokarev
// Chrome Native Messaging Host for DPI Detector Pro-mode.
// Speaks Chrome's 32-bit little-endian length-prefixed JSON protocol over stdin/stdout
// and delegates all checks directly to `core/bridge` (hyperion-cs/dpi-checkers).

package main

import (
	"encoding/binary"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"sync"

	dpich "dpidetector/bridge"
)

type request struct {
	Cmd     string `json:"cmd"`
	Target  string `json:"target"`
	Sni     string `json:"sni"`
	Port    int32  `json:"port"`
	Opts    string `json:"opts"`
	Force   bool   `json:"force"`
}

var writeMu sync.Mutex

func writeMsg(v any) error {
	payload, err := json.Marshal(v)
	if err != nil {
		return err
	}
	writeMu.Lock()
	defer writeMu.Unlock()
	if err := binary.Write(os.Stdout, binary.LittleEndian, uint32(len(payload))); err != nil {
		return err
	}
	_, err = os.Stdout.Write(payload)
	return err
}

func readMsg() ([]byte, error) {
	var length uint32
	if err := binary.Read(os.Stdin, binary.LittleEndian, &length); err != nil {
		return nil, err
	}
	if length == 0 || length > 10*1024*1024 {
		return nil, io.ErrUnexpectedEOF
	}
	buf := make([]byte, length)
	_, err := io.ReadFull(os.Stdin, buf)
	return buf, err
}

type stdoutListener struct{}

func (stdoutListener) OnEvent(raw string) {
	var parsed any
	if err := json.Unmarshal([]byte(raw), &parsed); err == nil {
		_ = writeMsg(parsed)
	}
}

func main() {
	home, _ := os.UserHomeDir()
	base := filepath.Join(home, ".dpi-detector-native")
	if err := dpich.Setup(base, filepath.Join(base, "cache"), ""); err != nil {
		_ = writeMsg(map[string]any{"type": "error", "msg": err.Error()})
		return
	}

	listener := stdoutListener{}
	for {
		raw, err := readMsg()
		if err != nil {
			return
		}
		var req request
		if err := json.Unmarshal(raw, &req); err != nil {
			_ = writeMsg(map[string]any{"type": "error", "msg": "invalid json: " + err.Error()})
			continue
		}

		switch req.Cmd {
		case "ping":
			_ = writeMsg(map[string]any{
				"type":    "pong",
				"author":  "by Ivan Tokarev",
				"geo":     json.RawMessage(dpich.GeoStatus()),
				"running": dpich.IsRunning(),
			})
		case "prepareGeo":
			go func(force bool) {
				if err := dpich.PrepareGeo(force, true, listener); err != nil {
					_ = writeMsg(map[string]any{"type": "error", "msg": err.Error()})
				}
				_ = writeMsg(map[string]any{"type": "geoStatus", "data": json.RawMessage(dpich.GeoStatus())})
			}(req.Force)
		case "start":
			if err := dpich.Start(req.Opts, listener); err != nil {
				_ = writeMsg(map[string]any{"type": "error", "msg": err.Error()})
			}
		case "cancel":
			dpich.Cancel()
			_ = writeMsg(map[string]any{"type": "log", "msg": "cancel requested"})
		case "probeHost":
			go func(r request) {
				res, err := dpich.CheckCustomHost(r.Target, r.Sni, r.Port)
				if err != nil {
					_ = writeMsg(map[string]any{"type": "probeResult", "ok": false, "error": err.Error()})
					return
				}
				_ = writeMsg(map[string]any{"type": "probeResult", "ok": true, "data": json.RawMessage(res)})
			}(req)
		default:
			_ = writeMsg(map[string]any{"type": "error", "msg": "unknown cmd: " + req.Cmd})
		}
	}
}
