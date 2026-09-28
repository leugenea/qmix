"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const script = fs.readFileSync(path.join(__dirname, "app.js"), "utf8");

function roomApp(fetch = async () => ({ ok: true, json: async () => ({}) })) {
  const elements = new Map();
  for (const id of ["current-track", "queue", "queue-form", "track-url", "message", "room-ended"]) {
    elements.set(id, {
      textContent: "",
      dataset: {},
      hidden: id === "room-ended",
      disabled: false,
      value: "",
      listeners: {},
      addEventListener(name, fn) { this.listeners[name] = fn; },
      appendChild() {},
    });
  }
  const submit = { disabled: false };
  const form = elements.get("queue-form");
  form.querySelector = () => submit;
  form.reset = () => { elements.get("track-url").value = ""; };
  const sources = [];
  class FakeEventSource {
    constructor(url) {
      this.url = url;
      this.listeners = {};
      this.closed = false;
      sources.push(this);
    }
    addEventListener(name, fn) { this.listeners[name] = fn; }
    close() { this.closed = true; }
    emit(name, data = "{}") { this.listeners[name]({ data }); }
  }
  const timers = new Map();
  let nextTimer = 0;
  const document = {
    visibilityState: "visible",
    listeners: {},
    getElementById: (id) => elements.get(id),
    createElement: () => ({ className: "", textContent: "" }),
    addEventListener(name, fn) { this.listeners[name] = fn; },
  };
  vm.runInNewContext(script, {
    document,
    window: { location: { pathname: "/r/testroom" } },
    EventSource: FakeEventSource,
    fetch,
    setTimeout(fn) { timers.set(++nextTimer, fn); return nextTimer; },
    clearTimeout(id) { timers.delete(id); },
  });
  return { elements, submit, sources, timers, document, form };
}

test("room_closed presents a persistent ended state and closes the stream", () => {
  const app = roomApp();
  const source = app.sources[0];
  source.emit("room_closed");

  assert.equal(app.elements.get("room-ended").hidden, false);
  assert.match(app.elements.get("room-ended").textContent, /room has ended/i);
  assert.equal(source.closed, true);
  assert.equal(app.elements.get("track-url").disabled, true);
  assert.equal(app.submit.disabled, true);
  assert.equal(app.timers.size, 0);
});

test("room_closed cancels retry timers, queued callbacks, and visibility reconnects", async () => {
  const app = roomApp();
  const source = app.sources[0];
  await source.onerror();
  assert.equal(app.timers.size, 1);
  const queuedRetry = [...app.timers.values()][0];

  source.emit("room_closed");
  assert.equal(app.timers.size, 0);
  queuedRetry(); // A callback already queued by the browser may still run.
  source.onerror();
  app.document.listeners.visibilitychange();

  assert.equal(app.sources.length, 1);
  assert.equal(app.timers.size, 0);
  assert.equal(app.elements.get("room-ended").hidden, false);
});

test("reconnect 404 lookup ends the room when SSE has no terminal event", async () => {
  const requests = [];
  const app = roomApp(async (url, options) => {
    requests.push({ url, options });
    return { status: 404 };
  });
  const source = app.sources[0];
  await source.onerror();

  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, "/r/testroom");
  assert.equal(requests[0].options?.method, undefined);
  assert.equal(source.closed, true);
  assert.equal(app.timers.size, 0);
  assert.equal(app.elements.get("room-ended").hidden, false);
  assert.equal(app.submit.disabled, true);
  app.document.listeners.visibilitychange();
  assert.equal(app.sources.length, 1);
});

test("transient reconnect lookup failures retain backoff and retry", async () => {
  for (const lookup of [async () => ({ status: 503 }), async () => { throw Error("offline"); }]) {
    const app = roomApp(lookup);
    const source = app.sources[0];
    await source.onerror();
    assert.equal(source.closed, true);
    assert.equal(app.elements.get("room-ended").hidden, true);
    assert.equal(app.timers.size, 1);
    [...app.timers.values()][0]();
    assert.equal(app.sources.length, 2);
  }
});

