"""Transport regressions: pending stripes are not completion; pictures are not text."""
import asyncio
import sys
import unittest
from pathlib import Path
from unittest.mock import AsyncMock
sys.path.insert(0, str(Path(__file__).resolve().parent))
from field_backdoor import FieldBackdoor, BackdoorError
from field_mcp import FieldMcpServer

class Logger:
    session_id = 'receipts-test'
    path = Path('/tmp/receipts-test')
    def log(self, *args, **kwargs): pass

class ReceiptTests(unittest.IsolatedAsyncioTestCase):
    async def test_pending_stripe_and_another_request_cannot_complete_launch(self):
        client = FieldBackdoor(port=1, workspace=None, default_timeout=.05, logger=Logger())
        client.execution_verdicts = True
        client.boxes['box'] = {'uid':'box','code':'broken()', 'runningStrands':{}}
        counter = 0
        def emit(name, payload):
            nonlocal counter
            counter += 1
            client.event_seq = counter
            client.event_log.append({'seq':counter,'name':name,'uid':'box','payload':payload})
        async def send(name,payload,**kw):
            emit('is-running', {'uid':'box','isRunning':True,'mode':'launching'})
            emit('execution-verdict', {'uid':'box','requestId':'somebody-else','success':True})
        client.send_message = send
        client.get_property = AsyncMock(return_value='broken()')
        data, events, timeout = await client.launch_box('box', property_name='code', value=None, timeout=.02)
        self.assertTrue(timeout)
        self.assertIsNone(data['verdict'])

    async def test_failed_receipt_is_returned_without_waiting_for_running(self):
        client = FieldBackdoor(port=1, workspace=None, default_timeout=.05, logger=Logger())
        client.execution_verdicts = True
        client.boxes['box'] = {'uid':'box','code':'broken()', 'runningStrands':{}}
        async def send(name,payload,**kw):
            client.event_seq = 1
            client.event_log.append({'seq':1,'name':'execution-verdict','uid':'box','payload':{
                'uid':'box','requestId':payload['requestId'],'success':False,'error':'compiler failed'}})
        client.send_message = send
        client.get_property = AsyncMock(return_value='broken()')
        data, events, timeout = await client.launch_box('box', property_name='code', value=None, timeout=.02)
        self.assertFalse(timeout)
        self.assertFalse(data['verdict']['success'])
        self.assertFalse(data['running'])

    async def test_execution_ignores_stale_cached_source_after_a_tool_patch(self):
        client = FieldBackdoor(port=1, workspace=None, default_timeout=.05, logger=Logger())
        cached = {'uid': 'box', 'code': 'old source'}
        client.get_property = AsyncMock(return_value='new live source')
        source = await client.execution_code('box', cached, 'code', .05)
        self.assertEqual(source, 'new live source')
        client.get_property.assert_awaited_once()

    async def test_mcp_forwards_images_as_images_and_tool_failure_as_failure(self):
        server = FieldMcpServer.__new__(FieldMcpServer)
        server.timeout=1
        server._ai_name_map={'field_capture':'CaptureRenderWindowRequest'}
        client=type('Client',(),{})()
        client.ai_tool_call=AsyncMock(return_value={'ok':True,'result':{'success':False,'message':'partial'},
            'images':[{'dataUrl':'data:image/png;base64,aGVsbG8=','caption':'Observed frame'}]})
        server._ensure_attached=AsyncMock(return_value=client)
        result=(await server._call_ai_tool(1,'field_capture',{}))['result']
        self.assertTrue(result['isError'])
        self.assertEqual(result['content'][-1],{'type':'image','mimeType':'image/png','data':'aGVsbG8='})
        self.assertNotIn('base64',result['content'][0]['text'])

if __name__=='__main__': unittest.main()
