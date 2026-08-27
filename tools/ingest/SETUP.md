# Pointing your domain at this machine

End state: `https://ingest.yourdomain.com` is a real HTTPS endpoint that lands in
`nova_ingest.py` on this Mac, with no port forwarded, no inbound firewall hole, and your home IP
appearing nowhere in the jar.

`cloudflared` is already installed here (2026.3.0, via Homebrew). Steps 1 and 2 are the only ones
that need you — the rest is `./tunnel-setup.sh`.

---

## How it works

```
 player's jar ──HTTPS──► Cloudflare edge ──┐
                                            │  the tunnel is an OUTBOUND
                                            │  connection this Mac opened
 this Mac:  cloudflared ────────────────────┘
                  │ http://127.0.0.1:8787
                  ▼
            nova_ingest.py  ──►  ~/nova-data/
```

Nothing dials *in*. `cloudflared` dials *out* to Cloudflare and traffic comes back down that
connection, so there is no listening port on your router and no address to scan. The collector
binds `127.0.0.1`, so even locally nothing but cloudflared can reach it.

---

## 1. Get a domain onto Cloudflare

You need a domain whose DNS Cloudflare controls. A bare IP cannot get a TLS certificate, which is
the whole reason this step exists.

- **Cheapest path:** buy it at [Cloudflare Registrar](https://dash.cloudflare.com/?to=/:account/domains/register)
  — sold at wholesale, no markup, and the DNS is already wired up. A `.com` is roughly $10–12/yr.
- **Already own one elsewhere:** add the site in the Cloudflare dashboard and switch its
  nameservers to the two Cloudflare gives you. Propagation is usually minutes.

The subdomain (`ingest.`) does not need to be registered separately — step 3 creates it.

## 2. Authorise this machine

```bash
cloudflared tunnel login
```

Opens a browser; pick the domain you just set up. It drops a certificate at
`~/.cloudflared/cert.pem`. That cert can create tunnels and DNS records on your account, so treat
it like a credential — it is not something to commit or share.

## 3–6. Everything else

```bash
cd ~/Desktop/ProFPS/tools/ingest
./tunnel-setup.sh ingest.yourdomain.com
```

It creates the tunnel, points the DNS record at it, writes `~/.cloudflared/config.yml`, installs
both background services, and then proves the whole path works by POSTing a batch through the
public hostname and reading it back out of the collector. If any step fails it stops there and
says which.

To see what it will do without doing it: `./tunnel-setup.sh --dry-run ingest.yourdomain.com`.

---

## What the script sets up

**`~/.cloudflared/config.yml`** — the UUID is filled in from the tunnel it just created:

```yaml
tunnel: nova-ingest
credentials-file: /Users/you/.cloudflared/<UUID>.json
ingress:
  - hostname: ingest.yourdomain.com
    service: http://127.0.0.1:8787
  - service: http_status:404
```

The catch-all `404` matters: without it cloudflared refuses to start, and with it anything hitting
the tunnel on a hostname you did not configure gets nothing.

**Two LaunchAgents**, so both survive a reboot:

| Service | What |
|---|---|
| `com.cloudflare.cloudflared` | installed by `cloudflared service install` |
| `com.novaclient.ingest` | the collector, from `com.novaclient.ingest.plist` |

⚠️ **These are *user* LaunchAgents, not system daemons.** They start when you log in and stop when
you log out. Fine for a Mac that stays logged in; if you want collection to survive a logout you
need LaunchDaemons in `/Library/LaunchDaemons` running as a non-login user, which is a bigger
change. A Mac that goes to sleep also stops collecting — check
System Settings → Battery/Energy Saver → "Prevent automatic sleeping" if that matters.

---

## 7. Point the jar at it

Two places, and **both matter**:

```java
// ProFPSConfig.java
public String dataContributionEndpoint = "https://ingest.yourdomain.com";
```

```java
// ContributionUploader.java — change this before you publish a build
private static final String CLIENT_TOKEN = "<yours>";
```

Then pass the same token to the collector (the script reads `NOVA_TOKEN` if set, else the default).

### The gotcha that will get you

Changing the default **does not move anyone who has already run the jar.** Their `profps.json`
already has the old endpoint saved, and `load()` keeps whatever is in the file. You need a
migration to force it:

```java
if (configVersion < 104) {
    // Existing installs are pinned to the placeholder endpoint; move them.
    dataContributionEndpoint = new ProFPSConfig().dataContributionEndpoint;
    configVersion = 104;
    changed = true;
}
```

Bumping `configVersion` also means updating the `assertEquals(103, …)` in the test suite —
there are 28 of them:

```bash
grep -rl "assertEquals(103, " src/test/java/ | \
  xargs sed -i '' 's/assertEquals(103, \([A-Za-z]*\)\.configVersion)/assertEquals(104, \1.configVersion)/g'
```

## 8. Two Cloudflare rules before you publish

The token ships inside a decompilable jar, so it filters scanners, not people. These two are what
actually bound the damage, and they cannot be created from the CLI — the tunnel credential has no
WAF permission, so this is dashboard work.

Start at [dash.cloudflare.com](https://dash.cloudflare.com) → **goatmath.org** → **Security** →
**WAF**. (Some accounts now show this as Security → Security rules; the expressions are the same
either way, and both rule types are available on the free plan.)

### Rate limiting rule

Tab **Rate limiting rules** → *Create rule*.

| Field | Value |
|---|---|
| Name | `nova-ingest-rate` |
| Expression (use the *Edit expression* box) | `(http.host eq "ingest.goatmath.org" and http.request.uri.path eq "/v1/ticks")` |
| Rate | `30` requests per `1 minute` |
| Counting characteristic | IP |
| Action | Block, for `10 minutes` |

A real client sends 6 per minute, so 30 leaves plenty of headroom for a spool drain catching up
after a network drop.

### Method rule

Tab **Custom rules** → *Create rule*.

| Field | Value |
|---|---|
| Name | `nova-ingest-method` |
| Expression | `(http.host eq "ingest.goatmath.org" and http.request.uri.path eq "/v1/ticks" and http.request.method ne "POST")` |
| Action | Block |

### Check they took

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://ingest.goatmath.org/v1/ticks   # expect 403
for i in $(seq 1 40); do curl -s -o /dev/null https://ingest.goatmath.org/healthz; done
```

The GET should turn from `404` (collector said no) into `403` (Cloudflare said no) once the method
rule is live — that difference is how you tell the rule is actually in the path.

Optional extras: cap request body size at the edge, and turn on Bot Fight Mode.

---

## Checking on it

```bash
curl https://ingest.yourdomain.com/healthz          # from anywhere
curl http://127.0.0.1:8787/healthz                  # from this Mac
cloudflared tunnel info nova-ingest                 # is the tunnel connected
tail -f /tmp/nova-ingest.log                        # collector output
launchctl list | grep -E "cloudflared|novaclient"   # are both services up
python3 read_ticks.py ~/nova-data                   # what has arrived
```

In-game, `/nova data` prints whether the client is recording, what it has sent, and the last HTTP
status it got back — that is the fastest way to tell a bad endpoint from an idle one.

## If it does not work

| Symptom | Cause |
|---|---|
| `/nova data` shows `ConnectException` | collector not running, or endpoint typo |
| `HTTP 401` | jar token ≠ collector `--token` |
| `HTTP 502` from the public URL | tunnel is up, collector is not |
| `HTTP 530` / DNS failure | the DNS route was never created, or nameservers are not Cloudflare yet |
| Public URL times out, localhost fine | cloudflared not running — `launchctl list \| grep cloudflared` |
| Everything 200 but no ticks | not a transport problem — a module is gating the recorder, see `/nova data` |

## Tearing it down

```bash
launchctl unload ~/Library/LaunchAgents/com.novaclient.ingest.plist
cloudflared service uninstall
cloudflared tunnel delete nova-ingest
```
