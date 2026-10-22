import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createGateway } from '../gateway.ts';

const capability = 'a'.repeat(64);
const host = 'https://windows.example';
const endpoint = 'https://edge.example/functions/v1/gateway/mcp/' + capability;
const identity = createHash('sha256').update(capability).digest('hex').slice(0, 16);
const core = ['server_info', 'android_status', 'android_command', 'android_result'];
function json(body: unknown, status = 200, headers = {}) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', ...headers } });
}
function tool(id: unknown, value: unknown, isError = false) {
  return json({ jsonrpc: '2.0', id, result: { isError, content: [{ type: 'text', text: JSON.stringify(value) }] } });
}
function command(name: string, args = {}) {
  return new Request(endpoint, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ jsonrpc: '2.0', id: 42, method: 'tools/call', params: { name, arguments: args } }) });
}
function peer(count = 13, modern = false, recent = true) {
  const calls: any[] = [];
  const fetcher = async (url: any, options: any) => {
    assert.equal(String(url), host + '/mcp/' + capability);
    const rpc = JSON.parse(new TextDecoder().decode(options.body instanceof Uint8Array ? options.body : new TextEncoder().encode(options.body)));
    calls.push({ rpc, options });
    const version = modern ? '1.4.1' : '1.3.0';
    if (rpc.method === 'initialize') return json({ jsonrpc: '2.0', id: rpc.id, result: { serverInfo: { name: 'CHK Home MCP', version } } });
    if (rpc.method === 'tools/list') return json({ jsonrpc: '2.0', id: rpc.id, result: { tools: Array.from({ length: count }, (_, index) => ({ name: core[index] || 'other_' + index, description: 'Original.', inputSchema: { type: 'object', properties: {} } })) } });
    if (rpc.params?.name === 'server_info') return tool(rpc.id, { name: 'CHK Home MCP', version, tools: count, ...(modern ? { server_id: identity } : {}) });
    if (rpc.params?.name === 'android_status') return tool(rpc.id, { android_recent: recent, queued: 0, leased: 0, ...(modern ? { server_id: identity } : {}) });
    if (rpc.params?.name === 'android_result') return tool(rpc.id, { id: 'same', state: 'pending', success: false });
    return tool(rpc.id, { id: 'new', state: 'done', success: true, message: 'done' });
  };
  return { fetcher, calls };
}
function value(body: any) { return JSON.parse(body.result.content[0].text); }

for (const count of [13, 20]) test('diagnostic accepts ' + count + ' tools without recursive Edge calls', async () => {
  const mock = peer(count, count === 20);
  const response = await createGateway(mock.fetcher, host)(new Request(endpoint.replace('/mcp/', '/self-test/')));
  const report = await response.json();
  assert.equal(response.status, 200);
  assert.equal(report.passed, true);
  assert.equal(report.server.tools, count);
  assert.equal(report.server.identity, count === 20 ? 'matched' : 'not_provided_by_relay');
  assert.equal(mock.calls.length, 4);
  assert.equal(report.edge_invocations, 1);
  assert.equal(report.execution_verified, false);
  assert.equal(report.display_verified, false);
  assert.ok(!JSON.stringify(report).includes(capability));
});

test('offline Android is reported separately from a working Windows route', async () => {
  const mock = peer(13, false, false);
  const report = await (await createGateway(mock.fetcher, host)(new Request(endpoint.replace('/mcp/', '/self-test/')))).json();
  assert.equal(report.passed, true);
  assert.equal(report.android_channel.android_recent, false);
});

test('identity mismatch fails without reporting false success', async () => {
  const mock = peer(20, true);
  const fetcher = async (url: any, options: any) => {
    const body = await (await mock.fetcher(url, options)).json();
    if (body.result?.content) {
      const v = value(body);
      if (v.name) { v.server_id = 'wrong'; body.result.content[0].text = JSON.stringify(v); }
    }
    return json(body);
  };
  const response = await createGateway(fetcher, host)(new Request(endpoint.replace('/mcp/', '/self-test/')));
  assert.equal(response.status, 502);
  assert.equal((await response.json()).passed, false);
  assert.equal(mock.calls.length, 3);
});

test('diagnostic stops on authentication failure', async () => {
  let calls = 0;
  const response = await createGateway(async () => { calls++; return json({ error: 'forbidden' }, 401); }, host)(new Request(endpoint.replace('/mcp/', '/self-test/')));
  assert.equal(response.status, 502);
  assert.equal(calls, 1);
});

test('diagnostic transport failure is handled without leaking URL', async () => {
  const response = await createGateway(async () => { throw new Error(endpoint); }, host)(new Request(endpoint.replace('/mcp/', '/self-test/')));
  assert.equal(response.status, 502);
  const text = await response.text();
  assert.ok(!text.includes(capability));
  assert.equal(JSON.parse(text).passed, false);
});

