package com.example.agentbutton.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * A minimal Model Context Protocol server embedded in the Minecraft client.
 *
 * <p>Transport: MCP "Streamable HTTP" (single endpoint {@code /mcp}). For each JSON-RPC
 * request we return a single {@code application/json} response — a valid Streamable-HTTP
 * server that simply never opens an SSE stream. Notifications/responses are answered with
 * {@code 202 Accepted}. The server binds to {@code 127.0.0.1} and validates the
 * {@code Origin} header to guard against DNS-rebinding, per the spec's security notes.
 */
public final class McpServer {
	public static final String SERVER_NAME = "minecraft-agentbutton";
	public static final String SERVER_VERSION = "1.0.0";
	public static final String DEFAULT_PROTOCOL = "2025-06-18";

	private static final Logger LOGGER = LoggerFactory.getLogger("agentbutton/mcp");
	private static final Gson GSON = new Gson();

	private final int port;
	private final McpTools tools = new McpTools();
	private HttpServer http;

	public McpServer(int port) {
		this.port = port;
	}

	public int port() {
		return port;
	}

	public void start() throws IOException {
		http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
		http.createContext("/mcp", this::handle);
		http.setExecutor(Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "agentbutton-mcp");
			t.setDaemon(true);
			return t;
		}));
		http.start();
		LOGGER.info("MCP server listening on http://127.0.0.1:{}/mcp", port);
	}

	public void stop() {
		if (http != null) {
			http.stop(0);
			LOGGER.info("MCP server stopped");
		}
	}

	private void handle(HttpExchange ex) throws IOException {
		try {
			String origin = ex.getRequestHeaders().getFirst("Origin");
			if (origin != null && !isLocalOrigin(origin)) {
				respond(ex, 403, "text/plain", "Forbidden origin");
				return;
			}

			String method = ex.getRequestMethod();
			switch (method) {
				case "GET" -> { // we do not offer a server-initiated SSE stream
					ex.getResponseHeaders().add("Allow", "POST, DELETE");
					respond(ex, 405, "text/plain", "SSE stream not supported");
					return;
				}
				case "DELETE" -> { // session termination — we are stateless, just acknowledge
					respond(ex, 200, "text/plain", "");
					return;
				}
				case "POST" -> { /* handled below */ }
				default -> {
					respond(ex, 405, "text/plain", "Method not allowed");
					return;
				}
			}

			String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			JsonElement parsed = JsonParser.parseString(body);
			if (!parsed.isJsonObject()) {
				// Batches are not supported; respond with a parse error (no id).
				respond(ex, 200, "application/json", GSON.toJson(error(JsonNull.INSTANCE, -32600, "Only single JSON-RPC objects are supported")));
				return;
			}

			JsonObject msg = parsed.getAsJsonObject();
			boolean hasMethod = msg.has("method");
			boolean hasId = msg.has("id") && !msg.get("id").isJsonNull();

			// Notification (no id) or a client->server response: accept with 202, no body.
			if (!hasMethod || !hasId) {
				respond(ex, 202, "application/json", "");
				return;
			}

			JsonObject response = dispatch(msg);
			respond(ex, 200, "application/json", GSON.toJson(response));
		} catch (Exception e) {
			LOGGER.warn("MCP request handling failed", e);
			respond(ex, 200, "application/json", GSON.toJson(error(JsonNull.INSTANCE, -32700, "Parse error: " + e.getMessage())));
		}
	}

	private JsonObject dispatch(JsonObject req) {
		JsonElement id = req.get("id");
		String method = req.get("method").getAsString();
		JsonObject params = req.has("params") && req.get("params").isJsonObject()
				? req.getAsJsonObject("params") : new JsonObject();

		try {
			return switch (method) {
				case "initialize" -> result(id, initialize(params));
				case "ping" -> result(id, new JsonObject());
				case "tools/list" -> result(id, tools.list());
				case "tools/call" -> result(id, tools.call(params));
				default -> error(id, -32601, "Method not found: " + method);
			};
		} catch (Exception e) {
			LOGGER.warn("MCP method '{}' failed", method, e);
			return error(id, -32603, "Internal error: " + e.getMessage());
		}
	}

	private JsonObject initialize(JsonObject params) {
		String proto = params.has("protocolVersion")
				? params.get("protocolVersion").getAsString() : DEFAULT_PROTOCOL;

		JsonObject caps = new JsonObject();
		JsonObject toolsCap = new JsonObject();
		toolsCap.addProperty("listChanged", false);
		caps.add("tools", toolsCap);

		JsonObject info = new JsonObject();
		info.addProperty("name", SERVER_NAME);
		info.addProperty("version", SERVER_VERSION);

		JsonObject r = new JsonObject();
		r.addProperty("protocolVersion", proto);
		r.add("capabilities", caps);
		r.add("serverInfo", info);
		r.addProperty("instructions",
				"Drive a running Minecraft client: read and click the on-screen GUI buttons "
				+ "(get_screen, click_button) and manage multiplayer servers "
				+ "(list_servers, join_server, disconnect, get_status).");
		return r;
	}

	// --- JSON-RPC envelope helpers ---

	static JsonObject result(JsonElement id, JsonObject result) {
		JsonObject o = base(id);
		o.add("result", result);
		return o;
	}

	static JsonObject error(JsonElement id, int code, String message) {
		JsonObject o = base(id);
		JsonObject err = new JsonObject();
		err.addProperty("code", code);
		err.addProperty("message", message);
		o.add("error", err);
		return o;
	}

	private static JsonObject base(JsonElement id) {
		JsonObject o = new JsonObject();
		o.addProperty("jsonrpc", "2.0");
		o.add("id", id == null ? JsonNull.INSTANCE : id);
		return o;
	}

	private static boolean isLocalOrigin(String origin) {
		return origin.startsWith("http://localhost") || origin.startsWith("https://localhost")
				|| origin.startsWith("http://127.0.0.1") || origin.startsWith("https://127.0.0.1")
				|| origin.startsWith("http://[::1]");
	}

	private static void respond(HttpExchange ex, int status, String contentType, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", contentType);
		if (status == 202 || bytes.length == 0) {
			ex.sendResponseHeaders(status, -1); // no response body
			ex.close();
			return;
		}
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}
}
