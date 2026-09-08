# Brutus Pepper

**A Pepper robot that greets your visitors, in your words.**

Pepper stands in the hall. Someone walks in, the robot turns to them, welcomes them
with the sentence you wrote, points to the meeting room, answers their questions, and
goes back to facing the door once the hall is empty again.

Everything that makes it *yours* — the welcome sentence, the places, the image on the
tablet, the AI provider — is set from a web page on a computer you own. No developer,
no app rebuild, no cloud account you do not control.

---

## How it works

Two pieces: an Android app on Pepper's tablet, and a **brain** running in Docker on a
computer in your building.

```
┌──────────────────────────────┐            ┌────────────────────────────────────┐
│  Pepper — Android tablet     │            │  Brain — Docker, your network      │
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

**The tablet holds no provider key.** It listens, shows, moves and speaks. Providers,
models, keys, media and welcome content live on the brain — so they change without
redeploying an APK on site.

## What it does

**Welcome profiles.** One per company or venue: your instructions, the places Pepper
can point to, the tablet image and the greeting. Switch between them in one click; one
is in service at a time.

**A greeting you actually wrote.** Pepper says exactly what is in the text box, word
for word. Two buttons write into it — one drafts a sentence from your profile, the
other offers variants around what you already have — and you pick. Nothing is ever
spoken that you have not read on screen.

**Pointing that means something.** Declare a place with the direction to say and the
side to point at. Pepper repeats the direction verbatim and raises the right arm. A
place that is not on the list gets an honest "I don't know, please ask at the desk"
rather than an invented floor number.

**A hall that resets itself.** While nobody is in sight, the tablet shows your image
full screen and the robot turns back to face the entrance. A visitor arrives, the image
steps aside; they leave, and thirty seconds later everything is ready for the next
person. No one has to touch anything between visitors.

**Hands-free conversation.** The wake word runs offline on the tablet: no audio leaves
the robot until someone actually speaks to it. Local Whisper keeps the audio on your
network entirely; cloud transcription is an explicit, separate choice.

**A gamepad when you need it.** Xbox, DS4 and DS5: drive with the left stick, turn with
the D-pad, remap any button to a gesture.

## Getting started

### The brain

On Windows, unzip the delivery and double-click `install/Installer-Pepper.cmd`. It
installs Docker Desktop if missing, starts it with your session, and opens port 8770 on
the private network — asking before each change, and `remove` undoes all of it.

On macOS and Linux:

```bash
cd server/brain && docker compose up -d
docker exec pepper-brain cat /data/admin.token    # for the web app
docker exec pepper-brain cat /data/pairing.token  # for the tablet
```

Open `http://<server>:8770`, paste the admin token, then choose a model, add your keys,
upload your media and fill in a profile.

Runs on a mini PC, a Mac or a Raspberry Pi. Multi-architecture image, Whisper on CPU.

### The tablet

```bash
scripts/download-vosk-model.sh      # the offline French wake-word model
./gradlew test assembleDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk` with `adb install -r`. Building
never installs anything: deployment and motion tests stay separate steps, to be done
with space cleared around the robot.

On first launch, open **Cerveau** on the tablet and enter the server address and the
pairing token. Never put the admin token there — it opens the settings, the tablet only
needs to talk.

## Checks

```bash
./gradlew test                                            # tablet
cd server && python -m unittest discover -s brain/tests -t .   # brain
python -m unittest discover -s install/tests              # installers
```

Microphone, echo and QiSDK motion can only be judged with the robot connected.

## Documentation

- [Installing the brain](install/README.md) — launchers, network, updates
- [Brain reference](server/brain/README.md) — providers, pricing, profiles
- [Client guide](docs/PEPPER_CLIENT_GUIDE.md) — the printable step-by-step

## Licence

MIT. See [LICENSE](LICENSE).

Pepper 1.9 / NAOqi 2.9. The app is additive: it replaces neither the launcher, nor
kiosk mode, nor the robot's own startup.

---
---

# Brutus Pepper

**Un robot Pepper qui accueille vos visiteurs, avec vos mots.**

Pepper se tient dans le hall. Quelqu'un entre, le robot se tourne vers lui, l'accueille
avec la phrase que vous avez écrite, lui indique la salle de réunion, répond à ses
questions, puis se remet face à la porte une fois le hall vide.

Tout ce qui le rend *vôtre* — la phrase d'accueil, les lieux, l'image de la tablette,
le fournisseur d'IA — se règle depuis une page web, sur un ordinateur qui vous
appartient. Sans développeur, sans reconstruire l'application, sans compte cloud que
vous ne maîtrisez pas.

---

