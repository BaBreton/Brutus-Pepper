# Installer l’application sur Pepper

L’application est fournie dans `application/Pepper.apk`. Le guide illustré se trouve
dans `docs/PEPPER_CLIENT_GUIDE.html`, avec une version imprimable au format PDF.
Les sources du serveur sont dans `server/brain` et les lanceurs dans `install`.

## Installation sur la tablette — avec votre installateur

1. Connecter la tablette et l’ordinateur au même réseau.
2. Activer le débogage ADB de la tablette et relever son adresse IP.
3. Avec Android Platform Tools installé, lancer les commandes suivantes en
   remplaçant `IP_TABLETTE` par son adresse :

```sh
adb connect IP_TABLETTE:5555
adb devices -l
adb -s IP_TABLETTE:5555 install -r application/Pepper.apk
adb -s IP_TABLETTE:5555 shell am start -n com.brutus.pepper/.MainActivity
```

Accepter la demande d’autorisation sur la tablette si elle apparaît. L’état ADB
doit être `device` avant l’installation. L’option `-r` conserve les réglages ;
ne pas désinstaller l’ancienne application pour contourner une erreur de signature.
Cette version est un APK de livraison interne signé avec la clé de développement,
prévu pour cette tablette, et non une publication sur un magasin d’applications.

## Relier Pepper au serveur

Démarrer le serveur en suivant le guide. Dans son administration, ouvrir
« Connecter Pepper ». Sur la tablette, ouvrir « Cerveau » et saisir l’adresse
du **serveur** avec son port `8770`, puis le code d’appairage affiché.
L’adresse de la tablette utilisée pour ADB et celle du serveur peuvent être différentes.

Les clés des fournisseurs se règlent dans « Voix et intelligence » sur le serveur.
Elles ne sont pas incluses dans ce ZIP.

## Contrôle après installation

Tester l’ouverture et la fermeture du menu latéral, le réveil avec « Pepper »,
une question et sa réponse, puis « Merci Pepper, à bientôt ». Pepper doit revenir
en attente : une conversation entre visiteurs ne doit pas relancer l’échange.
Dire à nouveau « Pepper » pour reprendre. Vérifier ensuite une image et la manette,
avec l’espace nécessaire autour du robot.
