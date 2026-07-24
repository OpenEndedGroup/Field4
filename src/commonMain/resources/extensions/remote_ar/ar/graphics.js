var disc = new THREE.TextureLoader().load('assets/circle.png');

var markAttributeUpdated = function (attribute, count) {
        attribute.needsUpdate = true
        if (typeof attribute.clearUpdateRanges === "function" && typeof attribute.addUpdateRange === "function") {
                attribute.clearUpdateRanges()
                attribute.addUpdateRange(0, count)
        }
        else {
                attribute.updateRange = {offset: 0, count: count}
        }
}

var newGeometry = function (numVertex, numElements, elementDim, oldMaterial, name) {

        console.log(" new geometry " + numVertex + " " + numElements + " " + elementDim)

        var geometry = new THREE.BufferGeometry();
        if (elementDim > 0)
                geometry.setIndex(new THREE.BufferAttribute(new Uint32Array(numElements * elementDim), 1));
        geometry.setAttribute('position', new THREE.BufferAttribute(new Float32Array(numVertex * 3), 3));
        geometry.setAttribute('color', new THREE.BufferAttribute(new Float32Array(numVertex * 4), 4));
        geometry.setAttribute('uv', new THREE.BufferAttribute(new Float32Array(numVertex * 2), 2));
        geometry.setDrawRange(0, 0)

        // geometry.attributes["position"].dynamic = true
        // geometry.attributes["color"].dynamic = true
        // geometry.attributes["uv"].dynamic = true

        var material = (oldMaterial) ? oldMaterial : (elementDim == 0 ? new THREE.PointsMaterial({
                side: THREE.DoubleSide,
                blending: THREE.NormalBlending,
                vertexColors:true,
                // map: disc,
                transparent: true,
                depthTest: false,
                size: 0.01
        }) : new THREE.MeshBasicMaterial({
                side: THREE.DoubleSide,
                blending: THREE.NormalBlending,
                vertexColors: true,
                transparent: true,
                // transparent: true,
                depthTest: false
        }));

        material.name = name + "_material"

        if (elementDim == 3)
                var mesh = new THREE.Mesh(geometry, material);
        else if (elementDim == 2)
                var mesh = new THREE.LineSegments(geometry, material);
        else if (elementDim == 0)
                var mesh = new THREE.Points(geometry, material);

        // mesh.frustumCulled = false

        mesh.name = name
        mesh.frustumCulled = false

        geometry.computeBoundingSphere()
        geometry.name = name
        geometry.frustumCulled = false

        SCENE.attach[name] = mesh

        return mesh
}

var meshes = {}

var default_maxVertex = 5
var default_maxElement = 1000

var geometryForName = function (name, dim, elementDim) {

        var oldMaterial = 0

        if (meshes[name]) {
                var r = meshes[name].geometry
                if (r.attributes["position"].count < dim) {

                        oldMaterial = meshes[name].material
                        SCENE.remove(meshes[name])

                        // fallthrough
                        console.log(" geometry is too small for name " + name + " resizing " + r.attributes["position"].count + " < " + dim)
                }
                else {
                        if (meshes[name].parent==null) // become disconnected somehow
                        {
                                SCENE.attach[name] = meshes[name]
                        }
                        return r
                }
        }

        console.log(" new geometry for name " + name)

        var d = 0
        if (name.endsWith("_f"))
                d = 3
        else if (name.endsWith("_s"))
                d = 2
        else if (name.endsWith("_p"))
                d = 0

        meshes[name] = newGeometry(Math.max(dim * 2, default_maxVertex), default_maxElement, d, oldMaterial, name)

        return meshes[name].geometry
}

