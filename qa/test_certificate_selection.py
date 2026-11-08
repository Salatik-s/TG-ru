"""Real OpenSSL trust/name/key checks using an isolated CA; no host trust changes."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
BASH = Path("C:/Program Files/Git/bin/bash.exe") if os.name == "nt" else Path("/bin/bash")
OPENSSL = Path("C:/Program Files/Git/usr/bin/openssl.exe") if os.name == "nt" else Path(shutil.which("openssl") or "/not-found")


@unittest.skipUnless(BASH.exists() and OPENSSL.exists(), "Bash/OpenSSL unavailable")
class CertificateSelectionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="familygram-certificate-test-")
        self.root = Path(self.temp.name)
        self.ca = self.root / "ca.pem"
        self.ca_key = self.root / "ca.key"
        self.openssl("req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256", "-nodes", "-days", "2", "-subj", "/CN=FamilyGram isolated test CA", "-addext", "basicConstraints=critical,CA:TRUE", "-keyout", str(self.ca_key), "-out", str(self.ca))

    def tearDown(self):
        self.temp.cleanup()

    def openssl(self, *args):
        subprocess.run([str(OPENSSL), *args], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)

    def lineage(self, name, hostname="24projects.ru", wrong_key=False):
        live = self.root / "live" / name
        archive = self.root / "archive" / name
        live.mkdir(parents=True)
        archive.mkdir(parents=True)
        key, csr, cert = archive / "privkey1.pem", archive / "request.pem", archive / "cert1.pem"
        self.openssl("req", "-new", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256", "-nodes", "-subj", "/CN=" + hostname, "-keyout", str(key), "-out", str(csr))
        extensions = archive / "extensions.txt"
        extensions.write_text("basicConstraints=critical,CA:FALSE\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:" + hostname + "\n")
        self.openssl("x509", "-req", "-in", str(csr), "-CA", str(self.ca), "-CAkey", str(self.ca_key), "-CAcreateserial", "-days", "2", "-extfile", str(extensions), "-out", str(cert))
        (live / "fullchain.pem").write_bytes(cert.read_bytes() + self.ca.read_bytes())
        (live / "privkey.pem").write_bytes(self.ca_key.read_bytes() if wrong_key else key.read_bytes())
        return live

    def select(self):
        installer = (ROOT / "deploy/install.sh").read_text(encoding="utf-8")
        function = installer[installer.index("select_certificate()"):installer.index('\n[[ "$MODE"')]
        # Windows fixtures cannot rely on symlink privileges. Only readlink resolution is
        # modeled; certificate trust, hostname, expiry and public-key checks are real.
        resolver = r'''
openssl() { "$FG_OPENSSL_EXE" "$@"; }
readlink() {
  local parent=${2%/*} file=${2##*/}
  local name=${parent##*/}
  case "$file" in fullchain.pem) printf '%s/archive/%s/fullchain1.pem' "$FG_CERT_ROOT" "$name";;
  privkey.pem) printf '%s/archive/%s/privkey1.pem' "$FG_CERT_ROOT" "$name";; *) return 1;; esac
}
'''
        return subprocess.run([str(BASH), "-c", "set -Eeuo pipefail\n" + resolver + function + '\nselect_certificate "$FG_CERT_ROOT"\nprintf "SELECTED=%s\\n" "$FAMILYGRAM_CERT_LIVE"'], env={**os.environ, "FG_OPENSSL_EXE": OPENSSL.as_posix(), "FG_CERT_ROOT": self.root.as_posix(), "SSL_CERT_FILE": self.ca.as_posix()}, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def test_portfolio_lineage_selected(self):
        live = self.lineage("24projects-24projects.ru")
        result = self.select()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("SELECTED=" + live.as_posix(), result.stdout)

    def test_other_domain_rejected(self):
        self.lineage("voise.24projects.ru", hostname="voise.24projects.ru")
        result = self.select()
        self.assertNotEqual(result.returncode, 0)

    def test_mismatched_private_key_rejected(self):
        self.lineage("24projects-24projects.ru", wrong_key=True)
        result = self.select()
        self.assertNotEqual(result.returncode, 0)

    def test_bad_preferred_candidate_falls_back(self):
        self.lineage("24projects-24projects.ru", hostname="wrong.example")
        live = self.lineage("24projects.ru-0001")
        result = self.select()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("SELECTED=" + live.as_posix(), result.stdout)


if __name__ == "__main__":
    unittest.main()
