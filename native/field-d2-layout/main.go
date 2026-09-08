// Command field-d2-layout is Field's graph-layout helper: it lays out a node/edge
// graph with one of d2's layout engines and reports the resulting geometry.
//
// It exists as a separate process because d2 (and TALA, its architecture-diagram
// layout engine) are Go, and Field is a JVM application. Running them in-process
// under GraalWasm was measured and rejected -- see README.md.
//
// Protocol: newline-delimited JSON, one response object per request object, on
// stdin/stdout. The caller may send a single request and close stdin, or keep the
// process alive and stream many. Diagnostics go to stderr and are never mixed into
// the response stream.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"runtime/debug"
	"strings"
	"time"

	"github.com/d2lang/d2/d2graph"
	"github.com/d2lang/d2/d2layouts/d2dagrelayout"
	"github.com/d2lang/d2/d2layouts/d2elklayout"
	"github.com/d2lang/d2/d2layouts/d2talalayout"
	"github.com/d2lang/d2/d2target"
	"github.com/d2lang/d2/lib/geo"
)

// protocolVersion is bumped whenever the request or response shape changes in a way
// a caller must notice. Field compares it on startup: a stale helper binary that
// silently ignores newer fields is a failure mode this repo has been bitten by
// before, so the handshake is mandatory rather than advisory.
const protocolVersion = 1

const defaultTimeout = 30 * time.Second

// ---- wire types ------------------------------------------------------------

type request struct {
	Op     string `json:"op"`     // "layout" (default) or "version"
	Engine string `json:"engine"` // "tala" (default), "dagre", "elk"

	Nodes []reqNode `json:"nodes"`
	Edges []reqEdge `json:"edges"`

	// TALA only. Seeds selects deterministic attempts; nil uses the engine default
	// of {1,2,3}. Concurrency bounds simultaneous attempts.
	Seeds       []int64 `json:"seeds,omitempty"`
	Concurrency int     `json:"concurrency,omitempty"`

	TimeoutMs int `json:"timeoutMs,omitempty"`

	// Coordinates is "absolute" (default) or "relative". Under "relative" a nested
	// node's x/y is measured from its parent's top-left instead of the graph origin,
	// which is the form Field's nested canvases position children in. Root-level
	// nodes are identical either way, and edge routes stay absolute in both.
	Coordinates string `json:"coordinates,omitempty"`

	// Direction biases the whole layout: "up", "down", "left" or "right". Empty lets the
	// engine decide. A pipeline usually wants "right" or "down"; a cyclic graph rarely
	// benefits, and TALA is free to ignore it where the structure says otherwise.
	Direction string `json:"direction,omitempty"`

	// Hierarchy puts TALA in its hierarchy mode, which treats the graph as a containment
	// tree rather than a free arrangement.
	Hierarchy bool `json:"hierarchy,omitempty"`
}

type reqNode struct {
	ID string `json:"id"`
	W  float64 `json:"w"`
	H  float64 `json:"h"`
	// Parent nests this node inside another by id; empty means the graph root.
	Parent string `json:"parent,omitempty"`
}

type reqEdge struct {
	// ID is echoed back so a caller can map results onto its own edges. Parallel
	// edges between the same pair are otherwise indistinguishable in the response.
	ID  string `json:"id,omitempty"`
	Src string `json:"src"`
	Dst string `json:"dst"`
}

type response struct {
	Protocol int    `json:"protocol"`
	OK       bool   `json:"ok"`
	Error    string `json:"error,omitempty"`

	// version op
	D2Version string   `json:"d2Version,omitempty"`
	Engines   []string `json:"engines,omitempty"`

	// layout op
	Engine    string    `json:"engine,omitempty"`
	ElapsedMs float64   `json:"elapsedMs,omitempty"`
	Bounds    *rect     `json:"bounds,omitempty"`
	Nodes     []resNode `json:"nodes,omitempty"`
	Edges     []resEdge `json:"edges,omitempty"`
}

type rect struct {
	X float64 `json:"x"`
	Y float64 `json:"y"`
	W float64 `json:"w"`
	H float64 `json:"h"`
}

type resNode struct {
	ID string `json:"id"`
	// Parent is echoed so a caller can walk the nesting -- and, under relative
	// coordinates, recover absolute positions without keeping its own copy of the
	// tree it sent.
	Parent string  `json:"parent,omitempty"`
	X      float64 `json:"x"`
	Y      float64 `json:"y"`
	// W and H are echoed because a layout may resize a node: a container's size is
	// derived from the children the engine placed inside it.
	W float64 `json:"w"`
	H float64 `json:"h"`
}

type resEdge struct {
	ID    string       `json:"id"`
	Src   string       `json:"src"`
	Dst   string       `json:"dst"`
	Route [][2]float64 `json:"route"`
}

// ---- graph construction ----------------------------------------------------

