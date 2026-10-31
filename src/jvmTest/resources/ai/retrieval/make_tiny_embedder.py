"""A tiny BERT-shaped embedder for OnnxSemanticEmbedder's tests: input_ids, attention_mask [B,L] int64 ->
last_hidden_state [B,L,4] float, where each token's vector is a fixed embedding row (Gather), so the mean
pool of a text is known exactly."""
import sys, onnx
from onnx import helper, TensorProto, numpy_helper
import numpy as np
table = np.array([[0,0,0,0],[1,0,0,0],[0,1,0,0],[0,0,1,0],[0,0,0,1],[1,1,0,0],[0,0,1,1],[1,0,1,0]], dtype=np.float32)
ids = helper.make_tensor_value_info("input_ids", TensorProto.INT64, ["batch_size", "sequence_length"])
mask = helper.make_tensor_value_info("attention_mask", TensorProto.INT64, ["batch_size", "sequence_length"])
out = helper.make_tensor_value_info("last_hidden_state", TensorProto.FLOAT, ["batch_size", "sequence_length", 4])
graph = helper.make_graph([helper.make_node("Gather", ["table", "input_ids"], ["last_hidden_state"], axis=0)],
                          "tiny_embedder", [ids, mask], [out], [numpy_helper.from_array(table, "table")])
model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 13)], producer_name="afield-test")
model.ir_version = 8
onnx.checker.check_model(model)
onnx.save(model, sys.argv[1])
