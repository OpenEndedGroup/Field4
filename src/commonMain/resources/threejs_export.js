import * as THREE from 'three';
import { GLTFExporter } from 'three/addons/exporters/GLTFExporter.js';

// Export display geometry, never the canonical solids used for fabrication. Work on detached
// copies: asynchronous encoding must not hold temporary material changes in the live viewer.
export async function exportViewerGLB(root, { xray = false, opacity = .12, units = '', names = new Map(), metadata = {} } = {}) {
    const scene = new THREE.Scene();
    scene.name = 'Field';
    const scale = new Map([['mm',.001],['cm',.01],['m',1],['in',.0254],['ft',.3048]]).get(units) ?? 1;
    scene.userData.field = { ...metadata, xray, xrayOpacity:opacity, sourceUnits:units || 'unspecified', metersPerUnit:scale };
    const model = new THREE.Group();
    model.name = 'Geometry';
    model.scale.setScalar(scale);
    scene.add(model);
    const resources = [];
    root.updateWorldMatrix(true, true);
    try {
        root.traverseVisible(source => {
            if (!source.isMesh && !source.isLine && !source.isPoints) return;
            const input = source.geometry, materials = Array.isArray(source.material) ? source.material : [source.material];
            const position = input?.getAttribute('position');
            if (!position || !position.count) return;
            // Slots may reuse oversized backing buffers. Export only the active draw range.
            const length = input.index?.count ?? position.count;
            const start = Math.max(0, input.drawRange.start);
            const end = Math.min(length, start + input.drawRange.count);
            const groups = input.groups.length ? input.groups : [{start:0, count:length, materialIndex:0}];
            for (const group of groups) {
                const material = materials[group.materialIndex ?? 0];
                if (!material || !material.visible) continue;
                const from = Math.max(start, group.start), to = Math.min(end, group.start + group.count);
                if (to <= from) continue;
                const geometry = copyGeometry(input, source, material, from, to, xray);
                resources.push(geometry);
                if (!geometry.getAttribute('position').count) continue;
                const color = material.color?.clone() ?? new THREE.Color(1, 1, 1);
                const constant = geometry.userData.constantColor;
                if (constant) color.multiply(new THREE.Color(constant[0], constant[1], constant[2]));
                const surface = source.isMesh;
                const alpha = (surface ? material.opacity * (xray ? opacity : 1)
                    : Math.min(1, material.opacity * (xray && source.isLine ? 1.6 : 1))) * (constant?.[3] ?? 1);
                const options = {color, opacity:alpha, vertexColors:!constant, transparent:alpha < 1 || geometry.userData.hasAlpha,
                    side:material.side ?? THREE.DoubleSide};
                // The line/point shaders are unlit; RGBA COLOR_0 carries their per-vertex alpha.
                const outputMaterial = surface ? new THREE.MeshStandardMaterial({...options,
                    roughness:material.roughness ?? .6, metalness:material.metalness ?? .1})
                    : new THREE.MeshBasicMaterial(options);
                resources.push(outputMaterial);
                const object = surface ? new THREE.Mesh(geometry, outputMaterial)
                    : source.isPoints ? new THREE.Points(geometry, outputMaterial) : new THREE.LineSegments(geometry, outputMaterial);
                const identity = names.get(source) ?? names.get(source.parent);
                object.name = identity?.label || source.name || (surface ? 'Surface' : source.isPoints ? 'Points' : 'Edges');
                object.userData = {field:{kind:surface ? 'surface' : source.isPoints ? 'points' : 'lines', ...identity}};
                // copyGeometry bakes world transforms, including explode and insertion playback.
                model.add(object);
            }
        });
        if (!model.children.length) throw new Error('There is no visible geometry to export.');
        return await new GLTFExporter().parseAsync(scene, {binary:true, onlyVisible:true});
    } finally {
        for (const resource of resources) resource.dispose();
    }
}

