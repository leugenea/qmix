"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const script = fs.readFileSync(path.join(__dirname, "app.js"), "utf8");

test("playback card headings do not imply an ended room is live or playing", () => {
  const page = fs.readFileSync(path.join(__dirname, "index.html"), "utf8");
  assert.match(page, /<p class="eyebrow">Playback<\/p>/);
  assert.match(page, /<h2 id="current-heading">Current track<\/h2>/);
});

function roomApp(fetch = async () => ({ ok: true, json: async () => ({}) })) {
  const elements = new Map();
  for (const id of ["current-track", "player-status", "player-position", "connection-status", "queue", "queue-form", "track-url", "message", "room-ended"]) {
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
  const timerDelays = new Map();
  const frames = new Map();
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
    AbortController,
    fetch,
    requestAnimationFrame(fn) { const id = ++nextTimer; frames.set(id, fn); return id; },
    cancelAnimationFrame(id) { frames.delete(id); },
    setTimeout(fn, delay) {
      const id = ++nextTimer;
      timers.set(id, fn);
      timerDelays.set(id, delay);
      return id;
    },
    clearTimeout(id) { timers.delete(id); timerDelays.delete(id); },
  });
  return { elements, submit, sources, timers, timerDelays, frames, document, form,
    flushFrame() {
      for (const [id, fn] of [...frames]) { frames.delete(id); fn(); }
    },
  };
}

test("snapshot shows authoritative playback state and last reported time", () => {
  const app = roomApp();
  app.sources[0].emit("queue_snapshot", JSON.stringify({
    current: { track_id: "one", title: "Song", artist: "Artist", state: "paused", pos_sec: 67, duration_sec: 203 },
    queue: [],
  }));

  assert.equal(app.elements.get("current-track").textContent, "Song — Artist");
  assert.equal(app.elements.get("player-status").textContent, "Paused");
  assert.equal(app.elements.get("player-position").textContent, "1:07 / 3:23");
  assert.equal(app.elements.get("connection-status").textContent, "Live");
});

test("known duration remains visible when no position was reported", () => {
  const app = roomApp();
  app.sources[0].emit("queue_snapshot", JSON.stringify({
    current: { track_id: "one", title: "Song", state: "playing", duration_sec: 125 }, queue: [],
  }));
  assert.equal(app.elements.get("player-position").textContent, "Duration 2:05");
  app.sources[0].emit("track_changed", JSON.stringify({ track_id: "two", title: "Unknown length", state: "playing", pos_sec: 0 }));
  assert.equal(app.elements.get("player-position").textContent, "0:00");
});

test("matching host reports show playing, paused, and playback error without estimating time", () => {
  let gets = 0;
  const app = roomApp(async () => { gets++; return { status: 200 }; });
  const source = app.sources[0];
  source.emit("queue_snapshot", JSON.stringify({ current: { track_id: "one", title: "Song", state: "playing", pos_sec: 0 }, queue: [] }));
  for (const [state, pos, label] of [["playing", 14, "Playing"], ["paused", 61, "Paused"], ["error", 63, "Playback error"]]) {
    source.emit("player_state", JSON.stringify({ track_id: "one", state, pos_sec: pos }));
    app.flushFrame();
    assert.equal(app.elements.get("player-status").textContent, label);
    assert.equal(app.elements.get("player-position").textContent, `${Math.floor(pos / 60)}:${String(pos % 60).padStart(2, "0")}`);
  }
  assert.equal(gets, 0);
  assert.equal(app.timers.size, 0); // No invented playback clock.
});

test("frequent player reports coalesce into one DOM update with no room GET", () => {
  let requests = 0;
  const app = roomApp(async () => { requests++; return { status: 200 }; });
  app.sources[0].emit("queue_snapshot", JSON.stringify({ current: { track_id: "one", title: "Song", state: "playing", pos_sec: 0 }, queue: [] }));
  const position = app.elements.get("player-position");
  let writes = 0;
  let value = position.textContent;
  Object.defineProperty(position, "textContent", {
    get: () => value,
    set: (next) => { writes++; value = next; },
  });
  const title = app.elements.get("current-track");
  const status = app.elements.get("player-status");
  let redundantWrites = 0;
  for (const element of [title, status]) {
    let text = element.textContent;
    Object.defineProperty(element, "textContent", {
      get: () => text,
      set: (next) => { redundantWrites++; text = next; },
    });
  }
  for (let i = 1; i <= 100; i++) {
    app.sources[0].emit("player_state", JSON.stringify({ track_id: "one", state: "playing", pos_sec: i }));
  }
  assert.equal(app.frames.size, 1);
  assert.equal(writes, 0);
  app.flushFrame();
  assert.equal(position.textContent, "1:40");
  assert.equal(writes, 1);
  assert.equal(redundantWrites, 0);
  assert.equal(app.frames.size, 0);
  assert.equal(requests, 0);
});

