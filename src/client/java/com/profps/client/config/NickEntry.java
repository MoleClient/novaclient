package com.profps.client.config;

/**
 * One "Nick Other" mapping: replace everyone shown as {@link #target} with
 * {@link #nick} (everywhere — chat, tab, nametags, scoreboards), and optionally
 * pull {@link #nick}'s skin too. Plain mutable fields with a no-arg constructor so
 * Gson round-trips it inside the config list.
 */
public final class NickEntry {
	public String target = "";
	public String nick = "";
	public boolean skin = true;

	public NickEntry() {}

	public NickEntry(String target, String nick, boolean skin) {
		this.target = target;
		this.nick = nick;
		this.skin = skin;
	}
}
