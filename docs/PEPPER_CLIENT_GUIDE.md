# Pepper · installer l’antenne

> Installer le serveur et relier l’application déjà présente sur le robot Pepper.

## Le principe

```text
Pepper (tablette)  ⇄  réseau local  ⇄  antenne Docker  ⇄  API choisies
       voix, écran                         audio, web, LLM, images
```

L’antenne est le serveur installé sur votre ordinateur. Elle conserve les clés API, les réglages et les médias en cache. L’application du robot Pepper est déjà installée sur sa tablette : les clés API ne sont jamais installées sur Pepper.

## 1 · Décompresser et installer le serveur

L’application est déjà dans Pepper. Cette procédure concerne uniquement l’antenne, c’est-à-dire le serveur qui tourne sur l’ordinateur du client.

1. Télécharger le fichier `Pepper-server.zip` et le placer dans **Téléchargements** ou dans un autre dossier facile à retrouver.
2. Décompresser le ZIP en entier : clic droit sur le fichier → **Extraire tout…** sous Windows, ou double-clic sur Mac. Choisir un dossier dédié, par exemple `Pepper` dans **Documents**. Ne pas travailler depuis l’aperçu du ZIP.
3. Vérifier que le dossier extrait contient au même niveau `install`, `server` et `docs`. Si `install` est caché à l’intérieur d’un second dossier, ouvrir ce second dossier pour la suite.
4. Installer puis démarrer **Docker Desktop** sur Mac ou Windows, ou **Docker Engine + Compose** sur Linux. L’ordinateur doit rester allumé et connecté au même réseau que Pepper pendant les visites.
5. Lancer l’installation :
   - **Windows** : ouvrir `install`, puis double-cliquer sur `Installer-Pepper.cmd`. Cet installateur fait tout : il installe Docker Desktop s’il manque, le fait démarrer avec la session Windows pour que l’antenne reparte après un redémarrage, et autorise le port 8770 sur le réseau privé pour que la tablette puisse joindre l’ordinateur. Il pose une question avant chaque modification, et `Installer-Pepper.cmd remove` défait le tout. Sur un poste où l’on préfère ne rien laisser s’installer automatiquement, `Pepper.cmd` démarre l’antenne seule, sans toucher au pare-feu ni au démarrage.
   - **Mac** : ouvrir `install`, puis double-cliquer sur `Pepper.command`. Si macOS bloque le fichier, faire Ctrl-clic → **Ouvrir**.
   - **Linux** : ouvrir un terminal dans le dossier extrait et lancer `bash install/pepper.sh setup`.

   Sous Windows, deux points bloquent souvent une première installation. Si Docker Desktop demande un redémarrage — c’est fréquent, il installe WSL 2 —, redémarrer puis relancer `Installer-Pepper.cmd` : il reprend là où il s’était arrêté. Et si le réseau de l’ordinateur est classé **Public**, Windows refusera les connexions du robot quelle que soit la règle de pare-feu : passer ce réseau en **Privé** dans Paramètres → Réseau et Internet.
6. Suivre les questions du lanceur. Il construit l’antenne localement, démarre Docker et attend que l’administration réponde. Le premier lancement peut prendre plusieurs minutes et nécessite Internet pour télécharger les composants.
7. Quand le lanceur indique que Pepper est prêt, ouvrir l’adresse d’administration sur cet ordinateur : `http://localhost:8770/`. Noter aussi l’adresse LAN affichée : c’est celle que la tablette Pepper utilisera.

Les données et les réglages restent dans les volumes Docker. Pour une mise à jour, suivre la procédure de sauvegarde et de relance dans `install/README.md` ; ne pas supprimer les volumes.

Le paquet serveur n’a pas besoin d’APK. Si un paquet complet contient `application/Pepper.apk`, cette copie est seulement un secours pour l’installateur : ne pas la réinstaller si l’application est déjà présente sur Pepper.

## 2 · Première configuration

### Les accès et les clés

Il ne faut pas confondre les accès internes de l’antenne avec les clés des services en ligne :

