# Brutus Pepper

Robot d'accueil Pepper 1.9 / NAOqi 2.9 en deux morceaux : une application Android
lancée manuellement sur la tablette, et un **cerveau** dockerisé installé sur le
réseau du client.

L'application est additive : elle ne remplace ni le lanceur, ni le mode kiosk, ni le
démarrage du robot.

## Expérience conversationnelle 0.11

Accueil tablette recentré sur un orbe vocal, état du micro, sous-titres et interruption
tactile. Administration simplifiée : préparer une visite, médias, intelligence et
appairage. Recherche d’entreprise sourcée avec validation avant activation.

Le transport audio est progressif et annulable. Whisper local reste disponible ;
OpenAI GPT Live Transcribe est une option cloud explicite, à valider avec le compte
client. Voir les [limites et mesures audio](server/brain/README.md#conversation-et-audio-progressif).
L’essai du micro, de l’écho et des enchaînements QiSDK nécessite le robot connecté.

Pour installer une antenne chez le client : [lanceurs et guide](install/README.md).
La version illustrée et imprimable est disponible dans le [guide client Pepper](docs/PEPPER_CLIENT_GUIDE.html) ; sa version texte est également fournie en [Markdown](docs/PEPPER_CLIENT_GUIDE.md).

## Architecture

```
┌──────────────────────────────┐            ┌────────────────────────────────────┐
│  Pepper — tablette Android   │            │  Cerveau — Docker, LAN du client   │
│                              │            │                                    │
│  • wake-word Vosk (local)    │   HTTP     │  Connecteurs LLM                   │
│  • capture micro + VAD       │◄──────────►│    anthropic · openai · bedrock    │
│  • TTS et gestes QiSDK       │            │  Connecteurs STT                   │
│  • rendu de scènes           │            │    whisper embarqué · cloud        │
│  • manette Bluetooth         │            │  Prompt système unique             │
│                              │            │  Médiathèque + recherche d'image   │
│  AUCUNE clé API              │            │  Mode hospitalité                  │
│  adresse + jeton d'appairage │            │  Webapp d'administration           │
└──────────────────────────────┘            └────────────────────────────────────┘
```

**La tablette ne détient aucune clé fournisseur.** Son jeton d’appairage reste un
secret à protéger. Elle capte, affiche,
bouge et parle. Fournisseurs, modèles, clés, médias et contexte d'accueil vivent sur
le cerveau — donc modifiables sans redéployer d'APK chez le client.

## Le cerveau

Voir [`server/brain/README.md`](server/brain/README.md) pour l'installation.

```bash
cd server/brain && docker compose up -d
docker exec pepper-brain cat /data/admin.token    # webapp d'administration
docker exec pepper-brain cat /data/pairing.token  # à saisir sur la tablette
```

La webapp est servie à la racine du serveur (`http://<serveur>:8770`). On y choisit le
fournisseur et le modèle, on saisit les clés, on dépose les médias et on renseigne le
mode hospitalité.

### Mode hospitalité

Un interrupteur. Tant qu'il est levé, Pepper aborde de lui-même chaque personne qui se
présente avec une phrase d'accueil, puis poursuit la conversation normalement. Baissé,
il redevient le robot d'accueil ordinaire — sans que l'opérateur ait à effacer ce qu'il
a saisi, qu'il retrouve intact à la réactivation.

L'opérateur décrit sa consigne en s'adressant à Pepper — « accueille les clients et
oriente-les vers la cuisine » — et déclare les lieux avec leur orientation : *la
cuisine, au fond du couloir à votre droite*. L'orientation est reprise mot pour mot, et
un lieu absent de la liste vaut un « je ne sais pas, demandez à l'accueil » plutôt
qu'une direction inventée : un robot qui envoie un visiteur au mauvais étage est pire
qu'un robot qui l'avoue.

La phrase d'accueil est rédigée à l'enregistrement, pas quand quelqu'un entre — un
appel au fournisseur coûte une à deux secondes, et l'opérateur voit ainsi ce que Pepper
dira avant qu'un visiteur ne l'entende.

L'hôte peut aussi choisir une image de la médiathèque : Pepper l'affiche en plein écran
entre deux visiteurs. On la touche pour retrouver l'interface, et elle revient après une
minute de calme — assez pour traverser les réglages sans être interrompu, assez court
pour qu'un hall laissé seul retrouve son écran d'accueil.

Cible matérielle : mini-PC x86, Mac ou Raspberry Pi. Image Docker multi-architecture,
Whisper en CPU int8.

## L'application tablette

```bash
./gradlew clean test lintDebug assembleDebug
```

L'APK est `app/build/outputs/apk/debug/app-debug.apk`. La construction n'installe
rien : l'installation par ADB et les essais de mouvement restent volontairement des
étapes séparées, à faire avec de l'espace dégagé autour de Pepper.

Au premier lancement, aller sur la page **Cerveau** et saisir l'adresse du serveur et
le jeton d'appairage. L'application tente immédiatement la connexion et affiche le
modèle actif.

### Ce que fait l'application

- écran d'accueil avec la conversation, le mode mains libres et le bouton d'annulation ;
- wake-word « Pepper » hors ligne (Vosk, modèle français embarqué) ;
- détection de parole calibrée sur le bruit de la pièce, avec hystérésis et fin de
  phrase adaptative ;
- manette Bluetooth Xbox, DS4 et DS5 : déplacement au stick gauche, rotation à la
  croix, actions remappables sur chaque bouton ;
- gestes QiSDK déclenchables à la voix ou à la manette — tourner sur soi-même,
  pointer à droite ou à gauche ;
- affichage sur la tablette à la demande : texte, média de la bibliothèque, image
  cherchée pour le robot, séquences en boucle, mode kiosk date et heure.

## Vérification

```bash
./gradlew clean test lintDebug assembleDebug          # tablette
cd server && ./brain/.venv/bin/python -m unittest discover -s brain/tests -t .   # cerveau
```

## Documents

- [Architecture cible](docs/superpowers/specs/2026-07-27-pepper-brain-architecture.md)
- [Audit du 2026-07-27](2026-05-02-pepper-audit-readonly.md)
- [Rapport temps et consommation des assistants](docs/PEPPER_USAGE_REPORT.md)
