# Cerveau Pepper

Serveur unique qui détient les clés API, les connecteurs LLM et de transcription, le
prompt système et la médiathèque. **Le robot ne détient aucune clé fournisseur** : il s'authentifie
avec un jeton d'appairage qui ne donne accès qu'aux routes de conversation.

## Installation

Pour la remise au client, utiliser le [guide et les lanceurs Mac / Windows / Linux](../../install/README.md).
Docker reste le prérequis ; les lanceurs construisent l’image, attendent la santé du
serveur et accompagnent la connexion. Aucun accès au robot pendant l’installation.

```bash
docker compose up -d
```

Au premier démarrage, deux jetons sont générés dans le volume `brain-data` :

```bash
docker exec pepper-brain cat /data/admin.token    # pour la webapp d'administration
docker exec pepper-brain cat /data/pairing.token  # à saisir une fois sur la tablette Pepper
```

Le modèle Whisper configuré est téléchargé en tâche de fond dès le démarrage, pour que
l'attente ne tombe pas sur le premier visiteur qui parle au robot. Suivre la progression :

```bash
docker compose logs -f brain
```

## Prérequis matériel

| Machine | Modèle Whisper conseillé |
|---|---|
| Petite machine / priorité à la réactivité | `base` (défaut) |
| CPU rapide / priorité à la précision | `small` |
| Machine puissante | `medium` |

Le modèle se choisit dans la webapp. Compter environ 150 Mo pour `base` et 500 Mo pour
`small`, stockés dans le volume `brain-models`.

## Conversation et audio progressif

La tablette envoie du PCM mono 16 kHz par WebSocket pendant la prise de parole.
L’orbe réagit au niveau réellement capté ; elle se contracte pendant le traitement.
Le bouton permet de finir la prise ou d’interrompre la réponse. Le micro est libéré
pendant la synthèse QiSDK : l’interruption vocale sans toucher la tablette n’est pas
annoncée comme disponible avant validation de l’écho sur le robot.

- **Whisper local** : audio sur le LAN, résultat final après le silence. Les partiels
  par redécodage sont désactivés par défaut : sur un CPU lent ils retardent le résultat.
  Un intégrateur peut tester `BRAIN_LOCAL_PARTIALS=1` sur une machine suffisamment rapide.
