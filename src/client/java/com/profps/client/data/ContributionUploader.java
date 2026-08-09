package com.profps.client.data;

import com.profps.ProFPS;
import com.profps.client.config.ProFPSConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Batches recorded rows, compresses them and posts them to the collector.
 *
 * <p>Threading is deliberately one-way. The client thread only ever appends to the batch it owns
 * and hands the sealed batch to a queue; every expensive step — gzip, TLS, disk — happens on a
 * daemon worker. Nothing the collector does can stall a tick: the queue is bounded and overflow is
 * dropped and counted rather than waited on. Losing rows is an acceptable failure for a training
 * corpus; a stuttering client is not.
 *
 * <p>Batches that fail to send spool to disk and are retried later, so a contributor playing
 * through a network drop still lands their session once they are back.
 */
final class ContributionUploader {
	private static final int BATCH_ROWS = 400;        // ~20s of ticks
	private static final long BATCH_MS = 10_000L;
	private static final int QUEUE_DEPTH = 16;
	private static final int SPOOL_DRAIN_PER_SEND = 3;
	private static final long SPOOL_CAP_BYTES = 256L * 1024L * 1024L;
	private static final String SPOOL_DIR = "profps_data_spool";

	/**
	 * Ships in the jar, so it is a filter and not a secret — it keeps drive-by scanners that find
	 * the hostname from filling the collector, nothing more. Real protection is the tunnel, the
	 * edge rate limit and the collector only ever appending bytes.
	 */
	private static final String CLIENT_TOKEN = "nova-Kxq9lQRPaHosMEl9uWf85wBF3vyprjws";

	private final ProFPSConfig config;
	private final BlockingQueue<Batch> outbound = new ArrayBlockingQueue<>(QUEUE_DEPTH);
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();

	// Client-thread state only. The dictionary is per batch and travels with it, so the worker
	// never reads a map the client thread is still writing.
	private final List<String> pending = new ArrayList<>(BATCH_ROWS);
	private final Map<String, Integer> dictionary = new LinkedHashMap<>();
	private long batchOpenedMs;
	private String sessionId = "";
	private String pseudonym = "";
	private String serverLabel;
	private int sequence;
	private int dropped;

	// Observability. Telemetry that fails quietly is worse than none: without these the only
	// symptom of a wrong endpoint is that nothing ever arrives, which looks identical to
	// "nobody has played yet". Read out by /nova data.
	private volatile int batchesSent;
	private volatile int batchesFailed;
	private volatile int rowsSent;
	private volatile String lastStatus = "nothing sent yet";

	int batchesSent() {
		return batchesSent;
	}

	int batchesFailed() {
		return batchesFailed;
	}

	int rowsSent() {
		return rowsSent;
	}

	int queued() {
		return outbound.size() + pending.size();
	}

	int dropped() {
		return dropped;
	}

	String lastStatus() {
		return lastStatus;
	}

	/** How many batches are sitting on disk waiting for the collector to come back. */
	static int spooled() {
		Path dir = spoolDir();
		if (!Files.isDirectory(dir)) return 0;
		try (Stream<Path> files = Files.list(dir)) {
			return (int) files.filter(Files::isRegularFile).count();
		} catch (IOException exception) {
			return -1;
		}
	}

	private volatile boolean running = true;
	private Thread worker;

	ContributionUploader(ProFPSConfig config) {
		this.config = config;
		// Quitting the game kills the daemon worker wherever it happens to be, so the exit path
		// writes anything still in hand straight to the spool instead of trying to send it. The
		// next launch picks it up. Without this, every session loses its tail.
		Runtime.getRuntime().addShutdownHook(new Thread(this::spoolRemaining, "profps-data-exit"));
	}

	private void spoolRemaining() {
		try {
			List<Batch> remaining = new ArrayList<>();
			if (!pending.isEmpty()) remaining.add(new Batch(header(), List.copyOf(pending)));
			outbound.drainTo(remaining);
			for (Batch batch : remaining) {
				spool(compress(batch));
			}
		} catch (Exception ignored) {
			// Nothing useful to do on the way out; the rows are already lost either way.
		}
	}

	// ── Client thread ────────────────────────────────────────────────────────────

	void beginSession(String sessionId, String pseudonym, String serverLabel) {
		this.sessionId = sessionId;
		this.pseudonym = pseudonym;
		this.serverLabel = serverLabel;
		this.sequence = 0;
		resetBatch();
	}

