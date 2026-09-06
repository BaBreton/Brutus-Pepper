#!/usr/bin/env bash
# Bash 3.2 compatible, shared by the macOS and Linux launcher.
valid_lan_ip() {
    local ip=$1 a b c d
    [[ $ip =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || return 1
    IFS=. read -r a b c d <<< "$ip"
    local part
    for part in "$a" "$b" "$c" "$d"; do
        [[ ${#part} -le 3 && ( $part == 0 || $part != 0* ) ]] || return 1
        (( 10#$part <= 255 )) || return 1
    done
    (( a > 0 && a < 224 && a != 127 )) || return 1
    [[ $a.$b != 169.254 ]]
}

physical_addresses() {
    local iface ip device
    if [[ $(uname -s) == Darwin ]]; then
        command -v networksetup >/dev/null || return 0
        # Hardware inventory, never default route (which may belong to a VPN).
        while read -r iface; do
            ip=$(ipconfig getifaddr "$iface" 2>/dev/null) || continue
            valid_lan_ip "$ip" && printf '%s %s\n' "$iface" "$ip"
        done < <(networksetup -listallhardwareports | awk '
            /^Hardware Port:/ { physical = ($0 ~ /Wi-Fi|AirPort|Ethernet|LAN/) }
            /^Device: en[0-9]+$/ && physical { print $2 }')
    else
        command -v ip >/dev/null || return 0
        for device in /sys/class/net/*; do
            [[ -e $device/device ]] || continue
            iface=${device##*/}
            while read -r ip; do
                valid_lan_ip "$ip" && printf '%s %s\n' "$iface" "$ip"
            done < <(ip -o -4 address show dev "$iface" scope global | awk '{split($4,a,"/"); print a[1]}')
        done
    fi
    return 0
}

show_addresses() {
    local override=$1 line choice index=0
    local addresses=() interfaces=()
    printf '\nAdministration sur cet ordinateur : http://localhost:8770/\n'
    if [[ -n $override ]]; then
        printf 'Adresse LAN choisie manuellement : http://%s:8770\n' "$override"
        printf 'Vérifiez que cette IPv4 appartient à cet ordinateur sur le réseau du robot.\n'
        return
    fi
    while read -r line; do
        [[ -n $line ]] || continue
        interfaces[$index]=${line%% *}
        addresses[$index]=${line#* }
        index=$((index + 1))
    done < <(physical_addresses)
    if (( index == 1 )); then
        printf 'Réseau physique %s : http://%s:8770\n' "${interfaces[0]}" "${addresses[0]}"
    elif (( index > 1 )); then
        printf 'Plusieurs réseaux physiques : choisissez celui du robot.\n'
        for ((choice=0; choice<index; choice++)); do
            printf '  %s) %s — %s\n' "$((choice + 1))" "${interfaces[$choice]}" "${addresses[$choice]}"
        done
        if [[ -t 0 && -t 1 ]]; then
            read -r -p 'Numéro (Entrée pour choisir plus tard) : ' choice || choice=''
            if [[ $choice =~ ^[0-9]+$ && ${#choice} -lt 4 ]] && (( 10#$choice >= 1 && 10#$choice <= index )); then
                printf 'Adresse LAN : http://%s:8770\n' "${addresses[$((10#$choice - 1))]}"
                return
            fi
        fi
        printf 'Aucune adresse choisie. Relancez avec --lan-ip ADRESSE_IPV4.\n'
    else
        printf 'Aucun réseau physique IPv4 identifié. Connectez le Wi-Fi/Ethernet, puis\n'
        printf 'relancez avec --lan-ip ADRESSE_IPV4 si nécessaire (voir le guide).\n'
    fi
}
