#!/usr/bin/env bash
#
# One-time setup of the release signing key for "Send to BYD".
#
# Two modes, auto-detected from whether the keystore already exists:
#   * FRESH  — no keystore yet: generates one, then stores its secrets in the
#              GNOME keyring so `./gradlew assembleRelease` signs automatically.
#   * RESTORE— keystore already present (e.g. you restored it from Keeper onto a
#              new laptop): re-stores the secrets in the keyring, no new key made.
#
# It NEVER overwrites an existing keystore — a different key can't update apps
# already installed with the old one.
#
# Usage:  ./setup-signing.sh [path-to-keystore]
#         (default path: ~/.android/byd-share-release.jks)
#
set -euo pipefail

KEYSTORE="${1:-$HOME/.android/byd-share-release.jks}"
ALIAS="byd-share"
SERVICE="byd-share"        # keyring "service" attribute the Gradle build looks up

die() { echo "error: $*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] && die "Don't run this with sudo/as root. The signing key and the GNOME keyring are per-user — under sudo the key lands in /root and secret-tool can't reach your keyring. Run it as yourself: ./setup-signing.sh"

command -v keytool     >/dev/null || die "keytool not found — install a JDK (e.g. Temurin 17)."
command -v secret-tool >/dev/null || die "secret-tool not found — install libsecret-tools."
[ -n "${DBUS_SESSION_BUS_ADDRESS:-}" ] || die "No D-Bus session bus — run this in your normal desktop session (not sudo / bare ssh), so the keyring is reachable."

echo "Keystore path: $KEYSTORE"
echo

if [ -e "$KEYSTORE" ]; then
    # ---- RESTORE mode -------------------------------------------------------
    echo "A keystore already exists here — NOT generating a new one."
    echo "Enter its password to verify it and (re)store the secrets in the keyring."
    read -rsp "Keystore password: " PW; echo
    [ -n "$PW" ] || die "Empty password."
    export KS_PW="$PW"
    keytool -list -keystore "$KEYSTORE" -alias "$ALIAS" -storepass:env KS_PW >/dev/null 2>&1 \
        || die "Password/alias didn't open the keystore. Nothing changed."
    echo "Verified."
else
    # ---- FRESH mode ---------------------------------------------------------
    echo "No keystore yet — creating one. Choose a password you'll keep forever"
    echo "(store password and key password are set the same, which is standard)."
    read -rsp "Choose a keystore password: " PW;  echo
    read -rsp "Re-enter the password:      " PW2; echo
    [ -n "$PW" ]        || die "Empty password not allowed."
    [ "$PW" = "$PW2" ]  || die "Passwords didn't match. Nothing changed."
    export KS_PW="$PW"

    mkdir -p "$(dirname "$KEYSTORE")"
    # -dname avoids the interactive identity questions; :env keeps the password
    # out of the process argument list (which `ps` could otherwise show).
    keytool -genkeypair -v \
        -keystore "$KEYSTORE" \
        -storetype PKCS12 \
        -alias "$ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass:env KS_PW -keypass:env KS_PW \
        -dname "CN=Send to BYD, OU=byd-share, O=stink.no, C=NO"
    echo "Created $KEYSTORE"
fi

# ---- store the four fields in the GNOME keyring -----------------------------
# printf '%s' (no trailing newline) so the stored secret is exactly the value.
echo
echo "Storing signing secrets in the GNOME keyring (service=$SERVICE)…"
printf '%s' "$KEYSTORE" | secret-tool store --label="byd-share storeFile"     service "$SERVICE" field storeFile
printf '%s' "$PW"       | secret-tool store --label="byd-share storePassword" service "$SERVICE" field storePassword
printf '%s' "$ALIAS"    | secret-tool store --label="byd-share keyAlias"      service "$SERVICE" field keyAlias
printf '%s' "$PW"       | secret-tool store --label="byd-share keyPassword"   service "$SERVICE" field keyPassword

# ---- verify ----------------------------------------------------------------
echo
echo "Keyring now holds:"
for f in storeFile storePassword keyAlias keyPassword; do
    v="$(secret-tool lookup service "$SERVICE" field "$f" 2>/dev/null || true)"
    case "$f" in
        *Password) echo "  $f: $([ -n "$v" ] && echo '**** (set)' || echo 'MISSING')" ;;
        *)         echo "  $f: ${v:-MISSING}" ;;
    esac
done

FINGERPRINT="$(keytool -list -v -keystore "$KEYSTORE" -alias "$ALIAS" -storepass:env KS_PW 2>/dev/null \
    | grep -E "SHA256:" | head -1 | sed 's/.*SHA256: *//')"

cat <<EOF

================================================================================
 STORE THESE IN KEEPER  (do it now — the keyring is NOT a backup)
================================================================================
 1. Attach this file to a Keeper record:
        $KEYSTORE
 2. Save these values in the same record:
        keyAlias        : $ALIAS
        storePassword   : <the password you just typed>
        keyPassword     : <same as storePassword>
        SHA-256 (cert)  : ${FINGERPRINT:-"(run: keytool -list -v -keystore $KEYSTORE -alias $ALIAS)"}

 (The password is not printed on purpose — it's the one you entered above. Type
  it straight into Keeper. Everything else is shown literally so you can copy it.)
================================================================================

 Build a signed release any time, no manual step:
     ./gradlew assembleRelease
     -> app/build/outputs/apk/release/app-release.apk

 If this laptop dies: restore the .jks from Keeper to the same path, then re-run
 this script — it detects the existing file and just re-stores the keyring.
EOF

unset KS_PW PW PW2 2>/dev/null || true
