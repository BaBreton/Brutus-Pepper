import unittest
from datetime import datetime

from brain.prompt import build_system_prompt


class PromptTest(unittest.TestCase):
    def test_contains_date_time_media_and_every_action(self):
        moment = datetime(2026, 7, 27, 14, 5)
        prompt = build_system_prompt(
            catalog=[{"name": "Accueil été", "kind": "video"}],
            hospitality={"active": True, "company": "Recepta",
                         "visitors": ["Jean Dupont"], "notes": "Réunion 15h"},
            now=moment,
        )
        self.assertIn("lundi 27 juillet 2026", prompt)
        self.assertIn("14:05", prompt)
        self.assertIn("Accueil été", prompt)
        self.assertIn("Recepta", prompt)
        self.assertIn("Jean Dupont", prompt)
        for action in ("turn_around", "point_right", "point_left",
                       "display_text", "display_media", "display_image",
                       "play_sequence", "show_kiosk"):
            self.assertIn(action, prompt)

    def test_omits_the_hospitality_block_when_empty(self):
        prompt = build_system_prompt(catalog=[], hospitality={}, now=datetime(2026, 7, 27, 9, 0))
        self.assertNotIn("Contexte du lieu", prompt)
        self.assertIn("aucun", prompt)

    def test_hospitality_block_appears_with_any_single_field(self):
        for hospitality in ({"company": "Recepta"},
                            {"visitors": ["Jean"]},
                            {"notes": "Réunion 15h"}):
            prompt = build_system_prompt(catalog=[], hospitality={"active": True, **hospitality},
                                         now=datetime(2026, 7, 27, 9, 0))
            self.assertIn("Contexte du lieu", prompt)

    def test_blank_hospitality_fields_are_ignored(self):
        # Une webapp envoie volontiers des chaînes vides plutôt que des champs absents.
        prompt = build_system_prompt(
            catalog=[],
            hospitality={"active": True, "company": "   ", "visitors": ["", "  "], "notes": ""},
            now=datetime(2026, 7, 27, 9, 0),
        )
        self.assertNotIn("Contexte du lieu", prompt)

    def test_the_switch_off_puts_the_robot_back_to_normal(self):
        # L'opérateur coupe l'hospitalité sans effacer ce qu'il a saisi : Pepper doit
        # redevenir un robot d'accueil ordinaire, et retrouver ce contexte à la réactivation.
        filled = {"company": "Recepta", "mission": "envoie-les vers la cuisine",
                  "places": [{"name": "cuisine", "directions": "au fond à votre droite"}]}
        off = build_system_prompt(catalog=[], hospitality={"active": False, **filled},
                                  now=datetime(2026, 7, 27, 9, 0))
        self.assertNotIn("Contexte du lieu", off)
        self.assertNotIn("cuisine", off)
        on = build_system_prompt(catalog=[], hospitality={"active": True, **filled},
                                 now=datetime(2026, 7, 27, 9, 0))
        self.assertIn("cuisine", on)


class HospitalityPlacesTest(unittest.TestCase):
    """Les lieux décrits par l'opérateur : « la cuisine, au fond à votre droite »."""

    def _prompt(self, **hospitality) -> str:
        return build_system_prompt(catalog=[], hospitality={"active": True, **hospitality},
                                   now=datetime(2026, 7, 27, 9, 0))

    def test_states_each_place_with_its_directions(self):
        prompt = self._prompt(places=[
            {"name": "cuisine", "directions": "au fond à votre droite"},
            {"name": "salle Ariane", "directions": "premier étage, à gauche en sortant de l'ascenseur"},
        ])
        self.assertIn("cuisine", prompt)
        self.assertIn("au fond à votre droite", prompt)
        self.assertIn("salle Ariane", prompt)

    def test_forbids_inventing_a_place(self):
        # Un robot d'accueil qui invente une direction envoie un visiteur au mauvais endroit.
        self.assertIn("N'invente jamais",
                      self._prompt(places=[{"name": "cuisine", "directions": "à droite"}]))

    def test_a_place_without_directions_is_dropped(self):
        # Nommer un lieu sans savoir l'indiquer invite le modèle à combler le vide.
        prompt = self._prompt(places=[{"name": "cuisine", "directions": "   "},
                                      {"name": "accueil", "directions": "devant vous"}])
        self.assertNotIn("cuisine", prompt)
        self.assertIn("accueil", prompt)

    def test_a_place_without_a_name_is_dropped(self):
        self.assertNotIn("au fond", self._prompt(places=[{"name": "", "directions": "au fond"}]))

    def test_survives_a_malformed_place(self):
        # La liste vient d'une webapp : elle peut arriver déformée sans faire tomber le robot.
        prompt = self._prompt(places=["cuisine", None, {"name": "accueil",
                                                        "directions": "devant vous"}])
        self.assertIn("devant vous", prompt)

    def test_carries_the_operator_mission(self):
        prompt = self._prompt(mission="Accueille les clients et envoie-les vers la cuisine")
        self.assertIn("Accueille les clients et envoie-les vers la cuisine", prompt)

    def test_says_plainly_that_the_context_is_an_addition_not_the_subject(self):
        # Signalé à l'usage : le modèle ne parlait plus que du contexte d'hospitalité et
        # y ramenait tout. Le bloc doit dire explicitement que c'est un complément.
        prompt = self._prompt(
            company="Maison Lafontaine",
            mission="accueille les invités et indique-leur le salon",
            places=[{"name": "le salon", "directions": "première porte à gauche"}],
        )
        lowered = prompt.lower()
        self.assertIn("complément", lowered)
        self.assertIn("ne ramène pas", lowered)

    def test_the_mission_is_scoped_to_the_greeting_not_the_whole_conversation(self):
        # « Ta mission d'accueil » se lisait comme la seule chose à faire. La consigne
        # doit être rattachée à la façon d'aborder les gens, pas au reste de l'échange.
        prompt = self._prompt(mission="envoie-les vers la cuisine")
        self.assertNotIn("Ta mission d'accueil", prompt)
        self.assertIn("aborder", prompt.lower())

    def test_places_can_include_a_pointing_side(self):
        prompt = self._prompt(places=[{
            "name": "cuisine", "directions": "au fond du couloir", "point_direction": "right"
        }])
        self.assertIn("pointage: droite", prompt.lower())
        self.assertIn("automatiquement", prompt.lower())

    def test_every_weekday_and_month_renders(self):
        # Une erreur d'indice sur les tables françaises ne se verrait qu'un jour donné.
        for month in range(1, 13):
            prompt = build_system_prompt(catalog=[], hospitality={},
                                         now=datetime(2026, month, 15, 9, 0))
            self.assertNotIn("IndexError", prompt)
            self.assertRegex(prompt, r"Date et heure : \w+ 15 \w+ 2026")
