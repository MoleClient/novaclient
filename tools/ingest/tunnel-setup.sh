#!/usr/bin/env bash
#
# Wires a Cloudflare Tunnel from a public hostname to the collector on this machine, installs both
# as background services, and then proves the whole path works end to end.
#
# Run `cloudflared tunnel login` first — that is the one step that needs a browser.
#
#   ./tunnel-setup.sh ingest.yourdomain.com
#   ./tunnel-setup.sh --dry-run ingest.yourdomain.com
#
set -euo pipefail

TUNNEL_NAME="${TUNNEL_NAME:-nova-ingest}"
PORT="${PORT:-8787}"
DATA_ROOT="${DATA_ROOT:-$HOME/nova-data}"
TOKEN="${NOVA_TOKEN:-nova-contrib-1}"
QUOTA_GB="${QUOTA_GB:-200}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AGENTS="$HOME/Library/LaunchAgents"
PLIST="$AGENTS/com.novaclient.ingest.plist"
CF_DIR="$HOME/.cloudflared"

DRY_RUN=0
[[ "${1:-}" == "--dry-run" ]] && { DRY_RUN=1; shift; }
HOSTNAME_ARG="${1:-}"

die()  { printf '\033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }
ok()   { printf '\033[32m✓\033[0m %s\n' "$*"; }
step() { printf '\n\033[1m%s\033[0m\n' "$*"; }
run()  { if (( DRY_RUN )); then printf '   would run: %s\n' "$*"; else eval "$@"; fi; }

[[ -n "$HOSTNAME_ARG" ]] || die "usage: $0 [--dry-run] ingest.yourdomain.com"
[[ "$HOSTNAME_ARG" == *.*.* ]] || die "expected a subdomain like ingest.yourdomain.com, got '$HOSTNAME_ARG'"
command -v cloudflared >/dev/null || die "cloudflared not installed — brew install cloudflared"
command -v python3 >/dev/null || die "python3 not found"
# LaunchAgents get almost no PATH, so the plist needs an absolute interpreter. Resolve it here and
# prove it can actually run the collector rather than discovering that from a silent service.
PYTHON="$(command -v python3)"
"$PYTHON" "$HERE/nova_ingest.py" --help >/dev/null 2>&1 \
	|| die "$PYTHON cannot run nova_ingest.py — try a newer python3"
[[ -f "$CF_DIR/cert.pem" ]] || die "not logged in yet — run: cloudflared tunnel login"
(( DRY_RUN )) && printf '\033[33mDRY RUN — nothing will be changed\033[0m\n'

# ── 3. tunnel ────────────────────────────────────────────────────────────────
step "1/6  Tunnel '$TUNNEL_NAME'"
if cloudflared tunnel list 2>/dev/null | awk '{print $2}' | grep -qx "$TUNNEL_NAME"; then
	ok "already exists, reusing it"
else
	run "cloudflared tunnel create '$TUNNEL_NAME'"
	(( DRY_RUN )) || ok "created"
fi

UUID=""
if ! (( DRY_RUN )); then
	UUID="$(cloudflared tunnel list 2>/dev/null | awk -v n="$TUNNEL_NAME" '$2==n {print $1}' | head -1)"
	[[ -n "$UUID" ]] || die "could not read the tunnel UUID back from 'cloudflared tunnel list'"
	CREDS="$CF_DIR/$UUID.json"
	[[ -f "$CREDS" ]] || die "credentials file missing: $CREDS"
	ok "uuid $UUID"
else
	UUID="<UUID>"; CREDS="$CF_DIR/<UUID>.json"
fi

# ── 4. DNS ───────────────────────────────────────────────────────────────────
step "2/6  DNS route $HOSTNAME_ARG"
# Idempotent in practice: re-running on an existing record errors, which is not fatal here.
if (( DRY_RUN )); then
	printf '   would run: cloudflared tunnel route dns %s %s\n' "$TUNNEL_NAME" "$HOSTNAME_ARG"
elif cloudflared tunnel route dns "$TUNNEL_NAME" "$HOSTNAME_ARG" 2>&1 | tee /tmp/nova-route.log; then
	ok "pointed at the tunnel"
else
	grep -qi "already exists\|record with that host" /tmp/nova-route.log \
		&& ok "record already present, leaving it alone" \
		|| die "could not create the DNS record — is $HOSTNAME_ARG's zone on this Cloudflare account?"
fi

# ── 5. config ────────────────────────────────────────────────────────────────
step "3/6  ~/.cloudflared/config.yml"
CONFIG_BODY="tunnel: $TUNNEL_NAME
credentials-file: $CREDS
ingress:
  - hostname: $HOSTNAME_ARG
    service: http://127.0.0.1:$PORT
  # Required catch-all. Without it cloudflared refuses to start.
  - service: http_status:404
"
if (( DRY_RUN )); then
	printf '   would write:\n%s\n' "$CONFIG_BODY" | sed 's/^/     /'
else
	[[ -f "$CF_DIR/config.yml" ]] && cp "$CF_DIR/config.yml" "$CF_DIR/config.yml.bak" \
		&& ok "backed up existing config to config.yml.bak"
	printf '%s' "$CONFIG_BODY" > "$CF_DIR/config.yml"
	ok "written"
fi

# ── 6. services ──────────────────────────────────────────────────────────────
step "4/6  Collector service"
PLIST_BODY='<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>com.novaclient.ingest</string>
  <key>ProgramArguments</key>
  <array>
    <string>'"$PYTHON"'</string>
    <string>'"$HERE"'/nova_ingest.py</string>
    <string>--root</string><string>'"$DATA_ROOT"'</string>
    <string>--token</string><string>'"$TOKEN"'</string>
    <string>--port</string><string>'"$PORT"'</string>
    <string>--quota-gb</string><string>'"$QUOTA_GB"'</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>/tmp/nova-ingest.log</string>
  <key>StandardErrorPath</key><string>/tmp/nova-ingest.log</string>
</dict>
</plist>
'
if (( DRY_RUN )); then
	printf '   would write %s and load it\n' "$PLIST"
else
	mkdir -p "$AGENTS" "$DATA_ROOT"
	printf '%s' "$PLIST_BODY" > "$PLIST"
	launchctl unload "$PLIST" 2>/dev/null || true
	launchctl load "$PLIST"
	sleep 2
	curl -fsS "http://127.0.0.1:$PORT/healthz" >/dev/null \
		|| die "collector did not come up — see /tmp/nova-ingest.log"
	ok "listening on 127.0.0.1:$PORT, writing to $DATA_ROOT"
fi

step "5/6  Tunnel service"
CF_PLIST="$AGENTS/com.cloudflare.cloudflared.plist"
if (( DRY_RUN )); then
	printf '   would run: cloudflared service install, then patch its plist to "tunnel run"\n'
else
	cloudflared service install 2>/dev/null || true
	[[ -f "$CF_PLIST" ]] || die "cloudflared service install did not write $CF_PLIST"

	# The installer writes a plist whose ProgramArguments is just the binary. With a named
	# tunnel in config.yml that is not enough: bare cloudflared prints "Use `cloudflared
	# tunnel run`" and exits 1, and KeepAlive spins it forever. Rewrite the args so the
	# service actually runs the tunnel.
	launchctl unload "$CF_PLIST" 2>/dev/null || true
	/usr/libexec/PlistBuddy -c "Delete :ProgramArguments" "$CF_PLIST" 2>/dev/null || true
	/usr/libexec/PlistBuddy \
		-c "Add :ProgramArguments array" \
		-c "Add :ProgramArguments:0 string $(command -v cloudflared)" \
		-c "Add :ProgramArguments:1 string --config" \
		-c "Add :ProgramArguments:2 string $CF_DIR/config.yml" \
		-c "Add :ProgramArguments:3 string tunnel" \
		-c "Add :ProgramArguments:4 string run" \
		"$CF_PLIST" >/dev/null
	launchctl load "$CF_PLIST"
	ok "installed and patched to 'tunnel run' (user LaunchAgent — runs while you are logged in)"

	printf '   waiting for the tunnel to register'
	for _ in $(seq 1 20); do
		cloudflared tunnel info "$TUNNEL_NAME" 2>/dev/null | grep -q "CONNECTOR ID" && break
		printf '.'; sleep 3
	done
	printf '\n'
	cloudflared tunnel info "$TUNNEL_NAME" 2>/dev/null | grep -q "CONNECTOR ID" \
		|| die "the tunnel never connected — see ~/Library/Logs/com.cloudflare.cloudflared.err.log"
	ok "connected to the Cloudflare edge"
fi

# ── verify ───────────────────────────────────────────────────────────────────
step "6/6  End-to-end check"
if (( DRY_RUN )); then
	printf '   would POST a test batch to https://%s/v1/ticks and read it back\n' "$HOSTNAME_ARG"
	printf '\n\033[33mDry run complete.\033[0m\n'
	exit 0
fi

printf '   waiting for DNS and the tunnel to settle'
for _ in $(seq 1 30); do
	curl -fsS --max-time 5 "https://$HOSTNAME_ARG/healthz" >/dev/null 2>&1 && break
	printf '.'; sleep 3
done
printf '\n'
curl -fsS --max-time 10 "https://$HOSTNAME_ARG/healthz" >/dev/null 2>&1 \
	|| die "https://$HOSTNAME_ARG/healthz is not answering yet.
     DNS can take a few minutes on a brand new record. Check with:
       cloudflared tunnel info $TUNNEL_NAME
       curl -v https://$HOSTNAME_ARG/healthz"
ok "https://$HOSTNAME_ARG/healthz answered"

# A real batch through the public hostname — the only check that exercises every hop.
# The batch is built by python but sent by curl: a Homebrew/framework python often has no CA
# bundle and fails TLS verification against a perfectly good certificate, and that failure looks
# exactly like a broken tunnel. curl uses the system trust store.
BEFORE="$(curl -fsS "http://127.0.0.1:$PORT/healthz" | python3 -c 'import sys,json;print(json.load(sys.stdin)["batches"])')"
CHECK_GZ="$(mktemp -t nova-check).gz"
# The pseudonym must be hex — the collector validates it before it goes near a file path.
python3 - "$CHECK_GZ" <<'PY'
import gzip, io, json, sys, uuid
header = {"t": "header", "schema": 2, "seq": 0, "session": str(uuid.uuid4()),
          "pseudonym": "deadbeefdeadbeef", "location": False,
          "fields": ["tick"], "entity_fields": [], "dict": []}
buf = io.BytesIO()
with gzip.GzipFile(fileobj=buf, mode="wb") as gz:
    gz.write((json.dumps(header) + "\n").encode())
    gz.write(b'{"n":0,"f":[0]}\n')
open(sys.argv[1], "wb").write(buf.getvalue())
PY
CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 25 -X POST "https://$HOSTNAME_ARG/v1/ticks" \
	-H 'Content-Type: application/x-ndjson' -H 'Content-Encoding: gzip' \
	-H "Authorization: Bearer $TOKEN" --data-binary "@$CHECK_GZ")"