var updateBuffer = function (rawdata, data, name, typeCode, dim, elementDim, dataOffset) {

        //console.log(" TYPE "+typeCode+" / "+name)

        if (typeCode === 'x') {
                var a = new Image()
                var objectURL = URL.createObjectURL(new Blob([rawdata.slice(dataOffset)]))
                a.onload = function () { URL.revokeObjectURL(objectURL) }
                a.src = objectURL
                var tex = new THREE.Texture(a)
                thatImageBuffer = tex

                console.log(" upgrading material ")
                var material = new THREE.MeshBasicMaterial({
                        map: tex,
                        side: THREE.DoubleSide,
                        blending: THREE.AdditiveBlending,
                        vertexColors: true,
                        transparent: true,
                        depthTest: false
                })

                if (!meshes[name])
                        geometryForName(name, 10, 10)

                meshes[name].material = material

                tex.needsUpdate = true

                return meshes[name]

        }

        var geometry = geometryForName(name, dim, elementDim)

        if (typeCode === 'V') {
                // console.log("vertex")
                geometry.attributes["position"].array.set(new Float32Array(rawdata, dataOffset))
                geometry.attributes["position"].needsUpdate = true;
                markAttributeUpdated(geometry.attributes["position"], dim * 3)

                if (name.endsWith("_p"))
                        geometry.setDrawRange(0, dim)

        } else if (typeCode === 'T') {
                geometry.attributes["uv"].array.set(new Float32Array(rawdata, dataOffset))
                geometry.attributes["uv"].needsUpdate = true;
                markAttributeUpdated(geometry.attributes["uv"], dim * 2)
        }
        else if (typeCode === 'C') {
                geometry.attributes["color"].array.set(new Float32Array(rawdata, dataOffset))
                geometry.attributes["color"].needsUpdate = true;
                markAttributeUpdated(geometry.attributes["color"], dim * 4)
        }
        else if (typeCode === 'E' && (elementDim > 0)) {
                var te = new Uint32Array(rawdata, dataOffset)
                // console.log(" checking element size " + te.length + " " + (geometry.index.count) + " " + (geometry.index.itemSize) + " " + geometry.index.array.length)
                if (geometry.index.array.length < te.length) {
                        // console.log(" had to rebuild elements " + geometry.index.count + " * " + geometry.index.itemSize + " < " + te.length)
                        geometry.setIndex(new THREE.BufferAttribute(new Uint32Array(te.length * 2), 1));
                }
                geometry.index.array.set(te)
                geometry.index.count = te.length
                geometry.index.needsUpdate = true;
                markAttributeUpdated(geometry.index, te.length)
                geometry.setDrawRange(0, te.length)
        }

        geometry.computeBoundingSphere()

        return meshes[name]
}

var SCENE, CAMERA

var enterScene = (_scene, _camera, updateCallback, preupdateCallback) => {

        SCENE = _scene;
        CAMERA = _camera;

        SCENE.description = () => {
                return "SCENE"
        }
        CAMERA.description = () => {
                return "CAMERA"
        }

        SCENE.name = "Root Scene"

        __superglobal.SCENE = SCENE
        __superglobal.CAMERA = CAMERA
        __superglobal.THREE = THREE
        // __superglobal.editor = editor

        SCENE.attach = new Proxy(new Map(), idempotencyHandler(function (n, is, was) {
                if (was) {
                        if (preupdateCallback)
                                preupdateCallback()

                        SCENE.remove(was)
                        if (updateCallback)
                                updateCallback()
                }
                if (is) {
                        if (!was)
                                if (preupdateCallback)
                                        preupdateCallback()

                        if (!is.name) is.name = n
                        SCENE.add(is)
                        if (updateCallback)
                                updateCallback()

                        is.description = () => "attached '" + n + "'"
                }
        }));

        // Kotlin indexed-access sugar `SCENE[name] = obj` / `SCENE[name]` (RemoteArKotlinJs's
        // `operator fun THREE.Scene.get/set`) compiles to these calls; route them straight through the
        // named-index proxy above so assigning a slot replaces whatever object was there.
        SCENE.__attachSet = function (name, value) { SCENE.attach[name] = value }
        SCENE.__attachGet = function (name) { return SCENE.attach[name] }
}

var mouseUp = new Proxy(new Map(), idempotencyHandler(function (n, is, was) { }));
var mouseDown = new Proxy(new Map(), idempotencyHandler(function (n, is, was) { }));
var mouseMove = new Proxy(new Map(), idempotencyHandler(function (n, is, was) { }));
var dblClick =  new Proxy(new Map(), idempotencyHandler(function (n, is, was) { }));

__superglobal.mouseUp = mouseUp
__superglobal.mouseDown = mouseDown
__superglobal.dblClick = dblClick
__superglobal.mouseMove = mouseMove


__superglobal.launch = launch
__superglobal.over = over


var labelEvent = (e, doIntersection=false) => {
        var ray = new THREE.Raycaster()
        ray.setFromCamera(e.coordinates, CAMERA)
        ray.params.Line.threshold = 0.001
        ray.params.Points.threshold = 0.01
        e.ray = ray.ray

        e.hit = undefined

        if (window.FieldRemoteXR && window.FieldRemoteXR.state.latestHit) {
                e.hit = window.FieldRemoteXR.state.latestHit.position.clone()
                e.xrHit = window.FieldRemoteXR.state.latestHit
        }

        if (doIntersection)
        {
                ray.far = 1000
                ray.near = 0.00001

                e.intersects = ray.intersectObjects( SCENE.children, true);
                window.RAY = ray
        }

}