test("stale 404 lookup cannot end room after visibility reconnect", async () => {
  let resolveLookup;
  const app = roomApp(() => new Promise((resolve) => { resolveLookup = resolve; }));
  const pending = app.sources[0].onerror();
  app.document.listeners.visibilitychange();
  resolveLookup({ status: 404 });
  await pending;

  assert.equal(app.elements.get("room-ended").hidden, true);
  assert.equal(app.sources.length, 2);
  assert.equal(app.sources[1].closed, false);
  assert.equal(app.timers.size, 0);
});

test("submit 404 ends the room rather than displaying a transient error", async () => {
  const app = roomApp(async () => ({ ok: false, status: 404, json: async () => ({ message: "room not found" }) }));
  app.elements.get("track-url").value = "https://example.test/song";
  await app.form.listeners.submit({ preventDefault() {} });

  assert.equal(app.elements.get("room-ended").hidden, false);
  assert.equal(app.elements.get("message").textContent, "");
  assert.equal(app.submit.disabled, true);
  assert.equal(app.sources[0].closed, true);
});

test("submit 404 ends immediately even if its response body stalls", async () => {
  const app = roomApp(async () => ({
    ok: false,
    status: 404,
    json: () => new Promise(() => {}),
  }));
  app.elements.get("track-url").value = "https://example.test/song";
  const pending = app.form.listeners.submit({ preventDefault() {} });
  await new Promise(setImmediate);
  assert.equal(app.elements.get("room-ended").hidden, false);
  assert.equal(app.submit.disabled, true);
  await pending;
});

test("in-flight successful submission cannot re-enable the form after room_closed", async () => {
  let resolveFetch;
  const app = roomApp(() => new Promise((resolve) => { resolveFetch = resolve; }));
  const input = app.elements.get("track-url");
  input.value = "https://example.test/song";
  const pending = app.form.listeners.submit({ preventDefault() {} });
  assert.equal(app.submit.disabled, true);
  app.sources[0].emit("room_closed");
  resolveFetch({ ok: true, json: async () => ({}) });
  await pending;

  assert.equal(input.value, "https://example.test/song");
  assert.equal(input.disabled, true);
  assert.equal(app.submit.disabled, true);
  assert.equal(app.elements.get("room-ended").hidden, false);
  assert.notEqual(app.elements.get("message").textContent, "Added to the queue");
});

test("in-flight failed submission and later submit cannot replace the ended state", async () => {
  let rejectFetch;
  let requests = 0;
  const app = roomApp(() => {
    requests++;
    return new Promise((_, reject) => { rejectFetch = reject; });
  });
  app.elements.get("track-url").value = "https://example.test/song";
  const pending = app.form.listeners.submit({ preventDefault() {} });
  app.sources[0].emit("room_closed");
  rejectFetch(new Error("network unavailable"));
  await pending;
  await app.form.listeners.submit({ preventDefault() {} });

  assert.equal(requests, 1);
  assert.equal(app.elements.get("message").textContent, "");
  assert.equal(app.elements.get("room-ended").hidden, false);
  assert.equal(app.submit.disabled, true);
});

test("a stale stream cannot end a room after visibility reconnect", () => {
  const app = roomApp();
  const old = app.sources[0];
  app.document.listeners.visibilitychange();
  assert.equal(old.closed, true);
  assert.equal(app.sources.length, 2);
  old.emit("room_closed");

  assert.equal(app.elements.get("room-ended").hidden, true);
  assert.equal(app.sources[1].closed, false);
  assert.equal(app.submit.disabled, false);
});

test("queued stream data cannot change the page after room_closed", () => {
  const app = roomApp();
  const source = app.sources[0];
  source.emit("queue_snapshot", JSON.stringify({ current: { title: "First track" }, queue: [] }));
  source.emit("room_closed");
  source.emit("track_changed", JSON.stringify({ title: "Late track" }));
  source.emit("queue_snapshot", JSON.stringify({ current: { title: "Late snapshot" }, queue: [] }));

  assert.equal(app.elements.get("current-track").textContent, "First track");
  assert.equal(app.elements.get("room-ended").hidden, false);
  assert.equal(app.submit.disabled, true);
});