test("old-track reports cannot overwrite a newer track or its position", () => {
  const app = roomApp();
  const source = app.sources[0];
  source.emit("queue_snapshot", JSON.stringify({ current: { track_id: "old", title: "Old", state: "playing", pos_sec: 4 }, queue: [] }));
  source.emit("player_state", JSON.stringify({ track_id: "old", state: "paused", pos_sec: 90 }));
  source.emit("track_changed", JSON.stringify({ track_id: "new", title: "New", state: "playing", pos_sec: 0, duration_sec: 120 }));
  source.emit("player_state", JSON.stringify({ track_id: "old", state: "error", pos_sec: 99 }));
  app.flushFrame();
  assert.equal(app.elements.get("current-track").textContent, "New");
  assert.equal(app.elements.get("player-status").textContent, "Playing");
  assert.equal(app.elements.get("player-position").textContent, "0:00 / 2:00");
  source.emit("player_state", JSON.stringify({ track_id: "new", state: "paused", pos_sec: 8 }));
  app.flushFrame();
  assert.equal(app.elements.get("player-position").textContent, "0:08 / 2:00");
});

test("ended report immediately clears the matching track and displays ended then idle", () => {
  const app = roomApp();
  const source = app.sources[0];
  source.emit("queue_snapshot", JSON.stringify({ current: { track_id: "one", title: "Song", state: "playing", pos_sec: 4, duration_sec: 80 }, queue: [] }));
  source.emit("player_state", JSON.stringify({ track_id: "one", state: "paused", pos_sec: 29 }));
  source.emit("player_state", JSON.stringify({ track_id: "one", state: "ended", pos_sec: 30 }));
  assert.equal(app.elements.get("current-track").textContent, "Nothing is playing");
  assert.equal(app.elements.get("player-status").textContent, "Ended");
  assert.equal(app.elements.get("player-position").textContent, "");
  app.flushFrame();
  assert.equal(app.elements.get("current-track").textContent, "Nothing is playing");
  source.emit("queue_snapshot", JSON.stringify({ current: null, queue: [] }));
  assert.equal(app.elements.get("player-status").textContent, "Idle");
});

test("disconnect stays visibly reconnecting until the new stream provides a fresh snapshot", async () => {
  const app = roomApp(async () => ({ status: 200 }));
  const old = app.sources[0];
  old.emit("queue_snapshot", JSON.stringify({ current: { track_id: "old", title: "Old", state: "playing", pos_sec: 20 }, queue: [] }));
  await old.onerror();
  assert.equal(app.elements.get("connection-status").textContent, "Reconnecting…");
  [...app.timers.values()][0]();
  const fresh = app.sources[1];
  fresh.onopen();
  assert.equal(app.elements.get("connection-status").textContent, "Reconnecting…");
  old.emit("queue_snapshot", JSON.stringify({ current: { track_id: "stale", title: "Stale" }, queue: [] }));
  old.emit("player_state", JSON.stringify({ track_id: "old", state: "error", pos_sec: 99 }));
  fresh.emit("player_state", JSON.stringify({ track_id: "old", state: "paused", pos_sec: 30 }));
  app.flushFrame();
  assert.equal(app.elements.get("connection-status").textContent, "Reconnecting…");
  fresh.emit("queue_snapshot", JSON.stringify({ current: null, queue: [] }));
  assert.equal(app.elements.get("connection-status").textContent, "Live");
  assert.equal(app.elements.get("current-track").textContent, "Nothing is playing");
  assert.equal(app.elements.get("player-status").textContent, "Idle");
  assert.equal(app.elements.get("player-position").textContent, "");
});

test("room_closed cancels an in-flight player render", () => {
  const app = roomApp();
  const source = app.sources[0];
  source.emit("queue_snapshot", JSON.stringify({ current: { track_id: "one", title: "Song", state: "playing", pos_sec: 0 }, queue: [] }));
  source.emit("player_state", JSON.stringify({ track_id: "one", state: "paused", pos_sec: 10 }));
  assert.equal(app.frames.size, 1);
  source.emit("room_closed");
  assert.equal(app.frames.size, 0);
  app.flushFrame();
  assert.equal(app.elements.get("player-status").textContent, "Stopped");
  assert.equal(app.elements.get("player-position").textContent, "");
});

