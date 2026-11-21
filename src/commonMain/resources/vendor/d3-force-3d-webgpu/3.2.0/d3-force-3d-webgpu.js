// https://erw-1.github.io/d3-force-3d-webgpu/ v3.2.0
(function (global, factory) {
typeof exports === 'object' && typeof module !== 'undefined' ? factory(exports, require('d3-binarytree'), require('d3-quadtree'), require('d3-octree'), require('d3-dispatch'), require('d3-timer')) :
typeof define === 'function' && define.amd ? define(['exports', 'd3-binarytree', 'd3-quadtree', 'd3-octree', 'd3-dispatch', 'd3-timer'], factory) :
(global = typeof globalThis !== 'undefined' ? globalThis : global || self, factory(global.d3 = global.d3 || {}, global.d3, global.d3, global.d3, global.d3, global.d3));
})(this, (function (exports, d3Binarytree, d3Quadtree, d3Octree, d3Dispatch, d3Timer) { 'use strict';

function center$1(x, y, z) {
  var nodes, strength = 1;

  if (x == null) x = 0;
  if (y == null) y = 0;
  if (z == null) z = 0;

  function force() {
    var i,
        n = nodes.length,
        node,
        sx = 0,
        sy = 0,
        sz = 0;

    for (i = 0; i < n; ++i) {
      node = nodes[i], sx += node.x || 0, sy += node.y || 0, sz += node.z || 0;
    }

    for (sx = (sx / n - x) * strength, sy = (sy / n - y) * strength, sz = (sz / n - z) * strength, i = 0; i < n; ++i) {
      node = nodes[i];
      if (sx) { node.x -= sx; }
      if (sy) { node.y -= sy; }
      if (sz) { node.z -= sz; }
    }
  }

  force.initialize = function(_) {
    nodes = _;
  };

  force.x = function(_) {
    return arguments.length ? (x = +_, force) : x;
  };

  force.y = function(_) {
    return arguments.length ? (y = +_, force) : y;
  };

  force.z = function(_) {
    return arguments.length ? (z = +_, force) : z;
  };

  force.strength = function(_) {
    return arguments.length ? (strength = +_, force) : strength;
  };

  // Description of this force for the WebGPU simulation.
  force.gpu = function() {
    return {type: "center", version: 0, x: x, y: y, z: z, strength: strength};
  };

  return force;
}

function constant(x) {
  return function() {
    return x;
  };
}

function jiggle(random) {
  return (random() - 0.5) * 1e-6;
}

function x$2(d) {
  return d.x + d.vx;
}

function y$2(d) {
  return d.y + d.vy;
}

function z$2(d) {
  return d.z + d.vz;
}

function collide$1(radius) {
  var nodes,
      nDim,
      radii,
      random,
      strength = 1,
      iterations = 1,
      version = 0;

  if (typeof radius !== "function") radius = constant(radius == null ? 1 : +radius);

  function force() {
    var i, n = nodes.length,
        tree,
        node,
        xi,
        yi,
        zi,
        ri,
        ri2;

    for (var k = 0; k < iterations; ++k) {
      tree =
          (nDim === 1 ? d3Binarytree.binarytree(nodes, x$2)
          :(nDim === 2 ? d3Quadtree.quadtree(nodes, x$2, y$2)
          :(nDim === 3 ? d3Octree.octree(nodes, x$2, y$2, z$2)
          :null
      ))).visitAfter(prepare);

      for (i = 0; i < n; ++i) {
        node = nodes[i];
        ri = radii[node.index], ri2 = ri * ri;
        xi = node.x + node.vx;
        if (nDim > 1) { yi = node.y + node.vy; }
        if (nDim > 2) { zi = node.z + node.vz; }
        tree.visit(apply);
      }
    }

    function apply(treeNode, arg1, arg2, arg3, arg4, arg5, arg6) {
      var args = [arg1, arg2, arg3, arg4, arg5, arg6];
      var x0 = args[0],
          y0 = args[1],
          z0 = args[2],
          x1 = args[nDim],
          y1 = args[nDim+1],
          z1 = args[nDim+2];

      var data = treeNode.data, rj = treeNode.r, r = ri + rj;
      if (data) {
        if (data.index > node.index) {
          var x = xi - data.x - data.vx,
              y = (nDim > 1 ? yi - data.y - data.vy : 0),
              z = (nDim > 2 ? zi - data.z - data.vz : 0),
              l = x * x + y * y + z * z;
          if (l < r * r) {
            if (x === 0) x = jiggle(random), l += x * x;
            if (nDim > 1 && y === 0) y = jiggle(random), l += y * y;
            if (nDim > 2 && z === 0) z = jiggle(random), l += z * z;
            l = (r - (l = Math.sqrt(l))) / l * strength;

            node.vx += (x *= l) * (r = (rj *= rj) / (ri2 + rj));
            if (nDim > 1) { node.vy += (y *= l) * r; }
            if (nDim > 2) { node.vz += (z *= l) * r; }

            data.vx -= x * (r = 1 - r);
            if (nDim > 1) { data.vy -= y * r; }
            if (nDim > 2) { data.vz -= z * r; }
          }
        }
        return;
      }
      return x0 > xi + r || x1 < xi - r
          || (nDim > 1 && (y0 > yi + r || y1 < yi - r))
          || (nDim > 2 && (z0 > zi + r || z1 < zi - r));
    }
  }

  function prepare(treeNode) {
    if (treeNode.data) return treeNode.r = radii[treeNode.data.index];
    for (var i = treeNode.r = 0; i < Math.pow(2, nDim); ++i) {
      if (treeNode[i] && treeNode[i].r > treeNode.r) {
        treeNode.r = treeNode[i].r;
      }
    }
  }

  function initialize() {
    if (!nodes) return;
    var i, n = nodes.length, node;
    radii = new Array(n);
    for (i = 0; i < n; ++i) node = nodes[i], radii[node.index] = +radius(node, i, nodes);
    ++version;
  }

  force.initialize = function(_nodes, ...args) {
    nodes = _nodes;
    random = args.find(arg => typeof arg === 'function') || Math.random;
    nDim = args.find(arg => [1, 2, 3].includes(arg)) || 2;
    initialize();
  };

  force.iterations = function(_) {
    return arguments.length ? (iterations = +_, force) : iterations;
  };

  force.strength = function(_) {
    return arguments.length ? (strength = +_, force) : strength;
  };

  force.radius = function(_) {
    return arguments.length ? (radius = typeof _ === "function" ? _ : constant(+_), initialize(), force) : radius;
  };

  // Description of this force for the WebGPU simulation.
  force.gpu = function() {
    return {type: "collide", version: version, radii: radii, strength: strength, iterations: iterations};
  };

  return force;
}

function index(d) {
  return d.index;
}

function find(nodeById, nodeId) {
  var node = nodeById.get(nodeId);
  if (!node) throw new Error("node not found: " + nodeId);
  return node;
}

function link$1(links) {
  var id = index,
      strength = defaultStrength,
      strengths,
      distance = constant(30),
      distances,
      nodes,
      nDim,
      count,
      bias,
      random,
      iterations = 1,
      version = 0;

  if (links == null) links = [];

  function defaultStrength(link) {
    return 1 / Math.min(count[link.source.index], count[link.target.index]);
  }

  function force(alpha) {
    for (var k = 0, n = links.length; k < iterations; ++k) {
      for (var i = 0, link, source, target, x = 0, y = 0, z = 0, l, b; i < n; ++i) {
        link = links[i], source = link.source, target = link.target;
        x = target.x + target.vx - source.x - source.vx || jiggle(random);
        if (nDim > 1) { y = target.y + target.vy - source.y - source.vy || jiggle(random); }
        if (nDim > 2) { z = target.z + target.vz - source.z - source.vz || jiggle(random); }
        l = Math.sqrt(x * x + y * y + z * z);
        l = (l - distances[i]) / l * alpha * strengths[i];
        x *= l, y *= l, z *= l;

        target.vx -= x * (b = bias[i]);
        if (nDim > 1) { target.vy -= y * b; }
        if (nDim > 2) { target.vz -= z * b; }

        source.vx += x * (b = 1 - b);
        if (nDim > 1) { source.vy += y * b; }
        if (nDim > 2) { source.vz += z * b; }
      }
    }
  }

  function initialize() {
    if (!nodes) return;

    var i,
        n = nodes.length,
        m = links.length,
        nodeById = new Map(nodes.map((d, i) => [id(d, i, nodes), d])),
        link;

    for (i = 0, count = new Array(n); i < m; ++i) {
      link = links[i], link.index = i;
      if (typeof link.source !== "object") link.source = find(nodeById, link.source);
      if (typeof link.target !== "object") link.target = find(nodeById, link.target);
      count[link.source.index] = (count[link.source.index] || 0) + 1;
      count[link.target.index] = (count[link.target.index] || 0) + 1;
    }

    for (i = 0, bias = new Array(m); i < m; ++i) {
      link = links[i], bias[i] = count[link.source.index] / (count[link.source.index] + count[link.target.index]);
    }

    strengths = new Array(m), initializeStrength();
    distances = new Array(m), initializeDistance();
  }

  function initializeStrength() {
    if (!nodes) return;

    for (var i = 0, n = links.length; i < n; ++i) {
      strengths[i] = +strength(links[i], i, links);
    }
    ++version;
  }

  function initializeDistance() {
    if (!nodes) return;

    for (var i = 0, n = links.length; i < n; ++i) {
      distances[i] = +distance(links[i], i, links);
    }
    ++version;
  }

  force.initialize = function(_nodes, ...args) {
    nodes = _nodes;
    random = args.find(arg => typeof arg === 'function') || Math.random;
    nDim = args.find(arg => [1, 2, 3].includes(arg)) || 2;
    initialize();
  };

  force.links = function(_) {
    return arguments.length ? (links = _, initialize(), force) : links;
  };

  force.id = function(_) {
    return arguments.length ? (id = _, force) : id;
  };

  force.iterations = function(_) {
    return arguments.length ? (iterations = +_, force) : iterations;
  };

  force.strength = function(_) {
    return arguments.length ? (strength = typeof _ === "function" ? _ : constant(+_), initializeStrength(), force) : strength;
  };

  force.distance = function(_) {
    return arguments.length ? (distance = typeof _ === "function" ? _ : constant(+_), initializeDistance(), force) : distance;
  };

  // Description of this force for the WebGPU simulation.
  force.gpu = function() {
    return {type: "link", version: version, links: links, iterations: iterations, strengths: strengths, distances: distances, bias: bias};
  };

  return force;
}

// https://en.wikipedia.org/wiki/Linear_congruential_generator#Parameters_in_common_use
const a = 1664525;
const c = 1013904223;
const m = 4294967296; // 2^32

function lcg() {
  let s = 1;
  return () => (s = (a * s + c) % m) / m;
}

var MAX_DIMENSIONS$1 = 3;

function x$1(d) {
  return d.x;
}

function y$1(d) {
  return d.y;
}

function z$1(d) {
  return d.z;
}

var initialRadius$1 = 10,
    initialAngleRoll$1 = Math.PI * (3 - Math.sqrt(5)), // Golden ratio angle
    initialAngleYaw$1 = Math.PI * 20 / (9 + Math.sqrt(221)); // Markov irrational number

function simulation$1(nodes, numDimensions) {
  numDimensions = numDimensions || 2;

  var nDim = Math.min(MAX_DIMENSIONS$1, Math.max(1, Math.round(numDimensions))),
      simulation,
      alpha = 1,
      alphaMin = 0.001,
      alphaDecay = 1 - Math.pow(alphaMin, 1 / 300),
      alphaTarget = 0,
      velocityDecay = 0.6,
      forces = new Map(),
      stepper = d3Timer.timer(step),
      event = d3Dispatch.dispatch("tick", "end"),
      random = lcg();

  if (nodes == null) nodes = [];

  function step() {
    tick();
    event.call("tick", simulation);
    if (alpha < alphaMin) {
      stepper.stop();
      event.call("end", simulation);
    }
  }

  function tick(iterations) {
    var i, n = nodes.length, node;

    if (iterations === undefined) iterations = 1;

    for (var k = 0; k < iterations; ++k) {
      alpha += (alphaTarget - alpha) * alphaDecay;

      forces.forEach(function (force) {
        force(alpha);
      });

      for (i = 0; i < n; ++i) {
        node = nodes[i];
        if (node.fx == null) node.x += node.vx *= velocityDecay;
        else node.x = node.fx, node.vx = 0;
        if (nDim > 1) {
          if (node.fy == null) node.y += node.vy *= velocityDecay;
          else node.y = node.fy, node.vy = 0;
        }
        if (nDim > 2) {
          if (node.fz == null) node.z += node.vz *= velocityDecay;
          else node.z = node.fz, node.vz = 0;
        }
      }
    }

    return simulation;
  }

  function initializeNodes() {
    for (var i = 0, n = nodes.length, node; i < n; ++i) {
      node = nodes[i], node.index = i;
      if (node.fx != null) node.x = node.fx;
      if (node.fy != null) node.y = node.fy;
      if (node.fz != null) node.z = node.fz;
      if (isNaN(node.x) || (nDim > 1 && isNaN(node.y)) || (nDim > 2 && isNaN(node.z))) {
        var radius = initialRadius$1 * (nDim > 2 ? Math.cbrt(0.5 + i) : (nDim > 1 ? Math.sqrt(0.5 + i) : i)),
          rollAngle = i * initialAngleRoll$1,
          yawAngle = i * initialAngleYaw$1;

        if (nDim === 1) {
          node.x = radius;
        } else if (nDim === 2) {
          node.x = radius * Math.cos(rollAngle);
          node.y = radius * Math.sin(rollAngle);
        } else { // 3 dimensions: use spherical distribution along 2 irrational number angles
          node.x = radius * Math.sin(rollAngle) * Math.cos(yawAngle);
          node.y = radius * Math.cos(rollAngle);
          node.z = radius * Math.sin(rollAngle) * Math.sin(yawAngle);
        }
      }
      if (isNaN(node.vx) || (nDim > 1 && isNaN(node.vy)) || (nDim > 2 && isNaN(node.vz))) {
        node.vx = 0;
        if (nDim > 1) { node.vy = 0; }
        if (nDim > 2) { node.vz = 0; }
      }
    }
  }

  function initializeForce(force) {
    if (force.initialize) force.initialize(nodes, random, nDim);
    return force;
  }

  initializeNodes();

  return simulation = {
    tick: tick,

    restart: function() {
      return stepper.restart(step), simulation;
    },

    stop: function() {
      return stepper.stop(), simulation;
    },

    numDimensions: function(_) {
      return arguments.length
          ? (nDim = Math.min(MAX_DIMENSIONS$1, Math.max(1, Math.round(_))), forces.forEach(initializeForce), simulation)
          : nDim;
    },

    nodes: function(_) {
      return arguments.length ? (nodes = _, initializeNodes(), forces.forEach(initializeForce), simulation) : nodes;
    },

    alpha: function(_) {
      return arguments.length ? (alpha = +_, simulation) : alpha;
    },

    alphaMin: function(_) {
      return arguments.length ? (alphaMin = +_, simulation) : alphaMin;
    },

    alphaDecay: function(_) {
      return arguments.length ? (alphaDecay = +_, simulation) : +alphaDecay;
    },

    alphaTarget: function(_) {
      return arguments.length ? (alphaTarget = +_, simulation) : alphaTarget;
    },

    velocityDecay: function(_) {
      return arguments.length ? (velocityDecay = 1 - _, simulation) : 1 - velocityDecay;
    },

    randomSource: function(_) {
      return arguments.length ? (random = _, forces.forEach(initializeForce), simulation) : random;
    },

    force: function(name, _) {
      return arguments.length > 1 ? ((_ == null ? forces.delete(name) : forces.set(name, initializeForce(_))), simulation) : forces.get(name);
    },

    find: function() {
      var args = Array.prototype.slice.call(arguments);
      var x = args.shift() || 0,
          y = (nDim > 1 ? args.shift() : null) || 0,
          z = (nDim > 2 ? args.shift() : null) || 0,
          radius = args.shift() || Infinity;

      var i = 0,
          n = nodes.length,
          dx,
          dy,
          dz,
          d2,
          node,
          closest;

      radius *= radius;

      for (i = 0; i < n; ++i) {
        node = nodes[i];
        dx = x - node.x;
        dy = y - (node.y || 0);
        dz = z - (node.z ||0);
        d2 = dx * dx + dy * dy + dz * dz;
        if (d2 < radius) closest = node, radius = d2;
      }

      return closest;
    },

    on: function(name, _) {
      return arguments.length > 1 ? (event.on(name, _), simulation) : event.on(name);
    }
  };
}

function manyBody$1() {
  var nodes,
      nDim,
      node,
      random,
      alpha,
      strength = constant(-30),
      strengths,
      distanceMin2 = 1,
      distanceMax2 = Infinity,
      theta2 = 0.81,
      version = 0;

  function force(_) {
    var i,
        n = nodes.length,
        tree =
            (nDim === 1 ? d3Binarytree.binarytree(nodes, x$1)
            :(nDim === 2 ? d3Quadtree.quadtree(nodes, x$1, y$1)
            :(nDim === 3 ? d3Octree.octree(nodes, x$1, y$1, z$1)
            :null
        ))).visitAfter(accumulate);

    for (alpha = _, i = 0; i < n; ++i) node = nodes[i], tree.visit(apply);
  }

  function initialize() {
    if (!nodes) return;
    var i, n = nodes.length, node;
    strengths = new Array(n);
    for (i = 0; i < n; ++i) node = nodes[i], strengths[node.index] = +strength(node, i, nodes);
    ++version;
  }

  function accumulate(treeNode) {
    var strength = 0, q, c, weight = 0, x, y, z, i;
    var numChildren = treeNode.length;

    // For internal nodes, accumulate forces from children.
    if (numChildren) {
      for (x = y = z = i = 0; i < numChildren; ++i) {
        if ((q = treeNode[i]) && (c = Math.abs(q.value))) {
          strength += q.value, weight += c, x += c * (q.x || 0), y += c * (q.y || 0), z += c * (q.z || 0);
        }
      }
      strength *= Math.sqrt(4 / numChildren); // scale accumulated strength according to number of dimensions

      treeNode.x = x / weight;
      if (nDim > 1) { treeNode.y = y / weight; }
      if (nDim > 2) { treeNode.z = z / weight; }
    }

    // For leaf nodes, accumulate forces from coincident nodes.
    else {
      q = treeNode;
      q.x = q.data.x;
      if (nDim > 1) { q.y = q.data.y; }
      if (nDim > 2) { q.z = q.data.z; }
      do strength += strengths[q.data.index];
      while (q = q.next);
    }

    treeNode.value = strength;
  }

  function apply(treeNode, x1, arg1, arg2, arg3) {
    if (!treeNode.value) return true;
    var x2 = [arg1, arg2, arg3][nDim-1];

    var x = treeNode.x - node.x,
        y = (nDim > 1 ? treeNode.y - node.y : 0),
        z = (nDim > 2 ? treeNode.z - node.z : 0),
        w = x2 - x1,
        l = x * x + y * y + z * z;

    // Apply the Barnes-Hut approximation if possible.
    // Limit forces for very close nodes; randomize direction if coincident.
    if (w * w / theta2 < l) {
      if (l < distanceMax2) {
        if (x === 0) x = jiggle(random), l += x * x;
        if (nDim > 1 && y === 0) y = jiggle(random), l += y * y;
        if (nDim > 2 && z === 0) z = jiggle(random), l += z * z;
        if (l < distanceMin2) l = Math.sqrt(distanceMin2 * l);
        node.vx += x * treeNode.value * alpha / l;
        if (nDim > 1) { node.vy += y * treeNode.value * alpha / l; }
        if (nDim > 2) { node.vz += z * treeNode.value * alpha / l; }
      }
      return true;
    }

    // Otherwise, process points directly.
    else if (treeNode.length || l >= distanceMax2) return;

    // Limit forces for very close nodes; randomize direction if coincident.
    if (treeNode.data !== node || treeNode.next) {
      if (x === 0) x = jiggle(random), l += x * x;
      if (nDim > 1 && y === 0) y = jiggle(random), l += y * y;
      if (nDim > 2 && z === 0) z = jiggle(random), l += z * z;
      if (l < distanceMin2) l = Math.sqrt(distanceMin2 * l);
    }

    do if (treeNode.data !== node) {
      w = strengths[treeNode.data.index] * alpha / l;
      node.vx += x * w;
      if (nDim > 1) { node.vy += y * w; }
      if (nDim > 2) { node.vz += z * w; }
    } while (treeNode = treeNode.next);
  }

  force.initialize = function(_nodes, ...args) {
    nodes = _nodes;
    random = args.find(arg => typeof arg === 'function') || Math.random;
    nDim = args.find(arg => [1, 2, 3].includes(arg)) || 2;
    initialize();
  };

  force.strength = function(_) {
    return arguments.length ? (strength = typeof _ === "function" ? _ : constant(+_), initialize(), force) : strength;
  };

  force.distanceMin = function(_) {
    return arguments.length ? (distanceMin2 = _ * _, force) : Math.sqrt(distanceMin2);
  };

  force.distanceMax = function(_) {
    return arguments.length ? (distanceMax2 = _ * _, force) : Math.sqrt(distanceMax2);
  };

  force.theta = function(_) {
    return arguments.length ? (theta2 = _ * _, force) : Math.sqrt(theta2);
  };

  // Description of this force for the WebGPU simulation, which builds the same tree on
  // the GPU (or sums every pair exactly when theta is 0).
  force.gpu = function() {
    return {type: "manyBody", version: version, strengths: strengths, distanceMin2: distanceMin2, distanceMax2: distanceMax2, theta2: theta2};
  };

  return force;
}

function radial$1(radius, x, y, z) {
  var nodes,
      nDim,
      strength = constant(0.1),
      strengths,
      radiuses,
      version = 0;

  if (typeof radius !== "function") radius = constant(+radius);
  if (x == null) x = 0;
  if (y == null) y = 0;
  if (z == null) z = 0;

  function force(alpha) {
    for (var i = 0, n = nodes.length; i < n; ++i) {
      var node = nodes[i],
          dx = node.x - x || 1e-6,
          dy = (node.y || 0) - y || 1e-6,
          dz = (node.z || 0) - z || 1e-6,
          r = Math.sqrt(dx * dx + dy * dy + dz * dz),
          k = (radiuses[i] - r) * strengths[i] * alpha / r;
      node.vx += dx * k;
      if (nDim>1) { node.vy += dy * k; }
      if (nDim>2) { node.vz += dz * k; }
    }
  }

  function initialize() {
    if (!nodes) return;
    var i, n = nodes.length;
    strengths = new Array(n);
    radiuses = new Array(n);
    for (i = 0; i < n; ++i) {
      radiuses[i] = +radius(nodes[i], i, nodes);
      strengths[i] = isNaN(radiuses[i]) ? 0 : +strength(nodes[i], i, nodes);
    }
    ++version;
  }

  force.initialize = function(initNodes, ...args) {
    nodes = initNodes;
    nDim = args.find(arg => [1, 2, 3].includes(arg)) || 2;
    initialize();
  };

  force.strength = function(_) {
    return arguments.length ? (strength = typeof _ === "function" ? _ : constant(+_), initialize(), force) : strength;
  };

  force.radius = function(_) {
    return arguments.length ? (radius = typeof _ === "function" ? _ : constant(+_), initialize(), force) : radius;
  };

  force.x = function(_) {
    return arguments.length ? (x = +_, force) : x;
  };

  force.y = function(_) {
    return arguments.length ? (y = +_, force) : y;
  };

  force.z = function(_) {
    return arguments.length ? (z = +_, force) : z;
  };

  // Description of this force for the WebGPU simulation.
  force.gpu = function() {
    return {type: "radial", version: version, radiuses: radiuses, strengths: strengths, x: x, y: y, z: z};
  };

  return force;
}

function x(x) {
  var strength = constant(0.1),
      nodes,
      strengths,
      xz,
      version = 0;

  if (typeof x !== "function") x = constant(x == null ? 0 : +x);

  function force(alpha) {
    for (var i = 0, n = nodes.length, node; i < n; ++i) {
      node = nodes[i], node.vx += (xz[i] - node.x) * strengths[i] * alpha;
    }
  }

  function initialize() {
    if (!nodes) return;
    var i, n = nodes.length;
    strengths = new Array(n);
    xz = new Array(n);
    for (i = 0; i < n; ++i) {
      strengths[i] = isNaN(xz[i] = +x(nodes[i], i, nodes)) ? 0 : +strength(nodes[i], i, nodes);
    }
    ++version;
  }

  force.initialize = function(_) {
    nodes = _;
    initialize();
  };

  force.strength = function(_) {
    return arguments.length ? (strength = typeof _ === "function" ? _ : constant(+_), initialize(), force) : strength;
  };

  force.x = function(_) {
    return arguments.length ? (x = typeof _ === "function" ? _ : constant(+_), initialize(), force) : x;
  };

  // Description of this force for the WebGPU simulation.
  force.gpu = function() {
    return {type: "position", axis: 0, version: version, targets: xz, strengths: strengths};
  };

  return force;
}

function y(y) {
  var strength = constant(0.1),
      nodes,
      strengths,
      yz,
      version = 0;

  if (typeof y !== "function") y = constant(y == null ? 0 : +y);

  function force(alpha) {
    for (var i = 0, n = nodes.length, node; i < n; ++i) {
      node = nodes[i], node.vy += (yz[i] - node.y) * strengths[i] * alpha;
    }
  }

  function initialize() {
    if (!nodes) return;
    var i, n = nodes.length;
    strengths = new Array(n);
    yz = new Array(n);
    for (i = 0; i < n; ++i) {
      strengths[i] = isNaN(yz[i] = +y(nodes[i], i, nodes)) ? 0 : +strength(nodes[i], i, nodes);
    }
    ++version;
  }

  force.initialize = function(_) {
    nodes = _;
    initialize();
  };

  force.strength = function(_) {
    return arguments.length ? (strength = typeof _ === "function" ? _ : constant(+_), initialize(), force) : strength;
  };

  force.y = function(_) {
    return arguments.length ? (y = typeof _ === "function" ? _ : constant(+_), initialize(), force) : y;
  };

  // Description of this force for the WebGPU simulation.
  force.gpu = function() {
    return {type: "position", axis: 1, version: version, targets: yz, strengths: strengths};
  };

  return force;
}

function z(z) {
  var strength = constant(0.1),
      nodes,
      strengths,
      zz,
      version = 0;

  if (typeof z !== "function") z = constant(z == null ? 0 : +z);

  function force(alpha) {
    for (var i = 0, n = nodes.length, node; i < n; ++i) {
      node = nodes[i], node.vz += (zz[i] - node.z) * strengths[i] * alpha;
    }
  }

  function initialize() {
    if (!nodes) return;
    var i, n = nodes.length;
    strengths = new Array(n);
    zz = new Array(n);
    for (i = 0; i < n; ++i) {
      strengths[i] = isNaN(zz[i] = +z(nodes[i], i, nodes)) ? 0 : +strength(nodes[i], i, nodes);
    }
    ++version;
  }

  force.initialize = function(_) {
    nodes = _;
    initialize();
  };

  force.strength = function(_) {
    return arguments.length ? (strength = typeof _ === "function" ? _ : constant(+_), initialize(), force) : strength;
  };

  force.z = function(_) {
    return arguments.length ? (z = typeof _ === "function" ? _ : constant(+_), initialize(), force) : z;
  };

  // Description of this force for the WebGPU simulation.
  force.gpu = function() {
    return {type: "position", axis: 2, version: version, targets: zz, strengths: strengths};
  };

  return force;
}

// Adapter / device acquisition. A single device is shared by all simulations unless
// one is passed in explicitly (e.g. to share buffers with a WebGPU renderer).

var devices = new WeakMap(); // GPU -> Promise<GPUDevice>

function isWebGPUAvailable() {
  return typeof navigator !== "undefined" && !!navigator.gpu;
}

function checkWebGPUSupport() {
  if (!isWebGPUAvailable()) return Promise.resolve(false);
  return navigator.gpu.requestAdapter().then(function(adapter) {
    return !!adapter;
  }, function() {
    return false;
  });
}

function requestDevice(gpu) {
  if (!gpu) gpu = isWebGPUAvailable() ? navigator.gpu : null;
  if (!gpu) return Promise.reject(new Error("WebGPU is not available"));

  var promise = devices.get(gpu);
  if (!promise) {
    devices.set(gpu, promise = gpu.requestAdapter({powerPreference: "high-performance"}).then(function(adapter) {
      if (!adapter) throw new Error("No WebGPU adapter found");
      // the largest buffers the adapter allows: big graphs need more than the defaults
      return adapter.requestDevice({requiredLimits: {
        maxStorageBufferBindingSize: adapter.limits.maxStorageBufferBindingSize,
        maxBufferSize: adapter.limits.maxBufferSize
      }});
    }).then(function(device) {
      device.lost.then(function() { if (devices.get(gpu) === promise) devices.delete(gpu); });
      return device;
    }));
    promise.catch(function() { if (devices.get(gpu) === promise) devices.delete(gpu); });
  }
  return promise;
}

// Numeric values of the WebGPU flag enums, so the library does not depend on the
// GPUBufferUsage / GPUShaderStage / GPUMapMode globals (absent in Node, for instance).

var MAP_READ = 0x0001,
    COPY_SRC = 0x0004,
    COPY_DST = 0x0008,
    UNIFORM = 0x0040,
    STORAGE = 0x0080,
    INDIRECT = 0x0100;

var COMPUTE = 0x4;

var MAP_MODE_READ = 0x1;

// Threads per workgroup for the one-thread-per-node kernels.
var WORKGROUP = 64;

// Size of the per-step uniform block (see wgsl.js: struct Sim).
var SIM_BYTES = 32;

// Bind group layouts and compute pipelines are created once per device and reused by
// every simulation on it.

var caches = new WeakMap();

function cacheFor(device) {
  var cache = caches.get(device);
  if (!cache) {
    cache = {layout0: null, layouts: new Map(), pipelines: new Map()};
    caches.set(device, cache);
  }
  return cache;
}

var BUFFER_TYPES = {
  uniform: {type: "uniform"},
  ro: {type: "read-only-storage"},
  rw: {type: "storage"}
};

// Group 0: per-step uniforms (dynamic offset, so one buffer holds a whole batch of
// steps) and the node state buffers.
function layout0(device) {
  var cache = cacheFor(device);
  return cache.layout0 || (cache.layout0 = device.createBindGroupLayout({
    entries: [
      {binding: 0, visibility: COMPUTE, buffer: {type: "uniform", hasDynamicOffset: true, minBindingSize: SIM_BYTES}},
      {binding: 1, visibility: COMPUTE, buffer: BUFFER_TYPES.rw},
      {binding: 2, visibility: COMPUTE, buffer: BUFFER_TYPES.rw}
    ]
  }));
}

// Group 1: described by a list such as ["uniform", "ro", "ro"].
function layout1(device, spec) {
  var cache = cacheFor(device), key = spec.join(","), layout = cache.layouts.get(key);
  if (!layout) {
    cache.layouts.set(key, layout = device.createBindGroupLayout({
      entries: spec.map(function(kind, binding) {
        return {binding: binding, visibility: COMPUTE, buffer: BUFFER_TYPES[kind]};
      })
    }));
  }
  return layout;
}

// A pipeline description is {code, entry, spec}; spec is null for kernels that only use
// group 0. `constants` sets the shader's `override` values, specialising it (a kernel
// tuned to the problem size, say) without another copy of the source.
function descriptor(device, def, constants) {
  var layouts = [layout0(device)];
  if (def.spec) layouts.push(layout1(device, def.spec));
  var compute = {module: device.createShaderModule({code: def.code}), entryPoint: def.entry};
  if (constants) compute.constants = constants;
  return {layout: device.createPipelineLayout({bindGroupLayouts: layouts}), compute: compute};
}

function keyOf(id, constants) {
  return constants ? id + JSON.stringify(constants) : id;
}

function pipeline(device, id, def, constants) {
  var cache = cacheFor(device), key = keyOf(id, constants), p = cache.pipelines.get(key);
  if (!p) cache.pipelines.set(key, p = device.createComputePipeline(descriptor(device, def, constants)));
  return p;
}

// Compile pipelines up front, off the main thread: `jobs` is a list of [id, constants].
// Rejects if a pipeline is invalid (a shader error, say); no error scope is used, so
// validation errors of the application's own work on a shared device are never captured.
function prewarm(device, defs, jobs) {
  var cache = cacheFor(device), queued = {};
  return Promise.all(jobs.filter(function(job) {
    var key = keyOf(job[0], job[1]);
    return !cache.pipelines.has(key) && !queued[key] && (queued[key] = true);
  }).map(function(job) {
    return device.createComputePipelineAsync(descriptor(device, defs[job[0]], job[1])).then(function(p) {
      cache.pipelines.set(keyOf(job[0], job[1]), p);
    });
  }));
}

// WGSL shared by every kernel. Bind group 0 is identical for all of them (one layout
// object), so it stays bound while the pass switches pipelines; group 1 carries the
// per-force buffers.
//
//   pos   xyz = position                       (w unused)
//   vel   xyz = velocity                       (w unused)
//
// Unused dimensions (numDimensions < 3) stay exactly 0 in pos and vel. Kernels that read
// other nodes' predicted positions (link, collide) get the snapshot, pos + vel refreshed
// just before them, in group 1.

var PRELUDE = /* wgsl */`
struct Sim {
  alpha: f32,
  decay: f32,   // velocity multiplier, i.e. 1 - velocityDecay
  n: u32,
  nDim: u32,
  step: u32,
  seed: u32,
  pad0: u32,
  pad1: u32,
}

@group(0) @binding(0) var<uniform> sim: Sim;
@group(0) @binding(1) var<storage, read_write> pos: array<vec4<f32>>;
@group(0) @binding(2) var<storage, read_write> vel: array<vec4<f32>>;

fn pcg(v: u32) -> u32 {
  let s = v * 747796405u + 2891336453u;
  let w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u;
  return (w >> 22u) ^ w;
}

// Same distribution as d3's jiggle: (random() - 0.5) * 1e-6. The value depends only on
// (a, b, axis, step, seed), so both ends of a pair compute the same number.
fn jiggle(a: u32, b: u32, axis: u32) -> f32 {
  let h = pcg(a ^ pcg(b ^ pcg(sim.seed ^ pcg(sim.step * 3u + axis))));
  return (f32(h) * (1.0 / 4294967296.0) - 0.5) * 1e-6;
}

fn activeDims() -> vec3<bool> {
  return vec3<bool>(true, sim.nDim > 1u, sim.nDim > 2u);
}

// Displacement between two coincident points, antisymmetric in (i, j).
fn coincident(i: u32, j: u32) -> vec3<f32> {
  let lo = min(i, j);
  let hi = max(i, j);
  var r = vec3<f32>(jiggle(lo, hi, 0u), jiggle(lo, hi, 1u), jiggle(lo, hi, 2u));
  if (i > j) { r = -r; }
  return select(vec3<f32>(0.0), r, activeDims());
}
`;

// pos + vel -> snap
var SNAPSHOT = PRELUDE + /* wgsl */`
@group(1) @binding(0) var<storage, read_write> snap: array<vec4<f32>>;

@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  let i = gid.x;
  if (i >= sim.n) { return; }
  snap[i] = vec4<f32>(pos[i].xyz + vel[i].xyz, 0.0);
}
`;

// Velocity decay, fixed positions, position update. Mirrors the loop at the end of
// simulation.tick():   x += vx *= velocityDecay   /   x = fx, vx = 0
var INTEGRATE = PRELUDE + /* wgsl */`
@group(1) @binding(0) var<storage, read> fixPos: array<vec4<f32>>;
@group(1) @binding(1) var<storage, read> fixMask: array<u32>;

@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  let i = gid.x;
  if (i >= sim.n) { return; }
  let m = fixMask[i];
  let pinned = vec3<bool>((m & 1u) != 0u, (m & 2u) != 0u, (m & 4u) != 0u);
  let v = vel[i].xyz * sim.decay;
  let p = pos[i].xyz + v;
  pos[i] = vec4<f32>(select(p, fixPos[i].xyz, pinned), 0.0);
  vel[i] = vec4<f32>(select(v, vec3<f32>(0.0), pinned), 0.0);
}
`;

// Largest finite value we hand to a shader in place of Infinity: WGSL does not
// guarantee that infinities survive a round trip through a uniform.
var BIG = 3e38;

function finite(x) {
  return x > BIG ? BIG : x < -BIG ? -BIG : x;
}

// A read-only storage buffer holding `array` (at least 16 bytes: zero-size bindings are
// not allowed).
function storage(device, array) {
  var buffer = device.createBuffer({
    size: Math.max(16, (array.byteLength + 3) & ~3),
    usage: STORAGE | COPY_DST
  });
  if (array.byteLength) device.queue.writeBuffer(buffer, 0, array);
  return buffer;
}

// f32 view over a JS array of numbers; anything non-numeric becomes 0 so that a stray
// undefined can never poison the simulation with NaN.
function floats(values, n) {
  var out = new Float32Array(n);
  for (var i = 0; i < n; ++i) out[i] = finite(+values[i] || 0);
  return out;
}

// A small uniform block that is only rewritten when a value actually changes.
function params$1(device, size) {
  var buffer = device.createBuffer({size: size, usage: UNIFORM | COPY_DST}),
      data = new Float32Array(size / 4),
      view = new Uint32Array(data.buffer),
      dirty = true;

  return {
    buffer: buffer,
    data: data,
    view: view,
    // values: array of numbers written as f32 from word 0; ints: {word: uint value}
    set: function(values, ints) {
      var i;
      for (i = 0; i < values.length; ++i) {
        var v = finite(+values[i]);
        if (data[i] !== v && !(v !== v && data[i] !== data[i])) data[i] = v, dirty = true;
      }
      if (ints) for (var word in ints) {
        if (view[word] !== ints[word]) view[word] = ints[word], dirty = true;
      }
      if (dirty) device.queue.writeBuffer(buffer, 0, data), dirty = false;
    },
    destroy: function() { buffer.destroy(); }
  };
}

// Small graphs give a GPU too few threads (one per node) to hide memory latency. Kernels
// that loop over all nodes therefore let SPLIT threads share each node's loop and add
// their partial sums; big graphs already have threads to spare and use SPLIT = 1.
var TARGET_THREADS = 1 << 18, MAX_SPLIT = 16, TILE_SIZE = 128;
function splitFor(n, forced) {
  var split = 1;
  if (forced > 0) { // the caller's choice: a power of two, at most one node per workgroup
    while (split * 2 <= Math.min(forced, TILE_SIZE)) split *= 2;
    return split;
  }
  while (split < MAX_SPLIT && n * split < TARGET_THREADS) split *= 2;
  return split;
}

function ceilDiv(a, b) {
  return Math.max(1, Math.ceil(a / b));
}

// Centering force: shift every node so that the mean position sits at (x, y, z).
// The mean is a two-level parallel reduction (workgroup partial sums, then one workgroup
// summing the partials into partial[0]), followed by a per-node shift. Up to 256 partial
// sums (65536 nodes), every workgroup of the shift sums them itself, the same way, which
// spares a dispatch.

var code$7 = PRELUDE + /* wgsl */`
struct P { center: vec3<f32>, strength: f32 }
@group(1) @binding(0) var<uniform> p: P;
@group(1) @binding(1) var<storage, read_write> partial: array<vec4<f32>>;

var<workgroup> sh: array<vec4<f32>, 256>;

fn reduceShared(li: u32) {
  for (var s = 128u; s > 0u; s = s >> 1u) {
    if (li < s) { sh[li] = sh[li] + sh[li + s]; }
    workgroupBarrier();
  }
}

@compute @workgroup_size(256)
fn reduce(@builtin(global_invocation_id) gid: vec3<u32>, @builtin(local_invocation_index) li: u32,
          @builtin(workgroup_id) wid: vec3<u32>) {
  var v = vec4<f32>(0.0);
  if (gid.x < sim.n) { v = vec4<f32>(pos[gid.x].xyz, 0.0); }
  sh[li] = v;
  workgroupBarrier();
  reduceShared(li);
  if (li == 0u) { partial[wid.x] = sh[0]; }
}

@compute @workgroup_size(256)
fn combine(@builtin(local_invocation_index) li: u32) {
  let groups = (sim.n + 255u) / 256u;
  var v = vec4<f32>(0.0);
  for (var g = li; g < groups; g += 256u) { v += partial[g]; }
  sh[li] = v;
  workgroupBarrier();
  reduceShared(li);
  if (li == 0u) { partial[0] = sh[0] / f32(sim.n); }
}

fn shiftBy(i: u32, mean: vec3<f32>) {
  let shift = select(vec3<f32>(0.0), (mean - p.center) * p.strength, activeDims());
  let q = pos[i];
  pos[i] = vec4<f32>(q.xyz - shift, q.w);
}

@compute @workgroup_size(256)
fn apply(@builtin(global_invocation_id) gid: vec3<u32>) {
  let i = gid.x;
  if (i >= sim.n) { return; }
  shiftBy(i, partial[0].xyz);
}

@compute @workgroup_size(256)
fn combineApply(@builtin(global_invocation_id) gid: vec3<u32>, @builtin(local_invocation_index) li: u32) {
  let groups = (sim.n + 255u) / 256u;
  var v = vec4<f32>(0.0);
  if (li < groups) { v += partial[li]; }
  sh[li] = v;
  workgroupBarrier();
  reduceShared(li);
  let mean = sh[0] / f32(sim.n);
  if (gid.x >= sim.n) { return; }
  shiftBy(gid.x, mean.xyz);
}
`;

var spec$2 = ["uniform", "rw"];

var center = {
  type: "center",
  pipelines: {
    centerReduce: {code: code$7, entry: "reduce", spec: spec$2},
    centerCombine: {code: code$7, entry: "combine", spec: spec$2},
    centerApply: {code: code$7, entry: "apply", spec: spec$2},
    centerCombineApply: {code: code$7, entry: "combineApply", spec: spec$2}
  },

  create: function(engine) {
    var device = engine.device,
        uniforms = params$1(device, 16),
        groups = ceilDiv(engine.n, 256),
        partial = device.createBuffer({size: groups * 16, usage: STORAGE}),
        bindGroup = device.createBindGroup({
          layout: engine.pipeline("centerReduce").getBindGroupLayout(1),
          entries: [
            {binding: 0, resource: {buffer: uniforms.buffer}},
            {binding: 1, resource: {buffer: partial}}
          ]
        });

    return {
      update: function(desc) {
        uniforms.set([desc.x, desc.y, desc.z, desc.strength]);
      },

      encode: function(pass) {
        pass.setBindGroup(1, bindGroup);
        pass.setPipeline(engine.pipeline("centerReduce"));
        pass.dispatchWorkgroups(groups);
        if (groups <= 256) {
          pass.setPipeline(engine.pipeline("centerCombineApply"));
        } else {
          pass.setPipeline(engine.pipeline("centerCombine"));
          pass.dispatchWorkgroups(1);
          pass.setPipeline(engine.pipeline("centerApply"));
        }
        pass.dispatchWorkgroups(groups);
      },

      destroy: function() {
        uniforms.destroy();
        partial.destroy();
      }
    };
  }
};

// Stable least-significant-digit radix sort of (key, value) pairs of u32, 8 bits per
// pass. Every pass takes three dispatches:
//
//   count     each tile of TILE pairs counts its digits into hist, digit by digit
//   scan      one workgroup per digit turns its row of hist into running totals over
//             the tiles, and records the digit's total
//   scatter   every pair goes to its position: the start of its digit (from the totals),
//             plus its tile's running total for that digit, plus the number of earlier
//             pairs of the tile with the same digit.
//             That rank comes from a bit mask per digit (one bit per thread), which keeps
//             the sort stable without subgroup operations.
//
// No global atomics: summing the counts with them serialises on 256 addresses, which
// cost more than the rest of the sort for a million keys.
//
// Pairs sit in two buffers used in turn; after an even number of passes the result is
// back in the first one.

var THREADS = 256, PER_THREAD = 4;
var SORT_TILE = THREADS * PER_THREAD;

var code$6 = PRELUDE + /* wgsl */`
struct SortParams { n: u32, tiles: u32, shift: u32, pad: u32 }
@group(1) @binding(0) var<uniform> sp: SortParams;
@group(1) @binding(1) var<storage, read> src: array<vec2<u32>>;
@group(1) @binding(2) var<storage, read_write> dst: array<vec2<u32>>;
@group(1) @binding(3) var<storage, read_write> hist: array<u32>;
@group(1) @binding(4) var<storage, read_write> totals: array<u32, 256>;

const THREADS = ${THREADS}u;
const PER_THREAD = ${PER_THREAD}u;
const TILE = ${THREADS * PER_THREAD}u;

var<workgroup> counts: array<atomic<u32>, 256>;
var<workgroup> sums: array<u32, 256>;
var<workgroup> masks: array<atomic<u32>, 2048>; // digit * 8 + thread / 32
var<workgroup> starts: array<u32, 256>;

fn digitOf(key: u32) -> u32 {
  return (key >> sp.shift) & 255u;
}

@compute @workgroup_size(${THREADS})
fn count(@builtin(workgroup_id) wid: vec3<u32>, @builtin(local_invocation_index) li: u32) {
  atomicStore(&counts[li], 0u);
  workgroupBarrier();
  for (var k = 0u; k < PER_THREAD; k++) {
    let i = wid.x * TILE + k * THREADS + li;
    if (i < sp.n) { atomicAdd(&counts[digitOf(src[i].x)], 1u); }
  }
  workgroupBarrier();
  hist[li * sp.tiles + wid.x] = atomicLoad(&counts[li]);
}

// Inclusive prefix sum of sums[0..256) (Hillis-Steele).
fn scanShared(li: u32) {
  for (var o = 1u; o < 256u; o <<= 1u) {
    var v = 0u;
    if (li >= o) { v = sums[li - o]; }
    workgroupBarrier();
    sums[li] += v;
    workgroupBarrier();
  }
}

@compute @workgroup_size(256)
fn scan(@builtin(workgroup_id) wid: vec3<u32>, @builtin(local_invocation_index) li: u32) {
  let d = wid.x;
  let row = d * sp.tiles;
  var running = 0u;
  for (var base = 0u; base < sp.tiles; base += 256u) {
    let t = base + li;
    var v = 0u;
    if (t < sp.tiles) { v = hist[row + t]; }
    sums[li] = v;
    workgroupBarrier();
    scanShared(li);
    if (t < sp.tiles) { hist[row + t] = running + sums[li] - v; }
    running += sums[255];
    workgroupBarrier();
  }
  if (li == 0u) { totals[d] = running; }
}

@compute @workgroup_size(${THREADS})
fn scatter(@builtin(workgroup_id) wid: vec3<u32>, @builtin(local_invocation_index) li: u32) {
  sums[li] = totals[li];
  workgroupBarrier();
  scanShared(li);
  starts[li] = sums[li] - totals[li] + hist[li * sp.tiles + wid.x];
  let word = li >> 5u;
  let bit = 1u << (li & 31u);

  for (var k = 0u; k < PER_THREAD; k++) {
    for (var w = 0u; w < 8u; w++) { atomicStore(&masks[li * 8u + w], 0u); }
    workgroupBarrier();

    let i = wid.x * TILE + k * THREADS + li;
    let valid = i < sp.n;
    var e = vec2<u32>(0u);
    var d = 0u;
    if (valid) {
      e = src[i];
      d = digitOf(e.x);
      atomicOr(&masks[d * 8u + word], bit);
    }
    workgroupBarrier();

    if (valid) {
      var rank = countOneBits(atomicLoad(&masks[d * 8u + word]) & (bit - 1u));
      for (var w = 0u; w < word; w++) { rank += countOneBits(atomicLoad(&masks[d * 8u + w])); }
      dst[starts[d] + rank] = e;
    }
    workgroupBarrier();

    var c = 0u;
    for (var w = 0u; w < 8u; w++) { c += countOneBits(atomicLoad(&masks[li * 8u + w])); }
    starts[li] += c;
    workgroupBarrier();
  }
}
`;

var spec$1 = ["uniform", "ro", "rw", "rw", "rw"];

var pipelines$2 = {
  sortCount: {code: code$6, entry: "count", spec: spec$1},
  sortScan: {code: code$6, entry: "scan", spec: spec$1},
  sortScatter: {code: code$6, entry: "scatter", spec: spec$1}
};

// A sorter for `n` pairs. The caller fills pairs[0] (vec2<u32> per pair: key, value) and
// encodes the sort: one pass per entry of `shifts`, sorting on the 8-bit digit of the key
// at that bit, least significant first. After p passes the pairs are in pairs[p & 1], so
// the key can be changed between passes (to sort on more than 32 bits).
function createSort(engine, n, shifts) {
  var device = engine.device,
      tiles = Math.max(1, Math.ceil(n / SORT_TILE)),
      slot = Math.max(256, device.limits.minUniformBufferOffsetAlignment | 0),
      make = function(size, usage) { return device.createBuffer({size: Math.max(16, size), usage: usage}); },
      pairs = [make(8 * n, STORAGE | COPY_DST | COPY_SRC), make(8 * n, STORAGE | COPY_SRC)],
      hist = make(1024 * tiles, STORAGE),
      totals = make(1024, STORAGE),
      passes = shifts.length,
      params = make(slot * passes, UNIFORM | COPY_DST),
      data = new Uint32Array(slot * passes / 4),
      groups = [], p;

  for (p = 0; p < passes; ++p) {
    data.set([n, tiles, shifts[p], 0], p * slot / 4);
    groups.push(device.createBindGroup({
      layout: engine.pipeline("sortCount").getBindGroupLayout(1),
      entries: [
        {binding: 0, resource: {buffer: params, offset: p * slot, size: 16}},
        {binding: 1, resource: {buffer: pairs[p & 1]}},
        {binding: 2, resource: {buffer: pairs[1 - (p & 1)]}},
        {binding: 3, resource: {buffer: hist}},
        {binding: 4, resource: {buffer: totals}}
      ]
    }));
  }
  device.queue.writeBuffer(params, 0, data);

  return {
    pairs: pairs,
    // where the sorted pairs end up
    result: pairs[passes & 1],

    // passes [from, to), all of them by default
    encode: function(pass, from, to) {
      for (var p = from || 0, end = to === undefined ? passes : to; p < end; ++p) {
        pass.setBindGroup(1, groups[p]);
        pass.setPipeline(engine.pipeline("sortCount"));
        pass.dispatchWorkgroups(tiles);
        pass.setPipeline(engine.pipeline("sortScan"));
        pass.dispatchWorkgroups(256);
        pass.setPipeline(engine.pipeline("sortScatter"));
        pass.dispatchWorkgroups(tiles);
      }
    },

    destroy: function() {
      pairs.forEach(function(b) { b.destroy(); });
      hist.destroy(), totals.destroy(), params.destroy();
    }
  };
}

// Collision force for large graphs: the same pairs as the all-pairs kernel of collide.js,
// found with a uniform grid. Cells are twice the largest radius wide, so two nodes that
// overlap are in the same or neighbouring cells (3^dims of them). Every step:
//
//   keys    each node's cell, as a slot of a table of (a power of two >= 2n) slots
//   sort    radix sort of (slot, node) pairs: a cell's nodes become a run
//   starts  where each run starts, and the sorted nodes' (position, radius)
//   query   every node looks through the runs of its neighbouring cells
//
// A block of cells takes consecutive slots, its cells in Morton order, from a hashed
// offset: the sort keeps nearby cells together, so neighbouring threads look through
// nearby runs, and cells of different blocks share a slot only by chance (a hash of each
// cell would scatter them; a Morton code cut to the table's bits would put a lattice with
// the right spacing all in one slot). A shared slot only costs distance tests, except that
// the two runs are then one: a node skips a neighbouring cell whose slot it has already
// looked through. The start of a run is not cleared between steps; it is checked against
// the sorted slots.

var WG$1 = 64;

var code$5 = PRELUDE + /* wgsl */`
struct G {
  n: u32,
  mask: u32,     // table slots - 1
  pad0: u32,
  pad1: u32,
  strength: f32,
  inv: f32,      // 1 / cell width
  pad2: f32,
  pad3: f32,
}
@group(1) @binding(0) var<uniform> g: G;
@group(1) @binding(1) var<storage, read> radius: array<f32>;
@group(1) @binding(2) var<storage, read> snap: array<vec4<f32>>;
@group(1) @binding(3) var<storage, read_write> pairs: array<vec2<u32>>;
@group(1) @binding(4) var<storage, read_write> starts: array<u32>;
@group(1) @binding(5) var<storage, read_write> sorted: array<vec4<f32>>;

fn cellOf(p: vec3<f32>) -> vec3<i32> {
  return select(vec3<i32>(0), vec3<i32>(floor(p * g.inv)), activeDims());
}

fn spread3(v: u32) -> u32 { // 10 bits -> every third bit
  var x = v & 0x3ffu;
  x = (x | (x << 16u)) & 0x030000ffu;
  x = (x | (x << 8u)) & 0x0300f00fu;
  x = (x | (x << 4u)) & 0x030c30c3u;
  x = (x | (x << 2u)) & 0x09249249u;
  return x;
}
fn spread2(v: u32) -> u32 { // 16 bits -> every other bit
  var x = v & 0xffffu;
  x = (x | (x << 8u)) & 0x00ff00ffu;
  x = (x | (x << 4u)) & 0x0f0f0f0fu;
  x = (x | (x << 2u)) & 0x33333333u;
  x = (x | (x << 1u)) & 0x55555555u;
  return x;
}

// The cell's slot. A block of 8^3 cells (16^2 in 2D, 256 in 1D) takes consecutive slots,
// its cells in Morton order, from an offset hashed from the block's coordinates (pcg mixes
// the high bits in: without it, blocks whose coordinates are all multiples of 2^k would
// only reach every 2^k-th offset).
fn hash(c: vec3<i32>) -> u32 {
  let q = bitcast<vec3<u32>>(c);
  var block = q >> vec3<u32>(8u, 0u, 0u);
  var m = q.x & 255u;
  if (sim.nDim == 2u) {
    block = q >> vec3<u32>(4u, 4u, 0u);
    m = (spread2(q.y & 15u) << 1u) | spread2(q.x & 15u);
  }
  if (sim.nDim == 3u) {
    block = q >> vec3<u32>(3u);
    m = (spread3(q.z & 7u) << 2u) | (spread3(q.y & 7u) << 1u) | spread3(q.x & 7u);
  }
  return (pcg((block.x * 73856093u) ^ (block.y * 19349663u) ^ (block.z * 83492791u)) + m) & g.mask;
}

@compute @workgroup_size(${WG$1})
fn keys(@builtin(global_invocation_id) gid: vec3<u32>) {
  let i = gid.x;
  if (i >= g.n) { return; }
  pairs[i] = vec2<u32>(hash(cellOf(snap[i].xyz)), i);
}

@compute @workgroup_size(${WG$1})
fn begin(@builtin(global_invocation_id) gid: vec3<u32>) {
  let s = gid.x;
  if (s >= g.n) { return; }
  let e = pairs[s];
  if (s == 0u || pairs[s - 1u].x != e.x) { starts[e.x] = s; }
  sorted[s] = vec4<f32>(snap[e.y].xyz, radius[e.y]);
}

@compute @workgroup_size(${WG$1})
fn query(@builtin(global_invocation_id) gid: vec3<u32>) {
  let s = gid.x;
  if (s >= g.n) { return; }
  let me = sorted[s];
  let i = pairs[s].y;
  let ri2 = me.w * me.w;
  let home = cellOf(me.xyz);
  let reach = vec3<i32>(select(vec3<i32>(0), vec3<i32>(1), activeDims()));
  var seen: array<u32, 27>;
  var count = 0u;
  var acc = vec3<f32>(0.0);

  for (var dz = -reach.z; dz <= reach.z; dz++) {
    for (var dy = -reach.y; dy <= reach.y; dy++) {
      for (var dx = -reach.x; dx <= reach.x; dx++) {
        let key = hash(home + vec3<i32>(dx, dy, dz));
        var again = false;
        for (var k = 0u; k < count; k++) { if (seen[k] == key) { again = true; } }
        if (again) { continue; }
        seen[count] = key;
        count += 1u;

        let start = starts[key];
        if (start >= g.n || pairs[start].x != key || (start > 0u && pairs[start - 1u].x == key)) { continue; }
        for (var t = start; t < g.n && pairs[t].x == key; t++) {
          if (t == s) { continue; }
          let q = sorted[t];
          let rj = q.w;
          let r = me.w + rj;
          var d = me.xyz - q.xyz;
          var l = dot(d, d);
          if (l >= r * r) { continue; }
          if (l == 0.0) {
            d = coincident(i, pairs[t].y);
            l = dot(d, d);
          }
          let len = sqrt(l);
          let rj2 = rj * rj;
          acc += d * ((r - len) / len * g.strength * rj2 / (ri2 + rj2));
        }
      }
    }
  }

  let v = vel[i];
  vel[i] = vec4<f32>(v.xyz + acc, v.w);
}
`;

var spec = ["uniform", "ro", "ro", "rw", "rw", "rw"];

var pipelines$1 = Object.assign({
  gridKeys: {code: code$5, entry: "keys", spec: spec},
  gridBegin: {code: code$5, entry: "begin", spec: spec},
  gridQuery: {code: code$5, entry: "query", spec: spec}
}, pipelines$2);

function createGrid(engine) {
  var device = engine.device,
      n = engine.n,
      bits = Math.max(8, Math.ceil(Math.log2(2 * n))),
      shifts = [],
      make = function(size, usage) { return device.createBuffer({size: Math.max(16, size), usage: usage}); },
      uniforms = make(32, UNIFORM | COPY_DST),
      data = new ArrayBuffer(32),
      sort,
      starts = make(4 << bits, STORAGE),
      sorted = make(16 * n, STORAGE),
      groups = null,
      radii = null,
      last = null;

  for (var b = 0; b < bits; b += 8) shifts.push(b);
  sort = createSort(engine, n, shifts);

  return {
    // radiusBuffer: per-node radii; rmax: the largest
    update: function(desc, radiusBuffer, rmax) {
      var key = [desc.strength, rmax].join();
      if (key !== last) {
        last = key;
        var u32 = new Uint32Array(data), f32 = new Float32Array(data);
        u32[0] = n, u32[1] = (1 << bits) - 1 >>> 0;
        f32[4] = desc.strength, f32[5] = rmax > 0 ? 1 / (2 * rmax) : 0;
        device.queue.writeBuffer(uniforms, 0, data);
      }
      if (radiusBuffer !== radii) {
        radii = radiusBuffer;
        groups = [sort.pairs[0], sort.result].map(function(pairs) {
          return device.createBindGroup({
            layout: engine.pipeline("gridKeys").getBindGroupLayout(1),
            entries: [
              {binding: 0, resource: {buffer: uniforms}},
              {binding: 1, resource: {buffer: radii}},
              {binding: 2, resource: {buffer: engine.snap}},
              {binding: 3, resource: {buffer: pairs}},
              {binding: 4, resource: {buffer: starts}},
              {binding: 5, resource: {buffer: sorted}}
            ]
          });
        });
      }
    },

    // one collision round, after engine.snapshot()
    encode: function(pass) {
      pass.setBindGroup(1, groups[0]);
      pass.setPipeline(engine.pipeline("gridKeys"));
      pass.dispatchWorkgroups(ceilDiv(n, WG$1));
      sort.encode(pass);
      pass.setBindGroup(1, groups[1]);
      pass.setPipeline(engine.pipeline("gridBegin"));
      pass.dispatchWorkgroups(ceilDiv(n, WG$1));
      pass.setPipeline(engine.pipeline("gridQuery"));
      pass.dispatchWorkgroups(ceilDiv(n, WG$1));
    },

    destroy: function() {
      sort.destroy();
      [uniforms, starts, sorted].forEach(function(b) { b.destroy(); });
    }
  };
}

// Collision force. collide.js visits every pair once and pushes both nodes apart; here
// every node computes its own share of every overlapping pair instead, which gives the
// same numbers without any node writing to another node's velocity:
//
//     d = pi - pj   (positions are pos + vel, from the snapshot)
//     if |d| < ri + rj:  vi += d * (ri + rj - |d|) / |d| * strength * rj^2 / (ri^2 + rj^2)
//
// The CPU force resolves pairs one after another, so a node already pushed by an earlier
// pair is seen at its new position by later ones; here all pairs are resolved against the
// same snapshot. Use `iterations` to tighten dense packings, as with the CPU force.
//
// As in manyBody.js, SPLIT threads share each node's loop over the sources. Large graphs
// find the same pairs with a grid instead (collideGrid.js).

var TILE$1 = 128;

// From this many nodes, the grid's few dispatches beat testing every pair.
var GRID_MIN = 8192;

var code$4 = PRELUDE + /* wgsl */`
struct P { strength: f32, pad0: f32, pad1: f32, pad2: f32 }
@group(1) @binding(0) var<uniform> p: P;
@group(1) @binding(1) var<storage, read> radius: array<f32>;
@group(1) @binding(2) var<storage, read> snap: array<vec4<f32>>;

override SPLIT: u32 = 1u;
var<workgroup> tile: array<vec4<f32>, ${TILE$1}>;
var<workgroup> partial: array<vec4<f32>, ${TILE$1}>;

@compute @workgroup_size(${TILE$1})
fn main(@builtin(workgroup_id) wid: vec3<u32>, @builtin(local_invocation_index) li: u32) {
  let n = sim.n;
  let lane = li % SPLIT;
  let i = wid.x * (${TILE$1}u / SPLIT) + li / SPLIT;
  let live = i < n;
  var me = vec3<f32>(0.0);
  var ri = 0.0;
  if (live) { me = snap[i].xyz; ri = radius[i]; }
  let ri2 = ri * ri;
  var acc = vec3<f32>(0.0);

  for (var base = 0u; base < n; base += ${TILE$1}u) {
    let j = base + li;
    var t = vec4<f32>(0.0);
    if (j < n) { t = vec4<f32>(snap[j].xyz, radius[j]); }
    tile[li] = t;
    workgroupBarrier();

    let count = min(${TILE$1}u, n - base);
    for (var k = lane; k < count; k += SPLIT) {
      let q = tile[k];
      let rj = q.w;
      let r = ri + rj;
      var d = me - q.xyz;
      var l = dot(d, d);
      if (l >= r * r || base + k == i) { continue; }
      if (l == 0.0) {
        d = coincident(i, base + k);
        l = dot(d, d);
      }
      let s = sqrt(l);
      let rj2 = rj * rj;
      acc += d * ((r - s) / s * p.strength * rj2 / (ri2 + rj2));
    }
    workgroupBarrier();
  }

  partial[li] = vec4<f32>(acc, 0.0);
  workgroupBarrier();
  if (lane == 0u && live) {
    var total = vec3<f32>(0.0);
    for (var s = 0u; s < SPLIT; s++) { total += partial[li + s].xyz; }
    let v = vel[i];
    vel[i] = vec4<f32>(v.xyz + total, v.w);
  }
}
`;

var collide = {
  type: "collide",
  pipelines: Object.assign({collide: {code: code$4, entry: "main", spec: ["uniform", "ro", "ro"], specialised: true}}, pipelines$1),

  variants: function(n, nDim, split) {
    return [["collide", {SPLIT: splitFor(n, split)}]];
  },

  create: function(engine) {
    var device = engine.device,
        split = {SPLIT: splitFor(engine.n, engine.forcedSplit)},
        uniforms = params$1(device, 16),
        radii = null,
        bindGroup = null,
        version = -1,
        iterations = 1,
        rmax = 0,
        grid = engine.n >= GRID_MIN ? createGrid(engine) : null;

    return {
      update: function(desc) {
        if (desc.version !== version) {
          version = desc.version;
          if (radii) radii.destroy();
          var values = floats(desc.radii, engine.n);
          radii = storage(device, values);
          rmax = 0;
          for (var i = 0; i < values.length; ++i) if (values[i] > rmax) rmax = values[i];
          bindGroup = null;
        }
        iterations = rmax > 0 ? desc.iterations : 0; // nothing can collide
        if (grid) return grid.update(desc, radii, rmax);
        uniforms.set([desc.strength]);
        if (!bindGroup) bindGroup = device.createBindGroup({
          layout: engine.pipeline("collide", split).getBindGroupLayout(1),
          entries: [
            {binding: 0, resource: {buffer: uniforms.buffer}},
            {binding: 1, resource: {buffer: radii}},
            {binding: 2, resource: {buffer: engine.snap}}
          ]
        });
      },

      encode: function(pass) {
        for (var k = 0; k < iterations; ++k) {
          engine.snapshot(pass);
          if (grid) { grid.encode(pass); continue; }
          pass.setPipeline(engine.pipeline("collide", split));
          pass.setBindGroup(1, bindGroup);
          pass.dispatchWorkgroups(ceilDiv(engine.n * split.SPLIT, TILE$1));
        }
      },

      destroy: function() {
        uniforms.destroy();
        if (radii) radii.destroy();
        if (grid) grid.destroy();
      }
    };
  }
};

// Link force. link.js walks the links in order and adds to both endpoints, which cannot
// be parallelised over links without write conflicts. Instead every node gathers the
// contribution of each link it belongs to, using a CSR adjacency list built on the CPU
// whenever the links change:
//
//     d = (target + v) - (source + v)      (from the snapshot)
//     d *= (|d| - distance) / |d| * alpha * strength
//     target -= d * bias        source += d * (1 - bias)
//
// The CPU force applies links sequentially, so later links already see the velocities
// changed by earlier ones; here they all see the same snapshot. `iterations` refines it.

var code$3 = PRELUDE + /* wgsl */`
struct Link { src: u32, dst: u32, distance: f32, strength: f32, bias: f32 }

@group(1) @binding(0) var<storage, read> links: array<Link>;
@group(1) @binding(1) var<storage, read> adjacency: array<u32>;   // link * 2 + (node is target)
@group(1) @binding(2) var<storage, read> rowStart: array<u32>;    // n + 1 offsets into adjacency
@group(1) @binding(3) var<storage, read> snap: array<vec4<f32>>;

@compute @workgroup_size(${WORKGROUP})
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  let i = gid.x;
  if (i >= sim.n) { return; }
  var acc = vec3<f32>(0.0);

  for (var e = rowStart[i]; e < rowStart[i + 1u]; e++) {
    let code = adjacency[e];
    let id = code >> 1u;
    let link = links[id];
    var d = snap[link.dst].xyz - snap[link.src].xyz;
    if (d.x == 0.0) { d.x = jiggle(id, 0u, 0u); }
    if (sim.nDim > 1u && d.y == 0.0) { d.y = jiggle(id, 0u, 1u); }
    if (sim.nDim > 2u && d.z == 0.0) { d.z = jiggle(id, 0u, 2u); }
    let l = length(d);
    d *= (l - link.distance) / l * sim.alpha * link.strength;
    if ((code & 1u) == 1u) { acc -= d * link.bias; } else { acc += d * (1.0 - link.bias); }
  }

  let v = vel[i];
  vel[i] = vec4<f32>(v.xyz + acc, v.w);
}
`;

var link = {
  type: "link",
  pipelines: {link: {code: code$3, entry: "main", spec: ["ro", "ro", "ro", "ro"]}},

  create: function(engine) {
    var device = engine.device,
        buffers = [],
        bindGroup = null,
        version = -1,
        iterations = 1;

    function release() {
      buffers.forEach(function(b) { b.destroy(); });
      buffers = [];
    }

    function build(desc) {
      var n = engine.n,
          links = desc.links,
          m = links.length,
          packed = new ArrayBuffer(Math.max(1, m) * 20),
          u32 = new Uint32Array(packed),
          f32 = new Float32Array(packed),
          rowStart = new Uint32Array(n + 1),
          adjacency = new Uint32Array(Math.max(1, 2 * m)),
          i, k, s, t;

      for (k = 0; k < m; ++k) {
        s = links[k].source.index, t = links[k].target.index;
        u32[5 * k] = s;
        u32[5 * k + 1] = t;
        f32[5 * k + 2] = +desc.distances[k];
        f32[5 * k + 3] = +desc.strengths[k];
        f32[5 * k + 4] = +desc.bias[k];
        ++rowStart[s + 1], ++rowStart[t + 1];
      }
      for (i = 0; i < n; ++i) rowStart[i + 1] += rowStart[i];

      var cursor = rowStart.slice(0, n);
      for (k = 0; k < m; ++k) {
        adjacency[cursor[links[k].source.index]++] = 2 * k;
        adjacency[cursor[links[k].target.index]++] = 2 * k + 1;
      }

      release();
      buffers = [storage(device, packed), storage(device, adjacency), storage(device, rowStart)];
      bindGroup = device.createBindGroup({
        layout: engine.pipeline("link").getBindGroupLayout(1),
        entries: buffers.concat([engine.snap]).map(function(buffer, binding) {
          return {binding: binding, resource: {buffer: buffer}};
        })
      });
    }

    return {
      update: function(desc) {
        if (desc.version !== version) version = desc.version, build(desc);
        iterations = desc.links.length ? desc.iterations : 0;
      },

      encode: function(pass) {
        for (var k = 0; k < iterations; ++k) {
          engine.snapshot(pass);
          pass.setPipeline(engine.pipeline("link"));
          pass.setBindGroup(1, bindGroup);
          pass.dispatchWorkgroups(ceilDiv(engine.n, WORKGROUP));
        }
      },

      destroy: release
    };
  }
};

// Many-body force with a Barnes-Hut tree that reproduces the one manyBody.js builds with
// d3-octree (d3-quadtree in 2D, d3-binarytree in 1D), so the GPU gives the same forces as
// the CPU, theta and all.
//
// The d3 tree: a cube [x0, x0 + size) where x0 = floor(min) and size is the smallest power
// of two that covers the max; cells split at their midpoints until every leaf holds one
// point (or coincident points). A cell is approximated when w * w / theta2 < l (w: its
// width, l: squared distance to its centre of mass), and its strength is the sum of its
// children's, times sqrt(4 / numChildren) (0.71 in 3D, 1 in 2D, 1.41 in 1D). A point's
// strength therefore reaches a cell k levels above its leaf scaled by c^k:
//
//     value(cell at depth D) = sum over its points of strength * c^(leafDepth - D)
//
// and the cell's centre of mass weighs each point by |strength| * c^leafDepth.
//
// On the GPU (every step, in this order):
//
//   bbox      bounding box of the positions (atomics on order-preserving float bits)
//   cube      the d3 cube, from the box (one thread; also empties the box for the next step).
//             Up to BOX_MAX nodes, bboxCube does both in one workgroup.
//   keys      every node's Morton code in the cube: LEVELS levels, in two u32 halves
//   sort      radix sort of (code, node) pairs on the high halves only; then fix orders
//             the runs of points sharing a high half on their low halves (longRuns, a
//             workgroup each, the rare runs of more than RUN points). Up to RANK_MAX
//             nodes, rank sorts them on the whole codes in one step instead.
//   prep      per sorted point: its leaf depth (from the codes around it), and the bottom
//             of a pyramid of partial sums over runs of sorted points: strength * c^depth,
//             its absolute value w, and w * (position - p0), p0 being the run's first
//             point (sums about a nearby point keep f32 precision far from the origin)
//   up        the higher levels of the pyramid
//   build     a binary radix tree over the codes (Karras 2012). Each of its nodes stands
//             for the d3 cells between its parent's split and its own, which all hold the
//             same points; their sums come from the pyramid.
//   collapse  every node lists its children in the d3 tree: the first nodes or points
//             below it that are in deeper cells (up to 2^DIMS of them, at most DIMS levels
//             down the binary tree). The binary nodes in between are never visited.
//   walk      for every point, the d3 traversal: a node is tested at each of the cell
//             depths it stands for, and approximated at the first one that passes.
//
// Points closer than the smallest cell (size / 2^LEVELS, 20 levels in 3D and 24 in 1D and
// 2D) share a leaf cell here, where d3 would split further; they still interact exactly
// with each other.

var WG = 64, REDUCE = 512, RUN = 32, LEVELS = [0, 24, 24, 20];

// Up to RANK_MAX nodes, a one-step rank sort (n^2 comparisons) beats the radix sort's
// dispatches; up to SHARED_MAX nodes, walking the tree with 8 threads per node beats one;
// up to BOX_MAX nodes, one workgroup finds the box faster than a dispatch more.
var RANK_MAX = 8192, SHARED_MAX = 32768, BOX_MAX = 16384;

// Digits of the high half of the codes that the sort has to look at (the code sits in its
// high bits: 12, 24 or 30 bits in 1D, 2D and 3D).
var SHIFTS = [null, [16, 24], [8, 16, 24], [0, 8, 16, 24]];

var params = /* wgsl */`
override DIMS: u32 = 3u;
override LEVELS: u32 = 20u;
override LOGC: f32 = -0.5; // log2(sqrt(4 / 2^DIMS))
override HALF: u32 = 30u;  // bits of code in each half: LEVELS / 2 * DIMS

struct BH {
  n: u32,
  base: u32,    // up: the pyramid level it starts from
  inner: u32,   // nodes of the binary tree: max(1, n - 1)
  total: u32,   // pyramid entries
  theta2: f32,
  distanceMin2: f32,
  distanceMax2: f32,
  pad1: f32,
  offs: array<vec4<u32>, 8>, // where each pyramid level starts
}

@group(1) @binding(0) var<uniform> bh: BH;

fn levelStart(k: u32) -> u32 { return bh.offs[k >> 2u][k & 3u]; }
fn levelCount(k: u32) -> u32 { return (bh.n + (1u << k) - 1u) >> k; }

// c^k, c = sqrt(4 / 2^DIMS): a power of sqrt(2), so exact up to the rounding of sqrt(2)
fn damp(k: i32) -> f32 {
  if (LOGC == 0.0) { return 1.0; }
  let half = k >> 1u;
  let odd = select(1.0, exp2(LOGC), (k & 1) != 0);
  return ldexp(odd, select(-half, half, LOGC > 0.0));
}

// Depth of the d3 cell that splits between codes sharing prefix bits.
fn depthOf(prefix: i32) -> u32 {
  return min(u32(prefix) / DIMS, LEVELS);
}
`;

// For the kernels that use the sorted points (layouts A and B).
var lookups = /* wgsl */`
// Pyramid entry k: its weighted sums (four f32 from 4k) and its strength (at 4 total + k).
// Plain f32s, so that threads writing neighbouring entries never share a vector.
fn pyrW(k: u32) -> vec4<f32> { return vec4<f32>(pyr[4u * k], pyr[4u * k + 1u], pyr[4u * k + 2u], pyr[4u * k + 3u]); }
fn pyrA(k: u32) -> f32 { return pyr[4u * bh.total + k]; }

// Length of the common prefix of the codes at sorted positions a and b (-1 if b is out of
// range). The sorted pairs hold the high halves; the low halves are looked up only when
// those are equal. Equal codes are told apart by their position, as in Karras's
// construction.
fn delta(a: u32, b: i32) -> i32 {
  if (b < 0 || b >= i32(bh.n)) { return -1; }
  let pa = pairs[a];
  let pb = pairs[u32(b)];
  if (pa.x != pb.x) { return i32(countLeadingZeros(pa.x ^ pb.x)); }
  let la = codes[pa.y].y;
  let lb = codes[pb.y].y;
  if (la != lb) { return i32(HALF + countLeadingZeros(la ^ lb)); }
  return 64 + i32(countLeadingZeros(a ^ u32(b)));
}
`;

// Kernels that build the pyramid (bind group layout A).
var build = PRELUDE + /* wgsl */`
// box: min x, y, z, -, max x, y, z, -. cube: origin, size. shift: log2(2^LEVELS / size)
struct Misc { box: array<atomic<u32>, 8>, cube: vec4<f32>, shift: i32, pad0: i32, pad1: i32, pad2: i32 }
@group(1) @binding(1) var<storage, read_write> misc: Misc;
@group(1) @binding(2) var<storage, read_write> pairs: array<vec2<u32>>;
@group(1) @binding(3) var<storage, read> strength: array<f32>;
@group(1) @binding(4) var<storage, read_write> leaves: array<vec4<f32>>;
@group(1) @binding(5) var<storage, read_write> pyr: array<f32>;
@group(1) @binding(6) var<storage, read_write> codes: array<vec2<u32>>; // by node: high, low half
` + params + lookups + /* wgsl */`
// Floats as u32 with the same order, for atomicMin / atomicMax.
fn ordered(f: f32) -> u32 {
  let b = bitcast<u32>(f);
  return select(b | 0x80000000u, ~b, (b & 0x80000000u) != 0u);
}
fn unordered(u: u32) -> f32 {
  return bitcast<f32>(select(~u, u & 0x7fffffffu, (u & 0x80000000u) != 0u));
}

var<workgroup> boxLo: array<vec3<f32>, 256>;
var<workgroup> boxHi: array<vec3<f32>, 256>;

@compute @workgroup_size(256)
fn bbox(@builtin(global_invocation_id) gid: vec3<u32>, @builtin(local_invocation_index) li: u32,
        @builtin(num_workgroups) groups: vec3<u32>) {
  var lo = vec3<f32>(3.4e38);
  var hi = vec3<f32>(-3.4e38);
  for (var i = gid.x; i < bh.n; i += groups.x * 256u) {
    let p = pos[i].xyz;
    lo = min(lo, p);
    hi = max(hi, p);
  }
  boxLo[li] = lo;
  boxHi[li] = hi;
  workgroupBarrier();
  for (var s = 128u; s > 0u; s >>= 1u) {
    if (li < s) {
      boxLo[li] = min(boxLo[li], boxLo[li + s]);
      boxHi[li] = max(boxHi[li], boxHi[li + s]);
    }
    workgroupBarrier();
  }
  if (li < 3u) {
    atomicMin(&misc.box[li], ordered(boxLo[0][li]));
    atomicMax(&misc.box[4u + li], ordered(boxHi[0][li]));
  }
}

// d3's cover(): x0 = floor(min), then double the (cubic) extent until it holds the max.
fn cover(lo: vec3<f32>, hi: vec3<f32>) {
  let origin = floor(lo);
  var size = 1.0;
  var log2size = 0;
  for (var k = 0u; k < 128u; k++) {
    if (all(hi < origin + size)) { break; }
    size *= 2.0;
    log2size += 1;
  }
  misc.cube = vec4<f32>(origin, size);
  misc.shift = i32(LEVELS) - log2size;
}

// A single thread: every thread reading the box atomically would queue on six addresses.
@compute @workgroup_size(1)
fn cube() {
  cover(vec3<f32>(unordered(atomicLoad(&misc.box[0])), unordered(atomicLoad(&misc.box[1])), unordered(atomicLoad(&misc.box[2]))),
        vec3<f32>(unordered(atomicLoad(&misc.box[4])), unordered(atomicLoad(&misc.box[5])), unordered(atomicLoad(&misc.box[6]))));
  for (var j = 0u; j < 8u; j++) { atomicStore(&misc.box[j], select(0u, 0xffffffffu, j < 4u)); }
}

@compute @workgroup_size(256)
fn bboxCube(@builtin(local_invocation_index) li: u32) {
  var lo = vec3<f32>(3.4e38);
  var hi = vec3<f32>(-3.4e38);
  for (var i = li; i < bh.n; i += 256u) {
    let p = pos[i].xyz;
    lo = min(lo, p);
    hi = max(hi, p);
  }
  boxLo[li] = lo;
  boxHi[li] = hi;
  workgroupBarrier();
  for (var s = 128u; s > 0u; s >>= 1u) {
    if (li < s) {
      boxLo[li] = min(boxLo[li], boxLo[li + s]);
      boxHi[li] = max(boxHi[li], boxHi[li + s]);
    }
    workgroupBarrier();
  }
  if (li == 0u) { cover(boxLo[0], boxHi[0]); }
}

var<workgroup> redW: array<vec4<f32>, 256>;
var<workgroup> redA: array<f32, 256>;
var<workgroup> redP: array<vec3<f32>, 256>; // first point of each run

// Two consecutive runs as one: (sum of w * (p - p0), sum of w) about the first run's p0.
fn join(w1: vec4<f32>, w2: vec4<f32>, p1: vec3<f32>, p2: vec3<f32>) -> vec4<f32> {
  if (w2.w == 0.0) { return w1; }
  return vec4<f32>(w1.xyz + w2.xyz + w2.w * (p2 - p1), w1.w + w2.w);
}

fn setPyr(k: u32, w: vec4<f32>, a: f32) {
  pyr[4u * k] = w.x;
  pyr[4u * k + 1u] = w.y;
  pyr[4u * k + 2u] = w.z;
  pyr[4u * k + 3u] = w.w;
  pyr[4u * bh.total + k] = a;
}

// This thread holds block (wid * 256 + li) of pyramid level base + 1, whose first point is
// p; write it, and the blocks of the eight levels above it that this workgroup completes.
fn reduceUp(base: u32, wid: u32, li: u32, w: vec4<f32>, a: f32, p: vec3<f32>) {
  var b = wid * 256u + li;
  if (b < levelCount(base + 1u)) { setPyr(levelStart(base + 1u) + b, w, a); }
  redW[li] = w;
  redA[li] = a;
  redP[li] = p;
  workgroupBarrier();
  for (var k = 1u; k <= 8u; k++) {
    let half = 1u << (k - 1u);
    if ((li & ((half << 1u) - 1u)) == 0u) {
      let sw = join(redW[li], redW[li + half], redP[li], redP[li + half]);
      let sa = redA[li] + redA[li + half];
      redW[li] = sw;
      redA[li] = sa;
      let level = base + 1u + k;
      b = (wid * 256u + li) >> k;
      if (b < levelCount(level)) { setPyr(levelStart(level) + b, sw, sa); }
    }
    workgroupBarrier();
  }
}

@compute @workgroup_size(256)
fn prep(@builtin(workgroup_id) wid: vec3<u32>, @builtin(local_invocation_index) li: u32) {
  var w = vec4<f32>(0.0);
  var a = 0.0;
  var first = vec3<f32>(0.0);
  for (var h = 0u; h < 2u; h++) {
    let l = wid.x * ${REDUCE}u + li * 2u + h;
    if (l < bh.n) {
      let node = pairs[l].y;
      let p = pos[node].xyz;
      let s = strength[node];
      leaves[l] = vec4<f32>(p, s);
      var depth = 0u; // a lone point is the root
      if (bh.n > 1u) { depth = depthOf(max(delta(l, i32(l) - 1), delta(l, i32(l) + 1))) + 1u; }
      let al = s * damp(i32(depth));
      let e = vec4<f32>(0.0, 0.0, 0.0, abs(al));
      setPyr(l, e, al);
      if (h == 0u) { first = p; w = e; } else { w = join(w, e, first, p); }
      a += al;
    }
  }
  reduceUp(0u, wid.x, li, w, a, first);
}

@compute @workgroup_size(256)
fn up(@builtin(workgroup_id) wid: vec3<u32>, @builtin(local_invocation_index) li: u32) {
  let base = bh.base;
  let count = levelCount(base);
  let start = levelStart(base);
  var w = vec4<f32>(0.0);
  var a = 0.0;
  var first = vec3<f32>(0.0);
  for (var h = 0u; h < 2u; h++) {
    let e = wid.x * ${REDUCE}u + li * 2u + h;
    if (e < count) {
      let p = leaves[e << base].xyz;
      if (h == 0u) { first = p; w = pyrW(start + e); } else { w = join(w, pyrW(start + e), first, p); }
      a += pyrA(start + e);
    }
  }
  reduceUp(base, wid.x, li, w, a, first);
}
`;

// Kernels that put the points in the order of their codes (bind group layout S; longRuns:
// S2, the same with the run list read-only). The radix sort sorts on the high halves
// only, which leaves the points of a cell LEVELS / 2 levels down (runs sharing a high
// half) in node order. Sorting each run on the low halves then gives exactly the order of
// a sort on the whole codes. Runs are short: in one of up to RUN points, every point finds
// its place by comparing its low half with the others' (fix); a longer run is listed for
// longRuns, which sorts it with a workgroup.
var order = PRELUDE + /* wgsl */`
struct Misc { box: array<atomic<u32>, 8>, cube: vec4<f32>, shift: i32, pad0: i32, pad1: i32, pad2: i32 }
@group(1) @binding(1) var<storage, read_write> misc: Misc;
@group(1) @binding(2) var<storage, read_write> src: array<vec2<u32>>;    // keys: the sort's input; fix: its output; longRuns: scratch
@group(1) @binding(3) var<storage, read_write> dst: array<vec2<u32>>;    // (high half, node), in the final order
@group(1) @binding(4) var<storage, read_write> codes: array<vec2<u32>>;  // by node: high, low half
@group(1) @binding(5) var<storage, read_write> runs: array<atomic<u32>>; // longRuns' dispatch (x, y, z), then the long runs' starts from 4
@group(1) @binding(5) var<storage, read> runList: array<u32>;            // the same, for longRuns, which is dispatched from it
` + params + /* wgsl */`
const RUN = ${RUN}u;

fn spread3(v: u32) -> u32 { // 10 bits -> every third bit
  var x = v & 0x3ffu;
  x = (x | (x << 16u)) & 0x030000ffu;
  x = (x | (x << 8u)) & 0x0300f00fu;
  x = (x | (x << 4u)) & 0x030c30c3u;
  x = (x | (x << 2u)) & 0x09249249u;
  return x;
}
fn spread2(v: u32) -> u32 { // 16 bits -> every other bit
  var x = v & 0xffffu;
  x = (x | (x << 8u)) & 0x00ff00ffu;
  x = (x | (x << 4u)) & 0x0f0f0f0fu;
  x = (x | (x << 2u)) & 0x33333333u;
  x = (x | (x << 1u)) & 0x55555555u;
  return x;
}

// Morton code of LEVELS / 2 levels, most significant bits first, with d3's child order
// (z, y, x) at each level, in the high bits of a u32.
fn morton(q: vec3<u32>) -> u32 {
  var m = q.x;
  if (DIMS == 3u) { m = (spread3(q.z) << 2u) | (spread3(q.y) << 1u) | spread3(q.x); }
  if (DIMS == 2u) { m = (spread2(q.y) << 1u) | spread2(q.x); }
  return m << (32u - HALF);
}

// floor((x - origin) / cell) for a cell of 2^-k, without rounding: x - origin is not exact
// in f32, and a point put on the wrong side of a cell boundary lands at another depth,
// which changes its weight in every cell above it. origin is an integer, so either
// origin * 2^k is one (k >= 0) or floor(x) - origin is exact and 2^-k a whole number.
fn cellOf(x: f32, origin: f32, k: i32) -> f32 {
  if (k >= 0) { return floor(ldexp(x, k)) - ldexp(origin, k); }
  return floor(ldexp(floor(x) - origin, k));
}

@compute @workgroup_size(${WG})
fn keys(@builtin(global_invocation_id) gid: vec3<u32>) {
  let i = gid.x;
  if (i == 0u) { atomicStore(&runs[0], 0u); } // no long run listed yet this step
  if (i >= bh.n) { return; }
  let origin = misc.cube.xyz;
  let k = misc.shift;
  let p = pos[i].xyz;
  let top = f32((1u << LEVELS) - 1u);
  let q = vec3<u32>(clamp(vec3<f32>(cellOf(p.x, origin.x, k), cellOf(p.y, origin.y, k), cellOf(p.z, origin.z, k)), vec3<f32>(0.0), vec3<f32>(top)));
  let half = LEVELS / 2u;
  let code = vec2<u32>(morton(q >> vec3<u32>(half)), morton(q & vec3<u32>((1u << half) - 1u)));
  codes[i] = code;
  src[i] = vec2<u32>(code.x, i);
}

// For small graphs, a sort in one step: every code's rank is the number of codes before it
// (smaller, or equal with a smaller node index: the radix sort's order). RANK_SPLIT threads
// share each code's count.
override RANK_SPLIT: u32 = 8u;
var<workgroup> rankTile: array<vec2<u32>, 256>;
var<workgroup> rankPart: array<u32, 256>;

@compute @workgroup_size(256)
fn rank(@builtin(workgroup_id) wid: vec3<u32>, @builtin(local_invocation_index) li: u32) {
  let lane = li % RANK_SPLIT;
  let i = wid.x * (256u / RANK_SPLIT) + li / RANK_SPLIT;
  let live = i < bh.n;
  var mine = vec2<u32>(0u);
  if (live) { mine = codes[i]; }
  var r = 0u;
  for (var base = 0u; base < bh.n; base += 256u) {
    if (base + li < bh.n) { rankTile[li] = codes[base + li]; }
    workgroupBarrier();
    let count = min(256u, bh.n - base);
    for (var k = lane; k < count; k += RANK_SPLIT) {
      let c = rankTile[k];
      if (c.x < mine.x || (c.x == mine.x && (c.y < mine.y || (c.y == mine.y && base + k < i)))) { r += 1u; }
    }
    workgroupBarrier();
  }
  rankPart[li] = r;
  workgroupBarrier();
  if (lane == 0u && live) {
    for (var s = 1u; s < RANK_SPLIT; s++) { r += rankPart[li + s]; }
    dst[r] = vec2<u32>(mine.x, i);
  }
}

@compute @workgroup_size(${WG})
fn fix(@builtin(global_invocation_id) gid: vec3<u32>) {
  let s = gid.x;
  if (s >= bh.n) { return; }
  let e = src[s];
  // this point's run of equal high halves, looked for no further than RUN points either way
  var first = s;
  loop {
    if (first == 0u || s - first > RUN || src[first - 1u].x != e.x) { break; }
    first -= 1u;
  }
  var last = s;
  loop {
    if (last + 1u >= bh.n || last - s > RUN || src[last + 1u].x != e.x) { break; }
    last += 1u;
  }
  if (first == last) { // alone in its cell: already in place
    dst[s] = e;
    return;
  }
  if (last - first + 1u > RUN) { // a long run: copied as it is, for longRuns
    dst[s] = e;
    if (first == s) {
      let k = atomicAdd(&runs[0], 1u);
      atomicStore(&runs[4u + k], s);
    }
    return;
  }
  // this point's place in its run: after the points with a smaller low half, and, the run
  // being in node order, after those with the same one that come before it
  let lo = codes[e.y].y;
  var place = 0u;
  for (var j = first; j <= last; j++) {
    let other = codes[src[j].y].y;
    if (other < lo || (other == lo && j < s)) { place += 1u; }
  }
  dst[first + place] = e;
}

var<workgroup> lrCount: array<atomic<u32>, 256>;
var<workgroup> lrSums: array<u32, 256>;
var<workgroup> lrMasks: array<atomic<u32>, 2048>; // digit * 8 + thread / 32
var<workgroup> lrStarts: array<u32, 256>;
var<workgroup> lrEnd: atomic<u32>;
var<workgroup> lrEndAt: u32;

// Inclusive prefix sum of lrSums (Hillis-Steele).
fn lrScan(li: u32) {
  for (var o = 1u; o < 256u; o <<= 1u) {
    var v = 0u;
    if (li >= o) { v = lrSums[li - o]; }
    workgroupBarrier();
    lrSums[li] += v;
    workgroupBarrier();
  }
}

fn lrRead(i: u32, even: bool) -> vec2<u32> {
  if (even) { return dst[i]; }
  return src[i];
}

fn lrWrite(i: u32, even: bool, e: vec2<u32>) {
  if (even) { src[i] = e; } else { dst[i] = e; }
}

// One long run per workgroup: a stable radix sort of dst[first, end) on the low halves,
// 8 bits per pass, through src and back.
@compute @workgroup_size(256)
fn longRuns(@builtin(workgroup_id) wid: vec3<u32>, @builtin(local_invocation_index) li: u32) {
  let first = runList[4u + wid.x];
  let high = dst[first].x;
  if (li == 0u) { atomicStore(&lrEnd, bh.n); }
  workgroupBarrier();
  var end = bh.n;
  for (var base = first; base < bh.n; base += 256u) {
    let s = base + li;
    if (s < bh.n && dst[s].x != high) { atomicMin(&lrEnd, s); }
    workgroupBarrier();
    if (li == 0u) { lrEndAt = atomicLoad(&lrEnd); }
    end = workgroupUniformLoad(&lrEndAt);
    if (end < bh.n) { break; }
  }
  let count = end - first;
  let word = li >> 5u;
  let bit = 1u << (li & 31u);

  for (var sweep = 0u; sweep < 4u; sweep++) {
    let shift = 8u * sweep;
    let even = (sweep & 1u) == 0u;
    atomicStore(&lrCount[li], 0u);
    workgroupBarrier();
    for (var i = li; i < count; i += 256u) {
      let e = lrRead(first + i, even);
      atomicAdd(&lrCount[(codes[e.y].y >> shift) & 255u], 1u);
    }
    workgroupBarrier();
    lrSums[li] = atomicLoad(&lrCount[li]);
    workgroupBarrier();
    lrScan(li);
    lrStarts[li] = lrSums[li] - atomicLoad(&lrCount[li]);
    workgroupBarrier();

    for (var b = 0u; b < count; b += 256u) {
      for (var w = 0u; w < 8u; w++) { atomicStore(&lrMasks[li * 8u + w], 0u); }
      workgroupBarrier();
      let i = b + li;
      let valid = i < count;
      var e = vec2<u32>(0u);
      var d = 0u;
      if (valid) {
        e = lrRead(first + i, even);
        d = (codes[e.y].y >> shift) & 255u;
        atomicOr(&lrMasks[d * 8u + word], bit);
      }
      workgroupBarrier();
      if (valid) {
        var rank = countOneBits(atomicLoad(&lrMasks[d * 8u + word]) & (bit - 1u));
        for (var w = 0u; w < word; w++) { rank += countOneBits(atomicLoad(&lrMasks[d * 8u + w])); }
        lrWrite(first + lrStarts[d] + rank, even, e);
      }
      workgroupBarrier();
      var c = 0u;
      for (var w = 0u; w < 8u; w++) { c += countOneBits(atomicLoad(&lrMasks[li * 8u + w])); }
      lrStarts[li] += c;
      workgroupBarrier();
    }
    storageBarrier(); // this sweep's writes, for the next sweep's reads
  }
}
`;

// Kernels that build and walk the tree (bind group layout B). The tree lives in one buffer
// of vec4<u32>, in three parts (inner = n - 1 nodes):
//
//   cells  [0, inner)             centre of mass and strength, as f32 bits
//   links  [inner, 2 inner)       the binary tree: left, right, and the node's depth
//   kids   [2 inner, 4 inner)     two entries per node: its children in the d3 tree
//
// A child reference holds a node or sorted position (INDEX bits), LEAF for a point, and,
// in kids, a node's depth from bit 24. Unused kids are NONE. The walk reads 16 bytes per
// child it visits.
var walk = PRELUDE + /* wgsl */`
struct Misc { box: array<u32, 8>, cube: vec4<f32>, shift: i32, pad0: i32, pad1: i32, pad2: i32 }
@group(1) @binding(1) var<storage, read_write> misc: Misc;
@group(1) @binding(2) var<storage, read_write> pairs: array<vec2<u32>>;
@group(1) @binding(3) var<storage, read_write> leaves: array<vec4<f32>>;
@group(1) @binding(4) var<storage, read_write> pyr: array<f32>;
@group(1) @binding(5) var<storage, read_write> tree: array<vec4<u32>>;
@group(1) @binding(6) var<storage, read_write> codes: array<vec2<u32>>;
` + params + lookups + /* wgsl */`
const INDEX = 0x7fffffu;
const LEAF = 0x800000u;
const NONE = 0xffffffffu;
const STACK = 128u;

struct Sum { w: vec4<f32>, a: f32 }

// Sum of the pyramid over sorted positions [first, last], about the first point (p0).
fn rangeSum(first: u32, last: u32, p0: vec3<f32>) -> Sum {
  var s = Sum(vec4<f32>(0.0), 0.0);
  var lo = first;
  var hi = last + 1u;
  var k = 0u;
  loop {
    if (lo >= hi) { break; }
    let start = levelStart(k);
    if ((lo & 1u) != 0u) {
      let w = pyrW(start + lo);
      s.w += vec4<f32>(w.xyz + w.w * (leaves[lo << k].xyz - p0), w.w);
      s.a += pyrA(start + lo);
      lo += 1u;
    }
    if ((hi & 1u) != 0u) {
      hi -= 1u;
      let w = pyrW(start + hi);
      s.w += vec4<f32>(w.xyz + w.w * (leaves[hi << k].xyz - p0), w.w);
      s.a += pyrA(start + hi);
    }
    lo >>= 1u;
    hi >>= 1u;
    k += 1u;
  }
  return s;
}

@compute @workgroup_size(${WG})
fn build(@builtin(global_invocation_id) gid: vec3<u32>) {
  let n = i32(bh.n);
  let i = i32(gid.x);
  if (i >= n - 1) { return; }
  let ui = u32(i);

  // direction and extent of the range of codes this node covers
  let before = delta(ui, i - 1);
  let after = delta(ui, i + 1);
  let d = select(-1, 1, after > before);
  let floorPrefix = min(before, after);
  var span = 2;
  while (span < (1 << 30) && delta(ui, i + span * d) > floorPrefix) { span *= 2; }
  var len = 0;
  for (var t = span >> 1; t >= 1; t >>= 1) {
    if (delta(ui, i + (len + t) * d) > floorPrefix) { len += t; }
  }
  let j = i + len * d;
  let prefix = delta(ui, j);

  // where its two halves split
  var s = 0;
  var stride = len;
  loop {
    stride = (stride + 1) >> 1;
    if (delta(ui, i + (s + stride) * d) > prefix) { s += stride; }
    if (stride <= 1) { break; }
  }
  let split = i + s * d + min(d, 0);
  let first = min(i, j);
  let last = max(i, j);
  var left = u32(split);
  if (first == split) { left |= LEAF; }
  var right = u32(split + 1);
  if (last == split + 1) { right |= LEAF; }

  let depth = depthOf(prefix);
  let p0 = leaves[first].xyz;
  let sum = rangeSum(u32(first), u32(last), p0);
  var com = p0;
  if (sum.w.w > 0.0) { com += sum.w.xyz / sum.w.w; }
  // d3 skips a cell whose strengths add up to exactly 0 (and all it holds); here the sum
  // carries f32 rounding, so a cancellation leaves a residue of the order of the terms
  var value = sum.a * damp(-i32(depth));
  if (abs(sum.a) <= sum.w.w * 0x1p-20f) { value = 0.0; }
  tree[ui] = bitcast<vec4<u32>>(vec4<f32>(com, value));
  tree[bh.inner + ui] = vec4<u32>(left, right, depth, 0u);
}

fn depthAt(node: u32) -> u32 {
  return tree[bh.inner + node].z;
}

@compute @workgroup_size(${WG})
fn collapse(@builtin(global_invocation_id) gid: vec3<u32>) {
  let i = gid.x;
  if (i + 1u >= bh.n) { return; }
  let link = tree[bh.inner + i];
  var list = array<u32, 8>(link.x, link.y, NONE, NONE, NONE, NONE, NONE, NONE);
  var count = 2u;
  for (var level = 1u; level < DIMS; level++) {
    var next = array<u32, 8>(NONE, NONE, NONE, NONE, NONE, NONE, NONE, NONE);
    var m = 0u;
    for (var c = 0u; c < count; c++) {
      let k = list[c];
      if ((k & LEAF) == 0u && depthAt(k) == link.z) { // the same cell: look through it
        let below = tree[bh.inner + k];
        next[m] = below.x;
        next[m + 1u] = below.y;
        m += 2u;
      } else {
        next[m] = k;
        m += 1u;
      }
    }
    list = next;
    count = m;
  }
  for (var c = 0u; c < count; c++) {
    if ((list[c] & LEAF) == 0u) { list[c] |= depthAt(list[c]) << 24u; }
  }
  tree[2u * bh.inner + 2u * i] = vec4<u32>(list[0], list[1], list[2], list[3]);
  tree[2u * bh.inner + 2u * i + 1u] = vec4<u32>(list[4], list[5], list[6], list[7]);
}

// The centres of mass of the nodes at depth bh.base, weighing each child as d3 does: by the
// absolute value of its strength (a point's own, or a cell's at the depth below this one).
// The children are deeper, so already done.
@compute @workgroup_size(${WG})
fn recentre(@builtin(global_invocation_id) gid: vec3<u32>) {
  let i = gid.x;
  if (i + 1u >= bh.n || depthAt(i) != bh.base) { return; }
  let at = 2u * bh.inner + 2u * i;
  let kids = array<vec4<u32>, 2>(tree[at], tree[at + 1u]);
  var p0 = vec3<f32>(0.0);
  var sum = vec4<f32>(0.0); // w * (p - p0), w
  for (var c = 0u; c < 8u; c++) {
    let k = kids[c >> 2u][c & 3u];
    if (k == NONE) { break; }
    var q: vec4<f32>;
    if ((k & LEAF) != 0u) {
      q = leaves[k & INDEX];
    } else {
      q = bitcast<vec4<f32>>(tree[k & INDEX]);
      q.w *= damp(i32(((k >> 24u) & 31u) - bh.base - 1u));
    }
    let w = abs(q.w);
    if (w == 0.0) { continue; }
    if (sum.w == 0.0) { p0 = q.xyz; }
    sum += vec4<f32>(w * (q.xyz - p0), w);
  }
  if (sum.w > 0.0) {
    let cell = bitcast<vec4<f32>>(tree[i]);
    tree[i] = bitcast<vec4<u32>>(vec4<f32>(p0 + sum.xyz / sum.w, cell.w));
  }
}

// One point's force from a leaf point: exactly as manyBody.js does for points it visits.
fn pointForce(me: vec3<f32>, mine: u32, j: u32) -> vec3<f32> {
  let q = leaves[j];
  var d = q.xyz - me;
  var l = dot(d, d);
  if (l >= bh.distanceMax2) { return vec3<f32>(0.0); }
  if (l == 0.0) {
    d = coincident(pairs[mine].y, pairs[j].y);
    l = dot(d, d);
  }
  if (l < bh.distanceMin2) { l = sqrt(bh.distanceMin2 * l); }
  return d * (q.w * sim.alpha / l);
}

struct Visit { accepted: bool, force: vec3<f32> }

// Whether point t can approximate node cur, and with what force. The node stands for the
// d3 cells from depth top down to depth, which all hold the same points: d3 approximates
// at the first (widest) one that is far enough, if any.
fn visit(cur: u32, top: u32, depth: u32, me: vec3<f32>, t: u32, size: f32) -> Visit {
  if (top > depth) { return Visit(false, vec3<f32>(0.0)); } // points sharing the smallest cell
  let cell = bitcast<vec4<f32>>(tree[cur]);
  if (cell.w == 0.0) { return Visit(true, vec3<f32>(0.0)); } // d3 skips cells with no strength
  var d = cell.xyz - me;
  var l = dot(d, d);
  let reach = bh.theta2 * l;
  var w = ldexp(size, -i32(top));
  var D = top;
  loop {
    if (D > depth || w * w < reach) { break; }
    w *= 0.5;
    D += 1u;
  }
  if (D > depth) { return Visit(false, vec3<f32>(0.0)); }
  if (l >= bh.distanceMax2) { return Visit(true, vec3<f32>(0.0)); }
  if (l == 0.0) {
    d = coincident(pairs[t].y, bh.n + cur);
    l = dot(d, d);
  }
  if (l < bh.distanceMin2) { l = sqrt(bh.distanceMin2 * l); }
  return Visit(true, d * (cell.w * damp(i32(depth - D)) * sim.alpha / l));
}

@compute @workgroup_size(${WG})
fn walk(@builtin(global_invocation_id) gid: vec3<u32>) {
  let t = gid.x;
  if (t >= bh.n || bh.n < 2u) { return; }
  let me = leaves[t].xyz;
  let size = misc.cube.w;
  var acc = vec3<f32>(0.0);

  // depth-first over the d3 tree. A stack entry is a node to open (INDEX bits) and its
  // depth (from bit 26); opening it handles all its children, and stacks those that must be
  // opened in turn (at most 2^DIMS - 1 more per level).
  var stack: array<u32, STACK>;
  var sp = 0u;
  let rootDepth = min(depthAt(0u), 31u);
  let root = visit(0u, 0u, rootDepth, me, t, size);
  acc += root.force;
  if (!root.accepted) {
    stack[0] = rootDepth << 26u;
    sp = 1u;
  }
  // a tree node is opened at most once: the bound only guards against a malformed tree
  // hanging the GPU (a hung GPU takes the whole device down)
  for (var guard = 0u; guard < bh.n; guard++) {
    if (sp == 0u) { break; }
    sp -= 1u;
    let e = stack[sp];
    let base = 2u * bh.inner + 2u * (e & INDEX);
    let top = (e >> 26u) + 1u;
    let kids = array<vec4<u32>, 2>(tree[base], tree[base + 1u]);
    for (var c = 0u; c < 8u; c++) {
      let k = kids[c >> 2u][c & 3u];
      if (k == NONE) { break; }
      if ((k & LEAF) != 0u) {
        if ((k & INDEX) != t) { acc += pointForce(me, t, k & INDEX); }
        continue;
      }
      let depth = (k >> 24u) & 31u;
      let v = visit(k & INDEX, top, depth, me, t, size);
      acc += v.force;
      if (!v.accepted && sp < STACK) {
        stack[sp] = (k & INDEX) | (depth << 26u);
        sp += 1u;
      }
    }
  }

  let b = pairs[t].y;
  let v = vel[b];
  vel[b] = vec4<f32>(v.xyz + acc, v.w);
}

// The same walk for small graphs, where one thread per point leaves the GPU idle and the
// walks are long chains of dependent reads: LANES threads share each point. Its nodes to
// open are on a stack in workgroup memory; every round, each lane takes one (from the top)
// and handles its children, then the nodes they open are stacked in lane order.
const LANES = 8u;
const BODIES = 8u; // 64 / LANES
const CAP = 256u;  // stack entries per point
var<workgroup> coStack: array<u32, 2048>; // BODIES * CAP
var<workgroup> coSize: array<u32, 8>;
var<workgroup> coPush: array<u32, 64>;
var<workgroup> coAcc: array<vec3<f32>, 64>;
var<workgroup> coLeft: u32;

@compute @workgroup_size(64)
fn walkShared(@builtin(workgroup_id) wid: vec3<u32>, @builtin(local_invocation_index) li: u32) {
  let b = li / LANES;
  let lane = li % LANES;
  let t = wid.x * BODIES + b;
  let live = t < bh.n && bh.n > 1u;
  var me = vec3<f32>(0.0);
  if (live) { me = leaves[t].xyz; }
  let size = misc.cube.w;
  var acc = vec3<f32>(0.0);

  if (lane == 0u) {
    var s = 0u;
    if (live) {
      let rootDepth = min(depthAt(0u), 31u);
      let root = visit(0u, 0u, rootDepth, me, t, size);
      acc += root.force;
      if (!root.accepted) { coStack[b * CAP] = rootDepth << 26u; s = 1u; }
    }
    coSize[b] = s;
  }
  for (var guard = 0u; guard < bh.n; guard++) {
    workgroupBarrier();
    if (li == 0u) {
      var left = 0u;
      for (var k = 0u; k < BODIES; k++) { left += coSize[k]; }
      coLeft = left;
    }
    if (workgroupUniformLoad(&coLeft) == 0u) { break; }

    let size0 = coSize[b];
    var e = NONE;
    if (lane < size0) { e = coStack[b * CAP + size0 - 1u - lane]; }
    workgroupBarrier();

    var opened = array<u32, 8>(NONE, NONE, NONE, NONE, NONE, NONE, NONE, NONE);
    var m = 0u;
    if (e != NONE) {
      let base = 2u * bh.inner + 2u * (e & INDEX);
      let top = (e >> 26u) + 1u;
      let kids = array<vec4<u32>, 2>(tree[base], tree[base + 1u]);
      for (var c = 0u; c < 8u; c++) {
        let k = kids[c >> 2u][c & 3u];
        if (k == NONE) { break; }
        if ((k & LEAF) != 0u) {
          if ((k & INDEX) != t) { acc += pointForce(me, t, k & INDEX); }
          continue;
        }
        let depth = (k >> 24u) & 31u;
        let v = visit(k & INDEX, top, depth, me, t, size);
        acc += v.force;
        if (!v.accepted) {
          opened[m] = (k & INDEX) | (depth << 26u);
          m += 1u;
        }
      }
    }
    coPush[li] = m;
    workgroupBarrier();

    let kept = size0 - min(size0, LANES);
    var at = kept;
    for (var k = 0u; k < lane; k++) { at += coPush[b * LANES + k]; }
    for (var j = 0u; j < m; j++) {
      if (at + j < CAP) { coStack[b * CAP + at + j] = opened[j]; }
    }
    if (lane == LANES - 1u) { coSize[b] = min(at + m, CAP); }
  }

  coAcc[li] = acc;
  workgroupBarrier();
  if (lane == 0u && live) {
    for (var k = 1u; k < LANES; k++) { acc += coAcc[li + k]; }
    let node = pairs[t].y;
    let v = vel[node];
    vel[node] = vec4<f32>(v.xyz + acc, v.w);
  }
}
`;

var specA = ["uniform", "rw", "rw", "ro", "rw", "rw", "rw"],
    specS = ["uniform", "rw", "rw", "rw", "rw", "rw"],
    specS2 = ["uniform", "rw", "rw", "rw", "rw", "ro"],
    specB = ["uniform", "rw", "rw", "rw", "rw", "rw", "rw"];

// (specialised: only ever compiled with constants, see treeVariants)
var pipelines = Object.assign({
  bhBbox: {code: build, entry: "bbox", spec: specA, specialised: true},
  bhCube: {code: build, entry: "cube", spec: specA, specialised: true},
  bhBboxCube: {code: build, entry: "bboxCube", spec: specA, specialised: true},
  bhPrep: {code: build, entry: "prep", spec: specA, specialised: true},
  bhUp: {code: build, entry: "up", spec: specA, specialised: true},
  bhKeys: {code: order, entry: "keys", spec: specS, specialised: true},
  bhRank: {code: order, entry: "rank", spec: specS, specialised: true},
  bhFix: {code: order, entry: "fix", spec: specS, specialised: true},
  bhLongRuns: {code: order, entry: "longRuns", spec: specS2, specialised: true},
  bhBuild: {code: walk, entry: "build", spec: specB, specialised: true},
  bhCollapse: {code: walk, entry: "collapse", spec: specB, specialised: true},
  bhRecentre: {code: walk, entry: "recentre", spec: specB, specialised: true},
  bhWalk: {code: walk, entry: "walk", spec: specB, specialised: true},
  bhWalkShared: {code: walk, entry: "walkShared", spec: specB, specialised: true}
}, pipelines$2);

function treeConstants(dims) {
  return {DIMS: dims, LEVELS: LEVELS[dims], LOGC: 0.5 * (2 - dims), HALF: LEVELS[dims] / 2 * dims};
}

// The rank sort's constants: threads per code for ~2^17 threads in all.
function rankConstants(n, dims) {
  return Object.assign({RANK_SPLIT: Math.min(32, Math.max(1, 1 << Math.round(Math.log2(Math.max(1, (1 << 17) / n)))))}, treeConstants(dims));
}

// The pipelines ([id, constants]) a tree over n nodes in dims dimensions uses.
function treeVariants(n, dims) {
  var c = treeConstants(dims);
  return (n <= BOX_MAX ? ["bhBboxCube"] : ["bhBbox", "bhCube"]).concat(["bhKeys", "bhPrep", "bhUp", "bhBuild", "bhCollapse", "bhRecentre"]).map(function(id) {
    return [id, c];
  }).concat(n <= RANK_MAX ? [["bhRank", rankConstants(n, dims)]] : [["bhFix", c], ["bhLongRuns", c]]).concat([
    [n <= SHARED_MAX ? "bhWalkShared" : "bhWalk", c]
  ]);
}

function createBarnesHut(engine) {
  var device = engine.device,
      n = engine.n,
      dims = engine.nDim,
      inner = Math.max(1, n - 1),
      constants = treeConstants(dims),
      ranking = rankConstants(n, dims),
      shifts = SHIFTS[dims],
      slot = Math.max(256, device.limits.minUniformBufferOffsetAlignment | 0),
      count = function(k) { return Math.ceil(n / Math.pow(2, k)); },
      ups = [], offsets = [], total = 0, from, k;

  // prep writes levels 0..9, each up the nine above the level it starts from
  for (from = 9; count(from) > 1; from += 9) ups.push(from);
  for (k = 0; k <= 9 * (ups.length + 1); ++k) offsets.push(total), total += count(k);

  var make = function(size, usage) { return device.createBuffer({size: Math.max(16, size), usage: usage}); },
      sort = createSort(engine, n, shifts), // on the high halves of the codes
      sorted = sort.pairs[1 - (shifts.length & 1)], // (high half, node) in the final order
      misc = make(64, STORAGE | COPY_DST),
      leaves = make(16 * n, STORAGE),
      pyr = make(20 * total, STORAGE),
      codes = make(8 * n, STORAGE),
      tree = make(64 * inner, STORAGE),
      runs = make(16 + 4 * (Math.floor(n / (RUN + 1)) + 1), STORAGE | INDIRECT | COPY_DST),
      // uniform slots: the main one, one per up, one per depth for recentre
      slots = 1 + ups.length + LEVELS[dims],
      uniforms = make(slot * slots, UNIFORM | COPY_DST),
      data = new ArrayBuffer(slot * slots),
      strengths = null,
      mixed = false,
      groupsA = null,
      groupB = null,
      groupsRecentre = null,
      groupKeys, groupSort, groupLongRuns,
      last = null;

  // an empty box for the first step; longRuns' dispatch is (x, 1, 1)
  device.queue.writeBuffer(misc, 0, new Uint32Array([-1, -1, -1, -1, 0, 0, 0, 0]));
  device.queue.writeBuffer(runs, 0, new Uint32Array([0, 1, 1, 0]));

  function pipeline(id) {
    return engine.pipeline(id, constants);
  }

  function groupBAt(s) {
    return device.createBindGroup({
      layout: pipeline("bhBuild").getBindGroupLayout(1), // the same layout B as every kernel here
      entries: [
        {binding: 0, resource: {buffer: uniforms, offset: s * slot, size: 160}},
        {binding: 1, resource: {buffer: misc}},
        {binding: 2, resource: {buffer: sorted}},
        {binding: 3, resource: {buffer: leaves}},
        {binding: 4, resource: {buffer: pyr}},
        {binding: 5, resource: {buffer: tree}},
        {binding: 6, resource: {buffer: codes}}
      ]
    });
  }

  // layout S (or S2, from bhLongRuns): from, into (distinct buffers: two writable bindings
  // of one buffer are invalid)
  function groupS(layoutOf, from, into) {
    return device.createBindGroup({
      layout: pipeline(layoutOf).getBindGroupLayout(1),
      entries: [
        {binding: 0, resource: {buffer: uniforms, offset: 0, size: 160}},
        {binding: 1, resource: {buffer: misc}},
        {binding: 2, resource: {buffer: from}},
        {binding: 3, resource: {buffer: into}},
        {binding: 4, resource: {buffer: codes}},
        {binding: 5, resource: {buffer: runs}}
      ]
    });
  }

  groupKeys = groupS("bhKeys", sort.pairs[0], sort.pairs[1]);
  groupSort = groupS("bhKeys", sort.result, sorted);
  if (n > RANK_MAX) groupLongRuns = groupS("bhLongRuns", sort.result, sorted);

  function writeParams(desc) {
    var u32 = new Uint32Array(data), f32 = new Float32Array(data);
    for (var s = 0; s < slots; ++s) {
      var w = s * slot / 4;
      u32[w] = n;
      u32[w + 1] = s > ups.length ? s - ups.length - 1 : s ? ups[s - 1] : 0;
      u32[w + 2] = inner;
      u32[w + 3] = total;
      f32[w + 4] = desc.theta2;
      f32[w + 5] = desc.distanceMin2;
      f32[w + 6] = Math.min(3e38, desc.distanceMax2);
      for (var k = 0; k < offsets.length; ++k) u32[w + 8 + k] = offsets[k];
    }
    device.queue.writeBuffer(uniforms, 0, data);
  }

  return {
    // strengthBuffer: the force's per-node strengths (indexed by node); mixed: whether they
    // have both signs
    update: function(desc, strengthBuffer, isMixed) {
      var key = [desc.theta2, desc.distanceMin2, desc.distanceMax2].join();
      if (key !== last) last = key, writeParams(desc);
      mixed = isMixed;
      if (strengthBuffer !== strengths) {
        strengths = strengthBuffer;
        groupsA = [];
        for (var s = 0; s <= ups.length; ++s) {
          groupsA.push(device.createBindGroup({
            layout: pipeline("bhPrep").getBindGroupLayout(1), // layout A
            entries: [
              {binding: 0, resource: {buffer: uniforms, offset: s * slot, size: 160}},
              {binding: 1, resource: {buffer: misc}},
              {binding: 2, resource: {buffer: sorted}},
              {binding: 3, resource: {buffer: strengths}},
              {binding: 4, resource: {buffer: leaves}},
              {binding: 5, resource: {buffer: pyr}},
              {binding: 6, resource: {buffer: codes}}
            ]
          }));
        }
        groupB = groupB || groupBAt(0);
      }
      if (mixed && !groupsRecentre) {
        groupsRecentre = [];
        for (var d = 0; d < LEVELS[dims]; ++d) groupsRecentre.push(groupBAt(1 + ups.length + d));
      }
    },

    encode: function(pass) {
      if (n < 2) return; // a lone node feels nothing
      pass.setBindGroup(1, groupsA[0]);
      if (n <= BOX_MAX) {
        pass.setPipeline(pipeline("bhBboxCube"));
        pass.dispatchWorkgroups(1);
      } else {
        pass.setPipeline(pipeline("bhBbox"));
        pass.dispatchWorkgroups(Math.min(256, ceilDiv(n, 256 * 8)));
        pass.setPipeline(pipeline("bhCube"));
        pass.dispatchWorkgroups(1);
      }
      pass.setBindGroup(1, groupKeys);
      pass.setPipeline(pipeline("bhKeys"));
      pass.dispatchWorkgroups(ceilDiv(n, WG));
      if (n <= RANK_MAX) {
        pass.setBindGroup(1, groupSort);
        pass.setPipeline(engine.pipeline("bhRank", ranking));
        pass.dispatchWorkgroups(ceilDiv(n * ranking.RANK_SPLIT, 256));
      } else {
        sort.encode(pass);
        pass.setBindGroup(1, groupSort);
        pass.setPipeline(pipeline("bhFix"));
        pass.dispatchWorkgroups(ceilDiv(n, WG));
        pass.setBindGroup(1, groupLongRuns);
        pass.setPipeline(pipeline("bhLongRuns"));
        pass.dispatchWorkgroupsIndirect(runs, 0); // usually none
      }
      pass.setBindGroup(1, groupsA[0]);
      pass.setPipeline(pipeline("bhPrep"));
      pass.dispatchWorkgroups(ceilDiv(n, REDUCE));
      for (var s = 0; s < ups.length; ++s) {
        pass.setBindGroup(1, groupsA[s + 1]);
        pass.setPipeline(pipeline("bhUp"));
        pass.dispatchWorkgroups(ceilDiv(count(ups[s]), REDUCE));
      }
      pass.setBindGroup(1, groupB);
      pass.setPipeline(pipeline("bhBuild"));
      pass.dispatchWorkgroups(ceilDiv(n - 1, WG));
      pass.setPipeline(pipeline("bhCollapse"));
      pass.dispatchWorkgroups(ceilDiv(n - 1, WG));
      if (mixed) {
        // deepest first; the smallest cells, where d3 would split further, keep their centres
        pass.setPipeline(pipeline("bhRecentre"));
        for (var d = LEVELS[dims] - 1; d >= 0; --d) {
          pass.setBindGroup(1, groupsRecentre[d]);
          pass.dispatchWorkgroups(ceilDiv(n - 1, WG));
        }
        pass.setBindGroup(1, groupB);
      }
      if (n <= SHARED_MAX) {
        pass.setPipeline(pipeline("bhWalkShared"));
        pass.dispatchWorkgroups(ceilDiv(n, 8));
      } else {
        pass.setPipeline(pipeline("bhWalk"));
        pass.dispatchWorkgroups(ceilDiv(n, WG));
      }
    },

    destroy: function() {
      sort.destroy();
      [misc, leaves, pyr, codes, tree, runs, uniforms].forEach(function(b) { b.destroy(); });
    }
  };
}

// Many-body force. With theta > 0 (0.9 by default) it is the Barnes-Hut tree of
// barnesHut.js, which gives the same forces as the CPU. With theta ~ 0 it is this exact
// all-pairs kernel: each workgroup streams the whole node list through shared memory one
// tile at a time, so global memory traffic is n * n / TILE reads rather than n * n.
//
// SPLIT threads share each node: thread `lane` of a node takes every SPLIT-th source of
// each tile, and the partial sums are added at the end. That multiplies the threads in
// flight for small graphs, where one thread per node leaves most of a GPU idle.
//
// For every other node j (same arithmetic as manyBody.js, minus the tree):
//     if l < distanceMax2:  if l < distanceMin2: l = sqrt(distanceMin2 * l)
//                           v += (pj - pi) * strength[j] * alpha / l

var TILE = 128;

// Below this theta^2 the tree would open every cell anyway: sum all pairs instead.
var EXACT_THETA2 = 1e-6;

var code$2 = PRELUDE + /* wgsl */`
struct P { distanceMin2: f32, distanceMax2: f32, pad0: f32, pad1: f32 }
@group(1) @binding(0) var<uniform> p: P;
@group(1) @binding(1) var<storage, read> strength: array<f32>;

override SPLIT: u32 = 1u;
var<workgroup> tile: array<vec4<f32>, ${TILE}>;
var<workgroup> partial: array<vec4<f32>, ${TILE}>;

@compute @workgroup_size(${TILE})
fn main(@builtin(workgroup_id) wid: vec3<u32>, @builtin(local_invocation_index) li: u32) {
  let n = sim.n;
  let lane = li % SPLIT;
  let i = wid.x * (${TILE}u / SPLIT) + li / SPLIT;
  let live = i < n;
  var me = vec3<f32>(0.0);
  if (live) { me = pos[i].xyz; }
  var acc = vec3<f32>(0.0);

  for (var base = 0u; base < n; base += ${TILE}u) {
    let j = base + li;
    var t = vec4<f32>(0.0);
    if (j < n) { t = vec4<f32>(pos[j].xyz, strength[j]); }
    tile[li] = t;
    workgroupBarrier();

    let count = min(${TILE}u, n - base);
    for (var k = lane; k < count; k += SPLIT) {
      let q = tile[k];
      var d = q.xyz - me;
      var l = dot(d, d);
      if (l >= p.distanceMax2 || base + k == i) { continue; }
      if (l == 0.0) {
        d = coincident(i, base + k);
        l = dot(d, d);
      }
      if (l < p.distanceMin2) { l = sqrt(p.distanceMin2 * l); }
      acc += d * (q.w * sim.alpha / l);
    }
    workgroupBarrier();
  }

  partial[li] = vec4<f32>(acc, 0.0);
  workgroupBarrier();
  if (lane == 0u && live) {
    var total = vec3<f32>(0.0);
    for (var s = 0u; s < SPLIT; s++) { total += partial[li + s].xyz; }
    let v = vel[i];
    vel[i] = vec4<f32>(v.xyz + total, v.w);
  }
}
`;

var manyBody = {
  type: "manyBody",
  pipelines: Object.assign({manyBody: {code: code$2, entry: "main", spec: ["uniform", "ro"], specialised: true}}, pipelines),

  // the kernels, with their constants, that n nodes in nDim dimensions use
  variants: function(n, nDim, split) {
    return [["manyBody", {SPLIT: splitFor(n, split)}]].concat(treeVariants(n, nDim));
  },

  create: function(engine) {
    var device = engine.device,
        split = {SPLIT: splitFor(engine.n, engine.forcedSplit)},
        uniforms = params$1(device, 16),
        strengths = null,
        bindGroup = null,
        version = -1,
        tree = null,
        mixed = false, // strengths of both signs (the tree then needs d3's centres of mass)
        exact = true;

    return {
      update: function(desc) {
        if (desc.version !== version) {
          version = desc.version;
          if (strengths) strengths.destroy();
          var values = floats(desc.strengths, engine.n), negative = false, positive = false;
          for (var i = 0; i < values.length; ++i) negative = negative || values[i] < 0, positive = positive || values[i] > 0;
          mixed = negative && positive;
          strengths = storage(device, values);
          bindGroup = null;
        }
        exact = !(desc.theta2 > EXACT_THETA2);
        if (!exact) return (tree || (tree = createBarnesHut(engine))).update(desc, strengths, mixed);
        uniforms.set([desc.distanceMin2, desc.distanceMax2]);
        if (!bindGroup) bindGroup = device.createBindGroup({
          layout: engine.pipeline("manyBody", split).getBindGroupLayout(1),
          entries: [
            {binding: 0, resource: {buffer: uniforms.buffer}},
            {binding: 1, resource: {buffer: strengths}}
          ]
        });
      },

      encode: function(pass) {
        if (!exact) return tree.encode(pass);
        pass.setPipeline(engine.pipeline("manyBody", split));
        pass.setBindGroup(1, bindGroup);
        pass.dispatchWorkgroups(ceilDiv(engine.n * split.SPLIT, TILE));
      },

      destroy: function() {
        uniforms.destroy();
        if (strengths) strengths.destroy();
        if (tree) tree.destroy();
      }
    };
  }
};

// forceX / forceY / forceZ:   v[axis] += (goal[i] - p[axis]) * weight[i] * alpha

var code$1 = PRELUDE + /* wgsl */`
struct P { axis: u32, pad0: u32, pad1: u32, pad2: u32 }
@group(1) @binding(0) var<uniform> p: P;
@group(1) @binding(1) var<storage, read> goal: array<f32>;
@group(1) @binding(2) var<storage, read> weight: array<f32>;

@compute @workgroup_size(${WORKGROUP})
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  let i = gid.x;
  if (i >= sim.n) { return; }
  var v = vel[i];
  v[p.axis] += (goal[i] - pos[i][p.axis]) * weight[i] * sim.alpha;
  vel[i] = v;
}
`;

var position = {
  type: "position",
  pipelines: {position: {code: code$1, entry: "main", spec: ["uniform", "ro", "ro"]}},

  create: function(engine) {
    var device = engine.device,
        uniforms = params$1(device, 16),
        buffers = [],
        bindGroup = null,
        version = -1,
        axis = 0;

    return {
      update: function(desc) {
        axis = desc.axis;
        uniforms.set([], {0: axis});
        if (desc.version !== version) {
          version = desc.version;
          buffers.forEach(function(b) { b.destroy(); });
          buffers = [storage(device, floats(desc.targets, engine.n)), storage(device, floats(desc.strengths, engine.n))];
          bindGroup = device.createBindGroup({
            layout: engine.pipeline("position").getBindGroupLayout(1),
            entries: [
              {binding: 0, resource: {buffer: uniforms.buffer}},
              {binding: 1, resource: {buffer: buffers[0]}},
              {binding: 2, resource: {buffer: buffers[1]}}
            ]
          });
        }
      },

      encode: function(pass) {
        if (axis >= engine.nDim) return; // forceZ on a 2D simulation has nothing to act on
        pass.setPipeline(engine.pipeline("position"));
        pass.setBindGroup(1, bindGroup);
        pass.dispatchWorkgroups(ceilDiv(engine.n, WORKGROUP));
      },

      destroy: function() {
        uniforms.destroy();
        buffers.forEach(function(b) { b.destroy(); });
      }
    };
  }
};

// forceRadial: pull every node towards the sphere of `radius[i]` around (x, y, z).
// As in radial.js, a component that is exactly zero is replaced by 1e-6 so a node sitting
// on the centre still gets a direction.

var code = PRELUDE + /* wgsl */`
struct P { center: vec3<f32>, pad0: f32 }
@group(1) @binding(0) var<uniform> p: P;
@group(1) @binding(1) var<storage, read> radiuses: array<f32>;
@group(1) @binding(2) var<storage, read> weight: array<f32>;

@compute @workgroup_size(${WORKGROUP})
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  let i = gid.x;
  if (i >= sim.n) { return; }
  var d = pos[i].xyz - p.center;
  d = select(d, vec3<f32>(1e-6), d == vec3<f32>(0.0));
  d = select(vec3<f32>(0.0), d, activeDims());
  let r = length(d);
  let k = (radiuses[i] - r) * weight[i] * sim.alpha / r;
  let v = vel[i];
  vel[i] = vec4<f32>(v.xyz + d * k, v.w);
}
`;

var radial = {
  type: "radial",
  pipelines: {radial: {code: code, entry: "main", spec: ["uniform", "ro", "ro"]}},

  create: function(engine) {
    var device = engine.device,
        uniforms = params$1(device, 16),
        buffers = [],
        bindGroup = null,
        version = -1;

    return {
      update: function(desc) {
        uniforms.set([desc.x, desc.y, desc.z]);
        if (desc.version !== version) {
          version = desc.version;
          buffers.forEach(function(b) { b.destroy(); });
          buffers = [storage(device, floats(desc.radiuses, engine.n)), storage(device, floats(desc.strengths, engine.n))];
          bindGroup = device.createBindGroup({
            layout: engine.pipeline("radial").getBindGroupLayout(1),
            entries: [
              {binding: 0, resource: {buffer: uniforms.buffer}},
              {binding: 1, resource: {buffer: buffers[0]}},
              {binding: 2, resource: {buffer: buffers[1]}}
            ]
          });
        }
      },

      encode: function(pass) {
        pass.setPipeline(engine.pipeline("radial"));
        pass.setBindGroup(1, bindGroup);
        pass.dispatchWorkgroups(ceilDiv(engine.n, WORKGROUP));
      },

      destroy: function() {
        uniforms.destroy();
        buffers.forEach(function(b) { b.destroy(); });
      }
    };
  }
};

// A force takes part in a GPU simulation by exposing force.gpu(), which returns a
// descriptor {type, version, ...}. `type` selects the pass below; `version` changes
// whenever the force recomputes its per-node values, telling the pass to re-upload them.
var passes = {};
[center, collide, link, manyBody, position, radial].forEach(function(pass) {
  passes[pass.type] = pass;
});

// Steps encoded into one command buffer. Each gets its own slot of the per-step uniform
// block (alpha changes from step to step), selected with a dynamic offset.
var MAX_BATCH = 64;

// Submissions the queue may hold before Engine.busy() reports true. Completion is only
// acknowledged asynchronously, so leave room for a frame or two of jitter.
var MAX_PENDING = 3;

var definitions = {
  snapshot: {code: SNAPSHOT, entry: "main", spec: ["rw"]},
  integrate: {code: INTEGRATE, entry: "main", spec: ["ro", "ro"]}
};
Object.keys(passes).forEach(function(type) {
  Object.assign(definitions, passes[type].pipelines);
});

// Compile ahead of time, and in parallel, the kernels a simulation of `n` nodes in `nDim`
// dimensions uses with forces of the given types (all of them by default): the specialised
// variants the passes ask for (the many-body tree for these dimensions, say), and their
// other kernels. Others compile when first needed. Rejects on any shader error.
function prepare(device, n, nDim, split, types) {
  var jobs = [["snapshot"], ["integrate"]], seen = {};
  (types || Object.keys(passes)).forEach(function(type) {
    var pass = passes[type];
    if (!pass || seen[type]) return;
    seen[type] = true;
    if (pass.variants) jobs = jobs.concat(pass.variants(n, nDim, split));
    Object.keys(pass.pipelines).forEach(function(id) {
      if (!pass.pipelines[id].specialised) jobs.push([id]);
    });
  });
  return prewarm(device, definitions, jobs);
}

// The name of the first force that cannot run on the GPU, or null.
function unsupported(forces) {
  var found = null;
  forces.forEach(function(force, name) {
    if (found === null && !(typeof force.gpu === "function" && passes[(force.gpu() || {}).type])) found = name;
  });
  return found;
}

// Calls onLost if the device is lost while the engine is alive. The promise outlives the
// engine, so its callback must not close over the engine's state (nodes and their copies):
// it only holds this small object, which destroy() empties.
function watchLoss(device, onLost) {
  var watch = {onLost: onLost};
  device.lost.then(function(info) {
    if (watch.onLost) watch.onLost(info);
  });
  return watch;
}

function num(v) {
  return (v = +v) === v ? v : 0; // undefined / NaN -> 0
}

// Mirrors a node array on the GPU.
//
//   * The GPU holds the truth for position and velocity while a simulation runs.
//   * Nodes are written back asynchronously by readback().
//   * Changes made on the JS side in the meantime -- a moved x/y/z, a pinned fx/fy/fz --
//     are detected (by comparing against what was last exchanged) and uploaded at the
//     start of the next step. A node the user has touched is never overwritten by a
//     readback that was already in flight.
function createEngine(device, onLost, options) {
  var engine,
      nodes = [],
      n = 0,
      nDim = 2,
      plan = [],
      alive = true,
      seed = 0,
      stepCount = 0,
      enqueued = 0,   // steps submitted
      landed = 0,     // steps reflected in the JS nodes
      pending = 0,    // submissions the GPU has not finished
      epoch = 0,      // bumped whenever the buffers are replaced or overwritten
      stale = false,
      inflight = null,
      buffers = null;

  var alignment = Math.max(256, device.limits.minUniformBufferOffsetAlignment | 0),
      slotWords = alignment / 4,
      simData = new ArrayBuffer(MAX_BATCH * alignment),
      simF32 = new Float32Array(simData),
      simU32 = new Uint32Array(simData);

  // What the GPU was last told / told us about every node (exact JS doubles).
  var shadow = new Float64Array(0),   // x, y, z
      pinned = new Uint8Array(0),     // fx/fy/fz mask
      pinnedAt = new Float64Array(0); // fx, fy, fz

  var loss = watchLoss(device, onLost);

  function release() {
    plan.forEach(function(entry) { entry.pass.destroy(); });
    plan = [];
    if (buffers) {
      ++epoch;
      buffers.forEach(function(b) { b.destroy(); });
      buffers = null;
    }
  }

  // --- JS -> GPU ---------------------------------------------------------------------

  function uploadAll() {
    var pos = new Float32Array(4 * n),
        vel = new Float32Array(4 * n),
        fixPos = new Float32Array(4 * n),
        fixMask = new Uint32Array(n),
        i, j, node, mask;

    for (i = 0; i < n; ++i) {
      node = nodes[i], j = 3 * i;
      shadow[j] = pos[4 * i] = num(node.x);
      shadow[j + 1] = pos[4 * i + 1] = nDim > 1 ? num(node.y) : 0;
      shadow[j + 2] = pos[4 * i + 2] = nDim > 2 ? num(node.z) : 0;
      vel[4 * i] = num(node.vx);
      if (nDim > 1) vel[4 * i + 1] = num(node.vy);
      if (nDim > 2) vel[4 * i + 2] = num(node.vz);
      mask = 0;
      if (node.fx != null) mask |= 1, pinnedAt[j] = fixPos[4 * i] = +node.fx;
      if (nDim > 1 && node.fy != null) mask |= 2, pinnedAt[j + 1] = fixPos[4 * i + 1] = +node.fy;
      if (nDim > 2 && node.fz != null) mask |= 4, pinnedAt[j + 2] = fixPos[4 * i + 2] = +node.fz;
      pinned[i] = fixMask[i] = mask;
    }

    var queue = device.queue;
    queue.writeBuffer(buffers[0], 0, pos);
    queue.writeBuffer(buffers[1], 0, vel);
    queue.writeBuffer(buffers[4], 0, fixPos);
    queue.writeBuffer(buffers[5], 0, fixMask);
    ++epoch; // a readback still in flight predates this state
    stale = false;
    landed = enqueued;
  }

  function uploadPositions(a, b) {
    var out = new Float32Array(4 * (b - a)), i, j, node;
    for (i = a; i < b; ++i) {
      node = nodes[i], j = 3 * i;
      shadow[j] = out[4 * (i - a)] = num(node.x);
      shadow[j + 1] = out[4 * (i - a) + 1] = nDim > 1 ? num(node.y) : 0;
      shadow[j + 2] = out[4 * (i - a) + 2] = nDim > 2 ? num(node.z) : 0;
    }
    device.queue.writeBuffer(buffers[0], 16 * a, out);
  }

  function uploadPinned(a, b) {
    var pos = new Float32Array(4 * (b - a)), mask = new Uint32Array(b - a), i, j, node, m;
    for (i = a; i < b; ++i) {
      node = nodes[i], j = 3 * i, m = 0;
      if (node.fx != null) m |= 1, pinnedAt[j] = pos[4 * (i - a)] = +node.fx;
      if (nDim > 1 && node.fy != null) m |= 2, pinnedAt[j + 1] = pos[4 * (i - a) + 1] = +node.fy;
      if (nDim > 2 && node.fz != null) m |= 4, pinnedAt[j + 2] = pos[4 * (i - a) + 2] = +node.fz;
      pinned[i] = mask[i - a] = m;
    }
    device.queue.writeBuffer(buffers[4], 16 * a, pos);
    device.queue.writeBuffer(buffers[5], 4 * a, mask);
  }

  function syncFromJS() {
    var posRun = -1, pinRun = -1, i, j, node, x, y, z, m, posDirty, pinDirty;

    for (i = 0; i <= n; ++i) {
      posDirty = pinDirty = false;
      if (i < n) {
        node = nodes[i], j = 3 * i;
        x = num(node.x), y = nDim > 1 ? num(node.y) : 0, z = nDim > 2 ? num(node.z) : 0;
        posDirty = x !== shadow[j] || y !== shadow[j + 1] || z !== shadow[j + 2];

        m = (node.fx != null ? 1 : 0) | (nDim > 1 && node.fy != null ? 2 : 0) | (nDim > 2 && node.fz != null ? 4 : 0);
        pinDirty = m !== pinned[i]
            || (m & 1) !== 0 && +node.fx !== pinnedAt[j]
            || (m & 2) !== 0 && +node.fy !== pinnedAt[j + 1]
            || (m & 4) !== 0 && +node.fz !== pinnedAt[j + 2];
      }

      if (posDirty) { if (posRun < 0) posRun = i; }
      else if (posRun >= 0) uploadPositions(posRun, i), posRun = -1;

      if (pinDirty) { if (pinRun < 0) pinRun = i; }
      else if (pinRun >= 0) uploadPinned(pinRun, i), pinRun = -1;
    }
  }

  // --- GPU -> JS ---------------------------------------------------------------------

  function land(data) {
    var i, j, k, node, x, y, z, v = 4 * n;
    for (i = 0; i < n; ++i) {
      node = nodes[i], j = 3 * i, k = 4 * i;
      x = num(node.x), y = nDim > 1 ? num(node.y) : 0, z = nDim > 2 ? num(node.z) : 0;

      // Untouched since the last exchange: take the GPU's position. Otherwise the user
      // has moved it and syncFromJS() will push their value at the next step.
      if (x === shadow[j] && y === shadow[j + 1] && z === shadow[j + 2]) {
        node.x = shadow[j] = data[k];
        if (nDim > 1) node.y = shadow[j + 1] = data[k + 1];
        if (nDim > 2) node.z = shadow[j + 2] = data[k + 2];
      }

      node.vx = data[v + k];
      if (nDim > 1) node.vy = data[v + k + 1];
      if (nDim > 2) node.vz = data[v + k + 2];
    }
  }

  function readback() {
    if (inflight) return inflight;
    if (!alive || !n || landed === enqueued) return Promise.resolve();

    var target = enqueued, era = epoch, staging = buffers[6], bytes = 16 * n,
        encoder = device.createCommandEncoder();
    encoder.copyBufferToBuffer(buffers[0], 0, staging, 0, bytes);
    encoder.copyBufferToBuffer(buffers[1], 0, staging, bytes, bytes);
    device.queue.submit([encoder.finish()]);

    return inflight = staging.mapAsync(MAP_MODE_READ).then(function() {
      if (era === epoch) land(new Float32Array(staging.getMappedRange())), landed = target;
      staging.unmap();
    }, function(error) {
      if (era === epoch && alive) throw error; // otherwise the buffers were replaced under us
    }).then(function() {
      inflight = null;
    }, function(error) {
      inflight = null;
      throw error;
    });
  }

  // --- setup -------------------------------------------------------------------------

  return engine = {
    device: device,

    get n() { return n; },
    // threads per node in the all-pairs kernels, when the caller chose one (else automatic)
    get forcedSplit() { return options && options.split; },
    get nDim() { return nDim; },

    pipeline: function(id, constants) {
      return pipeline(device, id, definitions[id], constants);
    },

    // pos + vel, for kernels that read other nodes' predicted positions. snapshot()
    // refreshes it and leaves bind group 1 for the caller to set.
    get snap() { return buffers && buffers[2]; },

    snapshot: function(pass) {
      pass.setPipeline(engine.pipeline("snapshot"));
      pass.setBindGroup(1, buffers.bindGroupSnapshot);
      pass.dispatchWorkgroups(ceilDiv(n, WORKGROUP));
    },

    // Replace the node set: (re)allocate all buffers and upload the nodes. Forces must be
    // passed to setForces() again afterwards.
    setNodes: function(_nodes, _nDim) {
      release();
      nodes = _nodes, n = nodes.length, nDim = _nDim;
      enqueued = landed = 0;
      shadow = new Float64Array(3 * n);
      pinned = new Uint8Array(n);
      pinnedAt = new Float64Array(3 * n);
      if (!n) return engine;

      var vec4 = 16 * n,
          state = STORAGE | COPY_DST | COPY_SRC,
          make = function(size, usage) { return device.createBuffer({size: size, usage: usage}); };

      buffers = [
        make(vec4, state),                    // 0 pos
        make(vec4, state),                    // 1 vel
        make(vec4, STORAGE),                  // 2 snap
        make(MAX_BATCH * alignment, UNIFORM | COPY_DST), // 3 per-step uniforms
        make(vec4, STORAGE | COPY_DST),       // 4 pinned positions
        make(Math.max(16, 4 * n), STORAGE | COPY_DST), // 5 pinned mask
        make(2 * vec4, MAP_READ | COPY_DST)   // 6 readback staging
      ];
      buffers.bindGroup0 = device.createBindGroup({
        layout: layout0(device),
        entries: [
          {binding: 0, resource: {buffer: buffers[3], size: SIM_BYTES}},
          {binding: 1, resource: {buffer: buffers[0]}},
          {binding: 2, resource: {buffer: buffers[1]}}
        ]
      });
      buffers.bindGroupSnapshot = device.createBindGroup({
        layout: engine.pipeline("snapshot").getBindGroupLayout(1),
        entries: [{binding: 0, resource: {buffer: buffers[2]}}]
      });
      buffers.bindGroupIntegrate = device.createBindGroup({
        layout: engine.pipeline("integrate").getBindGroupLayout(1),
        entries: [
          {binding: 0, resource: {buffer: buffers[4]}},
          {binding: 1, resource: {buffer: buffers[5]}}
        ]
      });
      uploadAll();
      return engine;
    },

    // Build the pass list from the forces (in application order). Returns false, leaving
    // the engine untouched, when a force has no GPU implementation.
    setForces: function(forces) {
      var descs = forces.map(function(force) {
        return typeof force.gpu === "function" ? force.gpu() : null;
      });
      if (descs.some(function(d) { return !d || !passes[d.type]; })) return false;

      // A force that is still in the list keeps its pass (and its uploaded buffers).
      var previous = new Map(plan.map(function(entry) { return [entry.force, entry]; }));
      plan = n ? forces.map(function(force, i) {
        var entry = previous.get(force);
        if (entry && entry.type === descs[i].type) return previous.delete(force), entry;
        return {force: force, type: descs[i].type, pass: passes[descs[i].type].create(engine)};
      }) : [];
      previous.forEach(function(entry) { entry.pass.destroy(); });
      plan.forEach(function(entry, i) { entry.pass.update(descs[i]); });
      return true;
    },

    // Whether `count` nodes fit within this device's buffer and dispatch limits. The
    // largest buffer is the many-body tree's, 64 bytes per node.
    fits: function(count) {
      var limits = device.limits;
      return 64 * count <= Math.min(limits.maxStorageBufferBindingSize, limits.maxBufferSize)
          && Math.ceil(count / WORKGROUP) <= limits.maxComputeWorkgroupsPerDimension;
    },

    // Seed for the shaders' coincident-node jiggle, drawn from the simulation's random source.
    setSeed: function(random) {
      seed = Math.floor(random() * 4294967296) >>> 0;
    },

    // The JS nodes have been changed behind the engine's back (e.g. by CPU ticks):
    // re-upload everything at the next step.
    markStale: function() {
      stale = true;
    },

    // Submit `alphas.length` steps. `decay` is the velocity multiplier, 1 - velocityDecay.
    enqueue: function(alphas, decay) {
      if (!alive || !n) return;

      if (stale) uploadAll();
      else syncFromJS();
      plan.forEach(function(entry) { entry.pass.update(entry.force.gpu()); });

      var total = alphas.length, integrate = engine.pipeline("integrate"),
          groups = ceilDiv(n, WORKGROUP), start, count, s, w, encoder, pass;

      for (start = 0; start < total; start += MAX_BATCH) {
        count = Math.min(MAX_BATCH, total - start);
        for (s = 0; s < count; ++s) {
          w = s * slotWords;
          simF32[w] = alphas[start + s];
          simF32[w + 1] = decay;
          simU32[w + 2] = n;
          simU32[w + 3] = nDim;
          simU32[w + 4] = stepCount++ >>> 0;
          simU32[w + 5] = seed;
        }
        device.queue.writeBuffer(buffers[3], 0, simF32, 0, count * slotWords);

        encoder = device.createCommandEncoder();
        pass = encoder.beginComputePass();
        for (s = 0; s < count; ++s) {
          pass.setBindGroup(0, buffers.bindGroup0, [s * alignment]);
          for (w = 0; w < plan.length; ++w) plan[w].pass.encode(pass);
          pass.setPipeline(integrate);
          pass.setBindGroup(1, buffers.bindGroupIntegrate);
          pass.dispatchWorkgroups(groups);
        }
        pass.end();
        device.queue.submit([encoder.finish()]);

        ++pending;
        device.queue.onSubmittedWorkDone().then(function() { --pending; }, function() { pending = 0; });
      }
      enqueued += total;
    },

    // True when the GPU is still working through earlier submissions.
    busy: function() {
      return pending >= MAX_PENDING;
    },

    // Copy the latest state into the JS nodes (one readback at a time).
    readback: readback,

    // Resolves once the GPU has finished everything submitted so far (no readback).
    idle: function() {
      return device.queue.onSubmittedWorkDone();
    },

    // Resolves once the JS nodes reflect every step submitted so far.
    sync: function() {
      function next() {
        return readback().then(function() {
          if (alive && n && landed !== enqueued) return next();
        });
      }
      return next();
    },

    // The GPU-resident state, for rendering straight from the simulation's buffers:
    // vec4<f32> per node, xyz used.
    buffers: function() {
      return buffers && {positions: buffers[0], velocities: buffers[1], count: n, stride: 16};
    },

    destroy: function() {
      alive = false;
      loss.onLost = null;
      release();
      nodes = [], shadow = pinnedAt = new Float64Array(0), pinned = new Uint8Array(0);
    }
  };
}

// forceSimulation() with the physics running on the GPU (WebGPU compute shaders).
//
// The API is that of forceSimulation(nodes, numDimensions), plus a few additions (see
// the end of the returned object). The differences to keep in mind:
//
//   * Node positions are copied back from the GPU asynchronously, so after tick() the
//     nodes are up to date one readback later (about a frame); "tick" events fire once
//     the copy has landed. To step and then read synchronously, use `await tickAsync(n)`.
//   * Forces are described by force.gpu(); every force in this package has one. If the
//     simulation contains any other force, or WebGPU is unavailable, it runs on the CPU
//     exactly like forceSimulation().

var MAX_DIMENSIONS = 3;

// Up to CPU_TICKS_NODES nodes, the timer's first CPU_TICKS ticks run on the CPU (d3-force-3d's
// own code, cpuTick below) while the GPU starts up, so the layout moves at once. A fixed
// count rather than "until the GPU is ready": the tick where the GPU takes over shapes the
// final layout, which must not depend on how long the kernels took to compile.
var CPU_TICKS = 120,
    CPU_TICKS_NODES = 1000;

var initialRadius = 10,
    initialAngleRoll = Math.PI * (3 - Math.sqrt(5)), // Golden ratio angle
    initialAngleYaw = Math.PI * 20 / (9 + Math.sqrt(221)); // Markov irrational number

function simulation(nodes, numDimensions, options) {
  numDimensions = numDimensions || 2;
  options = options || {};

  // options.device    a GPUDevice to run on (default: requested from navigator.gpu)
  // options.gpu       a GPU object to request the device from (e.g. from Node's `webgpu` package)
  // options.split    threads per node in the GPU's all-pairs kernels (a power of two; by
  //                   default chosen from the number of nodes, more for small graphs)
  // options.readback  false: do not copy positions back into the nodes on every tick, for
  //                   renderers that draw straight from gpuBuffers(). Nodes then only
  //                   update when sync() or tickAsync() is called.
  // options.cpuTicks  how many of the timer's first ticks after the nodes are set run on the
  //                   CPU (default: 120 up to 1,000 nodes with readback, else 0). tick() and
  //                   tickAsync() skip the rest.
  var readback = options.readback !== false;

  var nDim = Math.min(MAX_DIMENSIONS, Math.max(1, Math.round(numDimensions))),
      simulation,
      alpha = 1,
      alphaMin = 0.001,
      alphaDecay = 1 - Math.pow(alphaMin, 1 / 300),
      alphaTarget = 0,
      velocityDecay = 0.6,
      forces = new Map(),
      stepper = d3Timer.timer(step),
      event = d3Dispatch.dispatch("tick", "end"),
      random = lcg();

  var engine = null,        // GPU state, once the device is ready
      ready,                // Promise<boolean>: settles when GPU setup has finished
      initializing = false, // waiting for the device; the timer idles meanwhile
      usable = false,       // engine exists and every force can run on it
      oversized = false,    // too many nodes for this device
      running = true,       // the timer is active (cleared by stop() and by "end")
      frame = false,        // a timer-driven GPU step + readback is under way
      flushing = null,      // readback loop started by tick()
      batch = false,        // tick() has already been called in this synchronous run
      handover = null,      // GPU -> CPU state transfer in progress
      warm = 0,             // timer ticks still to run on the CPU before the GPU takes over
      seeded = false,       // the shaders' jiggle seed has been drawn from `random`
      destroyed = false,
      warned = null;

  if (nodes == null) nodes = [];

  // --- CPU path (identical to forceSimulation) -----------------------------------------

  function cpuTick(iterations) {
    var i, n = nodes.length, node;

    if (handover) return simulation;
    if (iterations === undefined) iterations = 1;

    for (var k = 0; k < iterations; ++k) {
      alpha += (alphaTarget - alpha) * alphaDecay;

      forces.forEach(function (force) {
        force(alpha);
      });

      for (i = 0; i < n; ++i) {
        node = nodes[i];
        if (node.fx == null) node.x += node.vx *= velocityDecay;
        else node.x = node.fx, node.vx = 0;
        if (nDim > 1) {
          if (node.fy == null) node.y += node.vy *= velocityDecay;
          else node.y = node.fy, node.vy = 0;
        }
        if (nDim > 2) {
          if (node.fz == null) node.z += node.vz *= velocityDecay;
          else node.z = node.fz, node.vz = 0;
        }
      }
    }

    return simulation;
  }

  // --- GPU path ------------------------------------------------------------------------

  function gpuFail(error) {
    if (engine) engine.destroy();
    engine = null, usable = false, frame = false;
    if (!destroyed) console.warn("WebGPU simulation failed; continuing on the CPU.", error);
  }

  // (Re)build the GPU pass list from the current forces, or hand over to the CPU if a
  // force cannot run on the GPU.
  function refresh() {
    if (!engine || oversized) return;
    var name = unsupported(forces);

    if (name === null) {
      warned = null;
      engine.setForces(Array.from(forces.values()));
      if (!usable) usable = true, engine.markStale(); // the CPU may have moved the nodes
    } else {
      if (warned !== name) {
        warned = name;
        console.warn("Force \"" + name + "\" cannot run on the GPU (it has no force.gpu()): simulating on the CPU.");
      }
      if (usable) {
        usable = false;
        handover = engine.sync().then(function() { handover = null; }, function(error) { handover = null, gpuFail(error); });
      }
    }
  }

  // Submit `iterations` steps to the GPU (without waiting for them).
  function advance(iterations) {
    // Drawn at the first GPU step, not when the device is ready: CPU ticks may draw from
    // `random` too, and which tick the device is ready at depends on timing.
    if (!seeded) seeded = true, engine.setSeed(random);
    var alphas = new Array(iterations);
    for (var k = 0; k < iterations; ++k) {
      alpha += (alphaTarget - alpha) * alphaDecay;
      alphas[k] = alpha;
    }
    engine.enqueue(alphas, velocityDecay);
  }

  function gpuStep() {
    frame = true;
    advance(1);
    (readback ? engine.sync() : engine.idle()).then(function() {
      frame = false;
      if (!running) return;
      event.call("tick", simulation);
      if (alpha < alphaMin) {
        running = false;
        stepper.stop();
        event.call("end", simulation);
      }
    }, gpuFail);
  }

  function init() {
    var gpu = options.gpu || null;
    if (!options.device && !gpu && !isWebGPUAvailable()) return Promise.resolve(false);

    initializing = true;
    return (options.device ? Promise.resolve(options.device) : requestDevice(gpu)).then(function(device) {
      // the forces set so far (the usual chained .force() calls have run by now)
      var types = [];
      forces.forEach(function(force) { if (typeof force.gpu === "function") types.push((force.gpu() || {}).type); });
      return prepare(device, nodes.length, nDim, options.split, types).then(function() { return device; });
    }).then(function(device) {
      if (destroyed) return false;
      engine = createEngine(device, gpuFail, {split: options.split});
      reset();
      return usable;
    }).catch(function(error) {
      console.warn("WebGPU unavailable; simulating on the CPU.", error);
      return false;
    }).then(function(ok) {
      initializing = false;
      return ok;
    });
  }

  // --- simulation ----------------------------------------------------------------------

  function step() {
    if (handover) return;
    if (warm > 0) {
      --warm;
      if (engine) engine.markStale(); // the GPU takes over from the nodes as the CPU leaves them
    } else if (initializing) {
      return;
    } else if (usable) {
      return frame || gpuStep();
    }

    cpuTick();
    event.call("tick", simulation);
    if (alpha < alphaMin) {
      running = false;
      stepper.stop();
      event.call("end", simulation);
    }
  }

  // Keep the JS nodes following the GPU: one readback loop at a time.
  function flush() {
    if (flushing) return;
    flushing = engine.sync().then(function() { flushing = null; }, function(error) { flushing = null, gpuFail(error); });
  }

  function tick(iterations) {
    if (iterations === undefined) iterations = 1;
    warm = 0;
    if (!usable) return cpuTick(iterations);

    // A tick() per frame must not pile work up when the GPU cannot keep pace, so it is
    // skipped while the GPU is behind (as the CPU simulation would simply run slower).
    // Further calls within the same synchronous run (e.g. a warm-up loop) are a batch,
    // not a frame: those always run.
    var frameCall = !batch;
    if (frameCall) batch = true, Promise.resolve().then(function() { batch = false; });
    if (frameCall && iterations === 1 && engine.busy()) return simulation;

    advance(iterations);
    if (readback) flush();
    return simulation;
  }

  function initializeNodes() {
    for (var i = 0, n = nodes.length, node; i < n; ++i) {
      node = nodes[i], node.index = i;
      if (node.fx != null) node.x = node.fx;
      if (node.fy != null) node.y = node.fy;
      if (node.fz != null) node.z = node.fz;
      if (isNaN(node.x) || (nDim > 1 && isNaN(node.y)) || (nDim > 2 && isNaN(node.z))) {
        var radius = initialRadius * (nDim > 2 ? Math.cbrt(0.5 + i) : (nDim > 1 ? Math.sqrt(0.5 + i) : i)),
          rollAngle = i * initialAngleRoll,
          yawAngle = i * initialAngleYaw;

        if (nDim === 1) {
          node.x = radius;
        } else if (nDim === 2) {
          node.x = radius * Math.cos(rollAngle);
          node.y = radius * Math.sin(rollAngle);
        } else { // 3 dimensions: use spherical distribution along 2 irrational number angles
          node.x = radius * Math.sin(rollAngle) * Math.cos(yawAngle);
          node.y = radius * Math.cos(rollAngle);
          node.z = radius * Math.sin(rollAngle) * Math.sin(yawAngle);
        }
      }
      if (isNaN(node.vx) || (nDim > 1 && isNaN(node.vy)) || (nDim > 2 && isNaN(node.vz))) {
        node.vx = 0;
        if (nDim > 1) { node.vy = 0; }
        if (nDim > 2) { node.vz = 0; }
      }
    }
  }

  function initializeForce(force) {
    if (force.initialize) force.initialize(nodes, random, nDim);
    return force;
  }

  // The timer's first ticks on this node set run on the CPU (see CPU_TICKS).
  function arm() {
    warm = options.cpuTicks != null ? Math.max(0, Math.floor(options.cpuTicks)) || 0
        : readback && nodes.length <= CPU_TICKS_NODES ? CPU_TICKS : 0;
  }

  function reset() {
    if (!engine) return;
    if (oversized = !engine.fits(nodes.length)) {
      engine.setNodes([], nDim); // free the buffers
      usable = false;
      console.warn(nodes.length + " nodes exceed this GPU's buffer limits: simulating on the CPU.");
      return;
    }
    engine.setNodes(nodes, nDim);
    refresh();
  }

  initializeNodes();
  arm();
  ready = init();

  return simulation = {
    tick: tick,

    restart: function() {
      return running = true, stepper.restart(step), simulation;
    },

    stop: function() {
      return running = false, stepper.stop(), simulation;
    },

    numDimensions: function(_) {
      return arguments.length
          ? (nDim = Math.min(MAX_DIMENSIONS, Math.max(1, Math.round(_))), forces.forEach(initializeForce), reset(), simulation)
          : nDim;
    },

    nodes: function(_) {
      return arguments.length ? (nodes = _, initializeNodes(), arm(), forces.forEach(initializeForce), reset(), simulation) : nodes;
    },

    alpha: function(_) {
      return arguments.length ? (alpha = +_, simulation) : alpha;
    },

    alphaMin: function(_) {
      return arguments.length ? (alphaMin = +_, simulation) : alphaMin;
    },

    alphaDecay: function(_) {
      return arguments.length ? (alphaDecay = +_, simulation) : +alphaDecay;
    },

    alphaTarget: function(_) {
      return arguments.length ? (alphaTarget = +_, simulation) : alphaTarget;
    },

    velocityDecay: function(_) {
      return arguments.length ? (velocityDecay = 1 - _, simulation) : 1 - velocityDecay;
    },

    randomSource: function(_) {
      return arguments.length ? (random = _, forces.forEach(initializeForce), engine && (seeded = true, engine.setSeed(random)), simulation) : random;
    },

    force: function(name, _) {
      if (arguments.length < 2) return forces.get(name);
      if (_ == null) forces.delete(name);
      else forces.set(name, initializeForce(_));
      refresh();
      return simulation;
    },

    find: function() {
      var args = Array.prototype.slice.call(arguments);
      var x = args.shift() || 0,
          y = (nDim > 1 ? args.shift() : null) || 0,
          z = (nDim > 2 ? args.shift() : null) || 0,
          radius = args.shift() || Infinity;

      var i = 0,
          n = nodes.length,
          dx,
          dy,
          dz,
          d2,
          node,
          closest;

      radius *= radius;

      for (i = 0; i < n; ++i) {
        node = nodes[i];
        dx = x - node.x;
        dy = y - (node.y || 0);
        dz = z - (node.z ||0);
        d2 = dx * dx + dy * dy + dz * dz;
        if (d2 < radius) closest = node, radius = d2;
      }

      return closest;
    },

    on: function(name, _) {
      return arguments.length > 1 ? (event.on(name, _), simulation) : event.on(name);
    },

    // --- additions to the forceSimulation API ---

    // Promise for the simulation once `iterations` ticks have run and the nodes reflect
    // them. This is the way to run a layout to completion (the synchronous tick() cannot
    // wait for the GPU).
    tickAsync: function(iterations) {
      if (iterations === undefined) iterations = 1;
      warm = 0;
      return ready.then(function() { return handover; }).then(function() {
        if (!usable) return cpuTick(iterations);
        advance(iterations);
        return engine.sync().then(function() { return simulation; });
      });
    },

    // Promise for the simulation once the nodes reflect everything the GPU has computed.
    sync: function() {
      return ready.then(function() { return handover; }).then(function() {
        return usable && engine.sync();
      }).then(function() { return simulation; });
    },

    // True while the physics runs on the GPU.
    isGPUEnabled: function() {
      return usable;
    },

    // Promise for whether GPU setup succeeded (false: the simulation runs on the CPU).
    gpuReady: function() {
      return ready.then(function() { return usable; });
    },

    // The GPU-resident state, {positions, velocities, count, stride}: GPUBuffers holding a
    // vec4<f32> per node (xyz used). Lets a WebGPU renderer draw the layout without ever
    // reading it back. null until the GPU is ready.
    gpuBuffers: function() {
      return engine && usable ? engine.buffers() : null;
    },

    // Release GPU resources and stop the simulation.
    destroy: function() {
      destroyed = true, running = false, usable = false;
      stepper.stop();
      if (engine) engine.destroy(), engine = null;
      return simulation;
    }
  };
}

exports.checkWebGPUSupport = checkWebGPUSupport;
exports.forceCenter = center$1;
exports.forceCollide = collide$1;
exports.forceLink = link$1;
exports.forceManyBody = manyBody$1;
exports.forceRadial = radial$1;
exports.forceSimulation = simulation$1;
exports.forceSimulationGPU = simulation;
exports.forceX = x;
exports.forceY = y;
exports.forceZ = z;
exports.isWebGPUAvailable = isWebGPUAvailable;

}));