- **OpenAI → GPT Live Transcribe** : option explicitement choisie dans la webapp,
  audio transmis au fournisseur, service facturé et soumis à l’accès du compte.
  L’antenne rééchantillonne en 24 kHz, relaie les deltas et valide le tour à la fin
  de la prise. [Protocole officiel](https://developers.openai.com/api/docs/guides/realtime-transcription).
  Tests simulés inclus ; accès, schéma accepté et latence réelle à valider avec le compte client.
- Si le WebSocket échoue, la tablette réessaie une fois avec la prise complète en
  HTTP. Pour GPT Live Transcribe, ce repli utilise `gpt-4o-mini-transcribe`, compatible
  avec les fichiers audio. Les résultats annulés ne rejoignent pas le nouvel échange.

Le WebSocket `/api/robot/transcribe/stream` exige le jeton robot en en-tête Bearer,
jamais dans l’URL. Messages binaires PCM s16le ; commandes JSON `finish` / `cancel` ;
événements `ready` / `partial` / `final` / `error`. Limites : 60 s d’audio, 64 Kio par
bloc, 4 sessions, 1 inférence locale simultanée par processus. Un seul worker Uvicorn.
La tablette plafonne ses prises automatiques à 15 s. Pas d’audio persisté par ce transport.

Mesure indicative sur une machine quatre cœurs : phrase synthétique française de 5,83 s, CPU int8,
2 threads, modèles déjà disponibles, deux décodages complets : `base` 1,82 / 1,77 s,
`small` 8,73 / 8,80 s. Ce n’est pas un benchmark du microphone Pepper ni une garantie
de latence. Le délai de silence, le réseau, le LLM et le démarrage TTS s’ajoutent.
Mesurer notamment `PepperLatency finalisation_ms` dans logcat lors des essais robot.

## Préparer une visite

Dans « Préparer une visite », saisir l’entreprise invitée, éventuellement son domaine,
puis rechercher ou écrire une fiche. Serper ou Brave fournit les sources ; une synthèse utilise
le LLM configuré. Rien n’est activé par la recherche : relire la fiche, cocher la
validation et enregistrer. Votre entreprise hôte, les lieux et les consignes sont
séparés de l’entreprise invitée. L’accueil initial ne suppose pas l’identité d’une
personne détectée et ne récite pas les notes privées. Le contexte guide ensuite
la discussion. Couper l’accueil conserve la fiche.

Pour une réception où les visuels doivent être maîtrisés, privilégier la médiathèque
préparée par l’hôte. Brave utilise SafeSearch strict ; cela ne garantit pas tous les
résultats. Les licences des images trouvées sur Internet restent applicables.

## Vérification

```bash
curl -s http://localhost:8770/api/health
```

```bash
curl -s -H "Authorization: Bearer $(docker exec pepper-brain cat /data/pairing.token)" \
     http://localhost:8770/api/robot/hello
```

## Configurer un fournisseur

Depuis la webapp d'administration, ou directement :

```bash
ADMIN=$(docker exec pepper-brain cat /data/admin.token)
curl -s -X PUT http://localhost:8770/api/admin/connectors \
  -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" \
  -d '{"llm":{"active":"anthropic","model":"claude-haiku-4-5","credentials":{"anthropic":{"api_key":"VOTRE_CLE"}}}}'
```

Puis un vrai tour de conversation :

```bash
PAIR=$(docker exec pepper-brain cat /data/pairing.token)
curl -s -X POST http://localhost:8770/api/robot/chat \
  -H "Authorization: Bearer $PAIR" -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","text":"Bonjour, qui es-tu ?"}]}'
```

Les clés saisies ne ressortent jamais de l'API : `GET /api/admin/connectors` ne renvoie
qu'un état `configured` et les quatre derniers caractères.

### Choisir un fournisseur et prévoir le coût

La page « Voix et intelligence » affiche automatiquement les champs nécessaires au
fournisseur sélectionné (clé API, identifiants AWS et région, ou contact Wikimedia),
masque les secrets déjà enregistrés et affiche le tarif du modèle choisi. Laisser une clé vide conserve
la clé déjà enregistrée ; rien n'est envoyé tant qu'on n'a pas appuyé sur
« Enregistrer ».

Les montants ci-dessous sont des repères en dollars US, vérifiés le 6 septembre 2026,
et non un devis :

| Usage | Option | Tarif indicatif |
|---|---|---:|
| LLM | GPT-4.1 mini | $0,40 / 1 M tokens entrée · $1,60 / 1 M sortie |
| LLM | GPT-4.1 | $2 / 1 M entrée · $8 / 1 M sortie |
| LLM | Claude Haiku 4.5 | $1 / 1 M entrée · $5 / 1 M sortie |
| LLM | Claude Sonnet 5 | $2 / 1 M entrée · $10 / 1 M sortie |
| LLM | Claude Opus 5 | $5 / 1 M entrée · $25 / 1 M sortie |
| LLM | AWS Bedrock | variable selon le modèle, la région et le niveau de service |
| Transcription | Whisper local | $0 d'API ; machine, électricité et modèle à prévoir |
| Transcription | GPT Live Transcribe | $0,017 / minute, soit environ $0,0028 pour 10 secondes |
| Transcription | whisper-1 | $0,006 / minute |
| Transcription | gpt-4o-mini-transcribe | $1,25 / 1 M tokens audio entrée · $5 / 1 M sortie |
| Transcription | gpt-4o-transcribe | $2,50 / 1 M tokens audio entrée · $10 / 1 M sortie |
| Transcription | AWS Transcribe streaming | environ $0,010 / minute dans l'exemple us-east-1 |
| Recherche | Wikimedia Commons | pas de coût d'API ; licences et quotas à respecter |
| Recherche | Google via Serper | $1 / 1 000 recherches ; pack $50 / 50 000 crédits, valables 6 mois ; essai initial 2 500 recherches |
| Recherche | Brave Search | $5 / 1 000 recherches ; $5 de crédits mensuels offerts selon l'offre Search |

Pour une discussion naturelle avec transcription visible pendant la parole, choisir
GPT Live Transcribe et renseigner la clé OpenAI. Pour garder l'audio dans le réseau
du client, choisir Whisper local : `base` est le réglage par défaut et rapide, `small`
le réglage plus précis, et `medium` est réservé à une machine puissante. Les tarifs des modèles
LLM sont ceux des pages officielles [OpenAI](https://developers.openai.com/api/docs/models/gpt-4.1-mini),
[Anthropic](https://platform.claude.com/docs/en/about-claude/pricing) et
[AWS Transcribe](https://aws.amazon.com/transcribe/pricing/). Les tarifs peuvent
évoluer ; la webapp donne toujours le lien source correspondant à la carte affichée.

### Google Images et recherche web via Serper — recommandé

Pour les entreprises, monuments et objets, le choix recommandé est Google via
[Serper](https://serper.dev/), une API tierce donnant accès aux résultats Google.
Ce choix privilégie la couverture et le coût sans ajouter un service à administrer.
SearXNG impose une instance à maintenir et dépend de moteurs susceptibles de bloquer
les requêtes ; SerpApi reste une autre API Google possible. La pertinence n'est pas
garantie : valider les recherches habituelles du client avec son compte.

Dans **Voix et intelligence → Recherche web et images**, sélectionner
**Google Images via Serper — recommandé**, saisir la clé API, puis enregistrer.
La langue et le pays sont configurables en codes à deux lettres (`fr` par défaut,
`en`, `be`, `ch`…). Cette sélection s'applique aux images, aux réponses Internet et
à la préparation des visites. Une ancienne configuration Brave/Wikimedia est
conservée jusqu'à ce changement explicite. Une clé Serper absente n'entraîne pas
d'appel payant sur un autre fournisseur.

Le bouton **Tester les réglages enregistrés** lance le même parcours que le robot :
médiathèque prioritaire, recherche, téléchargement, puis aperçu authentifié. Au plus
une requête de recherche est facturée ; les téléchargements ne relancent pas la
recherche. Trois originaux peuvent être essayés, puis une miniature Google si les
éditeurs bloquent leurs fichiers. La miniature peut être de résolution inférieure.
Les clés restent sur l'antenne et les images sont servies depuis son cache à Pepper.

Serper annonce 2 500 requêtes initiales sans carte bancaire, puis des recharges
prépayées à partir de $50 (50 000 crédits, validité six mois, hors taxes), sans
abonnement mensuel. Une recherche web et une recherche d'images sont deux appels.
Le filtre adulte est demandé à l'API ; son application et les visuels doivent être
vérifiés avec le compte client. Cela n'établit ni licence de réutilisation ni
garantie sur le contenu. Les erreurs de clé, quota et service sont présentées sans
renvoyer la réponse brute du fournisseur.

## Retirer un connecteur avant livraison

Supprimer sa ligne d'import dans `brain/connectors/__init__.py` et reconstruire. Pour
retirer Bedrock :

```python
from brain.connectors import llm_bedrock  # noqa: F401   ← supprimer cette ligne
```

## Image multi-architecture

```bash
docker buildx build --platform linux/amd64,linux/arm64 -t pepper-brain:latest .
```

## Tests

```bash
cd .. && ./brain/.venv/bin/python -m unittest discover -s brain/tests -t .
```

## Ce que le chiffrement au repos protège

`settings.key` est posé à côté de `settings.enc`, dans le même volume. Fernet protège
contre la fuite accidentelle — un `cat`, un `grep` dans un journal, une sauvegarde
partielle — **pas** contre un accès au système de fichiers du serveur. La garantie
réelle vient du masquage systématique côté API et des permissions `0600`.

Le trafic entre le robot et le cerveau est en HTTP clair sur le réseau local du client :
le jeton d'appairage et l'audio y transitent en clair. C'est acceptable sur un réseau
fermé, mais cela doit être une décision consignée avec le client, pas un oubli.