function copyGeometry(input, object, material, start, end, xray) {
    if (!material.clippingPlanes?.length && (object.isMesh || object.isPoints || object.isLineSegments))
        return copyUnclippedGeometry(input, object, material, start, end, xray);
    const positions = [], colors = [], normals = [];
    const p = input.getAttribute('position'), c = input.getAttribute('color'), a = input.getAttribute('alpha'), n = input.getAttribute('normal');
    const matrix = object.matrixWorld, normalMatrix = new THREE.Matrix3().getNormalMatrix(matrix), reflected = matrix.determinant() < 0;
    const planes = material.clippingPlanes || [];
    const normal = new THREE.Vector3(), point = new THREE.Vector3();
    let hasAlpha = false;
    function vertex(at) {
        const i = input.index ? input.index.getX(at) : at;
        point.fromBufferAttribute(p, i).applyMatrix4(matrix);
        let alpha = a ? a.getX(i) : c?.itemSize === 4 ? c.getW(i) : 1;
        if (object.isLine && material.isShaderMaterial && xray) alpha = Math.min(1, alpha * 1.6);
        normal.set(0, 0, 0);
        if (n) normal.fromBufferAttribute(n, i).applyMatrix3(normalMatrix).normalize();
        return [...point.toArray(), c ? c.getX(i) : 1, c ? c.getY(i) : 1, c ? c.getZ(i) : 1, alpha, ...normal.toArray()];
    }
    function emit(v) {
        positions.push(v[0], v[1], v[2]); colors.push(v[3], v[4], v[5], v[6]);
        hasAlpha ||= v[6] < 1;
        if (n && object.isMesh && !material.flatShading) normals.push(v[7], v[8], v[9]);
    }
    function distance(plane, v) { return plane.normal.x*v[0] + plane.normal.y*v[1] + plane.normal.z*v[2] + plane.constant; }
    function intersection(a, b, da, db) { const t = da / (da - db); return a.map((v, i) => v + (b[i] - v)*t); }
    const stride = object.isMesh ? 3 : object.isLineSegments ? 2 : 1;
    for (let at = start; at < end; at += stride) {
        if (object.isPoints) {
            const v = vertex(at); if (planes.every(plane => distance(plane, v) >= 0)) emit(v);
        } else if (object.isLine) {
            const next = object.isLineSegments ? at + 1 : at + 1 < end ? at + 1 : object.isLineLoop ? start : end;
            if (next >= end) continue;
            let a = vertex(at), b = vertex(next), visible = true;
            for (const plane of planes) {
                const da = distance(plane, a), db = distance(plane, b);
                if (da < 0 && db < 0) { visible = false; break; }
                if (da < 0) a = intersection(a, b, da, db);
                else if (db < 0) b = intersection(a, b, da, db);
            }
            if (visible) { emit(a); emit(b); }
        } else if (at + 2 < end) {
            let polygon = [vertex(at), vertex(at + 1), vertex(at + 2)];
            for (const plane of planes) {
                const clipped = [];
                for (let i = 0; i < polygon.length; i++) {
                    const a = polygon[i], b = polygon[(i + 1) % polygon.length], da = distance(plane, a), db = distance(plane, b);
                    if (da >= 0) clipped.push(a);
                    if ((da < 0) !== (db < 0)) clipped.push(intersection(a, b, da, db));
                }
                polygon = clipped;
            }
            // A reflected transform needs reversed winding after being baked into the positions.
            for (let i = 1; i + 1 < polygon.length; i++) {
                const triangle = reflected ? [polygon[0], polygon[i+1], polygon[i]] : [polygon[0], polygon[i], polygon[i+1]];
                triangle.forEach(emit);
            }
        }
    }
    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
    geometry.setAttribute('color', new THREE.Float32BufferAttribute(colors, 4));
    if (normals.length) { geometry.setAttribute('normal', new THREE.Float32BufferAttribute(normals, 3)); geometry.normalizeNormals(); }
    else if (object.isMesh) geometry.computeVertexNormals();
    geometry.userData.hasAlpha = hasAlpha;
    return geometry;
}

// Large assemblies must not allocate millions of boxed JS numbers or a temporary array per
// vertex. Typed buffers keep export memory proportional to the actual GLB attributes.
function copyUnclippedGeometry(input, object, material, start, end, xray) {
    const stride = object.isMesh ? 3 : object.isLineSegments ? 2 : 1;
    const count = Math.floor((end-start)/stride)*stride;
    const p=input.getAttribute('position'), c=input.getAttribute('color'), a=input.getAttribute('alpha'), n=input.getAttribute('normal');
    function index(at) { return input.index ? input.index.getX(start+at) : start+at; }
    function alphaAt(i) {
        const alpha=a ? a.getX(i) : c?.itemSize===4 ? c.getW(i) : 1;
        return object.isLine && material.isShaderMaterial && xray ? Math.min(1,alpha*1.6) : alpha;
    }
    const first=index(0);
    let constant=[c ? c.getX(first) : 1,c ? c.getY(first) : 1,c ? c.getZ(first) : 1,alphaAt(first)];
    for(let at=1;at<count && constant;at++) {
        const i=index(at);
        if((c && (c.getX(i)!==constant[0] || c.getY(i)!==constant[1] || c.getZ(i)!==constant[2])) || alphaAt(i)!==constant[3]) constant=null;
    }
    // Uniform colors belong in the material, not millions of identical RGBA entries. This is
    // lossless and substantially reduces peak memory and file size for architectural assemblies.
    const positions = new Float32Array(count*3), colors = constant ? null : new Float32Array(count*4);
    const normals = object.isMesh && n && !material.flatShading ? new Float32Array(count*3) : null;
    const matrix=object.matrixWorld, normalMatrix=new THREE.Matrix3().getNormalMatrix(matrix), reflected=object.isMesh && matrix.determinant()<0;
    const point=new THREE.Vector3(), normal=new THREE.Vector3();
    let hasAlpha=false;
    for(let at=0;at<count;at++) {
        const local=reflected && at%3!==0 ? at+(at%3===1 ? 1 : -1) : at;
        const i=input.index ? input.index.getX(start+local) : start+local;
        point.fromBufferAttribute(p,i).applyMatrix4(matrix).toArray(positions,at*3);
        const alpha=alphaAt(i);
        if(colors) {
            colors[at*4]=c ? c.getX(i) : 1; colors[at*4+1]=c ? c.getY(i) : 1; colors[at*4+2]=c ? c.getZ(i) : 1;
            colors[at*4+3]=alpha; hasAlpha ||= alpha<1;
        }
        if(normals) normal.fromBufferAttribute(n,i).applyMatrix3(normalMatrix).normalize().toArray(normals,at*3);
    }
    const geometry=new THREE.BufferGeometry();
    geometry.setAttribute('position',new THREE.BufferAttribute(positions,3));
    if(colors) geometry.setAttribute('color',new THREE.BufferAttribute(colors,4));
    if(normals) geometry.setAttribute('normal',new THREE.BufferAttribute(normals,3));
    else if(object.isMesh) geometry.computeVertexNormals();
    geometry.userData.hasAlpha=hasAlpha;
    geometry.userData.constantColor=constant;
    return geometry;
}
