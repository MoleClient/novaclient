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

/** Client-side name and skin spoofing for the Nickname and Nick Other modules. */
public final class NicknameManager {
	private static final HttpClient HTTP = HttpClient.newHttpClient();

	// Active replacement set, rebuilt only when the config inputs change.
	private static volatile boolean active;
	private static volatile Pattern pattern;            // alternation of all real names
	private static volatile Map<String, String> map = Map.of(); // real -> nick

	private static final Map<String, SkinTextures> resolvedSkins = new ConcurrentHashMap<>(); // realName -> skin
	private static final Map<String, String> skinSourceFor = new ConcurrentHashMap<>(); // realName -> source username
	private static final Map<String, SkinTextures> sourceCache = new ConcurrentHashMap<>(); // source -> skin

	private static String lastSignature = "";

	private NicknameManager() {}

	/** Rebuilds the name and skin replacement sets. Called once per client tick. */
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

		// Standalone skin override: applies without a name change and ignores the master toggle.
		String selfForSkin = selfName();
		if (selfForSkin != null) {
			String novaSkin = trim(config.novaSkinUsername);
			if (!novaSkin.isEmpty()) {
				wantSkin.putIfAbsent(selfForSkin, novaSkin);
			}
		}

		String signature = desired + "|" + wantSkin;
		if (signature.equals(lastSignature)) return;
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
		// Names inside translatable args survive the rebuild, so flatten to the root style.
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
		if (replaced.equals(original)) return ordered; // unchanged line keeps its styling
		return Text.literal(replaced).setStyle(firstStyle[0]).asOrderedText();
	}

	/** Resolves a username's skin for the home-screen preview. */
	public static CompletableFuture<SkinTextures> previewSkin(String username) {
		return fetchSkin(username);
	}

	/** The spoofed skin to serve for a player shown under {@code realName}, or null. */
	public static SkinTextures skinFor(String realName) {
		// Not gated on `active`, which tracks name spoofing only; a skin override can stand alone.
		return realName == null ? null : resolvedSkins.get(realName);
	}

	/**
	 * The local player's resolved skin override, keyed by session username.
	 * The entity game profile name can differ from the session name, so it is not usable as a key.
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
			if (source.equals(skinSourceFor.get(real))) return; // already resolved or in flight
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

	// One shared lookup per username; Mojang rate-limits (429) parallel duplicate requests.
	private static final Map<String, CompletableFuture<SkinTextures>> inFlight = new ConcurrentHashMap<>();

	private static CompletableFuture<SkinTextures> fetchSkin(String username) {
		return inFlight.computeIfAbsent(username, u -> doFetchSkin(u).whenComplete((skin, ex) -> {
			if (skin == null) inFlight.remove(u); // evict failures so a later attempt can retry
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

	/** Resolves a username to a Mojang {@link GameProfile} with its textures property. */
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

			// The 2-arg GameProfile constructor yields an immutable property map, so build one here.
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
		// Some Mojang/CDN front-ends reject requests without a User-Agent with 403.
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
