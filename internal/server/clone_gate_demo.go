package server

import "sync"

// Demo for #202: duplicate of subscribeRef to prove clone-gate fails. Never merged.
func (h *Hub) cloneGateDemo(ref roomRef) (*subscriber, func()) {
	h.mu.Lock()
	defer h.mu.Unlock()

	rh := h.incarnations[ref]
	if rh == nil {
		rh = &roomHub{subs: make(map[*subscriber]struct{})}
		h.incarnations[ref] = rh
	}
	sub := &subscriber{ch: make(chan Event, 16), done: make(chan struct{})}
	rh.subs[sub] = struct{}{}

	var once sync.Once
	cancel := func() {
		once.Do(func() {
			h.disconnectRef(ref, sub)
		})
	}
	return sub, cancel
}
