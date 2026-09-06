import inspect
import unittest

from brain.connectors import llm


class RegistryTest(unittest.TestCase):
    def test_catalog_reports_configured_state_per_connector(self):
        credentials = {"anthropic": {"api_key": "sk-ant-x"}}
        catalog = llm.catalog(credentials)
        by_id = {entry["id"]: entry for entry in catalog}
        self.assertTrue(by_id["anthropic"]["configured"])
        self.assertFalse(by_id["openai"]["configured"])
        self.assertTrue(by_id["anthropic"]["models"])

    def test_get_raises_for_unknown_connector(self):
        with self.assertRaises(KeyError):
            llm.get("nope")

    def test_catalog_tolerates_a_null_credentials_value(self):
        # Un réglage effacé côté webapp, ou une migration, produit un JSON
        # `null` plutôt qu'une clé absente : catalog() ne doit pas planter.
        catalog = llm.catalog({"anthropic": None})
        by_id = {entry["id"]: entry for entry in catalog}
        self.assertFalse(by_id["anthropic"]["configured"])


class ValidateConversationTest(unittest.TestCase):
    def test_accepts_a_valid_alternating_conversation(self):
        llm.validate_conversation(
            "s",
            [
                llm.Message("user", "a"),
                llm.Message("assistant", "b"),
                llm.Message("user", "c"),
            ],
        )

    def test_rejects_an_empty_system(self):
        with self.assertRaises(llm.LlmError):
            llm.validate_conversation("   ", [llm.Message("user", "hi")])

    def test_rejects_an_empty_message_list(self):
        with self.assertRaises(llm.LlmError):
            llm.validate_conversation("s", [])

    def test_rejects_a_conversation_that_does_not_start_with_user(self):
        with self.assertRaises(llm.LlmError):
            llm.validate_conversation("s", [llm.Message("assistant", "hi")])

    def test_rejects_non_alternating_roles(self):
        with self.assertRaises(llm.LlmError):
            llm.validate_conversation(
                "s", [llm.Message("user", "a"), llm.Message("user", "b")]
            )

    def test_rejects_an_unknown_role(self):
        with self.assertRaises(llm.LlmError):
            llm.validate_conversation("s", [llm.Message("system", "a")])


class FakeMessages:
    def __init__(self, blocks, recorder):
        self._blocks = blocks
        self._recorder = recorder

    def create(self, **kwargs):
        self._recorder.update(kwargs)

        class Response:
            content = self._blocks

        return Response()


class FakeAnthropicClient:
    def __init__(self, blocks, recorder):
        self.messages = FakeMessages(blocks, recorder)


class Block:
    def __init__(self, text):
        self.type = "text"
        self.text = text


