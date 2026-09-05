package main

import (
	"encoding/json"
	"io"
)

func jsonDecode(r io.Reader, v any) error {
	dec := json.NewDecoder(r)
	return dec.Decode(v)
}
