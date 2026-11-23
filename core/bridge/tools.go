//go:build tools

package dpich

// Keeps golang.org/x/mobile/bind in go.mod (required by `gomobile bind`).
import _ "golang.org/x/mobile/bind"