test('server_info combines fresh PC and Android state in one handler invocation', async () => {
  const mock = peer();
  const handle = createGateway(mock.fetcher, host);
  const response = await handle(command('server_info'));
  const body = await response.json();
  assert.equal(body.id, 42);
  assert.equal(value(body).version, '1.3.0');
  assert.equal(value(body).android_channel.android_recent, true);
  assert.equal(value(body).gateway.edge_invocations, 1);
  assert.equal(mock.calls.length, 2);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  await handle(command('server_info'));
  assert.equal(mock.calls.length, 4); // no cached success
});

test('Android status failure preserves PC result and reports unavailable', async () => {
  const mock = peer();
  const handle = createGateway(async (url, options: any) => {
    if (String(options.body).includes('android_status')) throw new Error('offline');
    return mock.fetcher(url, options);
  }, host);
  const response = await handle(command('server_info'));
  assert.equal(response.status, 200);
  assert.deepEqual(value(await response.json()).android_channel, { available: false });
});

test('tool count and schemas remain unchanged; batching is discoverable', async () => {
  const mock = peer();
  const response = await createGateway(mock.fetcher, host)(new Request(endpoint, { method: 'POST', body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/list' }) }));
  const body = await response.json();
  assert.equal(body.result.tools.length, 13);
  assert.match(body.result.tools[2].description, /files_task/);
  assert.match(body.result.tools[2].description, /id:etape1/);
  assert.deepEqual(body.result.tools[2].inputSchema, { type: 'object', properties: {} });
  assert.equal(mock.calls.length, 1);
});

test('pending result advises spacing polls and preserving command id', async () => {
  const mock = peer();
  const response = await createGateway(mock.fetcher, host)(command('android_result', { id: 'same' }));
  assert.deepEqual(value(await response.json()), { id: 'same', state: 'pending', success: false, recommended_poll_delay_ms: 3000, resend_command: false });
  assert.equal(mock.calls.length, 1);
});

for (const name of ['TimeoutError', 'TypeError']) test(name + ' never automatically repeats a command', async () => {
  let count = 0;
  const handle = createGateway(async () => { count++; const error = new Error(endpoint); error.name = name; throw error; }, host);
  const response = await handle(command('android_command', { action: 'browser_tabs' }));
  assert.equal(count, 1);
  assert.equal(response.status, name === 'TimeoutError' ? 504 : 502);
  const body = await response.json();
  assert.equal(body.retry_command, false);
  assert.equal(body.execution_state, 'unknown');
  assert.ok(!JSON.stringify(body).includes(capability));
});

test('only allowlisted headers reach Windows; HTTP errors are preserved', async () => {
  const handle = createGateway(async (_url, options) => {
    const h = new Headers(options?.headers);
    assert.equal(h.get('authorization'), null);
    assert.equal(h.get('apikey'), null);
    assert.equal(h.get('cookie'), null);
    assert.equal(h.get('mcp-protocol-version'), '2025-06-18');
    assert.equal(options?.redirect, 'manual');
    return json({ error: 'busy' }, 429, { 'retry-after': '5' });
  }, host);
  const response = await handle(new Request(endpoint, { method: 'POST', headers: { authorization: 'secret', apikey: 'private', cookie: 'session', 'mcp-protocol-version': '2025-06-18' }, body: '{}' }));
  assert.equal(response.status, 429);
  assert.equal(response.headers.get('retry-after'), '5');
});

test('browser origin and invalid path are blocked before any upstream access', async () => {
  const handle = createGateway(async () => { throw new Error('must not call'); }, host);
  assert.equal((await handle(new Request(endpoint, { headers: { origin: 'https://bad.example' } }))).status, 403);
  assert.equal((await handle(new Request('https://edge.example/no-secret'))).status, 404);
  assert.equal((await handle(new Request(endpoint, { method: 'PUT' }))).status, 405);
});

test('request size is enforced with and without content-length', async () => {
  const handle = createGateway(async () => { throw new Error('must not call'); }, host);
  const body = 'x'.repeat(96 * 1024 + 1);
  for (const headers of [{}, { 'content-length': String(body.length) }]) {
    assert.equal((await handle(new Request(endpoint, { method: 'POST', body, headers }))).status, 413);
  }
});

test('notifications keep their empty 202 response', async () => {
  const handle = createGateway(async () => new Response(null, { status: 202 }), host);
  const response = await handle(new Request(endpoint, { method: 'POST', body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) }));
  assert.equal(response.status, 202);
  assert.equal(await response.text(), '');
});

test('media results remain streamed and unmodified', async () => {
  const original = JSON.stringify({ image: 'unchanged', success: true });
  let calls = 0;
  const handle = createGateway(async () => { calls++; return new Response(original, { headers: { 'content-type': 'application/json' } }); }, host);
  const response = await handle(command('android_command'));
  assert.equal(await response.text(), original);
  assert.equal(calls, 1);
});

test('healthcheck does not falsely return healthy for an upstream 500', async () => {
  const handle = createGateway(async () => new Response('error', { status: 500 }), host);
  const response = await handle(new Request('https://edge.example/healthcheck'));
  assert.equal(response.status, 502);
  assert.equal((await response.json()).upstreamReachable, false);
});
