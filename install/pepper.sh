#!/usr/bin/env bash
set +x
set -euo pipefail

INSTALL_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BRAIN_DIR="$(cd -- "$INSTALL_DIR/../server/brain" && pwd)"
# shellcheck source=network.sh
source "$INSTALL_DIR/network.sh"
fail() { printf 'Erreur : %s\n' "$*" >&2; exit 1; }
usage() {
    printf 'Usage : bash install/pepper.sh [start|setup|stop|status|check] [--lan-ip IPv4] [--wait secondes] [--open]\n'
    printf 'start construit et démarre ; setup ajoute un accès admin interactif local.\n'
}
ACTION=${1:-start}
[[ $# == 0 ]] || shift
LAN_IP=${PEPPER_LAN_IP:-}
WAIT=180
OPEN=0
case "$ACTION" in
    -h|--help|help) usage; exit 0 ;;
    start|setup|stop|status|check) ;;
    *) usage >&2; exit 1 ;;
esac
while (( $# )); do
    case "$1" in
        --lan-ip) [[ $# -ge 2 ]] || fail 'Valeur requise pour --lan-ip.'; LAN_IP=$2; shift 2 ;;
        --wait) [[ $# -ge 2 ]] || fail 'Valeur requise pour --wait.'; WAIT=$2; shift 2 ;;
        --open) OPEN=1; shift ;;
        *) fail "Option inconnue : $1" ;;
    esac
done
[[ -z $LAN_IP ]] || valid_lan_ip "$LAN_IP" || fail 'Adresse LAN IPv4 invalide (pas de localhost, multicast ou lien local).'
[[ $WAIT =~ ^[1-9][0-9]*$ && ${#WAIT} -le 4 ]] && (( WAIT <= 3600 )) || fail 'Le délai --wait doit être compris entre 1 et 3600 secondes.'
case "$(uname -s)/$(uname -m)" in
    Darwin/arm64|Darwin/x86_64|Linux/x86_64|Linux/aarch64|Linux/arm64) ;;
    *) fail 'Système non pris en charge : utilisez macOS ou Linux 64 bits (x86_64/ARM64), ou Pepper.cmd sur Windows.' ;;
esac
if [[ -n ${WSL_DISTRO_NAME:-} ]]; then
    fail 'Dans WSL, utilisez plutôt Pepper.cmd depuis Windows avec Docker Desktop en mode conteneurs Linux.'
fi
if [[ $ACTION == setup ]]; then
    [[ -t 0 && -t 1 && -t 2 && -z ${SSH_CONNECTION:-} && -z ${SSH_TTY:-} ]] || fail 'setup exige un terminal interactif local, sans redirection ni session SSH. Utilisez start sans jeton.'
fi
command -v docker >/dev/null || fail 'Docker absent. Installez Docker Desktop (Mac) ou Docker Engine et le plugin Compose (Linux), puis relancez.'
if [[ -n ${DOCKER_CONTEXT:-} || -z ${DOCKER_HOST:-} ]]; then
    ENDPOINT=$(docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null) || fail 'Contexte Docker introuvable. Sélectionnez un moteur Docker local.'
else
    ENDPOINT=$DOCKER_HOST
fi
case "$ENDPOINT" in
    unix:///*) ;;
    *) fail 'Le contexte Docker est distant ou TCP. Sélectionnez un contexte local avec socket Unix ; aucune installation distante effectuée.' ;;
esac
ENGINE=$(docker info --format '{{.OSType}}/{{.Architecture}}' 2>/dev/null) || fail 'Docker ne répond pas. Ouvrez Docker Desktop et attendez son démarrage ; sous Linux, faites vérifier le service et les droits du socket par votre administrateur.'
case "$ENGINE" in
    linux/amd64|linux/x86_64|linux/arm64|linux/aarch64) ;;
    *) fail 'Le moteur Docker doit utiliser des conteneurs Linux 64 bits (x86_64 ou ARM64).' ;;
esac
docker compose version >/dev/null 2>&1 || fail 'Docker Compose manque. Mettez Docker Desktop à jour ou installez le plugin docker-compose-plugin sous Linux.'
COMPOSE=(docker compose --project-directory "$BRAIN_DIR" --env-file "$INSTALL_DIR/compose.env" --project-name brain --file "$BRAIN_DIR/docker-compose.yml")
OWNER=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' pepper-brain 2>/dev/null) || OWNER=''
[[ -z $OWNER || $OWNER == brain ]] || fail 'Un conteneur pepper-brain appartient à une autre installation. Faites vérifier son projet et ses volumes par le support avant de continuer.'
if [[ $ACTION == check ]]; then
    printf 'Docker local Linux et Compose disponibles. Aucun conteneur modifié.\n'
    exit 0