	/** Interns a string into the current batch dictionary and returns its index. */
	int intern(String value) {
		Integer existing = dictionary.get(value);
		if (existing != null) return existing;
		int index = dictionary.size();
		dictionary.put(value, index);
		return index;
	}

	void submit(String row) {
		pending.add(row);
		if (pending.size() >= BATCH_ROWS || System.currentTimeMillis() - batchOpenedMs >= BATCH_MS) {
			flush();
		}
	}

	/** Seals whatever is buffered and queues it. Safe to call with nothing pending. */
	void flush() {
		if (pending.isEmpty()) {
			batchOpenedMs = System.currentTimeMillis();
			return;
		}
		Batch batch = new Batch(header(), List.copyOf(pending));
		resetBatch();
		ensureWorker();
		if (!outbound.offer(batch)) {
			// The worker is behind — most likely the collector is down and every send is
			// timing out. Drop this batch rather than let the queue become back-pressure
			// on the render loop.
			dropped++;
			if (dropped % 20 == 1) {
				ProFPS.LOGGER.warn("Data contribution backlogged; dropped {} batches so far.", dropped);
			}
		}
	}

	/** Throws away buffered rows without sending them — the mid-session opt-out path. */
	void discard() {
		resetBatch();
	}

	/**
	 * Seals the session's last batch and leaves the worker running to send it. Disconnecting is
	 * not shutting down — the player is usually about to join somewhere else.
	 */
	void endSession() {
		flush();
	}

	private void resetBatch() {
		pending.clear();
		dictionary.clear();
		batchOpenedMs = System.currentTimeMillis();
	}

	/**
	 * The first line of every batch. It carries the schema so a reader never has to guess at
	 * column order, and states plainly whether the location columns mean anything.
	 */
	String header() {
		StringBuilder out = new StringBuilder(1024);
		out.append("{\"t\":\"header\"");
		out.append(",\"schema\":").append(DataContribution.SCHEMA);
		out.append(",\"seq\":").append(sequence++);
		out.append(",\"session\":").append(quote(sessionId));
		out.append(",\"pseudonym\":").append(quote(pseudonym));
		out.append(",\"sent_ms\":").append(System.currentTimeMillis());
		out.append(",\"mc\":").append(quote(version("minecraft")));
		out.append(",\"mod\":").append(quote(version(ProFPS.MOD_ID)));
		out.append(",\"tick_rate\":20");
		out.append(",\"location\":").append(config.dataContributionLocation);
		if (serverLabel != null) out.append(",\"server\":").append(quote(serverLabel));
		out.append(",\"fields\":").append(names(DataContribution.FIELDS));
		out.append(",\"entity_fields\":").append(names(DataContribution.ENTITY_FIELDS));
		out.append(",\"dict\":[");
		boolean first = true;
		for (String key : dictionary.keySet()) {
			if (!first) out.append(',');
			out.append(quote(key));
			first = false;
		}
		return out.append("]}").toString();
	}

	private static String names(String[] values) {
		StringBuilder out = new StringBuilder(values.length * 12);
		out.append('[');
		for (int i = 0; i < values.length; i++) {
			if (i > 0) out.append(',');
			out.append(quote(values[i]));
		}
		return out.append(']').toString();
	}

	private static String version(String modId) {
		try {
			return FabricLoader.getInstance().getModContainer(modId)
					.map(container -> container.getMetadata().getVersion().getFriendlyString())
					.orElse("unknown");
		} catch (Throwable ignored) {
			// No Fabric runtime — a unit test exercising the encoder. The version is a label on
			// the batch, not something the format depends on.
			return "unknown";
		}
	}

	// ── Worker thread ────────────────────────────────────────────────────────────

	private synchronized void ensureWorker() {
		if (worker != null && worker.isAlive()) return;
		running = true;
		worker = new Thread(this::run, "profps-data-contribution");
		worker.setDaemon(true);
		// Below the render and tick threads: shipping telemetry never competes for a core
		// with the thing being measured.
		worker.setPriority(Thread.MIN_PRIORITY);
		worker.start();
	}

	private void run() {
		while (running || !outbound.isEmpty()) {
			try {
				Batch batch = outbound.poll(2, TimeUnit.SECONDS);
				if (batch == null) continue;
				byte[] body = compress(batch);
				if (send(body)) {
					batchesSent++;
					rowsSent += batch.rows().size();
					drainSpool();
				} else {
					batchesFailed++;
					spool(body);
				}
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				return;
			} catch (Exception exception) {
				ProFPS.LOGGER.warn("Data contribution send failed.", exception);
			}
		}
	}

