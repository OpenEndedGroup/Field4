package main

import (
	"fmt"
	"testing"
)

func nodes(specs ...[3]interface{}) []reqNode {
	out := make([]reqNode, 0, len(specs))
	for _, s := range specs {
		out = append(out, reqNode{ID: s[0].(string), W: s[1].(float64), H: s[2].(float64)})
	}
	return out
}

func simple() *request {
	return &request{
		Nodes: nodes(
			[3]interface{}{"a", 180.0, 90.0},
			[3]interface{}{"b", 180.0, 90.0},
			[3]interface{}{"c", 240.0, 120.0},
		),
		Edges: []reqEdge{{ID: "e1", Src: "a", Dst: "b"}, {ID: "e2", Src: "b", Dst: "c"}},
	}
}

func TestVersionReportsLinkedD2(t *testing.T) {
	res := handleVersion()
	if !res.OK || res.Protocol != protocolVersion {
		t.Fatalf("version not ok: %+v", res)
	}
	// Read from build info, so an unset value means the module path drifted.
	if res.D2Version == "" || res.D2Version == "unknown" {
		t.Fatalf("d2 version not resolved from build info: %q", res.D2Version)
	}
}

func TestEveryEngineLaysOut(t *testing.T) {
	for _, engine := range []string{"", "tala", "dagre", "elk"} {
		t.Run("engine="+engine, func(t *testing.T) {
			req := simple()
			req.Engine = engine
			res := handleLayout(req)
			if !res.OK {
				t.Fatalf("layout failed: %s", res.Error)
			}
			if len(res.Nodes) != 3 || len(res.Edges) != 2 {
				t.Fatalf("got %d nodes %d edges", len(res.Nodes), len(res.Edges))
			}
			if res.Bounds == nil || res.Bounds.W <= 0 || res.Bounds.H <= 0 {
				t.Fatalf("degenerate bounds %+v", res.Bounds)
			}
			// Nothing may share a top-left; that would mean an unplaced node.
			seen := map[string]bool{}
			for _, n := range res.Nodes {
				k := fmt.Sprintf("%.3f,%.3f", n.X, n.Y)
				if seen[k] {
					t.Fatalf("two nodes at %s", k)
				}
				seen[k] = true
			}
		})
	}
}

func TestEdgeIDsAreEchoed(t *testing.T) {
	res := handleLayout(simple())
	if !res.OK {
		t.Fatal(res.Error)
	}
	for i, want := range []string{"e1", "e2"} {
		if res.Edges[i].ID != want {
			t.Fatalf("edge %d id = %q, want %q", i, res.Edges[i].ID, want)
		}
	}
}

func TestUnlabelledEdgesFallBackToIndex(t *testing.T) {
	req := simple()
	req.Edges = []reqEdge{{Src: "a", Dst: "b"}, {Src: "b", Dst: "c"}}
	res := handleLayout(req)
	if !res.OK {
		t.Fatal(res.Error)
	}
	if res.Edges[0].ID != "0" || res.Edges[1].ID != "1" {
		t.Fatalf("ids = %q, %q", res.Edges[0].ID, res.Edges[1].ID)
	}
}

// A container is declared at some nominal size and the engine grows it to fit what it
// placed inside. Callers rely on the returned w/h, not the ones they sent.
func TestContainerIsResizedAroundItsChildren(t *testing.T) {
	req := &request{
		Nodes: []reqNode{
			{ID: "outer", W: 50, H: 50},
			{ID: "in1", W: 120, H: 60, Parent: "outer"},
			{ID: "in2", W: 120, H: 60, Parent: "outer"},
		},
		Edges: []reqEdge{{Src: "in1", Dst: "in2"}},
	}
	res := handleLayout(req)
	if !res.OK {
		t.Fatal(res.Error)
	}
	var outer resNode
	for _, n := range res.Nodes {
		if n.ID == "outer" {
			outer = n
		}
	}
	if outer.W <= 50 || outer.H <= 50 {
		t.Fatalf("container was not grown around its children: %gx%g", outer.W, outer.H)
	}
}

// geometryKey collapses a response to everything a caller would draw.
func geometryKey(res response) string {
	s := ""
	for _, n := range res.Nodes {
		s += fmt.Sprintf("%s:%.4f,%.4f,%.4f,%.4f;", n.ID, n.X, n.Y, n.W, n.H)
	}
	for _, e := range res.Edges {
		s += e.ID + ":"
		for _, p := range e.Route {
			s += fmt.Sprintf("%.4f,%.4f|", p[0], p[1])
		}
	}
	return s
}

func TestSeededLayoutIsDeterministic(t *testing.T) {
	first := geometryKey(handleLayout(dense(5)))
	for i := 0; i < 3; i++ {
		if got := geometryKey(handleLayout(dense(5))); got != first {
			t.Fatalf("run %d differed from the first", i)
		}
	}
}

