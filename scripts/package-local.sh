#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
umask 077
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$sdk" ] || [ ! -x "$sdk/build-tools/35.0.0/apksigner" ]; then
    printf '%s\n' '请设置 ANDROID_HOME，并安装 Android Build Tools 35.0.0。' >&2
    exit 1
fi
unsigned=app/build/outputs/apk/release/app-release-unsigned.apk
if [ ! -f "$unsigned" ]; then
    printf '%s\n' '请先运行 ./gradlew assembleRelease。' >&2
    exit 1
fi
mkdir -p .signing dist
if [ ! -f .signing/relay.jks ]; then
    if [ -f .signing/password ]; then
        printf '%s\n' '发现已有密码但缺少签名密钥，请先恢复原密钥，避免生成无法覆盖升级的新签名。' >&2
        exit 1
    fi
    openssl rand -base64 36 > .signing/password
    keytool -genkeypair -keystore .signing/relay.jks -storetype JKS -alias sms-relay \
        -keyalg RSA -keysize 3072 -validity 10000 \
        -dname 'CN=SMS Relay Local,OU=Personal,O=Local,C=CN' \
        -storepass:file .signing/password -keypass:file .signing/password -noprompt
fi
"$sdk/build-tools/35.0.0/zipalign" -f -p 4 "$unsigned" dist/sms-relay-aligned.apk
"$sdk/build-tools/35.0.0/apksigner" sign --ks .signing/relay.jks --ks-key-alias sms-relay \
    --ks-pass file:.signing/password \
    --out dist/sms-relay-1.4.1.apk dist/sms-relay-aligned.apk
"$sdk/build-tools/35.0.0/apksigner" verify --verbose dist/sms-relay-1.4.1.apk
rm dist/sms-relay-aligned.apk
(cd dist && shasum -a 256 sms-relay-1.4.1.apk > SHA256SUMS)
printf '%s\n' '已生成 dist/sms-relay-1.4.1.apk；请保留 .signing/ 供后续升级使用。'