class AnthropicConnectorTest(unittest.TestCase):
    def setUp(self):
        from brain.connectors.llm_anthropic import AnthropicConnector

        self.connector = AnthropicConnector()

    def test_les_arguments_existent_dans_le_sdk_reel(self):
        # Protège contre une régression comme celle qui a motivé ce correctif :
        # `output_config` a été envoyé à un SDK épinglé qui ne le connaissait
        # pas encore, et les faux clients de test ne pouvaient pas le voir.
        from anthropic.resources.messages import Messages

        envoyes = {"model", "max_tokens", "system", "messages", "thinking", "output_config"}
        reels = set(inspect.signature(Messages.create).parameters)
        self.assertEqual(envoyes - reels, set())

    def test_is_configured_requires_an_api_key(self):
        self.assertFalse(self.connector.is_configured({}))
        self.assertFalse(self.connector.is_configured({"api_key": "  "}))
        self.assertTrue(self.connector.is_configured({"api_key": "sk-ant-x"}))

    def test_complete_sends_system_and_history_and_returns_text(self):
        recorder: dict = {}
        client = FakeAnthropicClient([Block("Bonjour !")], recorder)
        text = self.connector.complete(
            credentials={"api_key": "sk-ant-x"},
            model="claude-sonnet-5",
            system="Tu es Pepper.",
            messages=[llm.Message("user", "Salut")],
            client=client,
        )
        self.assertEqual(text, "Bonjour !")
        self.assertEqual(recorder["model"], "claude-sonnet-5")
        self.assertEqual(recorder["system"], "Tu es Pepper.")
        self.assertEqual(recorder["messages"], [{"role": "user", "content": "Salut"}])

    def test_complete_sends_thinking_and_output_config_for_a_model_that_supports_effort(self):
        recorder: dict = {}
        client = FakeAnthropicClient([Block("Bonjour !")], recorder)
        self.connector.complete(
            credentials={"api_key": "sk-ant-x"},
            model="claude-sonnet-5",
            system="Tu es Pepper.",
            messages=[llm.Message("user", "Salut")],
            client=client,
        )
        # Latence : pas de réflexion pour un tour de parole.
        self.assertEqual(recorder["thinking"], {"type": "disabled"})
        self.assertEqual(recorder["output_config"], {"effort": "low"})

    def test_complete_omits_thinking_and_output_config_for_a_model_that_does_not_support_them(self):
        # claude-haiku-4-5 est une génération 4.5 : elle rejette ces paramètres.
        recorder: dict = {}
        client = FakeAnthropicClient([Block("Bonjour !")], recorder)
        self.connector.complete(
            credentials={"api_key": "sk-ant-x"},
            model="claude-haiku-4-5",
            system="Tu es Pepper.",
            messages=[llm.Message("user", "Salut")],
            client=client,
        )
        self.assertNotIn("thinking", recorder)
        self.assertNotIn("output_config", recorder)

    def test_complete_rejects_an_empty_answer(self):
        client = FakeAnthropicClient([], {})
        with self.assertRaises(llm.LlmProviderUnavailable):
            self.connector.complete(
                credentials={"api_key": "sk-ant-x"},
                model="claude-haiku-4-5",
                system="s",
                messages=[llm.Message("user", "Salut")],
                client=client,
            )

    def test_complete_raises_llm_not_configured_when_the_api_key_is_missing(self):
        with self.assertRaises(llm.LlmNotConfigured):
            self.connector.complete(
                credentials={},
                model="claude-haiku-4-5",
                system="s",
                messages=[llm.Message("user", "Salut")],
            )

    def test_complete_rejects_an_invalid_conversation_before_touching_the_client(self):
        with self.assertRaises(llm.LlmError):
            self.connector.complete(
                credentials={"api_key": "sk-ant-x"},
                model="claude-haiku-4-5",
                system="s",
                messages=[llm.Message("assistant", "bonjour")],
                client=FakeAnthropicClient([], {}),
            )

    def test_client_defaults_to_a_short_timeout_and_a_single_retry(self):
        from brain.connectors.llm_anthropic import AnthropicConnector

        client = AnthropicConnector._client({"api_key": "sk-ant-x"})
        self.assertEqual(client.timeout, 15.0)
        self.assertEqual(client.max_retries, 1)

    def test_client_timeout_and_retries_are_overridable(self):
        from brain.connectors.llm_anthropic import AnthropicConnector

        client = AnthropicConnector._client(
            {"api_key": "sk-ant-x"}, timeout=5.0, max_retries=0
        )
        self.assertEqual(client.timeout, 5.0)
        self.assertEqual(client.max_retries, 0)


class OpenAiConnectorTest(unittest.TestCase):
    def setUp(self):
        from brain.connectors.llm_openai import OpenAiConnector

        self.connector = OpenAiConnector()

    def test_les_arguments_existent_dans_le_sdk_reel(self):
        from openai.resources.chat.completions import Completions

        envoyes = {"model", "max_tokens", "messages"}
        reels = set(inspect.signature(Completions.create).parameters)
        self.assertEqual(envoyes - reels, set())

    def test_is_configured_requires_an_api_key(self):
        self.assertFalse(self.connector.is_configured({}))
        self.assertTrue(self.connector.is_configured({"api_key": "sk-x"}))

    def test_complete_prepends_the_system_message(self):
        recorder: dict = {}

        class Completions:
            def create(self, **kwargs):
                recorder.update(kwargs)

                class Choice:
                    class message:
                        content = "Salut !"

                class Response:
                    choices = [Choice()]

                return Response()

        class Chat:
            completions = Completions()

        class Client:
            chat = Chat()

        text = self.connector.complete(
            credentials={"api_key": "sk-x"},
            model="gpt-4.1-mini",
            system="Tu es Pepper.",
            messages=[llm.Message("user", "Salut")],
            client=Client(),
        )
        self.assertEqual(text, "Salut !")
        self.assertEqual(recorder["messages"][0], {"role": "system", "content": "Tu es Pepper."})
        self.assertEqual(recorder["messages"][1], {"role": "user", "content": "Salut"})

    def test_complete_raises_llm_not_configured_when_the_api_key_is_missing(self):
        with self.assertRaises(llm.LlmNotConfigured):
            self.connector.complete(
                credentials={},
                model="gpt-4.1-mini",
                system="s",
                messages=[llm.Message("user", "Salut")],
            )

    def test_client_defaults_to_a_short_timeout_and_a_single_retry(self):
        from brain.connectors.llm_openai import OpenAiConnector

        client = OpenAiConnector._client({"api_key": "sk-x"})
        self.assertEqual(client.timeout, 15.0)
        self.assertEqual(client.max_retries, 1)