func buildGraph(req *request) (*d2graph.Graph, []*d2graph.Object, error) {
	if len(req.Nodes) == 0 {
		return nil, nil, errors.New("no nodes")
	}
	g := d2graph.NewGraph()
	switch req.Direction {
	case "", "up", "down", "left", "right":
		g.Root.Direction.Value = req.Direction
	default:
		return nil, nil, fmt.Errorf(
			"unknown direction %q (want up, down, left or right)", req.Direction)
	}
	if req.Hierarchy {
		g.Root.Shape.Value = d2target.ShapeHierarchy
	}
	byID := make(map[string]*d2graph.Object, len(req.Nodes))
	// d2graph keys Object.Children by strings.ToLower(id) (d2graph.go:728), so two ids
	// differing only in case would collide there while both landed in ChildrenArray --
	// which surfaces much later as "inconsistent Children and ChildrenArray sets".
	// Reject it up front, where the message can name the actual ids.
	folded := make(map[string]string, len(req.Nodes))
	order := make([]*d2graph.Object, 0, len(req.Nodes))

	for i := range req.Nodes {
		n := &req.Nodes[i]
		if n.ID == "" {
			return nil, nil, fmt.Errorf("node %d has an empty id", i)
		}
		if _, dup := byID[n.ID]; dup {
			return nil, nil, fmt.Errorf("duplicate node id %q", n.ID)
		}
		if prior, clash := folded[strings.ToLower(n.ID)]; clash {
			return nil, nil, fmt.Errorf(
				"node ids %q and %q differ only in case, which d2 cannot distinguish", prior, n.ID)
		}
		folded[strings.ToLower(n.ID)] = n.ID
		if n.W <= 0 || n.H <= 0 {
			return nil, nil, fmt.Errorf("node %q has non-positive size %gx%g", n.ID, n.W, n.H)
		}
		o := &d2graph.Object{
			Graph:      g,
			ID:         n.ID,
			IDVal:      n.ID,
			Box:        geo.NewBox(geo.NewPoint(0, 0), n.W, n.H),
			Children:   make(map[string]*d2graph.Object),
			Attributes: d2graph.Attributes{},
		}
		byID[n.ID] = o
		order = append(order, o)
		g.Objects = append(g.Objects, o)
	}

	// Parents are wired in a second pass so declaration order does not matter.
	for i := range req.Nodes {
		n := &req.Nodes[i]
		o := byID[n.ID]
		parent := g.Root
		if n.Parent != "" {
			if n.Parent == n.ID {
				return nil, nil, fmt.Errorf("node %q is its own parent", n.ID)
			}
			p, ok := byID[n.Parent]
			if !ok {
				return nil, nil, fmt.Errorf("node %q names unknown parent %q", n.ID, n.Parent)
			}
			parent = p
		}
		o.Parent = parent
		parent.Children[strings.ToLower(o.ID)] = o
		parent.ChildrenArray = append(parent.ChildrenArray, o)
	}
	if err := checkAcyclicNesting(order); err != nil {
		return nil, nil, err
	}

	for i := range req.Edges {
		e := &req.Edges[i]
		src, ok := byID[e.Src]
		if !ok {
			return nil, nil, fmt.Errorf("edge %d names unknown src %q", i, e.Src)
		}
		dst, ok := byID[e.Dst]
		if !ok {
			return nil, nil, fmt.Errorf("edge %d names unknown dst %q", i, e.Dst)
		}
		g.Edges = append(g.Edges, &d2graph.Edge{
			Index:      i,
			Src:        src,
			Dst:        dst,
			DstArrow:   true,
			Attributes: d2graph.Attributes{},
		})
	}
	return g, order, nil
}

// checkAcyclicNesting rejects a parent cycle before it reaches the engine, where it
// would present as a hang or a stack overflow rather than an error.
func checkAcyclicNesting(objects []*d2graph.Object) error {
	const maxDepth = 256 // matches the engine's own tree-depth ceiling
	for _, o := range objects {
		depth := 0
		for p := o.Parent; p != nil; p = p.Parent {
			depth++
			if depth > maxDepth {
				return fmt.Errorf("nesting of node %q exceeds depth %d (cycle?)", o.ID, maxDepth)
			}
		}
	}
	return nil
}

// ---- layout ----------------------------------------------------------------

func runLayout(ctx context.Context, engine string, g *d2graph.Graph, req *request) (err error) {
	// A panic in a layout engine must not take the helper down: Field may have many
	// documents open, and one pathological graph should cost one error.
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("%s layout panicked: %v", engine, r)
		}
	}()

	switch engine {
	case "tala":
		opts := d2talalayout.DefaultOptions()
		if len(req.Seeds) > 0 {
			opts.Seeds = req.Seeds
		}
		if req.Concurrency > 0 {
			opts.MaxConcurrency = req.Concurrency
		}
		return d2talalayout.Layout(ctx, g, &opts)
	case "dagre":
		return d2dagrelayout.DefaultLayout(ctx, g)
	case "elk":
		return d2elklayout.DefaultLayout(ctx, g)
	default:
		return fmt.Errorf("unknown engine %q (want tala, dagre or elk)", engine)
	}
}

