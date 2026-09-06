# Brutus Pepper

Application Android et serveur open source pour le robot Pepper.

Brutus Pepper relie la tablette du robot Pepper à une antenne Docker. La tablette
affiche la conversation et les médias ; le serveur gère l’audio, les fournisseurs,
les recherches web et les réglages. Les clés restent sur l’antenne.

## Organisation

```text
Pepper (Android)  <->  réseau local  <->  antenne Docker  <->  fournisseurs choisis
     voix, écran             API FastAPI       LLM, audio, web et images
```

Le dépôt est organisé en deux parties :

- `app/` : application Android exécutée sur la tablette Pepper ;
- `server/brain/` : serveur FastAPI, interface d’administration, connecteurs et
  médiathèque ;
- `install/` : lanceurs Mac, Windows et Linux et vérifications d’installation ;
- `docs/` : guide d’installation et notices des composants tiers ;
- `deploy/fwhisper/` : exemples facultatifs pour une installation Whisper dédiée.

## Démarrer l’antenne

Prérequis : Docker Desktop sur macOS ou Windows, ou Docker Engine avec Compose
sur Linux.

```bash
bash install/pepper.sh setup
```

Le lanceur construit le serveur localement, crée les volumes persistants et affiche
les adresses utiles. Ouvrir l’adresse indiquée, saisir le jeton administrateur,
puis régler les connecteurs dans **Voix et intelligence**. Le serveur est aussi
lançable directement pour du développement :

```bash
docker compose -f server/brain/docker-compose.yml up --build
```

Le guide illustré est disponible dans
[`docs/PEPPER_CLIENT_GUIDE.pdf`](docs/PEPPER_CLIENT_GUIDE.pdf) et sa version
modifiable dans [`docs/PEPPER_CLIENT_GUIDE.md`](docs/PEPPER_CLIENT_GUIDE.md).

## Configurer les fournisseurs

Les clés se saisissent dans l’interface du serveur et sont chiffrées au repos.
Elles ne sont pas compilées dans l’APK et ne sont jamais renvoyées en clair par
l’API d’administration. Après **Enregistrer**, le nouveau réglage est utilisé à
la prochaine demande : il n’est pas nécessaire de relancer Docker.

### Recherche web et images

**Google via Serper** est le choix recommandé pour les recherches web, les images
et la préparation de fiches. Créer un compte sur
[serper.dev](https://serper.dev/), créer une clé dans le tableau de bord, puis
la coller dans **Voix et intelligence → Recherche web et images**. La même clé
sert aux deux usages.

Comme alternative, [Brave Search API](https://brave.com/search/api/) demande un
compte, une offre activée et une clé créée dans **API Keys**. Wikimedia Commons
reste disponible sans clé pour les installations qui le souhaitent, mais sa
couverture d’images est plus limitée.

Sans fournisseur web configuré, Pepper ne prétend pas avoir effectué une recherche
et indique que les informations en ligne ne sont pas accessibles.

### Modèle de conversation

- [OpenAI](https://platform.openai.com/api-keys) : créer une clé secrète, choisir
  **OpenAI** et un modèle ;
- [Anthropic Console](https://console.anthropic.com/settings/keys) : créer une
  clé API, choisir **Anthropic** et un modèle ;
- AWS Bedrock : activer l’accès au modèle dans la région choisie et fournir une
  identité IAM dédiée avec les permissions minimales.

### Transcription

- **Whisper local** : pas de clé ; l’audio reste sur le réseau de l’antenne ;
- **OpenAI** : utiliser la clé OpenAI et choisir GPT Live Transcribe ou un modèle
  de transcription ;
- **AWS Transcribe** : utiliser des credentials IAM et une région, comme expliqué
  dans le [guide de streaming AWS](https://docs.aws.amazon.com/transcribe/latest/dg/streaming.html).

## Construire et tester l’application

```bash
./scripts/download-vosk-model.sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Le modèle de réveil vocal est téléchargé séparément et reste ignoré par Git ; cette
étape est nécessaire pour fabriquer une APK complète.

L’APK généré se trouve dans `app/build/outputs/apk/debug/`. Pour une installation
sur Pepper, suivre `install/INSTALLER_APPLICATION.md` et renseigner l’adresse de
l’antenne dans **Cerveau**.

Tests du serveur et des lanceurs :

```bash
PYTHONPATH=server python3 -m unittest discover -s server/brain/tests -t server/brain
python3 -m unittest discover -s install/tests -v
```

## Sécurité et données

Ne jamais committer `.env`, `*.token`, `settings.key`, `settings.enc`, une clé
API ou un fichier de credentials cloud. Le port de l’antenne doit rester sur un
réseau privé de confiance ; ne pas le publier directement sur Internet.

Les volumes Docker contiennent les réglages, les jetons, la médiathèque et les
modèles Whisper. Ils doivent être sauvegardés séparément du code et protégés avec
les mêmes précautions que les clés API.

## Licence

Le code original de ce dépôt est distribué sous licence MIT. Les dépendances,
modèles et ressources Pepper/SoftBank restent soumis à leurs propres licences ;
voir [`docs/third-party/`](docs/third-party/).