var dispatchFLineIntersections = (event, callbackName) => {
        if (!event.intersects) return
        var delivered = new Set()
        for (var intersection of event.intersects) {
                var source = intersection.object && intersection.object._fieldSource
                if (!source || delivered.has(source)) continue
                delivered.add(source)
                event.intersection = intersection
                if (source[callbackName]) dispatchTo(event, source[callbackName], "FLine " + callbackName)
        }
}

var touchCallbackTargets = new WeakSet()

var clientPositionForEvent = (event) => {
        var touch = (event.touches && event.touches[0]) ||
                (event.changedTouches && event.changedTouches[0])
        return {
                x: touch ? touch.clientX : event.clientX,
                y: touch ? touch.clientY : event.clientY
        }
}

var dispatchInputEvent = (event, target, callbacks, callbackName) => {
        var position = clientPositionForEvent(event)
        var bounds = target.getBoundingClientRect()
        var width = bounds.width || target.clientWidth
        var height = bounds.height || target.clientHeight
        if (!width || !height || !Number.isFinite(position.x) || !Number.isFinite(position.y)) return

        var x = 2 * ((position.x - bounds.left) / width) - 1
        var y = -2 * ((position.y - bounds.top) / height) + 1
        event.coordinates = new THREE.Vector2(x, y)
        labelEvent(event, true)
        dispatchFLineIntersections(event, callbackName)
        dispatchTo(event, callbacks, callbackName)
}

// A-Frame's canvas receives ordinary browser input, while WebXR DOM Overlay sends
// pointer events to its overlay element. Install the same Field callback surface on
// both; this guard also makes head initialization safe to repeat.
var addTouchCallbacks = (target, gate) => {
        if (!target) throw new Error("Cannot install Field input callbacks without a target")
        if (touchCallbackTargets.has(target)) return target
        touchCallbackTargets.add(target)
        target.dataset.fieldRemoteInput = "true"

        var deliver = (event, callbacks, callbackName) => {
                if (gate && !gate(event)) return
                dispatchInputEvent(event, target, callbacks, callbackName)
        }

        if (window.PointerEvent) {
                var lastNonMouseDown = null
                var suppressNativeDoubleClickUntil = 0

                target.addEventListener('pointerup', (event) => {
                        deliver(event, mouseUp, "mouseUp")
                })
                target.addEventListener('pointerdown', (event) => {
                        if (gate && !gate(event)) return
                        if (event.pointerType !== "mouse") {
                                var now = performance.now()
                                if (lastNonMouseDown !== null && now - lastNonMouseDown < 300) {
                                        lastNonMouseDown = null
                                        suppressNativeDoubleClickUntil = now + 500
                                        dispatchInputEvent(event, target, dblClick, "dblClick")
                                        return
                                }
                                lastNonMouseDown = now
                        }
                        dispatchInputEvent(event, target, mouseDown, "mouseDown")
                })
                target.addEventListener('pointermove', (event) => {
                        deliver(event, mouseMove, "mouseMove")
                })
                target.addEventListener('dblclick', (event) => {
                        if (performance.now() < suppressNativeDoubleClickUntil) return
                        deliver(event, dblClick, "dblClick")
                })
                return target
        }

        // Pointer Events cover current Android browsers and WKWebView. Keep this
        // compatibility path for older embedded browsers.
        target.addEventListener('mouseup', (event) => {
                deliver(event, mouseUp, "mouseUp")
        })
        target.addEventListener('mousedown', (event) => {
                deliver(event, mouseDown, "mouseDown")
        })
        target.addEventListener('mousemove', (event) => {
                deliver(event, mouseMove, "mouseMove")
        })
        target.addEventListener('dblclick', (event) => {
                deliver(event, dblClick, "dblClick")
        })

        var tappedOnce = false
        target.addEventListener('touchstart', (event) => {
                if (gate && !gate(event)) return
                if (tappedOnce) {
                        tappedOnce = false
                        dispatchInputEvent(event, target, dblClick, "dblClick")
                        return
                }
                tappedOnce = true
                setTimeout(function () { tappedOnce = false }, 300)
                dispatchInputEvent(event, target, mouseDown, "mouseDown")
        })
        target.addEventListener('touchmove', (event) => {
                deliver(event, mouseMove, "mouseMove")
        })
        target.addEventListener('touchend', (event) => {
                deliver(event, mouseUp, "mouseUp")
        })
        return target
}
