#!/bin/sh
set -eu

script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
region=${AWS_REGION:-ap-southeast-2}
case "$region" in
    ''|*[!a-z0-9-]*) echo "Invalid AWS_REGION: $region" >&2; exit 1 ;;
esac
temp_dir=$(mktemp -d "$script_dir/.rds-certs.XXXXXX")

cleanup() {
    rm -f "$temp_dir"/rds-ca-*.pem "$temp_dir/bundle.pem" "$temp_dir/rds-truststore.p12"
    rmdir "$temp_dir"
}
trap cleanup EXIT

curl --fail --silent --show-error --location --proto '=https' --connect-timeout 10 --max-time 30 \
    "https://truststore.pki.rds.amazonaws.com/$region/$region-bundle.pem" \
    --output "$temp_dir/bundle.pem"

awk -v dir="$temp_dir" '
    /^-----BEGIN CERTIFICATE-----/ {
        file = sprintf("%s/rds-ca-%03d.pem", dir, ++count)
    }
    file != "" { print > file }
    /^-----END CERTIFICATE-----/ {
        close(file)
        file = ""
    }
    END { if (count == 0 || file != "") exit 1 }
' "$temp_dir/bundle.pem"

# The store contains public CA certificates only. MySQL Connector/J reads it without modifying JVM-wide trust.
for certificate in "$temp_dir"/rds-ca-*.pem; do
    keytool -importcert -noprompt -storetype PKCS12 -storepass changeit \
        -keystore "$temp_dir/rds-truststore.p12" \
        -alias "$(basename "$certificate" .pem)" -file "$certificate"
done

chmod 0644 "$temp_dir/rds-truststore.p12"
mv -f "$temp_dir/rds-truststore.p12" "$script_dir/rds-truststore.p12"
