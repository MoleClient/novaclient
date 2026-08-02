package com.profps.client.classics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.profps.ProFPS;
import com.profps.client.config.NickEntry;
import com.profps.client.config.ProFPSConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.PlainTextContent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextContent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Universal client-side name (and skin) spoofing for the Nickname / Nick Other
 * modules. The goal is that the chosen name appears <i>everywhere</i> the real one
 * would — chat, tab list, nametags, scoreboards, team prefixes, death messages,
 * anything — and the real name is left nowhere.
 *
 * <p>Replacement happens at the rendering chokepoints. Every string the game draws
 * goes through one of {@code TextRenderer.draw(String|Text|OrderedText, …)}; the
 * three mixins funnel each into {@link #spoof}. A {@code Text} keeps its styling
 * (the tree is rebuilt), an {@code OrderedText} is flattened and re-laid (only when
 * it actually contains a target, so untouched lines keep their styling), and a raw
 * {@code String} is swapped directly. Chat is also caught at {@code ChatHud.addMessage}.
 *
 * <p>Skins are pulled the same way NameMC shows them: resolve the source username
 * to its Mojang profile, hand that to the vanilla skin provider, and serve the
 * resulting {@link SkinTextures} from a {@code PlayerListEntry} mixin so both the
 * tab head and the in-world model change.
 *
 * <p>This is purely cosmetic on the local client — the server still knows your real
 * identity; only what you see is rewritten.
 */
public final class NicknameManager {
	private static final HttpClient HTTP = HttpClient.newHttpClient();

	// Active replacement set, rebuilt only when the config inputs change.
	private static volatile boolean active;
	private static volatile Pattern pattern;            // alternation of all real names
	private static volatile Map<String, String> map = Map.of(); // real -> nick

	// realName -> resolved skin (served to the PlayerListEntry mixin)
	private static final Map<String, SkinTextures> resolvedSkins = new ConcurrentHashMap<>();
	private static final Map<String, String> skinSourceFor = new ConcurrentHashMap<>(); // realName -> source username
	private static final Map<String, SkinTextures> sourceCache = new ConcurrentHashMap<>(); // source -> skin (shared)

	private static String lastSignature = "";

	private NicknameManager() {}

	// ── Driven once per client tick ─────────────────────────────────────────────

	public static void update(ProFPSConfig config) {
		LinkedHashMap<String, String> desired = new LinkedHashMap<>();
		Map<String, String> wantSkin = new LinkedHashMap<>(); // realName -> source username

		if (config.enabled) {
			String selfReal = selfName();
			if (config.nicknameEnabled && selfReal != null) {
				String nick = trim(config.nicknameSelfName);
				if (!nick.isEmpty() && !nick.equals(selfReal)) {
					desired.put(selfReal, nick);
					if (config.nicknameSelfSkin) {
						String from = trim(config.nicknameSelfSkinFrom);
						wantSkin.put(selfReal, from.isEmpty() ? nick : from);
					}
				}
			}
			if (config.nickOtherEnabled && config.nickOtherEntries != null) {
				for (NickEntry e : config.nickOtherEntries) {
					String target = trim(e.target);
					String nick = trim(e.nick);
					if (target.isEmpty() || nick.isEmpty()) continue;
					desired.putIfAbsent(target, nick);
					if (e.skin) wantSkin.putIfAbsent(target, nick);
				}
			}
		}

		// NovaClient "Configure" skin: wear another player's skin everywhere YOU see (your own
		// model, hand, inventory, tab) WITHOUT changing your name. Applies independently of the
		// Nickname module and even with the master toggle off — it's a cosmetic you set on purpose.
		String selfForSkin = selfName();
		if (selfForSkin != null) {
			String novaSkin = trim(config.novaSkinUsername);
			if (!novaSkin.isEmpty()) {
				wantSkin.putIfAbsent(selfForSkin, novaSkin);
			}
		}

		String signature = desired + "|" + wantSkin;
		if (signature.equals(lastSignature)) return; // nothing changed → no rebuild
		lastSignature = signature;

		if (desired.isEmpty()) {
			pattern = null;
			map = Map.of();
			active = false;
		} else {
			map = new LinkedHashMap<>(desired);
			// Longest names first so an overlapping shorter name can't match inside one.
			List<String> keys = new ArrayList<>(desired.keySet());
			keys.sort((a, b) -> b.length() - a.length());
			StringBuilder regex = new StringBuilder();
			for (String k : keys) {
				if (regex.length() > 0) regex.append('|');
				regex.append(Pattern.quote(k));
			}
			pattern = Pattern.compile(regex.toString());
			active = true;
		}

		updateSkins(wantSkin);
	}

	// ── Text replacement (called from the mixins) ───────────────────────────────

	public static boolean isActive() {
		return active;
	}

	private static boolean matches(String s) {
		Pattern p = pattern;
		return active && p != null && s != null && !s.isEmpty() && p.matcher(s).find();
	}

	public static String spoof(String in) {
		if (!matches(in)) return in;
		Map<String, String> m = map;
		Matcher matcher = pattern.matcher(in);
		StringBuilder sb = new StringBuilder();
		while (matcher.find()) {
			String hit = matcher.group();
			matcher.appendReplacement(sb, Matcher.quoteReplacement(m.getOrDefault(hit, hit)));
		}
		matcher.appendTail(sb);
		return sb.toString();
	}

	public static Text spoof(Text text) {
		if (text == null || !active) return text;
		if (!matches(text.getString())) return text;
		MutableText rebuilt = rebuild(text);
		// If a name still survives (e.g. it lived inside translatable args, not a
		// literal/sibling), flatten the whole thing so nothing remains — keeping the
		// root style. Common case (literal names + siblings) keeps full styling.
		if (matches(rebuilt.getString())) {
			return Text.literal(spoof(text.getString())).setStyle(text.getStyle());
		}
		return rebuilt;
	}

	private static MutableText rebuild(Text text) {
		TextContent content = text.getContent();
		MutableText base = content instanceof PlainTextContent plain
				? Text.literal(spoof(plain.string()))
				: MutableText.of(content);
		base.setStyle(text.getStyle());
		for (Text sibling : text.getSiblings()) {
			base.append(rebuild(sibling));
		}
		return base;
	}

	public static OrderedText spoof(OrderedText ordered) {
		if (ordered == null || !active) return ordered;
		StringBuilder flat = new StringBuilder();
		Style[] firstStyle = {Style.EMPTY};
		boolean[] gotStyle = {false};
		ordered.accept((index, style, codePoint) -> {
			flat.appendCodePoint(codePoint);
			if (!gotStyle[0]) {
				firstStyle[0] = style;
				gotStyle[0] = true;
			}
			return true;
		});
		String original = flat.toString();
		String replaced = spoof(original);
		if (replaced.equals(original)) return ordered; // untouched line → keep its styling
		return Text.literal(replaced).setStyle(firstStyle[0]).asOrderedText();
	}

	// ── Skins ────────────────────────────────────────────────────────────────────

	/** The spoofed skin to serve for a player shown under {@code realName}, or null. */
	/** Resolve a username's skin (NameMC-style) for the home-screen preview. */
	public static CompletableFuture<SkinTextures> previewSkin(String username) {
		return fetchSkin(username);
	}

	public static SkinTextures skinFor(String realName) {
		// NOTE: do NOT gate on `active` (which only tracks NAME spoofing). A skin override can
		// be set with no name change (the home-screen Configure skin), and resolvedSkins only
		// ever holds entries we actually want — so its presence is the correct test.
		return realName == null ? null : resolvedSkins.get(realName);
	}

	/**
	 * Your OWN resolved skin override, looked up by the session username — exactly how
	 * {@link #update} keys it. The local player entity's {@code getGameProfile().name()} can
	 * differ from the session name (casing / offline mode), so keying the self-skin off the
	 * entity profile would miss; the in-world getSkin hook uses this for the local player.
	 */
	public static SkinTextures selfSkinOverride() {
		String self = selfName();
		return self == null ? null : resolvedSkins.get(self);
	}

	private static void updateSkins(Map<String, String> wantSkin) {
		// Drop skins for players no longer being spoofed.
		resolvedSkins.keySet().removeIf(real -> !wantSkin.containsKey(real));
		skinSourceFor.keySet().removeIf(real -> !wantSkin.containsKey(real));

		wantSkin.forEach((real, source) -> {
			if (source.equals(skinSourceFor.get(real))) return; // already resolving/resolved this source
			skinSourceFor.put(real, source);
			resolvedSkins.remove(real);
			SkinTextures cached = sourceCache.get(source);
			if (cached != null) {
				resolvedSkins.put(real, cached);
				return;
			}
			fetchSkin(source).thenAccept(skin -> {
				if (skin == null) return;
				sourceCache.put(source, skin);
				// Only apply if this player is still mapped to this source.
				if (source.equals(skinSourceFor.get(real))) {
					resolvedSkins.put(real, skin);
				}
			});
		});
	}

	// Shares one lookup per username so callers (preview + nickname + repeats) don't each fire
	// their own Mojang request and rate-limit (429) themselves. Successful futures stay cached;
	// failed ones are evicted so a later attempt can retry — but never as a parallel burst.
	private static final Map<String, CompletableFuture<SkinTextures>> inFlight = new ConcurrentHashMap<>();

	private static CompletableFuture<SkinTextures> fetchSkin(String username) {
		return inFlight.computeIfAbsent(username, u -> doFetchSkin(u).whenComplete((skin, ex) -> {
			if (skin == null) inFlight.remove(u); // allow a future retry; keep successes cached
		}));
	}

	private static CompletableFuture<SkinTextures> doFetchSkin(String username) {
		return CompletableFuture.supplyAsync(() -> resolveProfile(username))
				.thenCompose(profile -> {
					if (profile == null) {
						ProFPS.LOGGER.warn("Nickname: could not resolve Mojang profile for \"{}\" (network blocked / rate-limited / no such name) — skin stays default", username);
						return CompletableFuture.completedFuture(null);
					}
					try {
						return MinecraftClient.getInstance().getSkinProvider()
								.fetchSkinTextures(profile)
								.thenApply(opt -> opt.orElse(null))
								.thenApply(skin -> {
									if (skin == null) {
										ProFPS.LOGGER.warn("Nickname: \"{}\" resolved but has no usable skin texture — default skin used", username);
									} else {
										ProFPS.LOGGER.info("Nickname: loaded skin for \"{}\"", username);
									}
									return skin;
								});
					} catch (RuntimeException ex) {
						ProFPS.LOGGER.warn("Nickname: skin provider rejected profile for \"{}\"", username, ex);
						return CompletableFuture.completedFuture(null);
					}
				})
				.exceptionally(ex -> {
					ProFPS.LOGGER.warn("Nickname: failed to load skin for {}", username, ex);
					return null;
				});
	}

	/** Resolve a username to a Mojang {@link GameProfile} with its textures property. */
	private static GameProfile resolveProfile(String username) {
		try {
			String idBody = get("https://api.mojang.com/users/profiles/minecraft/" + username);
			if (idBody == null) return null;
			JsonObject idJson = JsonParser.parseString(idBody).getAsJsonObject();
			String rawId = idJson.get("id").getAsString();
			String name = idJson.get("name").getAsString();
			UUID uuid = dashUuid(rawId);

			String profileBody = get("https://sessionserver.mojang.com/session/minecraft/profile/" + rawId + "?unsigned=false");
			if (profileBody == null) return null;
			JsonObject profileJson = JsonParser.parseString(profileBody).getAsJsonObject();
			JsonArray props = profileJson.getAsJsonArray("properties");

			// authlib's 2-arg GameProfile gives an IMMUTABLE property map, so put() throws
			// UnsupportedOperationException. Build a mutable PropertyMap and pass it to the
			// 3-arg constructor instead.
			PropertyMap properties = new PropertyMap(com.google.common.collect.LinkedHashMultimap.create());
			for (int i = 0; i < props.size(); i++) {
				JsonObject prop = props.get(i).getAsJsonObject();
				if (!"textures".equals(prop.get("name").getAsString())) continue;
				String value = prop.get("value").getAsString();
				String signature = prop.has("signature") ? prop.get("signature").getAsString() : null;
				properties.put("textures",
						signature != null ? new Property("textures", value, signature) : new Property("textures", value));
			}
			return new GameProfile(uuid, name, properties);
		} catch (RuntimeException | InterruptedException | java.io.IOException ex) {
			ProFPS.LOGGER.warn("Nickname: error resolving profile for \"{}\"", username, ex);
			return null;
		}
	}

	private static String get(String url) throws java.io.IOException, InterruptedException {
		// A User-Agent is required-ish: some Mojang/CDN front-ends reject UA-less requests
		// with 403, which would make every skin look-up silently fail.
		HttpResponse<String> response = HTTP.send(
				HttpRequest.newBuilder(URI.create(url))
						.header("User-Agent", "NovaClient/1.0 (Minecraft skin preview)")
						.timeout(java.time.Duration.ofSeconds(8))
						.GET().build(),
				HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			ProFPS.LOGGER.warn("Nickname: GET {} -> HTTP {} (skin lookup will fail)", url, response.statusCode());
			return null;
		}
		return response.body();
	}

	private static UUID dashUuid(String raw) {
		if (raw.contains("-")) return UUID.fromString(raw);
		return UUID.fromString(raw.replaceFirst(
				"(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
				"$1-$2-$3-$4-$5"));
	}

	private static String selfName() {
		MinecraftClient mc = MinecraftClient.getInstance();
		return mc.getSession() == null ? null : mc.getSession().getUsername();
	}

	private static String trim(String s) {
		return s == null ? "" : s.trim();
	}
}