func handleLayout(req *request) response {
	engine := req.Engine
	if engine == "" {
		engine = "tala"
	}
	relative := false
	switch req.Coordinates {
	case "", "absolute":
	case "relative":
		relative = true
	default:
		return response{Protocol: protocolVersion,
			Error: fmt.Sprintf("unknown coordinates %q (want absolute or relative)", req.Coordinates)}
	}
	g, order, err := buildGraph(req)
	if err != nil {
		return response{Protocol: protocolVersion, Error: err.Error()}
	}

	timeout := defaultTimeout
	if req.TimeoutMs > 0 {
		timeout = time.Duration(req.TimeoutMs) * time.Millisecond
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	start := time.Now()
	if err := runLayout(ctx, engine, g, req); err != nil {
		if ctx.Err() != nil {
			return response{Protocol: protocolVersion,
				Error: fmt.Sprintf("%s layout timed out after %s", engine, timeout)}
		}
		return response{Protocol: protocolVersion, Error: err.Error()}
	}
	elapsed := float64(time.Since(start).Microseconds()) / 1000.0

	res := response{
		Protocol: protocolVersion, OK: true, Engine: engine, ElapsedMs: elapsed,
		Nodes: make([]resNode, 0, len(order)),
		Edges: make([]resEdge, 0, len(g.Edges)),
	}

	var minX, minY, maxX, maxY float64
	for i, o := range order {
		if o.TopLeft == nil {
			return response{Protocol: protocolVersion,
				Error: fmt.Sprintf("%s layout left node %q unpositioned", engine, o.ID)}
		}
		x, y, w, h := o.TopLeft.X, o.TopLeft.Y, o.Width, o.Height

		// Bounds are always the absolute extent: they describe the drawing, not any
		// one node's frame of reference.
		if i == 0 {
			minX, minY, maxX, maxY = x, y, x+w, y+h
		} else {
			minX, minY = min(minX, x), min(minY, y)
			maxX, maxY = max(maxX, x+w), max(maxY, y+h)
		}

		parent := ""
		if o.Parent != nil && o.Parent != g.Root {
			parent = o.Parent.ID
			if relative && o.Parent.TopLeft != nil {
				x -= o.Parent.TopLeft.X
				y -= o.Parent.TopLeft.Y
			}
		}
		res.Nodes = append(res.Nodes, resNode{ID: o.ID, Parent: parent, X: x, Y: y, W: w, H: h})
	}
	res.Bounds = &rect{X: minX, Y: minY, W: maxX - minX, H: maxY - minY}

	for i, e := range g.Edges {
		id := ""
		if i < len(req.Edges) {
			id = req.Edges[i].ID
		}
		if id == "" {
			id = fmt.Sprintf("%d", i)
		}
		route := make([][2]float64, 0, len(e.Route))
		for _, p := range e.Route {
			route = append(route, [2]float64{p.X, p.Y})
		}
		res.Edges = append(res.Edges, resEdge{ID: id, Src: e.Src.ID, Dst: e.Dst.ID, Route: route})
	}
	return res
}

// handleVersion reports the d2 version actually linked into this binary, read from
// the Go build info rather than a hand-maintained constant -- so it cannot drift.
func handleVersion() response {
	d2Version := "unknown"
	if info, ok := debug.ReadBuildInfo(); ok {
		for _, dep := range info.Deps {
			if dep.Path == "github.com/d2lang/d2" {
				d2Version = dep.Version
				break
			}
		}
	}
	return response{
		Protocol: protocolVersion, OK: true,
		D2Version: d2Version,
		Engines:   []string{"tala", "dagre", "elk"},
	}
}

func main() {
	dec := json.NewDecoder(os.Stdin)
	enc := json.NewEncoder(os.Stdout)

	for {
		var req request
		if err := dec.Decode(&req); err != nil {
			if errors.Is(err, io.EOF) {
				return
			}
			_ = enc.Encode(response{Protocol: protocolVersion,
				Error: "malformed request: " + err.Error()})
			// The decoder cannot resynchronise mid-stream, so a parse error ends
			// the session rather than silently misreading everything after it.
			os.Exit(1)
		}

		var res response
		switch req.Op {
		case "", "layout":
			res = handleLayout(&req)
		case "version":
			res = handleVersion()
		default:
			res = response{Protocol: protocolVersion, Error: "unknown op " + req.Op}
		}
		if err := enc.Encode(res); err != nil {
			fmt.Fprintln(os.Stderr, "field-d2-layout: write failed:", err)
			os.Exit(1)
		}
	}
}
