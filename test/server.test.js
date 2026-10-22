import test from 'node:test';
import assert from 'node:assert/strict';
import { createSseServer } from '../src/server.js';

async function withServer(run) {
  const server = createSseServer({ heartbeatMs: 60_000 });
  server.listen(0, '127.0.0.1');
  await new Promise((resolve) => server.once('listening', resolve));
  const address = server.address();
  try {
    await run(`http://127.0.0.1:${address.port}`, server);
  } finally {
    server.closeSseClients();
    server.close();
    await new Promise((resolve) => server.once('close', resolve));
  }
}

test('health endpoint reports service status and live clients', async () => {
  await withServer(async (baseUrl) => {
    const response = await fetch(`${baseUrl}/health`);
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { status: 'ok', clients: 0, historySize: 0 });
  });
});

test('publish broadcasts a typed SSE event and assigns an event id', async () => {
  await withServer(async (baseUrl, server) => {
    const controller = new AbortController();
    const streamResponse = await fetch(`${baseUrl}/events`, { signal: controller.signal });
    assert.equal(streamResponse.status, 200);
    assert.match(streamResponse.headers.get('content-type'), /text\/event-stream/);

    const reader = streamResponse.body.getReader();
    const initial = new TextDecoder().decode((await reader.read()).value);
    assert.match(initial, /retry: 3000/);

    const publishResponse = await fetch(`${baseUrl}/publish`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ event: 'order.created', data: { orderId: 42 } }),
    });
    assert.equal(publishResponse.status, 202);
    assert.deepEqual(await publishResponse.json(), { published: true, id: '1', clients: 1 });

    const eventChunk = new TextDecoder().decode((await reader.read()).value);
    assert.match(eventChunk, /id: 1/);
    assert.match(eventChunk, /event: order\.created/);
    assert.match(eventChunk, /data: {"orderId":42}/);
    assert.equal(server.clientCount, 1);

    await reader.cancel();
    controller.abort();
  });
});

test('new clients can replay events after Last-Event-ID', async () => {
  await withServer(async (baseUrl) => {
    await fetch(`${baseUrl}/publish`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ data: 'first' }),
    });
    await fetch(`${baseUrl}/publish`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ data: 'second' }),
    });

    const controller = new AbortController();
    const response = await fetch(`${baseUrl}/events`, {
      headers: { 'Last-Event-ID': '1' },
      signal: controller.signal,
    });
    const reader = response.body.getReader();
    let content = '';
    while (!content.includes('data: "second"')) {
      content += new TextDecoder().decode((await reader.read()).value);
    }
    assert.doesNotMatch(content, /data: "first"/);
    assert.match(content, /id: 2/);
    assert.match(content, /data: "second"/);
    await reader.cancel();
    controller.abort();
  });
});

test('invalid publish payloads receive a useful error', async () => {
  await withServer(async (baseUrl) => {
    const response = await fetch(`${baseUrl}/publish`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ event: 'bad event', data: 'x' }),
    });
    assert.equal(response.status, 400);
    assert.match((await response.json()).error, /event must be/);
  });
});
