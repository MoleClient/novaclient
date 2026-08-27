package com.profps.client.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.profps.client.config.ProFPSConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives the real {@code RowWriter}, header and gzip path, then reads the bytes back. */
class ContributionEncodingTest {
	private static final String SESSION = "11112222-3333-4444-5555-666677778888";
	private static final String PSEUDONYM = "0123456789abcdef";

	private static ContributionUploader uploader(boolean location) {
		ProFPSConfig config = new ProFPSConfig();
		config.dataContributionLocation = location;
		ContributionUploader uploader = new ContributionUploader(config);
		uploader.beginSession(SESSION, PSEUDONYM, location ? "play.example.net" : null);
		return uploader;
	}

	/** Fills every declared column with a type-appropriate value. */
	private static String buildRow(ContributionUploader uploader, long tick) {
		DataContribution.RowWriter w =
				new DataContribution.RowWriter(DataContribution.FIELDS.length, uploader);
		for (String field : DataContribution.FIELDS) {
			switch (field) {
				case "main_item" -> w.s("minecraft:netherite_sword");
				case "off_item" -> w.s("minecraft:totem_of_undying");
				case "dim" -> w.s("minecraft:overworld");
				case "block_below" -> w.s("minecraft:deepslate");
				case "block_feet", "block_head" -> w.s("minecraft:air");
				case "activity" -> w.s(tick % 2 == 0 ? ActivityClassifier.COMBAT : ActivityClassifier.TRAVELING);
				case "tick" -> w.n(tick);
				case "on_ground", "key_forward", "pvp" -> w.b(true);
				case "overridden" -> w.b(false);
				default -> w.n(tick * 0.125D);
			}
		}
		List<String> entities = new ArrayList<>();
		DataContribution.RowWriter e =
				new DataContribution.RowWriter(DataContribution.ENTITY_FIELDS.length, uploader);
		for (String field : DataContribution.ENTITY_FIELDS) {
			switch (field) {
				case "type" -> e.s("minecraft:player");
				case "is_player" -> e.b(true);
				default -> e.n(1.5D);
			}
		}
		entities.add(e.fields());
		return w.finish(tick, entities, List.of("attack", "swing"));
	}

	private static List<JsonObject> readBack(byte[] gzip) throws IOException {
		List<JsonObject> lines = new ArrayList<>();
		try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				if (!line.isBlank()) lines.add(JsonParser.parseString(line).getAsJsonObject());
			}
		}
		return lines;
	}

	@Test
	void rowWidthMatchesTheDeclaredSchema() {
		ContributionUploader uploader = uploader(false);
		JsonArray fields = JsonParser.parseString(buildRow(uploader, 0L))
				.getAsJsonObject().getAsJsonArray("f");
		assertEquals(DataContribution.FIELDS.length, fields.size());
	}

	@Test
	void aShortRowIsRejectedRatherThanShipped() {
		ContributionUploader uploader = uploader(false);
		DataContribution.RowWriter w = new DataContribution.RowWriter(5, uploader);
		w.n(1.0D);
		w.n(2.0D);
		IllegalStateException error = assertThrows(IllegalStateException.class, w::fields);
		assertTrue(error.getMessage().contains("schema declares 5"), error.getMessage());
	}

	@Test
	void aBatchRoundTripsThroughGzip() throws IOException {
		ContributionUploader uploader = uploader(true);
		List<String> rows = new ArrayList<>();
		for (long tick = 0; tick < 50; tick++) {
			rows.add(buildRow(uploader, tick));
		}
		// header() must be built after the rows; row encoding fills the dictionary it carries.
		byte[] gzip = ContributionUploader.compress(new ContributionUploader.Batch(uploader.header(), rows));
		List<JsonObject> lines = readBack(gzip);

		JsonObject header = lines.get(0);
		JsonArray dict = header.getAsJsonArray("dict");
		JsonArray fieldNames = header.getAsJsonArray("fields");

		assertAll(
				() -> assertEquals(51, lines.size()),
				() -> assertEquals("header", header.get("t").getAsString()),
				() -> assertEquals(DataContribution.SCHEMA, header.get("schema").getAsInt()),
				() -> assertEquals(SESSION, header.get("session").getAsString()),
				() -> assertEquals(PSEUDONYM, header.get("pseudonym").getAsString()),
				() -> assertTrue(header.get("location").getAsBoolean()),
				() -> assertEquals("play.example.net", header.get("server").getAsString()),
				() -> assertEquals(DataContribution.FIELDS.length, fieldNames.size()),
				() -> assertEquals(DataContribution.ENTITY_FIELDS.length,
						header.getAsJsonArray("entity_fields").size()));

		// Resolve a dictionary-encoded column the way the reader does.
		int mainItemIndex = indexOf(fieldNames, "main_item");
		int activityIndex = indexOf(fieldNames, "activity");
		JsonObject firstRow = lines.get(1);
		JsonArray f = firstRow.getAsJsonArray("f");
		assertAll(
				() -> assertEquals(0, firstRow.get("n").getAsLong()),
				() -> assertEquals("minecraft:netherite_sword",
						dict.get(f.get(mainItemIndex).getAsInt()).getAsString()),
				() -> assertEquals(ActivityClassifier.COMBAT,
						dict.get(f.get(activityIndex).getAsInt()).getAsString()),
				() -> assertEquals(1, firstRow.getAsJsonArray("e").size()),
				() -> assertEquals(DataContribution.ENTITY_FIELDS.length,
						firstRow.getAsJsonArray("e").get(0).getAsJsonArray().size()),
				() -> assertEquals(2, firstRow.getAsJsonArray("v").size()));
	}

	/** Location off must zero the coordinate columns, not merely omit them from the header. */
	@Test
	void locationOffZeroesTheCoordinateColumns() throws IOException {
		ContributionUploader uploader = uploader(false);
		String row = buildRow(uploader, 7L);
		byte[] gzip = ContributionUploader.compress(
				new ContributionUploader.Batch(uploader.header(), List.of(row)));
		List<JsonObject> lines = readBack(gzip);
		JsonArray names = lines.get(0).getAsJsonArray("fields");
		JsonArray f = lines.get(1).getAsJsonArray("f");

		assertAll(
				() -> assertTrue(lines.get(0).get("location") != null
						&& !lines.get(0).get("location").getAsBoolean()),
				() -> assertTrue(lines.get(0).get("server") == null, "server must be absent"),
				// buildRow writes the same filler into every numeric column, so both abs_* match.
				() -> assertEquals(f.get(indexOf(names, "abs_x")), f.get(indexOf(names, "abs_y"))));
	}

	/** Writes a sample batch for the Python collector and reader to run against. */
	@Test
	void emitsASampleBatchForTheCollector() throws IOException {
		ContributionUploader uploader = uploader(true);
		List<String> rows = new ArrayList<>();
		for (long tick = 0; tick < 200; tick++) {
			rows.add(buildRow(uploader, tick));
		}
		byte[] gzip = ContributionUploader.compress(new ContributionUploader.Batch(uploader.header(), rows));
		Path out = Path.of("build", "tmp", "contribution-sample.ndjson.gz");
		Files.createDirectories(out.getParent());
		Files.write(out, gzip);
		assertTrue(Files.size(out) > 0);
	}

	private static int indexOf(JsonArray names, String field) {
		for (int i = 0; i < names.size(); i++) {
			if (names.get(i).getAsString().equals(field)) return i;
		}
		throw new IllegalArgumentException("no such field: " + field);
	}
}
