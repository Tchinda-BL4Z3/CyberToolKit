#!/usr/bin/env bash
#
# Génère la clé de signature de production pour CyberToolkit.
#
# # Pourquoi ce script existe
#
# Une clé de signature ne se régénère pas. Google Play exige que la même clé
# signe toutes les versions à vie, et Android refuse d'installer une mise à jour
# signée par une clé différente de celle de l'application déjà installée. Une clé
# perdue ou oubliée, c'est une application que vous ne pouvez plus mettre à
# jour - pour vous et pour tous ceux qui l'ont installée.
#
# Donc : le mot de passe est demandé interactivement et n'est écrit nulle part.
# Ne le mettez pas dans un fichier, ne le dites pas dans un chat, ne le notez pas
# dans le dépôt. Notez-le dans un gestionnaire de mots de passe.
#
# La clé n'est jamais ajoutée au dépôt : `.gitignore` couvre `*.jks`.
#
# # Utilisation
#
#   ./tools/create_keystore.sh
#
# Ensuite, pour construire l'APK release :
#
#   export KEYSTORE_PATH="$PWD/upload-keystore.jks"
#   export STORE_PASSWORD='...'   # votre mot de passe
#   export KEY_PASSWORD='...'     # même valeur par défaut
#   export KEY_ALIAS=upload
#   ./gradlew :app:assembleRelease
#
# # Vérification
#
#   ~/Android/Sdk/build-tools/37.0.0/apksigner verify --print-certs app-release.apk

set -euo pipefail

readonly ALIAS="upload"
readonly VALIDITY_DAYS=10950   # 30 ans : au-delà de la durée de vie de l'app
readonly KEYSTORE_NAME="upload-keystore.jks"
readonly PW_FILE="/tmp/.cyber_keystore_pw.$$"

cd "$(dirname "$0")/.."

# Aucun mot de passe ne doit apparaître dans la ligne de commande : il serait
# lisible par n'importe quel processus du système via /proc.
cleanup() { rm -f "$PW_FILE"; }
trap cleanup EXIT

if [[ -f "$KEYSTORE_NAME" ]]; then
  echo "ERREUR : $KEYSTORE_NAME existe déjà." >&2
  echo "" >&2
  echo "Régénérer une clé ne fonctionne PAS : Android refuserait ensuite toute" >&2
  echo "mise à jour de l'application déjà installée avec l'ancienne clé." >&2
  echo "" >&2
  echo "Si vous l'avez simplement déplacée, effacez ce fichier." >&2
  echo "Si vous l'avez réellement perdu, la clé est irrécupérable et il faut" >&2
  echo "réinitialiser l'identifiant de l'application." >&2
  exit 1
fi

echo "Clé de signature de production - CyberToolkit"
echo ""
echo "Ce mot de passe ne sera ni stocké ni affiché. Notez-le dans un"
echo "gestionnaire de mots de passe : sans lui, cette clé est inutilisable."
echo ""

read -r -s -p "Mot de passe du keystore (min. 6 caractères) : " STORE_PASSWORD
echo
if [[ ${#STORE_PASSWORD} -lt 6 ]]; then
  echo "ERREUR : 6 caractères minimum." >&2
  exit 1
fi

printf '%s' "$STORE_PASSWORD" > "$PW_FILE"
chmod 600 "$PW_FILE"
export STORE_PASSWORD

# keytol lit le mot de passe depuis un fichier ou une variable d'environnement ;
# les deux évitent qu'il apparaisse dans `ps`. `-keypass` est identique à
# `-storepass` pour un keystore PKCS12 créé par keytool, qui n'autorise pas deux
# mots de passe distincts.
keytool -genkeypair \
  -keystore "$KEYSTORE_NAME" \
  -storetype PKCS12 \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -sigalg SHA256withRSA \
  -validity "$VALIDITY_DAYS" \
  -dname "CN=CyberToolkit, OU=SECOPS, O=CyberToolkit, L=Douala, C=CM" \
  -storepass:file "$PW_FILE" \
  -keypass:file "$PW_FILE"

chmod 600 "$KEYSTORE_NAME"

echo ""
echo "Clé créée : $KEYSTORE_NAME"
echo ""
# `keytool -list` avec -storepass:file lève une ArrayIndexOutOfBoundsException sur
# le JDK 21 de cette machine, et `-storepass <val>` filtre la sortie dans le
# stderr. Le certificat est donc lu directement dans le keystore, ce qui est de
# toute façon ce qu'on veut afficher : qui, et jusqu'à quand.
if command -v openssl > /dev/null 2>&1; then
  openssl pkcs12 -in "$KEYSTORE_NAME" -passin "file:$PW_FILE" -nokeys -legacy 2>/dev/null \
    | openssl x509 -noout -subject -dates 2>/dev/null
fi

echo ""
echo "À conserver ABSOLUMENT : ce fichier ET son mot de passe."
echo "Ni l'un ni l'autre ne doit être versionné ni transmis."
echo ""
echo "Sauvegardez la clé hors de ce dossier (clé USB, coffre-fort, gestionnaire"
echo "de mots de passe). Le disque de développement peut être perdu."
echo ""
echo "Prochaine étape :"
echo "  export KEYSTORE_PATH=\$PWD/$KEYSTORE_NAME"
echo "  export STORE_PASSWORD='...' KEY_PASSWORD='...' KEY_ALIAS=$ALIAS"
echo "  ./gradlew :app:assembleRelease"