| Élément | Où il est conservé | Utilisation |
|---|---|---|
| **Jeton administrateur** | Serveur, dans ses données Docker | Ouvrir l’interface d’administration et modifier les réglages. Ne jamais le saisir dans Pepper. |
| **Jeton d’appairage** | Serveur et application Pepper | Autoriser la tablette à appeler l’antenne. Ce n’est pas un accès à l’administration. |
| **Clés API fournisseurs** | Serveur uniquement | LLM, transcription, recherche web et images. Elles ne sont jamais intégrées à l’application Pepper. |

Au premier lancement, `setup` propose d’afficher le jeton administrateur dans le terminal local. L’accepter uniquement devant l’ordinateur du client, puis le copier dans la page `http://localhost:8770/`. Le serveur crée et conserve ce jeton ; une simple relance ne le change pas. Si le jeton est perdu, relancer `setup` localement pour le réafficher.

Dans **Voix et intelligence**, régler les connecteurs. Les champs sont enregistrés côté antenne et les clés sont masquées après sauvegarde.

### Recommandé pour le web et les images

Choisir **Google Images via Serper — recommandé**, créer une clé sur [serper.dev](https://serper.dev/), puis renseigner :

| Champ | Valeur conseillée |
|---|---|
| Clé API | la clé Serper du client |
| Langue | `fr` |
| Pays | `fr`, `be` ou `ch` selon le lieu |

Le même réglage sert à chercher des informations, des images et les sources d’une fiche de visite. Le bouton **Tester les réglages enregistrés** vérifie la recherche et l’affichage d’une image sans afficher la clé.

Consulter le compte Serper pour les crédits disponibles et les tarifs. Vérifier la licence de chaque image avant usage public.

### Les autres connecteurs

- **Modèle de conversation (LLM)** : choisir le fournisseur, saisir sa clé et sélectionner un modèle dans la liste. Ce modèle prépare les réponses de Pepper et les brouillons de visite.
- **Transcription** : choisir Whisper local pour garder l’audio sur le réseau. Le texte peut ensuite être envoyé au service de conversation choisi. Pour le texte en continu avec OpenAI, sélectionner **GPT Live Transcribe** ; l’audio est envoyé au fournisseur. Les performances dépendent de l’ordinateur ou du service utilisé.
- **Brave Search** : autre fournisseur pour le web et les images. Consulter son compte fournisseur pour les crédits et les tarifs.

Après chaque modification, cliquer sur **Enregistrer**, puis vérifier que le badge du connecteur indique qu’il est configuré.

### Créer une clé API, simplement

Une seule clé suffit par service. Créez-la sur le site du fournisseur, copiez-la une fois dans l’interface **Voix et intelligence**, puis cliquez sur **Enregistrer**. Il n’est pas nécessaire de relancer Docker pour changer de fournisseur : le réglage est utilisé à la prochaine demande. Si aucun fournisseur web n’est configuré, Pepper n’invente pas une recherche ; il indique qu’il ne peut pas accéder aux informations en ligne.

**Pour les recherches et les images — Serper (recommandé)**

1. Ouvrir [serper.dev](https://serper.dev/) et choisir **Sign up**.
2. Dans le tableau de bord, créer ou copier une clé API.
3. Dans Pepper, choisir **Google via Serper**, coller la clé, puis renseigner `fr` comme langue et `fr` comme pays si la visite est en France.
4. Cliquer sur **Enregistrer**, puis utiliser **Tester les réglages enregistrés**.

La même clé Serper sert à la recherche web, aux images et à la préparation d’une fiche de visite. Une recherche peut consommer un crédit ; les tarifs visibles dans Pepper sont indicatifs et le compte Serper fait foi.

**Alternative — Brave Search**

1. Ouvrir [la page Brave Search API](https://brave.com/search/api/) et créer un compte.
2. Activer une offre, ouvrir la rubrique **API Keys** et créer une clé.
3. Dans Pepper, choisir **Brave Search**, coller la clé et enregistrer.

Brave est utile si vous préférez son index ou son offre. Il n’y a pas de réglage de serveur à modifier ensuite : la clé enregistrée est prise en compte directement.

**Pour le modèle qui répond — OpenAI ou Anthropic**

- [OpenAI](https://platform.openai.com/api-keys) : créer une clé secrète, choisir **OpenAI** dans **Modèle de conversation**, sélectionner un modèle et enregistrer.
- [Anthropic Console](https://console.anthropic.com/settings/keys) : créer une clé API, choisir **Anthropic**, sélectionner un modèle et enregistrer.

La clé doit rester dans l’antenne. Ne la mettez ni dans l’APK, ni dans une capture d’écran, ni dans le dépôt Git. Une clé valide peut aussi nécessiter une facturation active chez le fournisseur.

**Pour la transcription audio**

- **Whisper local** : aucun compte ni clé API. Le modèle est téléchargé par l’antenne ; commencer par `base` si la machine est modeste.
- **OpenAI** : utiliser la même page de clé OpenAI, puis choisir **GPT Live Transcribe** pour une transcription continue ou un modèle de transcription classique. L’audio est alors envoyé à OpenAI et facturé selon l’offre du compte.
- **AWS Transcribe** : créer un compte AWS, choisir une région, donner accès à Transcribe, puis créer une identité IAM limitée au besoin. Dans Pepper, sélectionner le connecteur AWS et renseigner la clé d’accès, la clé secrète et la région. Les mêmes identifiants AWS peuvent servir à Bedrock si l’identité possède aussi l’autorisation pour le modèle choisi.

Pour AWS, il n’y a pas une clé unique à copier depuis une page « API keys » : il s’agit de credentials IAM. Utiliser une identité dédiée, avec le minimum de permissions, et préférer des credentials temporaires quand l’installation le permet. Voir la documentation officielle [Amazon Transcribe Streaming](https://docs.aws.amazon.com/transcribe/latest/dg/streaming.html), [AWS Bedrock](https://docs.aws.amazon.com/bedrock/latest/userguide/conversation-inference.html) et [IAM pour Bedrock](https://docs.aws.amazon.com/bedrock/latest/userguide/security_iam_id-based-policy-examples.html).

Le bon ordre pour une première mise en route est : **Whisper local**, **Serper**, puis le LLM choisi. En cas d’erreur, ouvrir la carte concernée, remplacer uniquement la clé, enregistrer et relancer le bouton de test.

## 3 · Préparer une visite

Dans **Préparer une visite** :

1. Saisir l’entreprise, le site officiel, les personnes attendues et l’objectif.
2. Cliquer sur la recherche pour obtenir un brouillon et ses sources.
3. Relire et corriger la fiche, puis cocher **J’ai relu la fiche et vérifié qu’il s’agit de la bonne entreprise**.
4. Cocher **Utiliser cet accueil personnalisé**, puis cliquer sur **Enregistrer l’accueil**. Le réglage s’applique aux prochains échanges ; l’accueil spontané se règle sur la tablette.

Dans **Les lieux à indiquer**, renseigner le nom du lieu et la phrase que Pepper doit dire. Choisir ensuite **Ne pas pointer**, **Pointer à gauche** ou **Pointer à droite**. Par exemple, pour « la cuisine », écrire « au fond du couloir » et choisir **Pointer à droite** : lorsqu’un visiteur demande où se trouve la cuisine, Pepper donne cette indication et pointe à droite. Les lieux existants sans côté de pointage continuent de fonctionner sans geste.

Dans **L’image affichée sur la tablette**, choisir une image déposée au préalable dans **Images et vidéos**. Pepper l’affiche en plein écran tant que personne ne lui parle. Toucher l’image la retire et rend la tablette à son interface habituelle ; elle revient après une minute sans conversation ni geste sur l’écran. Choisir **Aucune image** pour laisser la tablette sur son interface. Changer d’image, ou décocher **Utiliser cet accueil personnalisé**, prend effet sur la tablette en une minute environ, sans redémarrer le robot.

Pepper ne doit pas présenter une supposition comme une information sur un visiteur. Les sources sont visibles pour permettre une vérification humaine.

## 4 · Utilisation au quotidien

Dire « Pepper » ou toucher **Parler** pour commencer. La boule indique l’écoute, le traitement et la réponse. Les textes et les images s’affichent sur la tablette. Une image reste visible quelques secondes (environ 8 secondes par défaut), puis disparaît automatiquement. Si Pepper reçoit plusieurs images, elles passent dans l’ordre comme un carrousel ; toucher l’image pour l’interrompre. Le bouton de sourdine coupe l’écoute.

Après sa réponse, Pepper écoute la suite. Si personne ne reprend la parole pendant le délai d’écoute, la conversation se termine et Pepper revient en attente de « Pepper ». Dire à nouveau son nom ou toucher **Parler** pour commencer un nouvel échange. **J’ai terminé** envoie la phrase en cours ; ce bouton ne termine pas toute la conversation.

Pour terminer explicitement, dire **« Merci Pepper, à bientôt »** ou **« Au revoir »**. Pepper termine sa réponse, puis arrête l’écoute de conversation : seule la détection du mot « Pepper » reste active. L’accueil automatique est bloqué, même si Pepper détecte une personne ou si le micro est coupé puis réactivé. Dire **« Pepper »** ou toucher **Parler** pour reprendre et lever ce blocage. **« Merci »** ou **« Merci Pepper »** seuls ne terminent pas la conversation ; une formule suivie d’une nouvelle question conserve aussi l’échange.

Sur la tablette, toucher **Menu** pour afficher les rubriques et les réglages, puis **Fermer** pour replier la navigation et laisser plus de place à la conversation.

La manette permet de se déplacer et de déclencher les actions configurées. Les gestes disponibles se trouvent dans **Actions** ; les boutons se règlent dans **Manette**.

## 5 · Connexion de Pepper

1. Sur l’ordinateur, ouvrir l’administration avec le **jeton administrateur**.
2. Ouvrir **Connecter Pepper** et afficher le **jeton d’appairage**. C’est le seul jeton à saisir sur la tablette.
3. Sur Pepper, ouvrir l’application déjà installée, puis **Cerveau**. Saisir l’adresse LAN de l’antenne, par exemple `http://<adresse-de-l-antenne>:8770`, et le jeton d’appairage.
4. Enregistrer, puis tester le réveil vocal, l’écoute, une réponse, le retour en attente après un silence, le menu repliable, une image et la manette.

Ne jamais utiliser le jeton administrateur sur Pepper et ne jamais saisir `localhost` sur la tablette : `localhost` désigne la tablette elle-même. Si le jeton d’appairage est régénéré dans l’administration, il faut le recopier dans Pepper.

Pour vérifier la fin de conversation :

1. Dire « Merci » pendant un échange : Pepper répond et écoute la suite.
2. Dire « Merci Pepper, à bientôt » : attendre sa réponse complète, puis vérifier le retour en attente de « Pepper ».
3. Parler entre visiteurs sans prononcer « Pepper » : la conversation ne doit pas repartir. Vérifier aussi que la détection d’une personne et la coupure puis réactivation du micro ne relancent pas l’accueil automatique.
4. Dire « Pepper » pour reprendre, puis refaire la clôture et vérifier la reprise avec **Parler**.

## Dépannage express

| Symptôme | Vérification |
|---|---|
| Pepper ne répond pas | Docker est démarré et l’adresse est sur le même réseau local. |
| Pas de transcription | Tester Whisper local ou vérifier la clé STT dans l’administration. |
| Pas d’image | Tester Serper, vérifier les crédits, puis essayer une requête plus précise. |
| Accueil vide | Valider la fiche de visite et configurer le connecteur web. |
| Clé refusée | La remplacer dans l’interface ; ne jamais la mettre dans l’application tablette. |

Pour arrêter l’antenne, utiliser le bouton ou le script fourni. Ne pas faire `docker compose down -v` : cette commande efface les données persistantes.