test("a null track_changed clears current without leaving stale playback or time", () => {
  const app = roomApp();
  const source = app.sources[0];
  source.emit("queue_snapshot", JSON.stringify({ current: { track_id: "one", title: "Song", state: "playing", pos_sec: 12 }, queue: [] }));
  source.emit("track_changed", "null");
  assert.equal(app.elements.get("current-track").textContent, "Nothing is playing");
  assert.equal(app.elements.get("player-status").textContent, "Idle");
  assert.equal(app.elements.get("player-position").textContent, "");
});

test("room_closed replaces live playback indicators with terminal status", () => {
  const app = roomApp();
  const source = app.sources[0];
  source.emit("queue_snapshot", JSON.stringify({
    current: { track_id: "one", title: "Song", state: "playing", pos_sec: 25 },
    queue: [{ title: "Next" }],
  }));
  const queue = app.elements.get("queue");
  queue.textContent = "Existing queue";
  source.emit("room_closed");

  assert.equal(app.elements.get("player-status").textContent, "Stopped");
  assert.equal(app.elements.get("connection-status").textContent, "Closed");
  assert.equal(app.elements.get("player-position").textContent, "");
  assert.equal(app.elements.get("current-track").textContent, "Song");
  assert.equal(queue.textContent, "Existing queue");
  assert.equal(app.elements.get("room-ended").hidden, false);
  assert.match(app.elements.get("room-ended").textContent, /room has ended/i);
  assert.equal(app.elements.get("track-url").disabled, true);
  assert.equal(app.submit.disabled, true);
  source.emit("queue_snapshot", JSON.stringify({ current: { title: "Late song", state: "playing" }, queue: [] }));
  assert.equal(app.elements.get("player-status").textContent, "Stopped");
  assert.equal(app.elements.get("connection-status").textContent, "Closed");
  assert.equal(queue.textContent, "Existing queue");
});

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
  source.emit("queue_snapshot", JSON.stringify({ current: { track_id: "one", title: "Song", state: "playing", pos_sec: 9 }, queue: [] }));
  await source.onerror();

  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, "/r/testroom");
  assert.equal(requests[0].options?.method, undefined);
  assert.equal(source.closed, true);
  assert.equal(app.timers.size, 0);
  assert.equal(app.elements.get("room-ended").hidden, false);
  assert.equal(app.elements.get("player-status").textContent, "Stopped");
  assert.equal(app.elements.get("connection-status").textContent, "Closed");
  assert.equal(app.elements.get("player-position").textContent, "");
  assert.equal(app.elements.get("track-url").disabled, true);
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

test("a stalled room lookup times out and retries without ending the room", async () => {
  let probeSignal;
  const app = roomApp((url, options) => {
    assert.equal(url, "/r/testroom");
    probeSignal = options?.signal;
    return new Promise(() => {}); // Simulate a stalled transport that ignores abort.
  });
  const source = app.sources[0];
  const pending = source.onerror();
  assert.equal(app.timers.size, 1);
  const [probeTimer, timeout] = [...app.timerDelays.entries()][0];
  assert.equal(timeout, 5000);

  app.timers.get(probeTimer)();
  await pending;

  assert.equal(probeSignal.aborted, true);
  assert.equal(source.closed, true);
  assert.equal(app.elements.get("room-ended").hidden, true);
  assert.equal(app.timers.size, 1);
  assert.equal([...app.timerDelays.values()][0], 1000);
  [...app.timers.values()][0]();
  assert.equal(app.sources.length, 2);
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
  app.sources[0].emit("queue_snapshot", JSON.stringify({ current: { track_id: "one", title: "Song", state: "playing", pos_sec: 8 }, queue: [] }));
  app.elements.get("track-url").value = "https://example.test/song";
  await app.form.listeners.submit({ preventDefault() {} });

  assert.equal(app.elements.get("room-ended").hidden, false);
  assert.equal(app.elements.get("player-status").textContent, "Stopped");
  assert.equal(app.elements.get("connection-status").textContent, "Closed");
  assert.equal(app.elements.get("player-position").textContent, "");
  assert.equal(app.elements.get("track-url").disabled, true);
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
