var disc = new THREE.TextureLoader().load( '/remote_3editor/assets/circle.png' );

var newGeometry = function (numVertex, numElements, elementDim, oldMaterial, name) {

        console.log(" new geometry " + numVertex + " " + numElements + " " + elementDim)

        var geometry = new THREE.BufferGeometry();
        if (elementDim > 0)
                geometry.setIndex(new THREE.BufferAttribute(new Uint32Array(numElements * elementDim), elementDim));
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
                map: disc,
                transparent: true,
                depthTest: false,
                size: 0.005
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
                mesh = new THREE.Mesh(geometry, material);
        else if (elementDim == 2)
                mesh = new THREE.LineSegments(geometry, material);
        else if (elementDim == 0)
                mesh = new THREE.Points(geometry, material);

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
                a.src = URL.createObjectURL(new Blob([rawdata.slice(dataOffset)]))
                var tex = new THREE.Texture(a)
                thatImageBuffer = tex
                URL.revokeObjectURL(a.src)

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

        geometry = geometryForName(name, dim, elementDim)

        if (typeCode === 'V') {
                // console.log("vertex")
                geometry.attributes["position"].array.set(new Float32Array(rawdata, dataOffset))
                geometry.attributes["position"].needsUpdate = true;
                geometry.attributes["position"].updateRange = { offset: 0, count: dim * 3 }

                if (name.endsWith("_p"))
                        geometry.setDrawRange(0, dim)

        } else if (typeCode === 'T') {
                geometry.attributes["uv"].array.set(new Float32Array(rawdata, dataOffset))
                geometry.attributes["uv"].needsUpdate = true;
                geometry.attributes["uv"].updateRange = { offset: 0, count: dim * 2 }
        }
        else if (typeCode === 'C') {
                geometry.attributes["color"].array.set(new Float32Array(rawdata, dataOffset))
                geometry.attributes["color"].needsUpdate = true;
                geometry.attributes["color"].updateRange = { offset: 0, count: dim * 4 }
        }
        else if (typeCode === 'E' && (elementDim > 0)) {
                var te = new Uint32Array(rawdata, dataOffset)
                // console.log(" checking element size " + te.length + " " + (geometry.index.count) + " " + (geometry.index.itemSize) + " " + geometry.index.array.length)
                if (geometry.index.array.length < te.length) {
                        // console.log(" had to rebuild elements " + geometry.index.count + " * " + geometry.index.itemSize + " < " + te.length)
                        geometry.setIndex(new THREE.BufferAttribute(new Uint32Array(te.length * 2), elementDim));
                }
                geometry.index.array.set(te)
                geometry.index.count = te.length
                geometry.index.needsUpdate = true;
                geometry.index.updateRange = { offset: 0, count: te.length }
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
        ray.linePrecision = 0.001
        e.ray = ray.ray

        e.hit = undefined

        // TODOTODOTOD

        if (window.FRAME) {
                hit = FRAME.hitTestNoAnchor(e.coordinates.x / 2 + 0.5, -e.coordinates.y / 2 + 0.5)
                if (hit) {
                        e.hit = new THREE.Vector3(hit[0].modelMatrix[12], hit[0].modelMatrix[13], hit[0].modelMatrix[14])
                }
                else {
                        e.hit = undefined
                }
        }

        if (doIntersection)
        {
                ray.far = 1000
                ray.near = 0.00001

                e.intersects = ray.intersectObjects( SCENE.children, true);
                console.log("intersection ", e.intersects, ray, SCENE.children)
                window.RAY = ray
        }

}

var addTouchCallbacks = (target, gate) => {
        target.addEventListener('mouseup', (e) => {
                if (gate && !gate(e)) return
                console.log("up", e)
                // e.stopImmediatePropagation()
                x = 2 * (e.clientX / e.target.clientWidth) - 1;
                y = -2 * (e.clientY / e.target.clientHeight) + 1;
                e.coordinates = new THREE.Vector2(x, y)
                labelEvent(e)
                dispatchTo(e, mouseUp, "mouseUp");

        });
        target.addEventListener('mousedown', (e) => {
                if (gate && !gate(e)) return
                // e.stopImmediatePropagation()
                x = 2 * (e.clientX / e.target.clientWidth) - 1;
                y = -2 * (e.clientY / e.target.clientHeight) + 1;
                e.coordinates = new THREE.Vector2(x, y)
                labelEvent(e, true)

                for(var ee of e.intersects)
                {
                        if (ee.object._fieldSource)
                        {
                                if (ee.object._fieldSource.mouseDown)
                                {
                                        dispatchTo(e, ee.object._fieldSource.mouseDown, "FLine mouseDown");
                                }
                        }
                }

                dispatchTo(e, mouseDown, "mouseDown");
        });
        target.addEventListener('dblclick', (e) => {
                if (gate && !gate(e)) return
                // e.stopImmediatePropagation()
                x = 2 * (e.clientX / e.target.clientWidth) - 1;
                y = -2 * (e.clientY / e.target.clientHeight) + 1;
                e.coordinates = new THREE.Vector2(x, y)
                labelEvent(e, false)
                dispatchTo(e, dblClick, "dblClick");
        });

        target.addEventListener('mousemove', (e) => {
                if (gate && !gate(e)) return
                // e.stopImmediatePropagation()
                x = 2 * (e.clientX / e.target.clientWidth) - 1;
                y = -2 * (e.clientY / e.target.clientHeight) + 1;
                e.coordinates = new THREE.Vector2(x, y)
                labelEvent(e)
                dispatchTo(e, mouseMove, "mouseMove");
        });

        target.addEventListener('touchend', (e) => {
                if (gate && !gate(e)) return
                // e.stopImmediatePropagation()
                if (e.touches && e.touches[0]) {
                        e.clientX = e.touches[0].clientX
                        e.clientY = e.touches[0].clientY
                }
                x = 2 * (e.clientX / e.target.clientWidth) - 1;
                y = -2 * (e.clientY / e.target.clientHeight) + 1;
                e.coordinates = new THREE.Vector2(x, y)
                labelEvent(e)
                dispatchTo(e, mouseUp, "mouseUp")
        });


        let tapedTwice = false;


        target.addEventListener('touchstart', (e) => {
                if (gate && !gate(e)) return

                if(!tapedTwice) {
                        tapedTwice = true;
                        setTimeout( function() { tapedTwice = false; }, 300 );
                }
                else
                {
                        // e.stopImmediatePropagation()
                        if (e.touches && e.touches[0]) {
                                e.clientX = e.touches[0].clientX
                                e.clientY = e.touches[0].clientY
                        }
                        x = 2 * (e.clientX / e.target.clientWidth) - 1;
                        y = -2 * (e.clientY / e.target.clientHeight) + 1;
                        e.coordinates = new THREE.Vector2(x, y)
                        labelEvent(e, false)
                        dispatchTo(e, dblClick, "dblClick")
                        return
                }

                // e.stopImmediatePropagation()
                if (e.touches && e.touches[0]) {
                        e.clientX = e.touches[0].clientX
                        e.clientY = e.touches[0].clientY
                }
                x = 2 * (e.clientX / e.target.clientWidth) - 1;
                y = -2 * (e.clientY / e.target.clientHeight) + 1;
                e.coordinates = new THREE.Vector2(x, y)
                labelEvent(e, true)

                for(var ee of e.intersects)
                {
                        if (ee.object._fieldSource)
                        {
                                if (ee.object._fieldSource.mouseDown)
                                {
                                        dispatchTo(e, ee.object._fieldSource.mouseDown, "FLine mouseDown");
                                }
                        }
                }

                dispatchTo(e, mouseDown, "mouseDown")

        });
        target.addEventListener('touchmove', (e) => {
                if (gate && !gate(e)) return
                // e.stopImmediatePropagation()
                if (e.touches && e.touches[0]) {
                        e.clientX = e.touches[0].clientX
                        e.clientY = e.touches[0].clientY
                }
                x = 2 * (e.clientX / e.target.clientWidth) - 1;
                y = -2 * (e.clientY / e.target.clientHeight) + 1;
                e.coordinates = new THREE.Vector2(x, y)
                labelEvent(e)
                dispatchTo(e, mouseMove, "mouseMove")
        });
}
