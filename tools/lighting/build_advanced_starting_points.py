#!/usr/bin/env python3
"""Regenerate editable transcript sources; never launches Vulkan or prepares an SDK."""
from pathlib import Path
import copy
import yaml

ROOT = Path(__file__).resolve().parents[2]
TRANSCRIPTS = ROOT / 'dev_docs/agents/transcripts'
DEMOS = ROOT / 'dev_docs/agents/demos'
RESOURCES = ROOT / 'src/jvmMain/resources/afield/four'


def main():
    template = yaml.safe_load((TRANSCRIPTS / 'ray_reconstruction.yaml').read_text())
    descriptions = {
        'computed_ray_geometry': 'Compute editable triangle clusters in Field and trace them with Vulkan Mega Geometry.',
        'gpu_shader_learning': 'Train on moduleHere RGB directly on the GPU, freeze the fit, and save portable learned weights.',
        'path_traced_reconstruction': 'Compare raw multi-bounce path tracing, progressive accumulation and Ray Reconstruction.',
    }
    for name, description in descriptions.items():
        operations = copy.deepcopy(template['operations'][:3])
        # Cluster builds require a bound ray-tracing pipeline; enable its ordinary negotiation.
        for operation in operations:
            item = operation.get('create', operation.get('launch'))
            if item:
                props = item.get('properties', item)
                props['code'] = props['code'].replace('Options.enableVR = false', 'Options.enableRayTracing = true\nOptions.enableVR = false')
        props = {'code': (DEMOS / name / 'example.kt').read_text()}
        if name == 'computed_ray_geometry':
            props['compute'] = (RESOURCES / 'geometry/terrain.comp').read_text()
        if name == 'gpu_shader_learning':
            props['vertex'] = (DEMOS / 'learned_from_module/vertex.glsl').read_text()
            props['fragment'] = (RESOURCES / 'neural/teacher.frag').read_text()
        operations += [
            {'create': {'box': name, 'name': name.replace('_', ' '),
                        'frame': {'x': 400.0, 'y': 160.0, 'w': 420.0, 'h': 100.0}, 'properties': props}},
            {'connect': {'parent': 'agent_graphics_context', 'child': name}},
            {'launch': {'box': name, 'property': 'code', 'type': 'code', 'strand': 'scene', 'code': props['code']}},
            {'wait': {'frames': 12}},
        ]
        (TRANSCRIPTS / f'{name}.yaml').write_text(yaml.safe_dump(
            {'name': name, 'description': description, 'operations': operations}, sort_keys=False, allow_unicode=True, width=110))


if __name__ == '__main__':
    main()
