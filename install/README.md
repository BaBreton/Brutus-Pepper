# Installer Pepper chez vous

Ce dossier installe le « cerveau » de Pepper sur votre ordinateur. Il doit rester
allumé et connecté au même réseau que le robot. L'installation de l'application
sur la tablette est une opération distincte, à réaliser avec votre installateur.

Pour une version plus visuelle de ce parcours, ouvrir `docs/PEPPER_CLIENT_GUIDE.html`
depuis le ZIP ; elle fonctionne hors ligne et peut être imprimée.

## 1. Préparer l'ordinateur

- **Mac** : Mac Intel ou Apple Silicon 64 bits avec Docker Desktop installé et ouvert.
- **Windows** : Windows 64 bits compatible avec Docker Desktop, virtualisation/WSL2
  configurés par votre installateur. Docker Desktop doit utiliser les **conteneurs Linux**.
- **Linux** : x86_64 ou ARM64 (dont Raspberry Pi avec OS 64 bits), Docker Engine,
  plugin Docker Compose, Bash, curl et iproute2. Votre compte doit pouvoir utiliser
  Docker ; faites régler les droits par votre administrateur, sans lancer ce guide avec sudo.

Téléchargez Docker depuis [le site officiel](https://docs.docker.com/get-started/get-docker/).
Son installation peut nécessiter un administrateur ; vérifiez les conditions de
licence applicables à votre entreprise. Prévoyez plusieurs Go libres et Internet
pour construire le serveur et télécharger le modèle vocal. Le premier lancement
peut prendre plusieurs minutes. Aucun accès au robot n'est nécessaire à cette étape.

Décompressez **tout le ZIP** dans un dossier, par exemple « Pepper client » dans
Documents. Gardez les dossiers `install` et `server` ensemble. Ne lancez pas les
fichiers directement dans l'aperçu du ZIP. Les espaces dans le chemin sont acceptés.

## 2. Démarrer

**Mac** : dans `install`, double-cliquez sur **Pepper.command**. Si macOS bloque
l'ouverture, vérifiez la provenance du ZIP avec votre installateur, puis utilisez
« Ouvrir » depuis le menu contextuel ou l'autorisation proposée dans les réglages
de sécurité. Si le fichier n'est pas exécutable, votre installateur peut lancer :

```bash
cd "/chemin/vers/Pepper client"
chmod +x install/Pepper.command
./install/Pepper.command
```

**Windows** : dans `install`, double-cliquez sur **Pepper.cmd** (et non sur le `.ps1`).
Le lanceur utilise Windows PowerShell 5.1 ou supérieur sans changer la stratégie
d'exécution permanente de Windows. Si une règle d'entreprise bloque le script,
demandez l'aide du service informatique. Le `.cmd` conserve la fenêtre ouverte.

**Linux** : ouvrez un terminal dans le dossier extrait, puis lancez :

```bash
bash install/pepper.sh setup
```

Le lanceur vérifie Docker, son moteur local et Compose, **construit l'image depuis
`server/brain`**, puis démarre le serveur. Il n'existe pas d'image Pepper publiée à
télécharger. Relancer la commande réutilise le cache de construction et les mêmes
volumes ; cela peut actualiser le conteneur, sans remettre les réglages à zéro.

Après construction, l'attente de santé est limitée à 180 secondes (un contrôle
HTTP en cours peut ajouter jusqu'à 2 secondes ; un moteur Docker bloqué doit être
débloqué séparément). Si elle expire, le serveur reste en place : relancez plus tard
ou utilisez `--wait 600` sous Mac/Linux, `-WaitSeconds 600` sous Windows.
« Prêt » signifie que l'administration répond ; le téléchargement du modèle Whisper
et la configuration d'un fournisseur peuvent encore être nécessaires pour parler.

## 3. Ouvrir l'administration et connecter Pepper