class BedrockConnectorTest(unittest.TestCase):
    def setUp(self):
        from brain.connectors.llm_bedrock import BedrockConnector

        self.connector = BedrockConnector()
        self.credentials = {
            "access_key": "AKIA-x",
            "secret_key": "secret",
            "region": "eu-west-1",
        }

    def test_is_configured_requires_the_three_fields(self):
        self.assertFalse(self.connector.is_configured({}))
        self.assertFalse(self.connector.is_configured({"access_key": "a"}))
        self.assertTrue(self.connector.is_configured(self.credentials))

    def test_models_derive_the_inference_profile_prefix_from_the_region(self):
        eu_ids = [m.id for m in self.connector.models({"region": "eu-west-1"})]
        us_ids = [m.id for m in self.connector.models({"region": "us-east-1"})]
        ap_ids = [m.id for m in self.connector.models({"region": "ap-southeast-2"})]
        self.assertTrue(all(i.startswith("eu.") for i in eu_ids))
        self.assertTrue(all(i.startswith("us.") for i in us_ids))
        self.assertTrue(all(i.startswith("apac.") for i in ap_ids))

    def test_complete_builds_a_request_valid_per_the_installed_botocore_model(self):
        # Pas d'appel réseau : botocore valide la forme de la requête dès
        # l'appel, avant tout envoi HTTP — c'est ce qui joue le rôle du test
        # de signature pour Anthropic/OpenAI.
        import boto3
        from botocore.stub import Stubber

        client = boto3.client(
            "bedrock-runtime",
            region_name="eu-west-1",
            aws_access_key_id="AKIA-x",
            aws_secret_access_key="secret",
        )
        stubber = Stubber(client)
        stubber.add_response(
            "converse",
            {
                "output": {"message": {"role": "assistant", "content": [{"text": "Bonjour !"}]}},
                "stopReason": "end_turn",
                "usage": {"inputTokens": 1, "outputTokens": 1, "totalTokens": 2},
                "metrics": {"latencyMs": 1},
            },
        )
        stubber.activate()
        text = self.connector.complete(
            credentials=self.credentials,
            model="eu.anthropic.claude-haiku-4-5-20251001-v1:0",
            system="Tu es Pepper.",
            messages=[llm.Message("user", "Salut")],
            client=client,
        )
        self.assertEqual(text, "Bonjour !")
        stubber.assert_no_pending_responses()

    def test_complete_rejects_an_empty_answer(self):
        class FakeClient:
            def converse(self, **kwargs):
                return {"output": {"message": {"content": []}}}

        with self.assertRaises(llm.LlmProviderUnavailable):
            self.connector.complete(
                credentials=self.credentials,
                model="eu.anthropic.claude-haiku-4-5-20251001-v1:0",
                system="s",
                messages=[llm.Message("user", "Salut")],
                client=FakeClient(),
            )

    def test_complete_raises_llm_not_configured_when_credentials_are_incomplete(self):
        with self.assertRaises(llm.LlmNotConfigured):
            self.connector.complete(
                credentials={},
                model="eu.anthropic.claude-haiku-4-5-20251001-v1:0",
                system="s",
                messages=[llm.Message("user", "Salut")],
            )

    def test_client_defaults_to_a_short_timeout_and_a_single_retry(self):
        from brain.connectors.llm_bedrock import BedrockConnector

        client = BedrockConnector._client(self.credentials)
        self.assertEqual(client.meta.config.connect_timeout, 15.0)
        self.assertEqual(client.meta.config.read_timeout, 15.0)
        self.assertEqual(client.meta.config.retries["total_max_attempts"], 2)
