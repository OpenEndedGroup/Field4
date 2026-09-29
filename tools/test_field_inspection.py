import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from field_inspection import export_review_evidence, inspection_script

class ReviewExportTest(unittest.TestCase):
    def test_export_preserves_reference_and_checks_image_identity(self):
        png = b'\x89PNG\r\n\x1a\nfixture'
        digest = hashlib.sha256(png).hexdigest()
        note = dict(id='annotation', evidence={kind: dict(sha256=digest) for kind in ('context', 'crop')})
        inbox = dict(notes=[dict(id='older-note'), note])
        with tempfile.TemporaryDirectory() as directory:
            files = export_review_evidence(inbox, directory, lambda _: png)
            self.assertEqual(len(files), 1)
            self.assertEqual(json.loads(Path(files[0]['note']).read_text()), note)
            self.assertEqual(Path(files[0]['crop']).read_bytes(), png)
            with self.assertRaises(ValueError):
                export_review_evidence(inbox, directory, lambda _: b'wrong image')
            note['evidence']['crop']['sha256'] = '../secret'
            with self.assertRaises(ValueError):
                export_review_evidence(inbox, directory, lambda _: png)

    def test_script_keeps_manual_publish_disabled(self):
        script = inspection_script('view', pick=dict(x=.2, y=.3, kind='point'))
        self.assertIn('api.pick(request.pick.x,request.pick.y,request.pick.kind', script)
        self.assertNotIn('publish:true', script)


class ReviewReplyClientTest(unittest.IsolatedAsyncioTestCase):
    async def test_reply_uses_bounded_note_api_and_read_only_sessions_cannot_write(self):
        from field_inspection import reply_review_note
        from types import SimpleNamespace
        from unittest.mock import patch
        import io
        captured=[]
        def open_request(request, **kwargs):
            captured.append(request)
            return io.BytesIO(b'{"ok":true,"note":{"version":3}}')
        args=dict(note_id='note-id',text='Persistent finding',actor='test-agent',checked_revision='r',expected_version=2)
        with patch('urllib.request.urlopen',side_effect=open_request):
            answer=await reply_review_note(SimpleNamespace(port=9811,read_only=False),'view',**args)
            self.assertTrue(answer['ok'])
            self.assertEqual(json.loads(captured[0].data)['reply'],'Persistent finding')
            self.assertEqual(captured[0].method,'POST')
            self.assertIn('/three-review-note?',captured[0].full_url)
            answer=await reply_review_note(SimpleNamespace(port=9811,read_only=True),'view',**args)
            self.assertFalse(answer['ok'])
            self.assertEqual(len(captured),1)
