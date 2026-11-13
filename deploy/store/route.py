"""Update the managed TG-ru Downloads vhost without changing its legacy routes."""

from pathlib import Path
import re
import sys


START = "    # BEGIN TG-ru Store"
END = "    # END TG-ru Store"
OLD_ROOT = "    location = / { try_files /index.html =404; }"
STORE_ROUTES = (
    "register|login|recover|cabinet|account|devices|downloads|help|checkout|visibility|"
    "terms|privacy|refunds|compatibility|store\\.css|store\\.mjs|"
    "preview-(home|header|cabinet)\\.css|tg-ru-mark\\.png|fonts/inter-(cyrillic|latin)\\.woff2"
)


def routes():
    backend = (
        "        proxy_pass http://127.0.0.1:48185;\n"
        "        proxy_set_header Host tg.24projects.ru;\n"
        "        proxy_http_version 1.1;\n"
        "        proxy_set_header Connection '';\n"
        "        proxy_connect_timeout 3s;\n"
        "        proxy_read_timeout 20s;\n"
        "        client_max_body_size 24k;\n"
        "        access_log off;\n"
        '        add_header X-Robots-Tag "noindex, nofollow" always;\n'
    )
    return (
        START + "\n"
        "    location = / {\n" + backend + "    }\n"
        f"    location ~ ^/({STORE_ROUTES})$ {{\n" + backend + "    }\n"
        "    location ~ ^/(form|api/store)/ {\n"
        "        limit_req zone=tg_ru_store_requests burst=30 nodelay;\n"
        "        limit_req_status 429;\n" + backend + "    }\n"
        "    location = /downloads/TG-ru.apk {\n"
        "        limit_req zone=tg_ru_store_downloads burst=5 nodelay;\n"
        "        limit_req_status 429;\n"
        "        error_log /dev/null crit;\n"
        + backend + "    }\n"
        "    location = /v1/store-transport {\n"
        "        if ($http_upgrade !~* ^websocket$) { return 404; }\n"
        "        proxy_pass http://127.0.0.1:28445;\n"
        "        proxy_http_version 1.1;\n"
        "        proxy_set_header Host tg.24projects.ru;\n"
        "        proxy_set_header Upgrade $http_upgrade;\n"
        "        proxy_set_header Connection upgrade;\n"
        "        proxy_connect_timeout 3s;\n"
        "        proxy_read_timeout 180s;\n"
        "        proxy_send_timeout 180s;\n"
        "        proxy_buffering off;\n"
        "        access_log off;\n"
        "    }\n"
        "    location = /_tg_ru_store_apk {\n"
        "        internal;\n"
        "        access_log off;\n"
        "        error_log /dev/null crit;\n"
        "        alias /opt/tg-ru-store/current/TG-ru.apk;\n"
        "        types {}\n"
        "        default_type application/vnd.android.package-archive;\n"
        '        add_header Content-Disposition \'attachment; filename="TG-ru.apk"\';\n'
        "        add_header X-Content-Type-Options nosniff always;\n"
        "        add_header Cache-Control 'private, no-store' always;\n"
        '        add_header X-Robots-Tag "noindex, nofollow" always;\n'
        "    }\n" + END
    )


def render(config):
    zones = (
        "limit_req_zone $binary_remote_addr zone=tg_ru_store_requests:1m rate=30r/m;\n"
        "limit_req_zone $binary_remote_addr zone=tg_ru_store_downloads:1m rate=6r/m;\n"
    )
    if config.count("# Managed by TG-ru Downloads") != 1 or config.count("listen 443 ssl;") != 1:
        raise ValueError("Unexpected TG-ru vhost ownership or structure")
    if config.count("    location = /index.html { try_files $uri =404; }") != 1:
        raise ValueError("Existing Android update endpoint is missing")
    if config.count("    location = /download/TG-ru.apk {") != 1 or config.count("    location / { return 404; }") != 1:
        raise ValueError("Existing Android download endpoint is missing")
    if START in config or END in config:
        if config.count(START) != 1 or config.count(END) != 1 or config.count(zones) != 1:
            raise ValueError("Unexpected Store markers")
        config = re.sub(r"(?ms)^" + re.escape(START) + r".*?^" + re.escape(END), OLD_ROOT, config, count=1)
        config = config.replace(zones, "", 1)
    if config.count(OLD_ROOT) != 1:
        raise ValueError("Unexpected existing root route")
    existing_zone = "limit_req_zone $binary_remote_addr zone=tg_ru_downloads_per_ip:1m rate=6r/m;\n"
    if config.count(existing_zone) != 1:
        raise ValueError("Existing download rate limit is missing")
    return config.replace(existing_zone, existing_zone + zones, 1).replace(OLD_ROOT, routes(), 1)


if __name__ == "__main__":
    source, target = map(Path, sys.argv[1:])
    target.write_text(render(source.read_text(encoding="utf-8")), encoding="utf-8", newline="\n")
