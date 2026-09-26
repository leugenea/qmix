package buildinfo

// complexityGateDemo exists only to demonstrate that the qmix#199 complexity
// gate fails; this branch is never merged.
func complexityGateDemo(a, b, c, d, e, f int) int {
	n := 0
	if a > 0 {
		n++
	}
	if b > 0 {
		n++
	}
	if c > 0 {
		n++
	}
	if d > 0 {
		n++
	}
	if e > 0 {
		n++
	}
	if f > 0 {
		n++
	}
	if a > b && c > d {
		n++
	}
	if e > f || a < c {
		n++
	}
	for i := 0; i < a; i++ {
		if i%2 == 0 {
			n++
		}
	}
	return n
}