rm -f "$CHECK_GZ"
printf '   test batch through the public hostname -> HTTP %s\n' "$CODE"
[[ "$CODE" == "204" ]] || die "expected 204, got $CODE (401 = token mismatch between jar and collector)"
AFTER="$(curl -fsS "http://127.0.0.1:$PORT/healthz" | python3 -c 'import sys,json;print(json.load(sys.stdin)["batches"])')"
[[ "$AFTER" -gt "$BEFORE" ]] \
	|| die "the POST was accepted but the collector's batch count did not move ($BEFORE -> $AFTER)"
ok "collector stored it (batches $BEFORE -> $AFTER)"

cat <<EOF

$(printf '\033[32mDone.\033[0m') https://$HOSTNAME_ARG is live and landing in $DATA_ROOT

Still to do by hand:
  1. ProFPSConfig.dataContributionEndpoint = "https://$HOSTNAME_ARG"
     plus a configVersion migration, or existing installs keep the old one — see SETUP.md
  2. Change CLIENT_TOKEN in ContributionUploader.java before publishing a build
  3. Cloudflare dashboard: rate-limit /v1/ticks and block non-POST — see SETUP.md
  4. Delete the tunnel-check session: rm -rf $DATA_ROOT/*/deadbeefdeadbeef
EOF
