#!/usr/bin/env bash
# ==================================================================
#  Verify a recovered arena.keystore against the HISTORICAL ReplyMate
#  release certificate BEFORE it signs anything (P-release-2 recovery).
#
#  No keytool needed (a tiny JVM verifier is compiled on the fly).
#  The password is read from $KS_PASS_FILE or prompted silently — never
#  echoed, never logged, never written to disk/history.
#
#  Usage:
#    scripts/verify_keystore.sh /path/to/arena.keystore
#    KS_PASS_FILE=/path/to/600-perm-pwfile scripts/verify_keystore.sh ks
#
#  Exit: 0 = MATCH (original identity confirmed) · 2 = MISMATCH ·
#        3 = could not unlock · 1 = usage/env error
# ==================================================================
set -euo pipefail

KS="${1:?usage: verify_keystore.sh <path-to-arena.keystore>}"
[ -f "$KS" ] || { echo "FATAL: not found: $KS" >&2; exit 1; }

# The identity that signed every shipped ReplyMate ≤1.5.8 APK
# (extracted from META-INF/ARENA.RSA of releases/ReplyMate-1.5.8.apk).
EXPECTED="B1:5F:2F:37:FC:E1:9B:56:46:83:FE:6B:85:72:5A:9D:31:92:DF:93:18:1E:F2:F3:62:86:B5:E2:1C:6A:85:ED"

if [ -n "${KS_PASS_FILE:-}" ]; then
  PASS="$(cat "$KS_PASS_FILE")"
elif [ -t 0 ]; then
  read -rsp "keystore password: " PASS; echo
else
  echo "FATAL: no tty and KS_PASS_FILE unset — refusing password on argv/stdin-pipe" >&2
  exit 1
fi

WORK="$(mktemp -d)"
cleanup() { rm -rf "$WORK"; unset PASS; }
trap cleanup EXIT

cat > "$WORK/VerifyKs.java" <<'JAVAEOF'
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.Enumeration;

public class VerifyKs {
    public static void main(String[] a) {
        String path = a[0], expected = a[1];
        char[] pass = System.getenv("KS_PASS").toCharArray();
        KeyStore ks;
        String type;
        try {
            // JKS or PKCS#12 — detect by magic (FE ED FE ED = JKS; 0x30 = DER SEQUENCE = PKCS#12)
            byte[] head = new byte[4];
            try (FileInputStream fin = new FileInputStream(path)) {
                int n = fin.read(head);
                if (n < 4) throw new java.io.IOException("store too short");
            }
            type = ((head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xED
                 && (head[2] & 0xFF) == 0xFE && (head[3] & 0xFF) == 0xED) ? "JKS" : "PKCS12";
            ks = KeyStore.getInstance(type);
            try (FileInputStream in = new FileInputStream(path)) { ks.load(in, pass); }
            System.out.println("store-type  = " + type);
        } catch (Exception e) {
            System.out.println("RESULT=UNLOCK-FAILED  (" + e.getClass().getSimpleName() + ": wrong password or corrupt store)");
            System.exit(3);
            return;
        }
        try {
            Enumeration<String> al = ks.aliases();
            if (!al.hasMoreElements()) { System.out.println("RESULT=EMPTY-STORE"); System.exit(2); return; }
            String alias = al.nextElement();
            Certificate c = ks.getCertificate(alias);
            byte[] fp = MessageDigest.getInstance("SHA-256").digest(c.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (byte b : fp) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16)).append(':');
            sb.setLength(sb.length() - 1);
            String got = sb.toString().toUpperCase();
            System.out.println("alias       = " + alias);
            System.out.println("cert-sha256 = " + got);
            System.out.println("expected    = " + expected);
            boolean match = got.equalsIgnoreCase(expected);
            System.out.println("RESULT=" + (match
                ? "MATCH — original ReplyMate release identity CONFIRMED. Update-in-place over <=1.5.8 is restored. Use via REPLYMATE_JKS (see docs/signing-key-migration.md SS4)."
                : "MISMATCH — this is NOT the key that signed the shipped line. Do NOT release with it."));
            System.exit(match ? 0 : 2);
        } catch (Exception e) {
            System.out.println("RESULT=ERROR (" + e.getClass().getSimpleName() + ")");
            System.exit(2);
        }
    }
}
JAVAEOF

javac -encoding UTF-8 -nowarn -d "$WORK" "$WORK/VerifyKs.java"
KS_PASS="$PASS" java -cp "$WORK" VerifyKs "$KS" "$EXPECTED"
