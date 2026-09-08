# Brutus Pepper

Reception assistant for the Pepper 1.9 robot (NAOqi 2.9), in two parts: an Android app
running on the robot's tablet, and a dockerized **brain** installed on the local
network. The brain holds the provider keys, the welcome content and the media; the
tablet only listens, speaks, moves and displays.

The app is additive: it replaces neither the launcher, nor kiosk mode, nor the robot's
own startup.

## Architecture

```
┌──────────────────────────────┐            ┌────────────────────────────────────┐
│  Pepper — Android tablet     │            │  Brain — Docker, local network     │
│                              │            │                                    │
│  • offline wake word (Vosk)  │   HTTP     │  Conversation models               │
│  • microphone capture        │◄──────────►│    OpenAI · Anthropic · Bedrock    │
│  • speech and QiSDK gestures │            │  Speech to text                    │
│  • full-screen display       │            │    local Whisper · cloud           │
│  • Bluetooth gamepad         │            │  Welcome profiles                  │
│                              │            │  Media library and image search    │
│  NO provider key             │            │  Administration web app            │
│  address + pairing token     │            │                                    │
└──────────────────────────────┘            └────────────────────────────────────┘
```

Providers, models, keys, media and welcome content live on the brain, so they can be
changed without redeploying an APK on site. The pairing token is the tablet's only
secret; the admin token never goes on the tablet.

## Features

- Welcome profiles: one per company or venue, holding the instructions, the places to
  point at, the tablet image and the greeting. One profile is in service at a time.
- The greeting is spoken exactly as typed. Two buttons write into the text area — one
  drafts a sentence from the profile, the other proposes variants — and the operator
  picks.
- Places are declared with the wording to say and the side to point at. The wording is
  repeated verbatim; an undeclared place yields "I don't know, please ask at the desk"
  rather than an invented direction.
- While nobody is detected, the tablet shows the configured image full screen and the
  robot turns back to its recorded starting orientation. A tap dismisses the image;
  it returns after thirty seconds without a visitor or an interaction.
- Offline wake word on the tablet: no audio leaves the robot until someone speaks to
  it. Local Whisper keeps audio on the network; cloud transcription is a separate,
  explicit choice.
- Bluetooth gamepad (Xbox, DS4, DS5): drive with the left stick, turn with the D-pad,
  remappable actions on each button.
- On-demand display: text, media from the library, an image searched for the robot,
  looping sequences, and a date/time kiosk mode.

## Requirements

- Pepper 1.9 running NAOqi 2.9, and the Android SDK to build the app.
- For the brain: Docker and Docker Compose on a mini PC, a Mac or a Raspberry Pi.
  Multi-architecture image, Whisper running on CPU.
- The robot and the brain must sit on the same private network.

## Brain setup

On Windows, extract the delivery and run `install/Installer-Pepper.cmd`. It installs
Docker Desktop if missing, starts it with the user session and opens TCP port 8770 on
the private profile, asking before each change. `Installer-Pepper.cmd remove` undoes
those three changes.

On macOS and Linux:

```bash
cd server/brain && docker compose up -d
docker exec pepper-brain cat /data/admin.token    # administration web app
docker exec pepper-brain cat /data/pairing.token  # to enter on the tablet
```

The web app is served at `http://<server>:8770`. It is where the provider and model are
chosen, the keys entered, the media uploaded and the welcome profiles filled in.

## Tablet setup

```bash
scripts/download-vosk-model.sh      # offline French wake-word model, ~40 MB
./gradlew test assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`; install it with
`adb install -r`. Building never installs anything: deployment and motion tests stay
separate steps, to be run with space cleared around the robot.

On first launch, open the **Cerveau** page on the tablet and enter the server address
and the pairing token.

## Tests

```bash
./gradlew test                                                 # tablet
cd server && python -m unittest discover -s brain/tests -t .   # brain
python -m unittest discover -s install/tests                   # installers
```

Microphone, echo and QiSDK motion can only be assessed with the robot connected.

## Documentation

- [install/README.md](install/README.md) — launchers, network, updates, backups
- [server/brain/README.md](server/brain/README.md) — providers, pricing, profiles
- [docs/PEPPER_CLIENT_GUIDE.md](docs/PEPPER_CLIENT_GUIDE.md) — printable step-by-step guide

## Licence

MIT. See [LICENSE](LICENSE).

---

# Brutus Pepper

Assistant d'accueil pour le robot Pepper 1.9 (NAOqi 2.9), en deux parties : une
application Android exécutée sur la tablette du robot, et un **cerveau** dockerisé
installé sur le réseau local. Le cerveau détient les clés fournisseur, le contenu
d'accueil et les médias ; la tablette se contente d'écouter, parler, bouger et afficher.

L'application est additive : elle ne remplace ni le lanceur, ni le mode kiosk, ni le
démarrage du robot.

## Architecture

