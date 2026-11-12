import unittest

from route import render


LEGACY_APK = """    location = /download/TG-ru.apk {
        limit_req zone=tg_ru_downloads_per_ip burst=5 nodelay;
        auth_basic "TG-ru";
        auth_basic_user_file /var/www/tg-ru-downloads/current/download.htpasswd;
        alias /var/www/tg-ru-downloads/current/packages/TG-ru.apk;
    }"""

LEGACY = """# Managed by TG-ru Downloads
server {
    listen 80;
    server_name tg.24projects.ru;
    location / { return 301 https://tg.24projects.ru$request_uri; }
}
limit_req_zone $binary_remote_addr zone=tg_ru_downloads_per_ip:1m rate=6r/m;
server {
    listen 443 ssl;
    server_name tg.24projects.ru;
    location = / { try_files /index.html =404; }
    location = /index.html { try_files $uri =404; }
""" + LEGACY_APK + """
    location / { return 404; }
}
"""


class StoreRoutingTest(unittest.TestCase):
    def test_preserves_legacy_routes_and_certificate_challenges(self):
        output = render(LEGACY)
        self.assertIn(LEGACY_APK, output)
        self.assertIn("    location = /index.html { try_files $uri =404; }", output)
        self.assertEqual(LEGACY.split("limit_req_zone")[0], output.split("limit_req_zone")[0])
        self.assertEqual(output, render(output))

    def test_store_apk_requires_private_internal_redirect(self):
        output = render(LEGACY)
        external = output.split("    location = /downloads/TG-ru.apk {", 1)[1].split("    }", 1)[0]
        internal = output.split("    location = /_tg_ru_store_apk {", 1)[1].split("    }", 1)[0]
        self.assertIn("proxy_pass http://127.0.0.1:48185;", external)
        self.assertIn("access_log off;", external)
        self.assertIn("internal;", internal)
        self.assertIn("access_log off;", internal)
        self.assertIn("alias /opt/tg-ru-store/current/TG-ru.apk;", internal)

    def test_unmanaged_vhost_or_missing_legacy_route_fails_closed(self):
        for unexpected in (
            LEGACY.replace("# Managed by TG-ru Downloads", "# Manually installed"),
            LEGACY.replace("location = /index.html", "location = /archive.html"),
            LEGACY.replace(LEGACY_APK, ""),
        ):
            with self.assertRaises(ValueError):
                render(unexpected)

    def test_store_wss_has_its_own_loopback_upstream(self):
        output = render(LEGACY)
        ws = output.split("    location = /v1/store-transport {", 1)[1].split("\n    }", 1)[0]
        self.assertIn("proxy_pass http://127.0.0.1:28445;", ws)
        self.assertIn("proxy_set_header Upgrade $http_upgrade;", ws)
        self.assertIn("return 404", ws)


if __name__ == "__main__":
    unittest.main()
