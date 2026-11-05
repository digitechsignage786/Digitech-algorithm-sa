/**
 * Digitech Algorithm market-data gateway.
 * Deploy as a Cloudflare Worker and set TWELVE_DATA_API_KEY as a Worker secret.
 * Never put the provider key in Android, GitHub source, or a URL.
 */
const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET, OPTIONS",
  "access-control-allow-headers": "Content-Type",
};

function json(data, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: JSON_HEADERS });
}

function validSymbol(value) {
  return typeof value === "string" && /^[A-Za-z0-9:/._-]{1,40}$/.test(value);
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: JSON_HEADERS });
    }

    if (url.pathname === "/health" && request.method === "GET") {
      return json({ ok: true, service: "digitech-market-data-gateway" });
    }

    if (!env.TWELVE_DATA_API_KEY) {
      return json({ error: "Worker secret TWELVE_DATA_API_KEY is not configured" }, 503);
    }

    if (url.pathname === "/time_series" && request.method === "GET") {
      const symbol = url.searchParams.get("symbol") || "";
      const interval = url.searchParams.get("interval") || "";
      const allowedIntervals = new Set(["1min", "5min", "15min", "30min", "1h", "4h", "1day"]);

      if (!validSymbol(symbol) || !allowedIntervals.has(interval)) {
        return json({ error: "Invalid symbol or interval" }, 400);
      }

      const outputsize = Math.min(
        5000,
        Math.max(1, Number.parseInt(url.searchParams.get("outputsize") || "5000", 10) || 5000)
      );

      const upstreamUrl = new URL("https://api.twelvedata.com/time_series");
      upstreamUrl.searchParams.set("symbol", symbol);
      upstreamUrl.searchParams.set("interval", interval);
      upstreamUrl.searchParams.set("outputsize", String(outputsize));
      upstreamUrl.searchParams.set("timezone", "UTC");
      upstreamUrl.searchParams.set("apikey", env.TWELVE_DATA_API_KEY);

      try {
        const upstream = await fetch(upstreamUrl.toString(), {
          method: "GET",
          headers: { "Accept": "application/json" },
        });
        const body = await upstream.text();
        return new Response(body, {
          status: upstream.status,
          headers: { ...JSON_HEADERS },
        });
      } catch {
        return json({ error: "Market-data provider request failed" }, 502);
      }
    }

    if (url.pathname === "/ws") {
      if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
        return json({ error: "WebSocket upgrade required" }, 426);
      }

      const upstreamUrl = new URL("wss://ws.twelvedata.com/v1/quotes/price");
      upstreamUrl.searchParams.set("apikey", env.TWELVE_DATA_API_KEY);

      try {
        const upstreamResponse = await fetch(upstreamUrl.toString(), {
          headers: { "Upgrade": "websocket" },
        });
        const upstreamSocket = upstreamResponse.webSocket;
        if (!upstreamSocket) {
          return json({ error: "Provider WebSocket handshake failed" }, 502);
        }

        const pair = new WebSocketPair();
        const client = pair[0];
        const server = pair[1];

        server.accept();
        upstreamSocket.accept();

        server.addEventListener("message", event => {
          if (upstreamSocket.readyState === WebSocket.OPEN) {
            upstreamSocket.send(event.data);
          }
        });

        upstreamSocket.addEventListener("message", event => {
          if (server.readyState === WebSocket.OPEN) {
            server.send(event.data);
          }
        });

        server.addEventListener("close", event => {
          try { upstreamSocket.close(event.code || 1000, "client closed"); } catch {}
        });
        upstreamSocket.addEventListener("close", event => {
          try { server.close(event.code || 1000, "provider closed"); } catch {}
        });
        server.addEventListener("error", () => {
          try { upstreamSocket.close(1011, "client socket error"); } catch {}
        });
        upstreamSocket.addEventListener("error", () => {
          try { server.close(1011, "provider socket error"); } catch {}
        });

        return new Response(null, { status: 101, webSocket: client });
      } catch {
        return json({ error: "Provider WebSocket connection failed" }, 502);
      }
    }

    return json({ error: "Not found" }, 404);
  },
};
