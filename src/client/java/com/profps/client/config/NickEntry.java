package com.profps.client.config;

/** One "Nick Other" mapping. Mutable fields with a no-arg constructor for Gson. */
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
