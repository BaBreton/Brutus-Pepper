"""Visit preparation: isolated stores and mocked providers, never billable calls."""
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

from brain import hospitality
from brain.main import create_app
from brain.prompt import build_system_prompt
from brain.connectors.websearch import SearchResult


class VisitApiTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.app = create_app(Path(self.tmp.name), preload_stt=False)
        self.state = self.app.state.brain
        self.client = TestClient(self.app)
        self.headers = {"Authorization": "Bearer " + self.state.tokens.admin_token}

    def tearDown(self):
        self.client.close()
        self.tmp.cleanup()

    def research(self, **body):
        return self.client.post('/api/admin/hospitality/research', headers=self.headers,
                                json={"company": "Atelier Client", **body})

    def save(self, **body):
        return self.client.put('/api/admin/hospitality', headers=self.headers, json=body)

    def test_new_routes_require_admin_not_pairing(self):
        for route in ('research', 'preview'):
            for headers in ({}, {"Authorization": "Bearer " + self.state.tokens.pairing_token}):
                result = self.client.post('/api/admin/hospitality/' + route,
                                          headers=headers, json={"company": "Client"})
                self.assertEqual(result.status_code, 401)

    def test_research_without_key_is_actionable_and_manual_save_works(self):
        result = self.research()
        self.assertEqual(result.status_code, 503)
        self.assertIn('Brave', result.json()['detail'])
        saved = self.save(active=True, active_visit={"company": "Client", "brief": "Artisanat",
                                                    "validated": True})
        self.assertEqual(saved.status_code, 200)
        self.assertEqual(saved.json()['active_visit']['brief'], 'Artisanat')

    def test_research_uses_registry_bounded_safe_sources_and_never_saves(self):
        self.state.settings.update_section('image_search', {'credentials': {'brave': {'api_key': 'test-key'}}})
        self.save(company='Hôte', active=False)
        before = self.state.settings.settings_path.read_bytes()
        results = [SearchResult('Atelier', 'Fabrication locale', 'https://atelier.example/about'),
                   SearchResult('Unsafe', 'ignored', 'javascript:alert(1)')]
        with patch('brain.connectors.websearch.BraveWebSearch.search', return_value=results) as search:
            result = self.research(domain='https://atelier.example/')
        self.assertEqual(result.status_code, 200, result.text)
        body = result.json()
        self.assertIn('Fabrication locale', body['draft'])
        self.assertEqual(len(body['sources']), 1)
        self.assertFalse(body['validated'])
        self.assertIn('site:atelier.example', search.call_args.args[1])
        self.assertEqual(before, self.state.settings.settings_path.read_bytes())

    def test_invalid_domains_rejected_before_search(self):
        for domain in ('http://127.0.0.1', 'localhost', 'http://10.0.0.1', 'a.example/path',
                       'https://user:pass@a.example', 'file:///etc/passwd', 'a.example?x=1',
                       'a.example:8443', 'bad domain.com', 'https://[::1]'):
            with self.subTest(domain=domain):
                self.assertEqual(self.research(domain=domain).status_code, 422)

    def test_invalid_profile_does_not_write(self):
        for profile in ({'company': 'C', 'sources': ['javascript:alert(1)']},
                        {'company': 'C', 'brief': 'x' * 6001},
                        {'company': 'C', 'visitors': ['x'] * 31}):
            self.assertEqual(self.save(active_visit=profile).status_code, 422)
        self.assertFalse(self.state.settings.settings_path.exists())

    def test_activation_requires_validation_and_company(self):
        for profile in ({'company': 'C', 'validated': False}, {'company': '', 'validated': True}):
            self.assertEqual(self.save(active=True, active_visit=profile).status_code, 422)

    def test_old_settings_defaults_and_legacy_updates_keep_visit(self):
        self.state.settings.save({'hospitality': {'company': 'Hôte', 'notes': 'Note privée'}})
        self.assertIsNone(self.state.settings.load()['hospitality']['active_visit'])
        result = self.save(active_visit={'company': 'Client', 'validated': True})
        self.assertEqual(result.status_code, 200)
        result = self.save(active=True)
        self.assertEqual(result.json()['active_visit']['company'], 'Client')
        self.assertEqual(result.json()['company'], 'Hôte')
        self.assertEqual(result.json()['notes'], 'Note privée')

    def test_preview_and_saved_visit_greeting_match_without_llm_or_identity_assumption(self):
        body = {'active': True, 'company': 'Hôte', 'notes': 'Secret budget 9000',
                'active_visit': {'company': 'Client', 'visitors': ['Camille'], 'validated': True}}
        with patch('brain.api.routes_admin._compose_greeting', side_effect=AssertionError('no LLM')):
            preview = self.client.post('/api/admin/hospitality/preview', headers=self.headers, json=body)
            self.assertEqual(preview.status_code, 200)
            self.assertFalse(self.state.settings.settings_path.exists())
            saved = self.save(**body)
        self.assertEqual(saved.json()['greeting'], preview.json()['greeting'])
        self.assertNotIn('Camille', saved.json()['greeting'])
        self.assertNotIn('9000', saved.json()['greeting'])

    def test_profile_only_in_active_prompt_and_private_notes_never_sent(self):
        hospitality = {'active': True, 'notes': 'Secret budget 9000',
                       'active_visit': {'company': 'Client', 'objective': 'Démo produit',
                                        'brief': 'Entreprise artisanale', 'validated': True}}
        prompt = build_system_prompt([], hospitality)
        self.assertIn('Entreprise artisanale', prompt)
        self.assertIn('Démo produit', prompt)
        self.assertNotIn('9000', prompt)
        self.assertIn('identité', prompt)
        self.assertIn('non fiables', prompt)
        hospitality['active'] = False
        self.assertNotIn('Entreprise artisanale', build_system_prompt([], hospitality))

    def test_place_point_direction_round_trips_and_rejects_unknown_values(self):
        saved = self.save(active=False, places=[{
            'name': 'Cuisine', 'directions': 'au fond du couloir', 'point_direction': 'right'
        }])
        self.assertEqual(saved.status_code, 200, saved.text)
        self.assertEqual(saved.json()['places'][0]['point_direction'], 'right')
        invalid = self.save(active=False, places=[{
            'name': 'Cuisine', 'directions': 'au fond', 'point_direction': 'behind'
        }])
        self.assertEqual(invalid.status_code, 422)

    def test_location_answer_gets_the_configured_pointing_action(self):
        response = hospitality.add_pointing_action(
            'La cuisine est au fond du couloir.',
            'Où est la cuisine ?',
            [{'name': 'Cuisine', 'directions': 'au fond du couloir', 'point_direction': 'right'}],
        )
        payload = json.loads(response)
        self.assertEqual(payload['speech'], 'La cuisine est au fond du couloir.')
        self.assertEqual(payload['actions'][0]['name'], 'point_right')

    def test_pointing_does_not_trigger_for_an_ordinary_mention(self):
        response = hospitality.add_pointing_action(
            'La cuisine est agréable.',
            'J’aime cuisiner.',
            [{'name': 'Cuisine', 'directions': 'au fond', 'point_direction': 'left'}],
        )
        self.assertEqual(response, 'La cuisine est agréable.')
