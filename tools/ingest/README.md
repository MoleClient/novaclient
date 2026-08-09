# Movement data collection

Opt-out, tick-resolution recordings of real play, uploaded to a collector running on your own
machine. The corpus is meant for training movement models, so everything is spatially relative by
default and world coordinates are a separate switch that ships off.

## How the pieces fit

```
 Minecraft client                          your Mac
 ┌────────────────────────┐               ┌──────────────────────────┐
 │ DataContribution       │               │ cloudflared  (dials OUT) │
 │  · 1 row per tick      │               │        ↓ 127.0.0.1:8787  │
 │ ContributionUploader   │  HTTPS POST   │ nova_ingest.py           │
 │  · gzip NDJSON /10s    │ ────────────► │  · appends gzip members  │
 │  · spools when offline │  Cloudflare   │  ~/nova-data/<day>/…     │
 └────────────────────────┘               └──────────────────────────┘
```

`cloudflared` makes an **outbound** connection to Cloudflare and traffic comes back down it. There
is no inbound port, no router change, and your home address never appears in the jar or on the
wire. The collector binds loopback only, so the tunnel is the sole way to reach it.

## Collector

```bash
python3 tools/ingest/nova_ingest.py --root ~/nova-data --token nova-contrib-1
```

Stdlib only, no dependencies. Flags: `--port` (8787), `--quota-gb` (200), `--token` (must match
`CLIENT_TOKEN` in [ContributionUploader.java](../../src/client/java/com/profps/client/data/ContributionUploader.java)).

It refuses anything that is not a `POST /v1/ticks` with the right bearer token, caps bodies at
8 MB, and inflates only as far as the first newline to read the batch header — the body itself is
never fully decompressed, so a gzip bomb costs nothing. Session and pseudonym are matched against
strict hex patterns before they are allowed anywhere near a file path. `GET /healthz` returns
counters.

Verified against a live server: valid batches `204`, wrong token `401`, `../../../../etc/passwd`
as a session id `400`, and a 199 KB body that inflates to 200 MB `400`.

## Tunnel

One-time, needs a domain on Cloudflare (~$10/yr — a bare IP cannot get a TLS certificate, which is
why this is not just "point it at my address"):

```bash
brew install cloudflared
cloudflared tunnel login                              # pick your domain in the browser
cloudflared tunnel create nova-ingest                 # writes ~/.cloudflared/<UUID>.json
cloudflared tunnel route dns nova-ingest ingest.yourdomain.com
```

`~/.cloudflared/config.yml`:

```yaml
tunnel: nova-ingest
credentials-file: /Users/YOU/.cloudflared/<UUID>.json
ingress:
  - hostname: ingest.yourdomain.com
    service: http://127.0.0.1:8787
  - service: http_status:404
```

```bash
cloudflared tunnel run nova-ingest      # foreground, to test
sudo cloudflared service install        # then at boot
```

Set `dataContributionEndpoint` in `profps.json` (or the default in `ProFPSConfig`) to
`https://ingest.yourdomain.com`. The client refuses anything that is not `https://`.

**Set these two rules in the Cloudflare dashboard before you publish a build.** The token ships in
the jar, so it filters scanners, not anyone who decompiles it:

- Rate limiting: `/v1/ticks`, ~30 requests/min per IP. A real client sends 6.
- WAF: block any method other than POST on that path.

## Reading the corpus

```bash
python3 tools/ingest/read_ticks.py ~/nova-data                    # summary
python3 tools/ingest/read_ticks.py ~/nova-data --activity combat  # one activity
python3 tools/ingest/read_ticks.py ~/nova-data --human-only       # drop module-driven ticks
python3 tools/ingest/read_ticks.py ~/nova-data --csv out.csv
```

## Format

One file per session, `~/nova-data/<day>/<pseudonym>/<session>.ndjson.gz`. Each uploaded batch is
appended as its own gzip member; concatenated members are a valid gzip stream, so the whole session
opens with `gzip.open` and nothing is ever recompressed.

Inside, a line whose `t` is `header` establishes the schema and a string dictionary for the rows
after it. Batches each carry their own dictionary, so a reader tracks the current one as it walks
the file. Rows are positional arrays, not objects:

```json
{"t":"header","schema":1,"seq":0,"session":"…","pseudonym":"…","location":false,
 "fields":["tick","ms","rel_x",…],"entity_fields":["type","is_player",…],"dict":["minecraft:stone",…]}
{"n":0,"f":[0,0,0.02,…],"e":[[0,1,…]],"v":[3,4]}
```

`f` is the local player, `e` is up to four nearby entities relative to you, `v` is the tick's
events. String-valued columns (`main_item`, `dim`, `block_*`, entity `type`, event names) are
indices into `dict`.

Field order **is** the wire format. Append to the end of `FIELDS` / `ENTITY_FIELDS` in
[DataContribution.java](../../src/client/java/com/profps/client/data/DataContribution.java) and bump
`SCHEMA`; never insert into the middle. A row writer that disagrees with the declared count throws
on the first tick instead of silently shifting every later column.

### Activity and segments

Every tick is labelled `combat`, `mining`, `building`, `traveling`, `falling`, `swimming`,
`riding`, `idle` or `menu`, and consecutive ticks of the same label share a `segment` number. So
"every PvP engagement" is a filter on two columns rather than a search.

Combat is held for three seconds past the last exchange — the disengage and the reposition are part
of the fight, and cutting at the last swing would teach a model that fights stop mid-motion. `pvp`
distinguishes a player opponent from a mob; `threat_dist` is the nearest one.

Segments also break across gaps. When the module gate drops a run of ticks, the next kept tick
starts a fresh segment, so nothing downstream reads across a hole as continuous motion.

```bash
python3 read_ticks.py ~/nova-data --activity combat
```

### Module filtering

This is what makes the corpus real gameplay rather than a recording of the client's own modules.
A model trained on Triggerbot's swings learns Triggerbot's timing; one trained on Freecam learns
to fly.

The gate walks the live module catalogue every tick, and **any module it does not explicitly
recognise as harmless suppresses recording**. New modules are excluded until somebody classifies
them — the fail-safe points the right way. Three scopes:

| Scope | Effect | Modules |
|---|---|---|
| `NONE` | recorded normally | `fullbright`, `nickname`, `nickother` |
| `BLOCKS` | mining and building ticks dropped | `autotool`, `fastbreak`, `breakon`, `fastplace`, `autosign` |
| `COMBAT` | fighting ticks dropped | `totem`, `fastuse`, `autoarmor`, `refill`, `autohotbar`, `cheststeal`, `invcleaner` |
| `ALL` | nothing recorded | **everything else** — all 60-odd movement, aim, view, netcode and ESP modules |

The scopes are independent vetoes, not a severity ladder: AutoTool and Auto Totem on together
suppress mining *and* combat while still keeping traversal. ESP-type modules are `ALL` on purpose —
seeing players through walls changes how someone moves, so those ticks are not ordinary play even
though no packet was forged.

Momentary modules report their toggle as permanently off and fire straight off a keybind, so the
gate also treats a held module keybind as active. After anything clears, recording stays off for a
further 40 ticks: knockback, momentum and swing cooldown outlive the module that caused them.

`overridden` is a backstop on top of all that — set whenever the player's real keys disagree with
the input the body was handed. `--human-only` drops those rows.

**Expect this to reject a lot.** On a client where somebody keeps half a dozen modules on, most
sessions contribute nothing, which is the correct outcome. The Data settings page shows a live
`Recording` / `Paused · <module> is on` line so it is never a mystery.

`abs_x/abs_y/abs_z` are zero unless the contributor turned on location data; the header's
`"location"` flag says which, so zeros are never mistaken for someone standing at world origin.

## Volume

A synthetic 400-tick batch gzips to 6.6 KB (~16 B/tick, ~330 B/s per player). Real play compresses
worse than the test pattern — budget somewhere in the 1–3 KB/s range per contributor, so roughly
5–10 MB per player-hour, and size the disk from there.

## What is collected

Position relative to where the session started, velocity, look angles and their per-tick deltas,
ground/collision/fluid state, health and hunger, the raw keys held versus the input actually
applied, held items, nearby entities as offsets from the player, and the blocks around the feet.

Not collected: username, account UUID, chat, inventory contents, and — unless the contributor opts
in separately — world coordinates and the server address. Recordings are tagged with a salted hash
of the account UUID so one contributor's sessions group together for train/test splits without the
account itself ever leaving their machine.