// dense builds a small cyclic net -- a Petri-net shape. A tree or a chain has
// essentially one good drawing, so seeds cannot be observed on one.
func dense(n int) *request {
	req := &request{}
	for i := 0; i < n; i++ {
		req.Nodes = append(req.Nodes,
			reqNode{ID: fmt.Sprintf("p%d", i), W: 60, H: 60},
			reqNode{ID: fmt.Sprintf("t%d", i), W: 40, H: 70},
			reqNode{ID: fmt.Sprintf("f%d", i), W: 50, H: 50})
	}
	for i := 0; i < n; i++ {
		r := (i + 1) % n
		req.Edges = append(req.Edges,
			reqEdge{Src: fmt.Sprintf("p%d", i), Dst: fmt.Sprintf("t%d", i)},
			reqEdge{Src: fmt.Sprintf("f%d", i), Dst: fmt.Sprintf("t%d", i)},
			reqEdge{Src: fmt.Sprintf("f%d", r), Dst: fmt.Sprintf("t%d", i)},
			reqEdge{Src: fmt.Sprintf("t%d", i), Dst: fmt.Sprintf("p%d", r)},
			reqEdge{Src: fmt.Sprintf("t%d", i), Dst: fmt.Sprintf("f%d", i)})
	}
	return req
}

func TestSeedsSelectDifferentDrawings(t *testing.T) {
	seen := map[string]int64{}
	for _, seed := range []int64{1, 2, 7, 99} {
		req := dense(5)
		req.Seeds = []int64{seed}
		res := handleLayout(req)
		if !res.OK {
			t.Fatalf("seed %d: %s", seed, res.Error)
		}
		k := geometryKey(res)
		if prev, dup := seen[k]; dup {
			t.Fatalf("seeds %d and %d produced identical geometry", prev, seed)
		}
		seen[k] = seed

		// Each seed must also be reproducible on its own, or "deterministic" means
		// nothing to a caller who pins one.
		again := dense(5)
		again.Seeds = []int64{seed}
		if k2 := geometryKey(handleLayout(again)); k2 != k {
			t.Fatalf("seed %d was not reproducible", seed)
		}
	}
}

func TestBadInputIsAnErrorNotACrash(t *testing.T) {
	cases := map[string]*request{
		"no nodes":        {},
		"unknown dst":     {Nodes: nodes([3]interface{}{"a", 10.0, 10.0}), Edges: []reqEdge{{Src: "a", Dst: "nope"}}},
		"unknown src":     {Nodes: nodes([3]interface{}{"a", 10.0, 10.0}), Edges: []reqEdge{{Src: "nope", Dst: "a"}}},
		"duplicate id":    {Nodes: nodes([3]interface{}{"a", 10.0, 10.0}, [3]interface{}{"a", 10.0, 10.0})},
		"zero width":      {Nodes: []reqNode{{ID: "a", W: 0, H: 10}}},
		"negative height": {Nodes: []reqNode{{ID: "a", W: 10, H: -5}}},
		"empty id":        {Nodes: []reqNode{{ID: "", W: 10, H: 10}}},
		"self parent":     {Nodes: []reqNode{{ID: "a", W: 10, H: 10, Parent: "a"}}},
		"unknown parent":  {Nodes: []reqNode{{ID: "a", W: 10, H: 10, Parent: "ghost"}}},
		"unknown engine":  {Engine: "bogus", Nodes: nodes([3]interface{}{"a", 10.0, 10.0})},
	}
	for name, req := range cases {
		t.Run(name, func(t *testing.T) {
			res := handleLayout(req)
			if res.OK {
				t.Fatal("expected an error")
			}
			if res.Error == "" {
				t.Fatal("error response carried no message")
			}
			if res.Protocol != protocolVersion {
				t.Fatalf("error response lost the protocol version")
			}
		})
	}
}

func TestTimeoutIsReported(t *testing.T) {
	req := simple()
	req.TimeoutMs = 1 // shorter than any real layout
	res := handleLayout(req)
	if res.OK {
		t.Skip("layout beat a 1ms deadline; nothing to assert")
	}
	if res.Error == "" {
		t.Fatal("timeout produced no message")
	}
}

// twoContainers keeps both nests off the origin, so relative and absolute
// coordinates cannot coincide -- the first version of this test used one container
// that happened to land at (0,0) and passed while proving nothing.
func twoContainers() *request {
	return &request{
		Nodes: []reqNode{
			{ID: "A", W: 50, H: 50},
			{ID: "a1", W: 120, H: 60, Parent: "A"},
			{ID: "a2", W: 120, H: 60, Parent: "A"},
			{ID: "B", W: 50, H: 50},
			{ID: "b1", W: 140, H: 80, Parent: "B"},
			{ID: "b2", W: 140, H: 80, Parent: "B"},
		},
		Edges: []reqEdge{{Src: "a1", Dst: "a2"}, {Src: "b1", Dst: "b2"}, {Src: "A", Dst: "B"}},
	}
}

// Regression: d2graph keys Object.Children by strings.ToLower(id), so building the
// map with the raw id made every uppercase container fail deep inside the engine
// with "inconsistent Children and ChildrenArray sets". Field box names are
// user-written, so this was not an exotic case.
func TestUppercaseIDsNest(t *testing.T) {
	res := handleLayout(twoContainers())
	if !res.OK {
		t.Fatalf("layout failed: %s", res.Error)
	}
	for _, n := range res.Nodes {
		if n.ID == "a1" && n.Parent != "A" {
			t.Fatalf("a1 lost its parent: %q", n.Parent)
		}
	}
}

