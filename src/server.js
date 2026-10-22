import { createServer } from 'node:http';
import { once } from 'node:events';

const DEFAULTS = {
  heartbeatMs: 25_000,
  historySize: 100,
  maxBodyBytes: 1024 * 1024,
  maxBufferedBytes: 1024 * 1024,
};

function jsonResponse(response, statusCode, body) {
  response.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
  });
  response.end(JSON.stringify(body));
}

function encodeEvent(event) {
  const lines = [];
  if (event.id !== undefined) lines.push(`id: ${event.id}`);
  if (event.event) lines.push(`event: ${event.event}`);
  for (const line of JSON.stringify(event.data).split(/\r?\n/)) {
    lines.push(`data: ${line}`);
  }
  return `${lines.join('\n')}\n\n`;
}

async function readJson(request, maxBytes) {
  let size = 0;
  const chunks = [];
  for await (const chunk of request) {
    size += chunk.length;
    if (size > maxBytes) {
      const error = new Error('Request body is too large');
      error.statusCode = 413;
      throw error;
    }
    chunks.push(chunk);
  }

  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    const error = new Error('Request body must be valid JSON');
    error.statusCode = 400;
    throw error;
  }
}

/** Create an SSE server. The returned server exposes its live client count. */
export function createSseServer(options = {}) {
  const config = { ...DEFAULTS, ...options };
  const clients = new Set();
  const history = [];
  let nextId = 1;

  function sendToClient(client, payload) {
    if (client.closed || client.response.destroyed) return;
    if (client.response.writableLength > config.maxBufferedBytes) {
      client.response.destroy();
      return;
    }
    client.response.write(payload);
  }

  function publish(eventName, data) {
    const event = { id: String(nextId++), event: eventName, data };
    if (config.historySize > 0) {
      history.push(event);
      if (history.length > config.historySize) history.shift();
    }
    const payload = encodeEvent(event);
    for (const client of clients) sendToClient(client, payload);
    return event;
  }

  const server = createServer(async (request, response) => {
    const url = new URL(request.url ?? '/', `http://${request.headers.host ?? 'localhost'}`);

    if (request.method === 'GET' && url.pathname === '/health') {
      return jsonResponse(response, 200, {
        status: 'ok',
        clients: clients.size,
        historySize: history.length,
      });
    }

    if (request.method === 'GET' && url.pathname === '/events') {
      const lastEventId = request.headers['last-event-id'] ?? url.searchParams.get('lastEventId');
      const cursor = Number.parseInt(lastEventId ?? '0', 10);

      response.writeHead(200, {
        'Content-Type': 'text/event-stream; charset=utf-8',
        'Cache-Control': 'no-cache, no-transform',
        Connection: 'keep-alive',
        'X-Accel-Buffering': 'no',
      });
      response.flushHeaders();
      response.write('retry: 3000\n: connected\n\n');

      const client = { response, closed: false };
      clients.add(client);
      const closeClient = () => {
        if (client.closed) return;
        client.closed = true;
        clients.delete(client);
      };
      request.on('aborted', closeClient);
      response.on('close', closeClient);
      response.on('error', closeClient);

      if (Number.isFinite(cursor) && cursor >= 0) {
        for (const event of history) {
          if (Number.parseInt(event.id, 10) > cursor) sendToClient(client, encodeEvent(event));
        }
      }
      return;
    }

    if (request.method === 'POST' && url.pathname === '/publish') {
      try {
        const body = await readJson(request, config.maxBodyBytes);
        if (body === null || typeof body !== 'object' || Array.isArray(body) || !('data' in body)) {
          return jsonResponse(response, 400, { error: 'Expected a JSON object with a data field' });
        }
        const eventName = body.event ?? 'message';
        if (typeof eventName !== 'string' || !/^[\w.-]{1,128}$/.test(eventName)) {
          return jsonResponse(response, 400, { error: 'event must be 1-128 letters, numbers, dots, underscores, or hyphens' });
        }
        const event = publish(eventName, body.data);
        return jsonResponse(response, 202, { published: true, id: event.id, clients: clients.size });
      } catch (error) {
        return jsonResponse(response, error.statusCode ?? 400, { error: error.message });
      }
    }

    if (request.method === 'GET' && url.pathname === '/') {
      return jsonResponse(response, 200, {
        name: 'SSE long-connection service',
        endpoints: { stream: 'GET /events', publish: 'POST /publish', health: 'GET /health' },
      });
    }

    jsonResponse(response, 404, { error: 'Not found' });
  });

  const heartbeat = setInterval(() => {
    for (const client of clients) sendToClient(client, `: heartbeat ${Date.now()}\n\n`);
  }, config.heartbeatMs);
  heartbeat.unref();

  server.on('close', () => clearInterval(heartbeat));
  server.publish = publish;
  server.closeSseClients = () => {
    for (const client of clients) client.response.end();
    clients.clear();
  };
  Object.defineProperty(server, 'clientCount', { get: () => clients.size });
  return server;
}

if (import.meta.url === new URL(process.argv[1] ?? '', 'file:').href) {
  const port = Number(process.env.PORT ?? 3000);
  const host = process.env.HOST ?? '0.0.0.0';
  const server = createSseServer({
    heartbeatMs: Number(process.env.HEARTBEAT_MS ?? DEFAULTS.heartbeatMs),
    historySize: Number(process.env.HISTORY_SIZE ?? DEFAULTS.historySize),
  });

  server.listen(port, host, () => {
    console.log(`SSE service listening on http://${host}:${port}`);
  });

  async function shutdown(signal) {
    console.log(`Received ${signal}; closing SSE connections...`);
    server.close();
    server.closeSseClients();
    const timeout = setTimeout(() => process.exit(1), 10_000);
    timeout.unref();
    await once(server, 'close').catch(() => {});
    clearTimeout(timeout);
    process.exit(0);
  }
  process.once('SIGINT', () => shutdown('SIGINT'));
  process.once('SIGTERM', () => shutdown('SIGTERM'));
}