La commande `setup` propose d'afficher le **jeton administrateur**. Répondez `o`
uniquement devant un terminal local privé, sans partage d'écran, enregistrement ou
transcription de session. Copiez le jeton dans le champ de connexion de
[l'administration locale](http://localhost:8770/), puis effacez le presse-papiers
si vous avez fini. Le navigateur peut être ouvert à votre demande. Son URL ne
contient jamais le jeton. Fermez ensuite le terminal pour limiter son exposition
dans le défilement, et déconnectez-vous de la webapp quand vous avez terminé.

Le lanceur n'enregistre aucun jeton et refuse cet affichage lorsque ses sorties
sont redirigées ou dans les sessions distantes détectées. Un outil tiers qui filme
ou enregistre la console reste capable de capturer ce que vous voyez. Les jetons
sont créés par le serveur dans son volume de données ; une relance ne les régénère pas.
La commande `start` n'affiche jamais de jeton. Pour le retrouver, relancez `setup`.

Dans la webapp, configurez le fournisseur et les réglages, puis ouvrez la partie
connexion/appairage du robot. Copiez **le jeton d'appairage affiché dans la webapp**
sur la tablette, avec l'adresse LAN indiquée par le lanceur, par exemple
`http://<adresse-de-l-antenne>:8770`. N'utilisez jamais le jeton administrateur sur la tablette.
Ne régénérez le jeton d'appairage que si vous souhaitez invalider l'ancien.

L'adresse `localhost` fonctionne uniquement sur l'ordinateur serveur. Le lanceur
cherche les cartes physiques Wi-Fi/Ethernet, sans se fier à la route par défaut
d'un VPN. S'il trouve plusieurs adresses, choisissez le réseau du robot ; sans
choix, il n'en annonce aucune comme adresse à utiliser. Si la détection est
impossible, consultez l'IPv4 Wi-Fi/Ethernet dans les réglages réseau et précisez-la :

```bash
bash install/pepper.sh start --lan-ip <adresse-de-l-antenne>
```

```powershell
.\install\Pepper.cmd start -LanIp <adresse-de-l-antenne>
```

`PEPPER_LAN_IP` permet aussi cette surcharge. C'est une adresse d'affichage : elle
ne change ni l'écoute du serveur ni le pare-feu. Une valeur manuelle doit appartenir
à cet ordinateur. Une réservation DHCP évite qu'elle change après un redémarrage.
Les ponts/VLAN et réseaux virtuels demandent parfois ce choix manuel.

Le serveur existant expose le port TCP **8770** sur les interfaces de l'ordinateur.
Faites autoriser ce port uniquement sur le réseau privé prévu pour Pepper. Aucun
réglage de pare-feu n'est modifié par les scripts. Ne publiez pas ce port sur Internet.
Le trafic local HTTP transporte jetons et audio sans chiffrement : utilisez un
réseau de confiance validé par votre responsable informatique. Un Wi-Fi invité
avec isolation des appareils ou un VPN peut empêcher la connexion du robot.

## Utilisation quotidienne

Sur la tablette, dites « Pepper » ou touchez **Parler** pour commencer. Après sa
réponse, Pepper écoute la suite. Sans nouvelle parole pendant le délai d’écoute,
la conversation se termine et Pepper revient en attente de « Pepper ». Le bouton
**J’ai terminé** envoie la phrase en cours ; il ne termine pas toute la conversation.
La sourdine coupe l’écoute. Touchez **Menu** pour afficher les rubriques et les
réglages, puis **Fermer** pour replier la navigation.

Pour terminer explicitement, dites **« Merci Pepper, à bientôt »** ou **« Au revoir »**.
Pepper termine sa réponse, puis garde uniquement la détection du mot « Pepper ».
L’accueil automatique reste bloqué, même après détection d’une personne ou coupure
puis réactivation du micro. Dites **« Pepper »** ou touchez **Parler** pour reprendre
et lever ce blocage. **« Merci »** ou **« Merci Pepper »** seuls gardent l’échange actif.

Depuis le dossier extrait, utilisez la ligne correspondant à votre système :

| Besoin | Mac / Linux | Windows PowerShell |
|---|---|---|
| Vérifier Docker sans modifier le serveur | `bash install/pepper.sh check` | `.\install\Pepper.cmd check` |
| Démarrer / mettre à jour depuis ce dossier | `bash install/pepper.sh start` | `.\install\Pepper.cmd start` |
| Accéder au jeton localement | `bash install/pepper.sh setup` | `.\install\Pepper.cmd setup` |
| Vérifier le serveur | `bash install/pepper.sh status` | `.\install\Pepper.cmd status` |
| Arrêter en gardant les données | `bash install/pepper.sh stop` | `.\install\Pepper.cmd stop` |

Dans une invite Windows, `install\Pepper.cmd stop` fonctionne aussi. Le `.cmd`
applique une autorisation d'exécution limitée à son processus PowerShell ; les
commandes `.ps1` directes dépendent de votre stratégie locale. `--open` (Mac/Linux)
ou `-Open` (Windows) ouvre le navigateur sans secret dans l'adresse.

Le serveur redémarre avec Docker après redémarrage de l'ordinateur, sauf si vous
l'avez arrêté explicitement. Docker lui-même doit être lancé. `stop` n'efface rien.

## Mise à jour et sauvegardes — avec votre installateur

Le projet Compose est toujours **`brain`**, le conteneur **`pepper-brain`** et les
volumes habituels **`brain_brain-data`** et **`brain_brain-models`**. C'est également
le nom par défaut de l'ancienne installation lancée depuis `server/brain`. Une
installation qui utilisait un autre nom de projet ou de volumes doit être vérifiée
par le support avant migration : ne créez pas un serveur vide en remplacement.
Une seule installation Pepper est prise en charge par moteur Docker (nom et port fixes).

Avant une mise à jour, planifiez une interruption, arrêtez Pepper avec `stop` et
faites sauvegarder **le volume de données complet**, avec permissions, dont
`settings.enc`, **`settings.key`**, les jetons et la médiathèque. Sauvegardez aussi
le volume des modèles si vous devez pouvoir repartir sans téléchargement.
Confirmez les noms et montages réels avec `docker inspect pepper-brain`, sans
afficher le contenu des fichiers secrets. Demandez au support un export des volumes
arrêtés via Docker Desktop ou un outil de sauvegarde Docker maîtrisé, puis testez la
restauration dans un environnement isolé avant de compter sur cette sauvegarde.

Une copie du ZIP, une image Docker ou un export du conteneur **ne sauvegarde pas les
volumes**. Les sauvegardes contiennent aussi la clé de déchiffrement et les jetons :
chiffrez-les, limitez les accès, conservez-les hors du poste et définissez une durée
de rétention. Ne les joignez pas à une demande de support ordinaire.

Conservez l'ancien dossier de sources. Extrayez le nouveau ZIP dans un autre
dossier et lancez `start` depuis ce nouveau dossier, sur le **même moteur Docker**.
Le nom du dossier n'affecte pas les volumes. Vérifiez ensuite les réglages,
l'appairage et une conversation. Un retour à une version précédente n'est pas
garanti si le format des données a changé : utilisez la sauvegarde et le support.
N'utilisez pas `docker compose down -v`, la suppression des volumes, ni la
réinitialisation de Docker Desktop pour résoudre un problème de démarrage.

## Préparer le ZIP — mainteneur

Depuis le dépôt de développement, avec Git et les outils système `zip`/`unzip` :

```bash
bash install/package-client.sh "/dossier/existant/pepper-client-version.zip"
```

Pour inclure aussi l’application tablette et le guide PDF, préparez ces fichiers,
puis utilisez l’option de livraison complète :

```bash
bash install/package-client.sh "/dossier/existant/pepper-client-version.zip" --with-app
```

Cette option ajoute `application/Pepper.apk`, `docs/PEPPER_CLIENT_GUIDE.pdf` et
`install/INSTALLER_APPLICATION.md`. L’APK provient de
`app/build/outputs/apk/debug/app-debug.apk`. Le script ne compile pas l’application
et ne génère pas le PDF : ces fichiers doivent être prêts avant son lancement.
Il les inclut dans le ZIP ; l’installation sur Pepper reste une étape distincte,
décrite dans `install/INSTALLER_APPLICATION.md`.

Le script refuse d'écraser un fichier. Il prend le contenu actuel des sources dans
`server/brain`, fichiers nouveaux non ignorés inclus, filtré par type source et par
exclusion des dossiers privés/temporaires, et une liste explicite de lanceurs et du
présent guide. Les guides client Markdown et HTML sont inclus s’ils sont présents.
Aucun commit ni ajout Git n'est effectué par le script.

Sans `--with-app`, l’APK et le guide PDF ne sont pas inclus.
Ni `.git`, ni environnements Python, données, modèles, clés, jetons, fichiers
temporaires, tests de l'installateur ou script d'emballage ne sont livrés. Les tests
Python du serveur sont des sources et peuvent être présents. Relisez les
sources avant diffusion : le filtrage des noms ne détecte pas un secret écrit dans
du code. Les liens symboliques sélectionnés sont refusés. Le ZIP fixe ordre, dates
et modes ; deux emballages du même contenu avec la même version de `zip` sont identiques.

Vérifications sans démarrer Docker :

```bash
python3 -m unittest discover -s install/tests -v
bash -n install/pepper.sh install/network.sh install/Pepper.command install/package-client.sh
```

Le simulateur vérifie les chemins avec espaces et les erreurs Docker, sans réseau
ni robot. Il ne prouve pas le démarrage réel sur Windows/Linux ou la compatibilité
de chaque machine. PowerShell et Docker doivent aussi être validés sur les OS cibles.