func TestCaseOnlyIDCollisionIsRejected(t *testing.T) {
	res := handleLayout(&request{Nodes: []reqNode{{ID: "Box", W: 10, H: 10}, {ID: "box", W: 10, H: 10}}})
	if res.OK {
		t.Fatal("expected ids differing only in case to be rejected")
	}
}

func TestRelativeCoordinatesAreParentLocal(t *testing.T) {
	abs := handleLayout(twoContainers())
	rel := func() response { r := twoContainers(); r.Coordinates = "relative"; return handleLayout(r) }()
	if !abs.OK || !rel.OK {
		t.Fatalf("abs=%s rel=%s", abs.Error, rel.Error)
	}
	at := func(res response, id string) resNode {
		for _, n := range res.Nodes {
			if n.ID == id {
				return n
			}
		}
		t.Fatalf("no node %q", id)
		return resNode{}
	}

	offsetSeen := false
	for _, n := range rel.Nodes {
		a := at(abs, n.ID)
		if n.Parent == "" {
			// Root-level nodes are the same in both modes.
			if n.X != a.X || n.Y != a.Y {
				t.Fatalf("root node %q moved: (%g,%g) vs (%g,%g)", n.ID, n.X, n.Y, a.X, a.Y)
			}
			continue
		}
		p := at(abs, n.Parent)
		if got, want := n.X, a.X-p.X; got != want {
			t.Fatalf("%q relative x = %g, want %g", n.ID, got, want)
		}
		if got, want := n.Y, a.Y-p.Y; got != want {
			t.Fatalf("%q relative y = %g, want %g", n.ID, got, want)
		}
		if p.X != 0 || p.Y != 0 {
			offsetSeen = true
		}
	}
	// Guards the trap this fixture exists for: with every container at the origin the
	// assertions above hold trivially.
	if !offsetSeen {
		t.Fatal("no container was off the origin; the test proved nothing")
	}
}

func TestBoundsStayAbsoluteUnderRelativeCoordinates(t *testing.T) {
	abs := handleLayout(twoContainers())
	rel := func() response { r := twoContainers(); r.Coordinates = "relative"; return handleLayout(r) }()
	if *abs.Bounds != *rel.Bounds {
		t.Fatalf("bounds differ: %+v vs %+v", *abs.Bounds, *rel.Bounds)
	}
}

func TestUnknownCoordinateModeIsRejected(t *testing.T) {
	r := twoContainers()
	r.Coordinates = "sideways"
	if handleLayout(r).OK {
		t.Fatal("expected an unknown coordinates mode to be rejected")
	}
}

func TestDirectionIsAccepted(t *testing.T) {
	for _, direction := range []string{"", "up", "down", "left", "right"} {
		req := dense(5)
		req.Direction = direction
		res := handleLayout(req)
		if !res.OK {
			t.Fatalf("direction %q: %s", direction, res.Error)
		}
	}
}

func TestUnknownDirectionIsRejected(t *testing.T) {
	req := dense(5)
	req.Direction = "widdershins"
	if handleLayout(req).OK {
		t.Fatal("expected an unknown direction to be rejected")
	}
}

// The bias is meant to change the drawing, not to be quietly dropped on the way through.
func TestDirectionChangesTheDrawing(t *testing.T) {
	seen := map[string]string{}
	for _, direction := range []string{"up", "down", "left", "right"} {
		req := dense(5)
		req.Direction = direction
		req.Seeds = []int64{1}
		res := handleLayout(req)
		if !res.OK {
			t.Fatalf("direction %q: %s", direction, res.Error)
		}
		seen[direction] = geometryKey(res)
	}
	if seen["up"] == seen["down"] && seen["up"] == seen["left"] && seen["up"] == seen["right"] {
		t.Fatal("every direction produced the same geometry; the bias is not reaching the engine")
	}
}

func TestHierarchyModeIsAccepted(t *testing.T) {
	req := dense(5)
	req.Hierarchy = true
	res := handleLayout(req)
	if !res.OK {
		t.Fatalf("hierarchy: %s", res.Error)
	}
	if len(res.Nodes) != len(req.Nodes) {
		t.Fatalf("hierarchy dropped nodes: %d of %d", len(res.Nodes), len(req.Nodes))
	}
}

// Routes are the half of the answer a caller that only reads positions throws away.
func TestEdgesComeBackRouted(t *testing.T) {
	res := handleLayout(dense(5))
	if !res.OK {
		t.Fatal(res.Error)
	}
	bent := 0
	for _, e := range res.Edges {
		if len(e.Route) < 2 {
			t.Fatalf("edge %s has a degenerate route: %v", e.ID, e.Route)
		}
		if len(e.Route) > 2 {
			bent++
		}
	}
	if bent == 0 {
		t.Fatal("no route has an interior point, so routing gained nothing over straight lines")
	}
}