	static byte[] compress(Batch batch) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
		try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
			gzip.write(batch.header().getBytes(StandardCharsets.UTF_8));
			gzip.write('\n');
			for (String row : batch.rows()) {
				gzip.write(row.getBytes(StandardCharsets.UTF_8));
				gzip.write('\n');
			}
		}
		return bytes.toByteArray();
	}

	private boolean send(byte[] body) {
		try {
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(config.dataContributionEndpoint + "/v1/ticks"))
					.timeout(Duration.ofSeconds(20))
					.header("Content-Type", "application/x-ndjson")
					.header("Content-Encoding", "gzip")
					.header("Authorization", "Bearer " + CLIENT_TOKEN)
					.header("X-Nova-Schema", Integer.toString(DataContribution.SCHEMA))
					.POST(HttpRequest.BodyPublishers.ofByteArray(body))
					.build();
			HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
			boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
			lastStatus = "HTTP " + response.statusCode();
			return ok;
		} catch (Exception exception) {
			// The message, not the stack: this is read out in chat by /nova data, and
			// "ConnectException: Connection refused" is exactly the useful part.
			lastStatus = exception.getClass().getSimpleName()
					+ (exception.getMessage() == null ? "" : ": " + exception.getMessage());
			return false;
		}
	}

	// ── Spool ────────────────────────────────────────────────────────────────────

	private static Path spoolDir() {
		return FabricLoader.getInstance().getConfigDir().resolve(SPOOL_DIR);
	}

	private void spool(byte[] body) {
		try {
			Path dir = spoolDir();
			Files.createDirectories(dir);
			if (spoolBytes(dir) + body.length > SPOOL_CAP_BYTES) {
				evictOldest(dir, body.length);
			}
			Files.write(dir.resolve(System.currentTimeMillis() + "-" + System.nanoTime() + ".ndjson.gz"), body);
		} catch (IOException exception) {
			ProFPS.LOGGER.warn("Failed to spool contribution batch.", exception);
		}
	}

	/** Sends a few spooled batches per successful upload so a backlog drains without a burst. */
	private void drainSpool() {
		Path dir = spoolDir();
		if (!Files.isDirectory(dir)) return;
		try (Stream<Path> files = Files.list(dir)) {
			List<Path> oldest = files.filter(Files::isRegularFile)
					.sorted(Comparator.comparing(Path::getFileName))
					.limit(SPOOL_DRAIN_PER_SEND)
					.toList();
			for (Path file : oldest) {
				byte[] body = Files.readAllBytes(file);
				if (!send(body)) return;
				Files.deleteIfExists(file);
			}
		} catch (IOException exception) {
			ProFPS.LOGGER.warn("Failed to drain contribution spool.", exception);
		}
	}

	private static long spoolBytes(Path dir) throws IOException {
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(Files::isRegularFile).mapToLong(path -> {
				try {
					return Files.size(path);
				} catch (IOException ignored) {
					return 0L;
				}
			}).sum();
		}
	}

	/** Oldest-first eviction: a long offline stretch keeps the most recent play, not the stalest. */
	private static void evictOldest(Path dir, long needed) throws IOException {
		try (Stream<Path> files = Files.list(dir)) {
			long freed = 0L;
			for (Path file : files.filter(Files::isRegularFile)
					.sorted(Comparator.comparing(Path::getFileName)).toList()) {
				long size = Files.size(file);
				Files.deleteIfExists(file);
				freed += size;
				if (freed >= needed) return;
			}
		}
	}

	// ── Encoding helpers ─────────────────────────────────────────────────────────

	/** Compact fixed-precision numbers: millimetre and millidegree resolution is plenty. */
	static String num(double value) {
		if (!Double.isFinite(value)) return "0";
		double rounded = Math.round(value * 1000.0D) / 1000.0D;
		if (rounded == Math.rint(rounded) && Math.abs(rounded) < 1.0E15D) {
			return Long.toString((long) rounded);
		}
		return Double.toString(rounded);
	}

	static String quote(String value) {
		StringBuilder out = new StringBuilder(value.length() + 2);
		out.append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
					else out.append(c);
				}
			}
		}
		return out.append('"').toString();
	}

	record Batch(String header, List<String> rows) {
	}
}
