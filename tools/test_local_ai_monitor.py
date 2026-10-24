"""Hardware failure must not mask model activity, and monitoring must not wake inference."""
import json
import unittest
from unittest.mock import patch

from tools.local_ai_monitor import Sampler, gpu_stats, parse_metrics


class LocalAiMonitorTest(unittest.TestCase):
    def test_gpu_values_include_missing_fields_and_multiple_devices(self):
        from subprocess import CompletedProcess
        output = '0, NVIDIA RTX 4090, 63, 98, 22000, 24564\n1, Other GPU, [N/A], 0, 100, 8000\n'
        with patch('tools.local_ai_monitor.subprocess.run', return_value=CompletedProcess([], 0, output)):
            gpus = gpu_stats()
        self.assertEqual(len(gpus), 2)
        self.assertEqual(gpus[0]['utilizationPercent'], 98)
        self.assertEqual(gpus[0]['memoryTotalMiB'], 24564)
        self.assertIsNone(gpus[1]['temperatureC'])

    def test_sleep_sampling_uses_only_endpoints_that_do_not_wake_model(self):
        sampler = Sampler('http://gpu-host:8081')
        paths = []
        def read(path):
            paths.append(path)
            return json.dumps({'is_sleeping': True, 'default_generation_settings': {'n_ctx': 131072}}) if path == '/props' else 'llamacpp:requests_processing 0'
        with patch('tools.local_ai_monitor.gpu_stats', side_effect=FileNotFoundError), patch.object(sampler, 'read', read):
            sampler.sample()
        self.assertEqual(paths, ['/props', '/metrics'])
        self.assertEqual(sampler.snapshot['state'], 'sleeping')
        self.assertTrue(sampler.snapshot['gpuUnavailable'])
        self.assertEqual(sampler.snapshot['contextCapacity'], 131072)

    def test_generation_rate_is_current_counter_delta_not_old_average(self):
        sampler = Sampler('http://gpu-host:8081')
        def read(path):
            return '{}' if path == '/props' else 'llamacpp:requests_processing 1\nllamacpp:requests_deferred 2\nllamacpp:tokens_predicted_total 180\nllamacpp:predicted_tokens_seconds 999'
        sampler.previous = (8, {'tokens_predicted_total': 100})
        with patch('tools.local_ai_monitor.gpu_stats', return_value=[]), patch.object(sampler, 'read', read), patch('tools.local_ai_monitor.time.monotonic', return_value=10):
            sampler.sample()
        self.assertEqual(sampler.snapshot['state'], 'generating')
        self.assertEqual(sampler.snapshot['generationTokensPerSecond'], 40)
        self.assertEqual(sampler.snapshot['requestsQueued'], 2)

    def test_backend_down_keeps_hardware_readings(self):
        sampler = Sampler('http://gpu-host:8081')
        with patch('tools.local_ai_monitor.gpu_stats', return_value=[{'index': '0'}]), patch.object(sampler, 'read', side_effect=OSError):
            sampler.sample()
        self.assertEqual(sampler.snapshot['state'], 'unavailable')
        self.assertEqual(sampler.snapshot['gpus'], [{'index': '0'}])

    def test_loading_is_distinct_from_disconnected(self):
        from urllib.error import HTTPError
        from io import BytesIO
        sampler = Sampler('http://gpu-host:8081')
        error = HTTPError('http://gpu-host:8081/props', 503, 'Unavailable', {}, BytesIO(b'{"error":{"message":"Loading model"}}'))
        with patch('tools.local_ai_monitor.gpu_stats', return_value=[]), patch.object(sampler, 'read', side_effect=[error, OSError()]):
            sampler.sample()
        self.assertEqual(sampler.snapshot['state'], 'loading')

    def test_metrics_ignore_comments_labels_and_invalid_values(self):
        self.assertEqual(parse_metrics('# HELP ignored\nother 1\nllamacpp:label{x="0"} 1\nllamacpp:bad nope\nllamacpp:requests_processing 1'), {'requests_processing': 1})
