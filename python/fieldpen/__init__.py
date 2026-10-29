"""fieldpen: an LSTM pen-trajectory model you can feed, continue, fork and retrain.

Inference also runs in Kotlin (`afield.ai.learning.pen`) without torch; this package is the
optional half for the browser lab, training and fine-tuning. See `README.md`.
"""
from .model import HandwritingModel, Config, load_model, save_model, export_for_field
from .session import Session, Controls