fi
if [[ $ACTION == stop ]]; then
    "${COMPOSE[@]}" stop brain || fail 'Arrêt impossible ; vérifiez Docker.'
    printf 'Pepper arrêté. Données et modèles conservés.\n'
    exit 0
fi
command -v curl >/dev/null || fail 'curl manque. Installez curl puis relancez.'
healthy() {
    local state body
    state=$(docker inspect --format '{{.State.Health.Status}}' pepper-brain 2>/dev/null) || return 1
    [[ $state == healthy ]] || return 1
    body=$(curl --noproxy '*' --fail --silent --connect-timeout 1 --max-time 2 http://127.0.0.1:8770/api/health) || return 1
    [[ $body =~ \"status\"[[:space:]]*:[[:space:]]*\"ok\" ]]
}
if [[ $ACTION == status ]]; then
    "${COMPOSE[@]}" ps brain || fail 'État Docker indisponible.'
    healthy || fail 'Pepper est arrêté ou pas encore prêt. Relancez start ; si nécessaire transmettez ce diagnostic au support.'
    printf 'Pepper répond à /api/health.\n'
    show_addresses "$LAN_IP"
    exit 0
fi
# Dockerfile copies the context; refuse common private artifacts in a source checkout.
UNSAFE=$(find "$BRAIN_DIR" \( -name .venv -o -name __pycache__ \) -prune -o \( -name .env -o -name '*.token' -o -name '*.key' -o -name '*.pem' -o -name '*.enc' -o -name data -o -name models -o -name venv \) -print -quit)
[[ -z $UNSAFE ]] || fail 'Le dossier server/brain contient des données privées ou un environnement local. Demandez un ZIP client propre au support avant de construire.'
printf 'Construction locale de Pepper (Internet requis au premier lancement)…\n'
"${COMPOSE[@]}" build brain || fail 'Construction échouée. Vérifiez Internet, l’espace disque et Docker, puis relancez.'
"${COMPOSE[@]}" up -d --no-build --pull never brain || fail 'Démarrage échoué. Vérifiez notamment que le port 8770 est libre ; aucun volume supprimé.'
printf 'Attente du serveur (jusqu’à %s s, hors construction)…\n' "$WAIT"
DEADLINE=$((SECONDS + WAIT))
until healthy; do
    (( SECONDS < DEADLINE )) || fail 'Serveur non prêt dans le délai prévu. Utilisez status ou relancez avec --wait 600. Le conteneur et les données sont conservés.'
    sleep 1
done
printf 'Pepper est prêt pour la configuration. Whisper peut encore télécharger son modèle.\n'
show_addresses "$LAN_IP"
if [[ $ACTION == setup ]]; then
    printf '\nLe jeton administrateur donne accès aux réglages et clés du client.\n'
    read -r -p 'Afficher ce jeton uniquement dans ce terminal local privé et non enregistré ? [o/N] ' ANSWER || ANSWER=''
    if [[ $ANSWER == o || $ANSWER == O ]]; then
        # Never capture the token, send it to stdout, a URL, a file or a logger.
        printf '\nJeton administrateur (à coller dans la webapp) :\n' > /dev/tty
        "${COMPOSE[@]}" exec -T brain cat /data/admin.token > /dev/tty 2>/dev/null || fail 'Jeton indisponible. Relancez setup après vérification du serveur.'
        printf '\n\n' > /dev/tty
    fi
    if (( OPEN == 0 )); then
        read -r -p 'Ouvrir l’administration dans le navigateur ? [o/N] ' ANSWER || ANSWER=''
        [[ $ANSWER != o && $ANSWER != O ]] || OPEN=1
    fi
fi
if (( OPEN )); then
    if [[ $(uname -s) == Darwin ]]; then
        open http://localhost:8770/ || printf 'Ouvrez http://localhost:8770/ manuellement.\n'
    elif command -v xdg-open >/dev/null; then
        xdg-open http://localhost:8770/ >/dev/null 2>&1 || printf 'Ouvrez http://localhost:8770/ manuellement.\n'
    else
        printf 'Ouvrez http://localhost:8770/ manuellement.\n'
    fi
fi