## Comment ça marche

Deux morceaux : une application Android sur la tablette de Pepper, et un **cerveau**
qui tourne dans Docker sur un ordinateur de vos locaux.

```
┌──────────────────────────────┐            ┌────────────────────────────────────┐
│  Pepper — tablette Android   │            │  Cerveau — Docker, votre réseau    │
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

**La tablette ne détient aucune clé fournisseur.** Elle écoute, affiche, bouge et
parle. Fournisseurs, modèles, clés, médias et contenu d'accueil vivent sur le cerveau —
donc modifiables sans redéployer d'APK sur place.

## Ce qu'il fait

**Des fiches d'accueil.** Une par entreprise ou par lieu : vos consignes, les lieux que
Pepper sait indiquer, l'image de la tablette et la phrase d'accueil. On bascule de
l'une à l'autre en un clic ; une seule est en service.

**Une phrase que vous avez vraiment écrite.** Pepper prononce exactement ce qu'il y a
dans la zone de texte, mot pour mot. Deux boutons écrivent dedans — l'un rédige une
phrase à partir de votre fiche, l'autre propose des variantes autour de ce que vous
avez déjà — et vous choisissez. Rien n'est jamais dit que vous n'ayez lu à l'écran.

**Un pointage qui veut dire quelque chose.** Déclarez un lieu avec l'indication à dire
et le côté à montrer. Pepper reprend l'indication mot pour mot et lève le bon bras. Un
lieu absent de la liste vaut un honnête « je ne sais pas, demandez à l'accueil » plutôt
qu'un étage inventé.

**Un hall qui se remet seul.** Tant que personne n'est en vue, la tablette affiche
votre image en plein écran et le robot se remet face à l'entrée. Un visiteur arrive,
l'image s'efface ; il repart, et trente secondes plus tard tout est prêt pour le
suivant. Personne n'a rien à toucher entre deux visites.

**Une conversation mains libres.** Le mot d'éveil tourne hors ligne sur la tablette :
aucun son ne sort du robot tant que personne ne lui parle vraiment. Whisper local garde
l'audio entièrement sur votre réseau ; la transcription cloud est un choix explicite et
séparé.

**Une manette quand il en faut une.** Xbox, DS4 et DS5 : déplacement au stick gauche,
rotation à la croix, chaque bouton remappable sur un geste.

## Démarrer

### Le cerveau

Sous Windows, décompressez la livraison et double-cliquez sur
`install/Installer-Pepper.cmd`. Il installe Docker Desktop s'il manque, le lance avec
votre session et ouvre le port 8770 sur le réseau privé — en demandant avant chaque
modification, et `remove` défait le tout.

Sous macOS et Linux :

```bash
cd server/brain && docker compose up -d
docker exec pepper-brain cat /data/admin.token    # pour la webapp
docker exec pepper-brain cat /data/pairing.token  # pour la tablette
```

Ouvrez `http://<serveur>:8770`, collez le jeton administrateur, puis choisissez un
modèle, saisissez vos clés, déposez vos médias et remplissez une fiche.

Tourne sur un mini-PC, un Mac ou un Raspberry Pi. Image multi-architecture, Whisper sur
processeur.

### La tablette

```bash
scripts/download-vosk-model.sh      # le modèle français hors ligne du mot d'éveil
./gradlew test assembleDebug
```

Installez `app/build/outputs/apk/debug/app-debug.apk` avec `adb install -r`. La
construction n'installe jamais rien : le déploiement et les essais de mouvement restent
des étapes séparées, à faire avec de l'espace dégagé autour du robot.

Au premier lancement, ouvrez **Cerveau** sur la tablette et saisissez l'adresse du
serveur et le jeton d'appairage. N'y mettez jamais le jeton administrateur : il ouvre
les réglages, alors que la tablette n'a besoin que de parler.

## Vérifications

```bash
./gradlew test                                            # tablette
cd server && python -m unittest discover -s brain/tests -t .   # cerveau
python -m unittest discover -s install/tests              # installeurs
```

Le micro, l'écho et les enchaînements QiSDK ne se jugent qu'avec le robot connecté.

## Documentation

- [Installer le cerveau](install/README.md) — lanceurs, réseau, mises à jour
- [Référence du cerveau](server/brain/README.md) — fournisseurs, tarifs, fiches
- [Guide client](docs/PEPPER_CLIENT_GUIDE.md) — le pas-à-pas imprimable

## Licence

MIT. Voir [LICENSE](LICENSE).

Pepper 1.9 / NAOqi 2.9. L'application est additive : elle ne remplace ni le lanceur, ni
le mode kiosk, ni le démarrage du robot.
