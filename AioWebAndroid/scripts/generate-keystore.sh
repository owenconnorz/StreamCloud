#!/usr/bin/env bash
# Generates a release keystore for AioWeb Android and prints the GitHub Secrets you need.
# Run once locally (requires JDK 17 installed → `keytool` is bundled with the JDK).
#
# Usage:  ./scripts/generate-keystore.sh
#
set -euo pipefail

OUT="release.keystore"
ALIAS="aioweb"
VALIDITY_YEARS=30

if [ -f "$OUT" ]; then
  echo "❌  $OUT already exists. Delete it first if you really want to regenerate."
  exit 1
fi

if ! command -v keytool >/dev/null 2>&1; then
  echo "❌  'keytool' not found. Install JDK 17 (e.g. 'sudo apt install openjdk-17-jdk-headless' or install Android Studio)."
  exit 1
fi

read -rsp "🔑  Choose a STORE password (min 6 chars): " STORE_PASS; echo
read -rsp "🔑  Confirm STORE password:               " STORE_PASS2; echo
[ "$STORE_PASS" = "$STORE_PASS2" ] || { echo "Passwords don't match"; exit 1; }
[ ${#STORE_PASS} -ge 6 ] || { echo "Password too short"; exit 1; }

read -rp  "👤  Your name (CN):                  " CN
read -rp  "🏢  Organization (O) [AioWeb]:       " ORG; ORG=${ORG:-AioWeb}
read -rp  "🌍  Country code (C, 2 letters) [US]: " CC;  CC=${CC:-US}

echo "→  Generating keystore $OUT (alias=$ALIAS, validity=${VALIDITY_YEARS}y)…"
keytool -genkeypair -v \
  -keystore "$OUT" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 2048 \
  -validity $((VALIDITY_YEARS*365)) \
  -storepass "$STORE_PASS" \
  -keypass  "$STORE_PASS" \
  -dname "CN=$CN, O=$ORG, C=$CC"

echo ""
echo "✅  Keystore created: $OUT"
echo ""
echo "─────────────────────────────────────────────────────────────"
echo " Now go to your GitHub repo → Settings → Secrets and variables → Actions"
echo " and add these FOUR repository secrets:"
echo "─────────────────────────────────────────────────────────────"
echo ""
B64=$(base64 -w0 "$OUT" 2>/dev/null || base64 "$OUT" | tr -d '\n')
echo "  KEYSTORE_BASE64    →  (the long string below — copy it ALL, no line breaks)"
echo ""
echo "$B64"
echo ""
echo "  KEYSTORE_PASSWORD  →  $STORE_PASS"
echo "  KEY_ALIAS          →  $ALIAS"
echo "  KEY_PASSWORD       →  $STORE_PASS"
echo ""
echo "─────────────────────────────────────────────────────────────"
echo " Local builds: copy keystore.properties.example → keystore.properties"
echo " and fill in the same values, then run:  ./gradlew assembleRelease"
echo "─────────────────────────────────────────────────────────────"
echo ""
echo "⚠️   KEEP $OUT AND THESE PASSWORDS SAFE — losing them means you can"
echo "    never publish updates that overwrite this app on a user's device."
echo "    DO NOT commit $OUT to git (it's already in .gitignore)."