```
┌──────────────────────────────┐            ┌────────────────────────────────────┐
│  Pepper — tablette Android   │            │  Cerveau — Docker, réseau local    │
│                              │            │                                    │
│  • mot d'éveil hors ligne    │   HTTP     │  Modèles de conversation           │
│  • capture du micro          │◄──────────►│    OpenAI · Anthropic · Bedrock    │
│  • parole et gestes QiSDK    │            │  Transcription                     │
│  • affichage plein écran     │            │    Whisper local · cloud           │
│  • manette Bluetooth         │            │  Fiches d'accueil                  │
│                              │            │  Médiathèque et recherche d'image  │
│  AUCUNE clé fournisseur      │            │  Webapp d'administration           │
│  adresse + jeton d'appairage │            │                                    │
└──────────────────────────────┘            └────────────────────────────────────┘
```

Fournisseurs, modèles, clés, médias et contenu d'accueil vivent sur le cerveau : ils se
modifient sans redéployer d'APK sur place. Le jeton d'appairage est le seul secret de
la tablette ; le jeton administrateur n'y est jamais saisi.

## Fonctions

- Fiches d'accueil : une par entreprise ou par lieu, regroupant les consignes, les
  lieux à indiquer, l'image de la tablette et la phrase d'accueil. Une seule fiche est
  en service à la fois.
- La phrase d'accueil est prononcée telle qu'elle est saisie. Deux boutons écrivent
  dans la zone de texte — l'un rédige une phrase à partir de la fiche, l'autre propose
  des variantes — et l'opérateur choisit.
- Les lieux se déclarent avec l'indication à dire et le côté à pointer. L'indication
  est reprise mot pour mot ; un lieu non déclaré donne « je ne sais pas, demandez à
  l'accueil » plutôt qu'une direction inventée.
- Tant que personne n'est détecté, la tablette affiche l'image configurée en plein
  écran et le robot revient à son orientation de départ enregistrée. Un appui retire
  l'image ; elle revient après trente secondes sans visiteur ni interaction.
- Mot d'éveil hors ligne sur la tablette : aucun son ne sort du robot avant qu'on lui
  parle. Whisper local garde l'audio sur le réseau ; la transcription cloud est un
  choix distinct et explicite.
- Manette Bluetooth (Xbox, DS4, DS5) : déplacement au stick gauche, rotation à la
  croix, actions remappables sur chaque bouton.
- Affichage à la demande : texte, média de la bibliothèque, image cherchée pour le
  robot, séquences en boucle, mode kiosk date et heure.

## Prérequis

- Pepper 1.9 sous NAOqi 2.9, et le SDK Android pour construire l'application.
- Pour le cerveau : Docker et Docker Compose sur un mini-PC, un Mac ou un Raspberry Pi.
  Image multi-architecture, Whisper sur processeur.
- Le robot et le cerveau doivent être sur le même réseau privé.

## Installation du cerveau

Sous Windows, décompresser la livraison et lancer `install/Installer-Pepper.cmd`. Il
installe Docker Desktop s'il est absent, le démarre avec la session utilisateur et
ouvre le port TCP 8770 sur le profil privé, en demandant avant chaque modification.
`Installer-Pepper.cmd remove` défait ces trois changements.

Sous macOS et Linux :

```bash
cd server/brain && docker compose up -d
docker exec pepper-brain cat /data/admin.token    # webapp d'administration
docker exec pepper-brain cat /data/pairing.token  # à saisir sur la tablette
```

La webapp est servie sur `http://<serveur>:8770`. C'est là qu'on choisit le fournisseur
et le modèle, qu'on saisit les clés, qu'on dépose les médias et qu'on remplit les
fiches d'accueil.

## Installation sur la tablette

```bash
scripts/download-vosk-model.sh      # modèle français du mot d'éveil, ~40 Mo
./gradlew test assembleDebug
```

L'APK est dans `app/build/outputs/apk/debug/app-debug.apk` ; l'installer avec
`adb install -r`. La construction n'installe rien : le déploiement et les essais de
mouvement restent des étapes séparées, à faire avec de l'espace dégagé autour du robot.

Au premier lancement, ouvrir la page **Cerveau** sur la tablette et saisir l'adresse du
serveur et le jeton d'appairage.

## Tests

```bash
./gradlew test                                                 # tablette
cd server && python -m unittest discover -s brain/tests -t .   # cerveau
python -m unittest discover -s install/tests                   # installeurs
```

Le micro, l'écho et les enchaînements QiSDK ne se jugent qu'avec le robot connecté.

## Documentation

- [install/README.md](install/README.md) — lanceurs, réseau, mises à jour, sauvegardes
- [server/brain/README.md](server/brain/README.md) — fournisseurs, tarifs, fiches
- [docs/PEPPER_CLIENT_GUIDE.md](docs/PEPPER_CLIENT_GUIDE.md) — guide pas-à-pas imprimable

## Licence

MIT. Voir [LICENSE](LICENSE).
