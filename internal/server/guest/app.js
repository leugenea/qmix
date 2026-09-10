(() => {
  "use strict";

  const code = decodeURIComponent(window.location.pathname.split("/").filter(Boolean)[1] || "");
  const currentElement = document.getElementById("current-track");
  const queueElement = document.getElementById("queue");
  const form = document.getElementById("queue-form");
  const urlInput = document.getElementById("track-url");
  const messageElement = document.getElementById("message");
  let current = null;
  let source = null;
  let retryTimer = null;
  let messageTimer = null;
  let retryDelay = 1000;
  const maxRetryDelay = 30000;

  function showMessage(message, kind, dismissAfter = 5000) {
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

  function renderCurrent() {
    currentElement.textContent = trackLabel(current);
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

  function connect() {
    if (source) source.close();
    const eventSource = new EventSource(`/rooms/${encodeURIComponent(code)}/events`);
    source = eventSource;
    eventSource.onopen = () => {
      if (source !== eventSource) return;
      retryDelay = 1000;
    };
    eventSource.onerror = () => {
      if (source !== eventSource) return;
      eventSource.close();
      clearTimeout(retryTimer);
      retryTimer = setTimeout(connect, retryDelay);
      retryDelay = Math.min(retryDelay * 2, maxRetryDelay);
    };
    eventSource.addEventListener("queue_snapshot", (event) => {
      if (source !== eventSource) return;
      const data = parseEvent(event);
      if (!data) return;
      current = data.current;
      renderCurrent();
      renderQueue(data.queue);
    });
    eventSource.addEventListener("queue_updated", (event) => {
      if (source !== eventSource) return;
      const data = parseEvent(event);
      if (data) renderQueue(data.queue);
    });
    eventSource.addEventListener("track_changed", (event) => {
      if (source !== eventSource) return;
      const data = parseEvent(event);
      if (!data) return;
      current = data;
      renderCurrent();
    });
    eventSource.addEventListener("player_state", (event) => {
      if (source !== eventSource) return;
      const data = parseEvent(event);
      if (data && current) {
        current.state = data.state;
        renderCurrent();
      }
    });
  }

  connect();

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const url = urlInput.value.trim();
    if (!url) return;

    const submit = form.querySelector('button[type="submit"]');
    submit.disabled = true;
    showMessage("Adding…", "progress", null);
    try {
      const response = await fetch(`/r/${encodeURIComponent(code)}/queue`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ url }),
      });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(data.message || "Could not add this link");
      form.reset();
      showMessage("Added to the queue", "success");
    } catch (error) {
      showMessage(error.message || "Could not add this link", "error");
    } finally {
      submit.disabled = false;
    }
  });

  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") {
      clearTimeout(retryTimer);
      retryDelay = 1000;
      connect();
    }
  });
})();
