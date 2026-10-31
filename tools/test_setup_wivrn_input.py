import tempfile
import json
from unittest.mock import patch
import unittest
from pathlib import Path

from tools.setup_wivrn_input import matching_nodes, rules, configure_private_serve


class WiVRnInputSetupTests(unittest.TestCase):
    def test_rule_grants_one_user_and_excludes_only_exact_virtual_devices_from_libinput(self):
        text = rules(1000)
        self.assertEqual(text.count('SUBSYSTEM=="input"'), 2)
        self.assertEqual(text.count('OWNER:="1000", MODE:="0600"'), 2)
        self.assertEqual(text.count('ENV{LIBINPUT_IGNORE_DEVICE}="1"'), 2)
        self.assertIn('ATTRS{name}=="WiVRn Keyboard"', text)
        self.assertIn('ATTRS{id/product}=="0839"', text)
        self.assertNotIn('GROUP=', text)
        with self.assertRaises(ValueError):
            rules(0)

    def test_existing_node_trigger_selection_checks_name_bus_vendor_and_product(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for index, (name, vendor, product, bus) in enumerate([
                ('WiVRn Keyboard', '4711', '0840', '0003'),
                ('WiVRn Mouse', '4711', '0839', '0003'),
                ('Physical keyboard', '4711', '0840', '0003'),
                ('WiVRn Mouse', '1234', '0839', '0003'),
                ('WiVRn Mouse', '4711', '0840', '0003'),
                ('WiVRn Mouse', '4711', '0839', '0005'),
            ]):
                device = root / f'event{index}' / 'device'
                (device / 'id').mkdir(parents=True)
                (device / 'name').write_text(name)
                for key, value in [('vendor', vendor), ('product', product), ('bustype', bus)]:
                    (device / 'id' / key).write_text(value)
            self.assertEqual({p.name for p in matching_nodes(root)}, {'event0', 'event1'})

    def test_private_serve_adds_a_port_without_changing_public_listener(self):
        before = {'TCP': {'443': {'HTTPS': True}}, 'Web': {'node.test:443': {'Handlers': {'/': {'Proxy': 'http://127.0.0.1:8792'}}}}, 'AllowFunnel': {'node.test:443': True}}
        after = json.loads(json.dumps(before))
        after['TCP']['8443'] = {'HTTPS': True}
        after['Web']['node.test:8443'] = {'Handlers': {'/': {'Proxy': 'https+insecure://localhost:9092'}}}
        with patch('tools.setup_wivrn_input.subprocess.check_output', side_effect=[json.dumps(before), json.dumps({'Self': {'DNSName': 'node.test.'}}), json.dumps(after)]), patch('tools.setup_wivrn_input.subprocess.run') as run:
            configure_private_serve(8443, 9092)
            run.assert_called_once_with(['tailscale', 'serve', '--bg', '--https=8443', '--yes', 'https+insecure://localhost:9092'], check=True)

    def test_private_serve_refuses_existing_listener_and_public_port(self):
        for before in [{'TCP': {'8443': {'HTTPS': True}}}, {'AllowFunnel': {'node.test:8443': True}}]:
            with patch('tools.setup_wivrn_input.subprocess.check_output', side_effect=[json.dumps(before), json.dumps({'Self': {'DNSName': 'node.test.'}})]), patch('tools.setup_wivrn_input.subprocess.run') as run:
                with self.assertRaises(RuntimeError):
                    configure_private_serve(8443, 9092)
                run.assert_not_called()
