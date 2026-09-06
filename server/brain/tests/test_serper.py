import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import httpx
from fastapi.testclient import TestClient

from brain.connectors import serper, websearch, llm
from brain.media import imagesearch
from brain.main import create_app


class Http:
    def __init__(self, body=None, status=200):
        self.response = httpx.Response(status, json=body)
        self.calls = []

    def post(self, url, **kwargs):
        self.calls.append((url, kwargs))
        return self.response


class SerperTest(unittest.TestCase):
    def test_image_contract_originals_then_thumbnail_no_duplicates(self):
        http = Http({'images': [
            {'title': 'Louvre', 'imageUrl': 'https://example.org/louvre.jpg',
             'thumbnailUrl': 'https://encrypted-tbn0.gstatic.com/image'},
            {'imageUrl': 'https://example.org/louvre.jpg'},
            {'imageUrl': 'javascript:alert(1)'},
            None]})
        result = imagesearch.get('serper').candidates(
            {'api_key': 'test-secret', 'country': 'be', 'language': 'fr'}, 'Louvre', http)
        self.assertEqual([r.url for r in result], ['https://example.org/louvre.jpg',
                                                  'https://encrypted-tbn0.gstatic.com/image'])
        url, call = http.calls[0]
        self.assertEqual(url, 'https://google.serper.dev/images')
        self.assertEqual(call['headers']['X-API-KEY'], 'test-secret')
        self.assertNotIn('test-secret', url)
        self.assertEqual(call['json']['gl'], 'be')
        self.assertEqual(call['json']['hl'], 'fr')
        self.assertEqual(call['json']['safe'], 'active')

    def test_web_contract_is_bounded(self):
        http = Http({'organic': [{'title': 'Entreprise', 'snippet': 'x' * 500,
                                'link': 'https://example.org'}] * 20})
        result = websearch.get('serper').search({'api_key': 'test'}, 'entreprise', http)
        self.assertEqual(len(result), websearch.MAX_RESULTS)
        self.assertEqual(len(result[0].snippet), websearch.MAX_SNIPPET_CHARS)
        self.assertEqual(http.calls[0][0], 'https://google.serper.dev/search')

    def test_failures_are_actionable_and_never_echo_provider_secrets(self):
        for status, error in [(401, serper.SerperAuthError), (403, serper.SerperAuthError),
                              (402, serper.SerperError), (429, serper.SerperError),
                              (500, serper.SerperError)]:
            with self.subTest(status=status), self.assertRaises(error) as caught:
                serper.query('images', {'api_key': 'test'}, 'x', Http({'error': 'SECRET'}, status))
            self.assertNotIn('SECRET', str(caught.exception))
        http = Http({})
        with self.assertRaises(serper.SerperAuthError):
            serper.query('images', {}, 'x', http)
        self.assertFalse(http.calls)
        with self.assertRaises(serper.SerperError):
            serper.query('images', {'api_key': 'test', 'country': 'France'}, 'x', http)
        self.assertFalse(http.calls)

    def test_malformed_results_and_no_results(self):
        for payload in [[], {'images': 'wrong'}, {'images': None}]:
            with self.assertRaises(imagesearch.ImageSearchUnavailable):
                imagesearch.get('serper').candidates({'api_key': 'test'}, 'x', Http(payload))
        with self.assertRaises(LookupError):
            imagesearch.get('serper').candidates({'api_key': 'test'}, 'x', Http({'images': []}))

    def test_selected_engine_does_not_silently_spend_a_different_key(self):
        settings = {'image_search': {'provider': 'serper', 'credentials': {'brave': {'api_key': 'test'}}}}
        self.assertEqual(websearch.configured(settings), (None, {}))
        settings['image_search']['credentials']['serper'] = {'api_key': 'serper-test'}
        self.assertEqual(websearch.configured(settings)[0].id, 'serper')
        settings['image_search']['provider'] = 'wikimedia'
        self.assertEqual(websearch.configured(settings)[0].id, 'brave')


class SerperIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.app = create_app(Path(self.tmp.name), preload_stt=False)
        self.client = TestClient(self.app)
        self.addCleanup(self.client.close)
        self.state = self.app.state.brain
        self.admin = {'Authorization': 'Bearer ' + self.state.tokens.admin_token}
        self.robot = {'Authorization': 'Bearer ' + self.state.tokens.pairing_token}
        response = self.client.put('/api/admin/connectors', headers=self.admin, json={
            'image_search': {'provider': 'serper', 'credentials': {
                'serper': {'api_key': 'serper-test-secret', 'language': 'fr', 'country': 'fr'}}}})
        self.assertEqual(response.status_code, 200)
        self.assertNotIn('serper-test-secret', response.text)
        entry = next(p for p in response.json()['image_search_available'] if p['id'] == 'serper')
        self.assertEqual([f['id'] for f in entry['credential_fields']], ['api_key', 'language', 'country'])

    def test_visit_research_uses_serper_without_saving_draft(self):
        before = self.state.settings.settings_path.read_bytes()
        with patch.object(websearch.SerperWebSearch, 'search', return_value=[
                websearch.SearchResult('Atelier', 'Artisanat', 'https://atelier.example')]) as search:
            response = self.client.post('/api/admin/hospitality/research', headers=self.admin,
                                        json={'company': 'Atelier'})
        self.assertEqual(response.status_code, 200, response.text)
        self.assertIn('Artisanat', response.json()['draft'])
        self.assertEqual(search.call_args.args[0]['api_key'], 'serper-test-secret')
        self.assertEqual(before, self.state.settings.settings_path.read_bytes())

    def test_preview_and_robot_share_search_and_admin_image_is_protected(self):
        image = imagesearch.FoundImage('https://example.org/image.jpg', 'Atelier', 'serper')
        jpeg = b'\xff\xd8\xff' + b'\x00' * 64
        from brain.storage import atomic_write
        atomic_write(self.state.images.root / 'abc123.jpg', jpeg)
        with patch.object(imagesearch.SerperImageSearch, 'candidates', return_value=[image]), \
                patch.object(self.state.images, 'fetch', return_value=('abc123', 'image/jpeg')):
            response = self.client.post('/api/admin/search/preview', headers=self.admin, json={'query': 'Atelier'})
            robot = self.client.get('/api/robot/image?q=Atelier', headers=self.robot)
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.json()['url'], '/api/admin/image/abc123')
        self.assertEqual(robot.json()['source'], 'serper')
        self.assertEqual(self.client.get(response.json()['url'], headers=self.admin).content, jpeg)
        self.assertEqual(self.client.get(response.json()['url'], headers=self.robot).status_code, 401)
        self.assertEqual(self.client.post('/api/admin/search/preview', headers=self.robot,
                                         json={'query': 'Atelier'}).status_code, 401)

    def test_search_answer_respects_real_connector_alternation(self):
        from brain.api.routes_robot import _answer_with_search
        import asyncio

        class Search:
            def search(self, credentials, query):
                return [websearch.SearchResult('Atelier', 'Artisanat', 'https://atelier.example')]

        async def answer(history):
            llm.validate_conversation('system', history)
            return 'Artisanat'

        result = asyncio.run(_answer_with_search(Search(), {}, 'Atelier',
                            [llm.Message('user', 'Quelle activité ?')], answer))
        self.assertEqual(result, 'Artisanat')
