"""The formulation registry: which physics this worker can offer, and whether it can import.

A formulation is listed here whether or not its Kratos application is installed. `available()`
reports both, so `hello` can say "embedded flow exists but FluidDynamicsApplication is missing"
rather than pretending the formulation does not exist -- the difference between a build to install
and a feature to wait for.
"""
import importlib

from .base import Formulation, FormulationError
from .flow import EmbeddedFlow
from .structural import BodyFittedStructural
from .thermal import StationaryThermal

#: Wire name to class. Ordered; the first entry is the default for `createBackgroundDomain`.
REGISTRY = {
    StationaryThermal.name: StationaryThermal,
    EmbeddedFlow.name: EmbeddedFlow,
    BodyFittedStructural.name: BodyFittedStructural,
}

DEFAULT = StationaryThermal.name


def _importable(module):
    try:
        importlib.import_module(module)
        return None
    except Exception as exc:
        return str(exc)


def available():
    """`{name: {available, summary, element, applications, missing}}` for every registered
    formulation, with one import attempt per distinct module."""
    cache = {}
    report = {}
    for name, formulation in REGISTRY.items():
        missing = {}
        for module in formulation.applications:
            if module not in cache:
                cache[module] = _importable(module)
            if cache[module] is not None:
                missing[module] = cache[module]
        report[name] = {
            'available': not missing,
            'summary': formulation.summary,
            'element': formulation.element,
            'applications': list(formulation.applications),
            'maxResolution': formulation.max_resolution,
            'suppliedMesh': formulation.supplied_mesh,
            'missing': missing,
        }
    return report


def create(name, grid, options=None):
    """Build the named formulation on `grid`, or say what the choices were."""
    formulation = REGISTRY.get(name)
    if formulation is None:
        raise FormulationError(
            f'unknown formulation {name!r}; this worker offers ' + ', '.join(sorted(REGISTRY)))
    return formulation(grid, options)


__all__ = ['REGISTRY', 'DEFAULT', 'Formulation', 'FormulationError', 'available', 'create']
