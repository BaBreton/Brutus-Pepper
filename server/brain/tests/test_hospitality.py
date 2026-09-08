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

    def fiche(self, **champs):
        base = {"id": "", "label": "Fiche", "company": "", "mission": "", "visitors": [],
                "notes": "", "places": [], "idle_media": "", "greeting": "",
                "greeting_pool": [], "greeting_point": "", "active_visit": None}
        base.update(champs)
        return base

    def save(self, active=True, profiles=None, active_profile=None, **champs):
        """Enregistre une fiche unique. Ce qui est enregistré est ce qui sert."""
        if profiles is None:
            profiles = [self.fiche(**champs)]
        corps = {"active": active, "profiles": profiles}
        if active_profile is not None:
            corps["active_profile"] = active_profile
        return self.client.put('/api/admin/hospitality', headers=self.headers, json=corps)

    def research(self, **body):
        return self.client.post('/api/admin/hospitality/research', headers=self.headers,
                                json={"company": "Atelier Client", **body})

    def accroche_robot(self):
        return self.client.get(
            '/api/robot/greeting',
            headers={"Authorization": "Bearer " + self.state.tokens.pairing_token}).json()

    def test_une_fiche_recoit_un_identifiant_et_un_nom(self):
        rendu = self.save(company="Le Comptoir", label="")
        self.assertEqual(rendu.status_code, 200)
        fiche = rendu.json()["profiles"][0]
        self.assertTrue(fiche["id"])
        # Sans nom saisi, l'entreprise sert d'etiquette plutot qu'un champ vide.
        self.assertEqual(fiche["label"], "Le Comptoir")
        self.assertEqual(rendu.json()["active_profile"], fiche["id"])

    def test_plusieurs_fiches_coexistent_et_une_seule_sert(self):
        rendu = self.save(profiles=[self.fiche(label="Hall", company="Hall",
                                               greeting="Bienvenue au hall."),
                                    self.fiche(label="Atelier", company="Atelier",
                                               greeting="Bienvenue a l'atelier.")])
        fiches = rendu.json()["profiles"]
        self.assertEqual([f["label"] for f in fiches], ["Hall", "Atelier"])
        # Par defaut la premiere ; l'accroche du robot suit la fiche en service.
        self.assertEqual(self.accroche_robot()["speech"], "Bienvenue au hall.")

        bascule = self.client.put('/api/admin/hospitality', headers=self.headers, json={
            "active": True, "active_profile": fiches[1]["id"],
            "profiles": fiches})
        self.assertEqual(bascule.status_code, 200)
        self.assertEqual(self.accroche_robot()["speech"], "Bienvenue a l'atelier.")

    def test_pepper_dit_exactement_la_zone_de_texte(self):
        self.save(company="La Fabrique", greeting="Bonjour ! Ici La Fabrique.")
        self.assertEqual(self.accroche_robot()["speech"], "Bonjour ! Ici La Fabrique.")

    def test_une_fiche_sans_phrase_laisse_la_tablette_a_son_accueil_habituel(self):
        # Plus de redaction de derniere minute : si la zone est vide, il n'y a rien
        # a dire de particulier et la tablette reprend ses formules.
        self.save(company="La Fabrique", greeting="")
        self.assertEqual(self.accroche_robot()["speech"], "")

    def test_l_identifiant_de_fiche_inconnu_retombe_sur_la_premiere(self):
        rendu = self.save(profiles=[self.fiche(label="A", greeting="Phrase A")],
                          active_profile="fiche-qui-n-existe-pas")
        self.assertEqual(rendu.json()["active_profile"], rendu.json()["profiles"][0]["id"])
        self.assertEqual(self.accroche_robot()["speech"], "Phrase A")

    def test_activer_sans_aucune_fiche_est_refuse(self):
        refus = self.client.put('/api/admin/hospitality', headers=self.headers,
                                json={"active": True, "profiles": []})
        self.assertEqual(refus.status_code, 422)
        self.assertIn("fiche", refus.json()["detail"])

    def test_une_ancienne_installation_a_plat_devient_une_fiche(self):
        from brain import hospitality as h

        ancien = {"active": True, "company": "Ancien Client", "greeting": "Bonjour !",
                  "places": [], "idle_media": "", "greeting_point": "left"}
        repris = h.normalise(ancien)
        self.assertEqual(len(repris["profiles"]), 1)
        self.assertEqual(repris["profiles"][0]["label"], "Ancien Client")
        self.assertEqual(repris["active_profile"], "reprise")
        self.assertEqual(h.active_card(ancien)["greeting"], "Bonjour !")
        # Une liste deja presente fait autorite : on ne reprend pas deux fois.
        deja = {"active": True, "company": "Ancien", "profiles": [{"id": "x", "label": "X"}]}
        self.assertEqual(len(h.normalise(deja)["profiles"]), 1)
        self.assertEqual(h.normalise(deja)["profiles"][0]["id"], "x")

    def test_le_geste_suit_la_fiche_en_service(self):
        lieux = [{"name": "la salle du conseil", "directions": "au fond a droite",
                  "point_direction": "right"}]
        self.save(places=lieux, greeting="Bienvenue ! La salle du conseil vous attend.")
        self.assertEqual(self.accroche_robot()["actions"][0]["name"], "point_right")

    def test_un_cote_choisi_l_emporte_sur_le_lieu_nomme(self):
        lieux = [{"name": "la cuisine", "directions": "a gauche", "point_direction": "left"}]
        self.save(places=lieux, greeting_point="right",
                  greeting="Bienvenue, la cuisine est par la.")
        self.assertEqual(self.accroche_robot()["actions"][0]["name"], "point_right")

    def test_un_cote_choisi_marche_sans_aucun_lieu_declare(self):
        self.save(greeting_point="left", greeting="Bienvenue ! L'accueil est sur votre gauche.")
        self.assertEqual(self.accroche_robot()["actions"][0]["name"], "point_left")

    def test_une_accroche_sans_lieu_ne_declenche_aucun_geste(self):
        lieux = [{"name": "la cuisine", "directions": "a gauche", "point_direction": "left"}]
        self.save(places=lieux, greeting="Bonjour et bienvenue !")
        self.assertEqual(self.accroche_robot()["actions"], [])

    def test_l_image_de_la_fiche_est_servie_a_la_tablette(self):
        from brain.media.library import MediaLibrary

        item = MediaLibrary(Path(self.tmp.name)).add_bytes(
            "Hall", "image/png", b"\x89PNG\r\n\x1a\n" + b"\x00" * 64)
        self.save(idle_media=item["id"])
        robot = self.client.get('/api/robot/idle-image',
                                headers={"Authorization": "Bearer " + self.state.tokens.pairing_token})
        self.assertEqual(robot.json()["id"], item["id"])

    def test_une_image_disparue_est_refusee_a_l_enregistrement(self):
        refus = self.save(idle_media="disparu", label="Hall")
        self.assertEqual(refus.status_code, 422)
        self.assertIn("médiathèque", refus.json()["detail"])

    def test_les_propositions_d_accroche_ne_touchent_a_rien(self):
        self.save(company="Le Comptoir", greeting="Phrase enregistree.")
        rendu = self.client.post('/api/admin/hospitality/greetings', headers=self.headers,
                                 json={"profile": self.fiche(company="Le Comptoir"), "count": 3})
        self.assertEqual(rendu.status_code, 200)
        # Sans modele configure, on rend au moins une phrase composee sans lui.
        self.assertFalse(rendu.json()["generated"])
        self.assertTrue(rendu.json()["greetings"][0])
        # Les reglages enregistres n'ont pas bouge.
        self.assertEqual(self.accroche_robot()["speech"], "Phrase enregistree.")

    def test_les_variantes_sont_decoupees_ligne_par_ligne(self):
        from brain import greeting as g

        class Modele:
            def complete(self, **kwargs):
                return "1. Bonjour et bienvenue !\n- Bienvenue chez nous !\n\nRavi de vous voir."

        propositions = g.variants({"company": "X"}, Modele(), credentials={}, model="m", count=5)
        self.assertEqual(propositions,
                         ["Bonjour et bienvenue !", "Bienvenue chez nous !", "Ravi de vous voir."])

    def test_un_modele_en_panne_ne_leve_pas(self):
        from brain import greeting as g
        from brain.connectors import llm as connecteurs

        class Modele:
            def complete(self, **kwargs):
                raise connecteurs.LlmTimeout("trop lent")

        self.assertEqual(g.variants({}, Modele(), credentials={}, model="m"), [])

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
        saved = self.save(active=True, active_visit={"company": "Client", "brief": "Artisanat"})
        self.assertEqual(saved.status_code, 200)
        self.assertEqual(saved.json()['profiles'][0]['active_visit']['brief'], 'Artisanat')

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

    def test_l_apercu_n_appelle_aucun_fournisseur_et_n_ecrit_rien(self):
        fiche = self.fiche(company='Hôte', notes='Secret budget 9000',
                           active_visit={'company': 'Client', 'visitors': ['Camille']})
        preview = self.client.post('/api/admin/hospitality/preview', headers=self.headers,
                                   json={'profile': fiche})
        self.assertEqual(preview.status_code, 200)
        self.assertFalse(self.state.settings.settings_path.exists())
        # Ni les noms attendus ni les notes privées ne sortent dans l'accroche.
        self.assertNotIn('Camille', preview.json()['greeting'])
        self.assertNotIn('9000', preview.json()['greeting'])

    def test_profile_only_in_active_prompt_and_private_notes_never_sent(self):
        hospitality = {'active': True, 'notes': 'Secret budget 9000',
                       'active_visit': {'company': 'Client', 'objective': 'Démo produit',
                                        'brief': 'Entreprise artisanale'}}
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
        self.assertEqual(saved.json()['profiles'][0]['places'][0]['point_direction'], 'right')
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
