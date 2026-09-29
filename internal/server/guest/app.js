(() => {
  "use strict";

  const code = decodeURIComponent(window.location.pathname.split("/").filter(Boolean)[1] || "");
  const currentElement = document.getElementById("current-track");
  const playerStatusElement = document.getElementById("player-status");
  const playerPositionElement = document.getElementById("player-position");
  const connectionElement = document.getElementById("connection-status");
  const queueElement = document.getElementById("queue");
  const form = document.getElementById("queue-form");
  const urlInput = document.getElementById("track-url");
  const messageElement = document.getElementById("message");
  let current = null;
  let lastTrackEnded = false;
  let pendingPlayerState = null;
  let playerFrame = null;
  let source = null;
  let retryTimer = null;
  let messageTimer = null;
  let retryDelay = 1000;
  const maxRetryDelay = 30000;
  const roomProbeTimeout = 5000;
  let roomEnded = false;

  function endRoom() {
    if (roomEnded) return;
    roomEnded = true;
    clearTimeout(retryTimer);
    clearTimeout(messageTimer);
    cancelAnimationFrame(playerFrame);
    playerFrame = null;
    pendingPlayerState = null;
    messageElement.textContent = "";
    delete messageElement.dataset.kind;
    if (source) source.close();
    source = null;
    playerStatusElement.textContent = "Stopped";
    playerPositionElement.textContent = "";
    connectionElement.textContent = "Closed";
    const notice = document.getElementById("room-ended");
    notice.textContent = "This room has ended. Tracks can no longer be added.";
    notice.hidden = false;
    urlInput.disabled = true;
    form.querySelector('button[type="submit"]').disabled = true;
  }

  function showMessage(message, kind, dismissAfter = 5000) {
    if (roomEnded) return;
    clearTimeout(messageTimer);
    messageTimer = null;
    messageElement.textContent = message;
    messageElement.dataset.kind = kind;
    if (dismissAfter === null) return;
    messageTimer = setTimeout(() => {
      messageElement.textContent = "";
      delete messageElement.dataset.kind;
      messageTimer = null;
    }, dismissAfter);
  }

  function trackLabel(track) {
    if (!track) return "Nothing is playing";
    return track.artist ? `${track.title} — ${track.artist}` : (track.title || "Unknown track");
  }

  function playbackLabel() {
    if (!current) return lastTrackEnded ? "Ended" : "Idle";
    return { playing: "Playing", paused: "Paused", error: "Playback error" }[current.state] || "Idle";
  }

  function positionLabel() {
    if (!current) return "";
    const seconds = (value) => `${Math.floor(value / 60)}:${String(value % 60).padStart(2, "0")}`;
    const position = Number.isInteger(current.pos_sec) && current.pos_sec >= 0 ? seconds(current.pos_sec) : "";
    const duration = Number.isInteger(current.duration_sec) && current.duration_sec > 0 ? seconds(current.duration_sec) : "";
    if (!position) return duration ? `Duration ${duration}` : "";
    return duration ? `${position} / ${duration}` : position;
  }

  function renderCurrent() {
    const label = trackLabel(current);
    const status = playbackLabel();
    const position = positionLabel();
    if (currentElement.textContent !== label) currentElement.textContent = label;
    if (playerStatusElement.textContent !== status) playerStatusElement.textContent = status;
    if (playerPositionElement.textContent !== position) playerPositionElement.textContent = position;
  }

  function renderQueue(queue) {
    queueElement.textContent = "";
    if (!Array.isArray(queue) || queue.length === 0) {
      const empty = document.createElement("li");
      empty.className = "queue-empty-state";
      empty.textContent = "The queue is empty";
      queueElement.appendChild(empty);
      return;
    }
    queue.forEach((track) => {
      const item = document.createElement("li");
      item.className = "queue-track-card";
      item.textContent = trackLabel(track);
      queueElement.appendChild(item);
    });
  }

  function parseEvent(event) {
    try {
      return JSON.parse(event.data);
    } catch (_) {
      return null;
    }
  }

  async function probeRoom() {
    const controller = new AbortController();
    let timeout;
    try {
      return await Promise.race([
        fetch(`/r/${encodeURIComponent(code)}`, { signal: controller.signal }),
        new Promise((_, reject) => {
          timeout = setTimeout(() => {
            controller.abort();
            reject(new Error("room lookup timed out"));
          }, roomProbeTimeout);
        }),
      ]);
    } finally {
      clearTimeout(timeout);
    }
  }

  function connect() {
    if (roomEnded) return;
    if (source) source.close();
    connectionElement.textContent = source ? "Reconnecting…" : "Connecting…";
    cancelAnimationFrame(playerFrame);
    playerFrame = null;
    pendingPlayerState = null;
    const eventSource = new EventSource(`/rooms/${encodeURIComponent(code)}/events`);
    source = eventSource;
    let checking = false;
    let snapshotReady = false;
    eventSource.onopen = () => {
      if (source !== eventSource) return;
      retryDelay = 1000;
    };
    eventSource.onerror = async () => {
      if (source !== eventSource || checking) return;
      checking = true;
      eventSource.close();
      connectionElement.textContent = "Reconnecting…";
      try {
        const response = await probeRoom();
        if (source !== eventSource) return;
        if (response.status === 404) {
          endRoom();
          return;
        }
      } catch (_) {
        // A failed lookup is inconclusive: keep the existing retry policy.
      }
      if (source !== eventSource) return;
      clearTimeout(retryTimer);
      retryTimer = setTimeout(connect, retryDelay);
      retryDelay = Math.min(retryDelay * 2, maxRetryDelay);
    };
    eventSource.addEventListener("queue_snapshot", (event) => {
      if (source !== eventSource) return;
      const data = parseEvent(event);
      if (!data) return;
      snapshotReady = true;
      lastTrackEnded = false;
      current = data.current;
      renderCurrent();
      renderQueue(data.queue);
      connectionElement.textContent = "Live";
    });
    eventSource.addEventListener("queue_updated", (event) => {
      if (source !== eventSource) return;
      if (!snapshotReady) return;
      const data = parseEvent(event);
      if (data) renderQueue(data.queue);
    });
    eventSource.addEventListener("track_changed", (event) => {
      if (source !== eventSource) return;
      if (!snapshotReady) return;
      const data = parseEvent(event);
      if (!data && event.data.trim() !== "null") return;
      lastTrackEnded = false;
      current = data;
      renderCurrent();
    });
    eventSource.addEventListener("player_state", (event) => {
      if (source !== eventSource) return;
      if (!snapshotReady) return;
      const data = parseEvent(event);
      if (!data || !current || data.track_id !== current.track_id) return;
      if (data.state === "ended") {
        cancelAnimationFrame(playerFrame);
        playerFrame = null;
        pendingPlayerState = null;
        current = null;
        lastTrackEnded = true;
        renderCurrent();
        return;
      }
      pendingPlayerState = data;
      if (playerFrame !== null) return;
      playerFrame = requestAnimationFrame(() => {
        playerFrame = null;
        if (roomEnded || !current || !pendingPlayerState || pendingPlayerState.track_id !== current.track_id || source !== eventSource) return;
        current.state = pendingPlayerState.state;
        current.pos_sec = pendingPlayerState.pos_sec;
        pendingPlayerState = null;
        renderCurrent();
      });
    });
    eventSource.addEventListener("room_closed", () => {
      if (source !== eventSource) return;
      endRoom();
    });
  }

  connect();

  async function addTrack(url) {
    const response = await fetch(`/r/${encodeURIComponent(code)}/queue`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ url }),
    });
    if (roomEnded) return;
    if (response.status === 404) {
      endRoom();
      return;
    }
    const data = await response.json().catch(() => ({}));
    if (roomEnded) return;
    if (!response.ok) throw new Error(data.message || "Could not add this link");
    form.reset();
    showMessage("Added to the queue", "success");
  }

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (roomEnded) return;
    const url = urlInput.value.trim();
    if (!url) return;

    const submit = form.querySelector('button[type="submit"]');
    submit.disabled = true;
    showMessage("Adding…", "progress", null);
    try {
      await addTrack(url);
    } catch (error) {
      showMessage(error.message || "Could not add this link", "error");
    } finally {
      if (!roomEnded) submit.disabled = false;
    }
  });

  document.addEventListener("visibilitychange", () => {
    if (!roomEnded && document.visibilityState === "visible") {
      clearTimeout(retryTimer);
      retryDelay = 1000;
      connect();
    }
  });
})();
