"""Bounded checks of the public catalogs and one published media variant."""
import json
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urljoin
from urllib.request import Request, urlopen


class Missing(Exception):
    pass


def fetch(url, limit=512_000, headers=None, form=None):
    encoded = urlencode(form).encode("utf-8") if form is not None else None
    request = Request(url, data=encoded, headers={"User-Agent": "Mozilla/5.0", **(headers or {})})
    with urlopen(request, timeout=8) as response:
        return response.read(limit), response.url


def zona():
    body, _ = fetch("https://w1.zona.im/api/series/mentalist-2008?season=1")
    release = json.loads(body)["data"]
    if release["kp_id"] != 412344:
        raise ValueError("unexpected release")
    if not any(row["part"] == 1 and not row.get("is_banned") for row in release["episodes"]):
        raise Missing("expected episode absent from verified release")


def animevost():
    body, _ = fetch("https://v14.vost.pw/tip/polnometrazhnyy-film/1895-koe-no-katachi1.html")
    if b"ratig-layer-1895" not in body:
        raise ValueError("release identity not present")
    if b"var data" not in body:
        raise ValueError("episode markup not recognized")


def animevost_api():
    origin = "https://api.animevost.org/v1"
    pages = []
    for page in (1, 2):
        body, _ = fetch(f"{origin}/last?page={page}&quantity=20")
        result = json.loads(body)
        if result["state"]["page"] != page or not isinstance(result["data"], list):
            raise ValueError("catalog page contract changed")
        ids = {row["id"] for row in result["data"] if row.get("title") and row.get("id", 0) > 0}
        if not ids:
            raise ValueError("catalog page has no valid titles")
        pages.append(ids)
    if pages[0] == pages[1]:
        raise ValueError("catalog repeats the same page")
    body, _ = fetch(origin + "/info", form={"id": "1895"})
    release = next((row for row in json.loads(body)["data"] if row.get("id") == 1895), None)
    if release is None:
        raise Missing("known release absent from API response")
    if not release.get("title") or not release.get("series"):
        raise ValueError("release has no title or episodes")


def libria():
    base = "https://anilibria.top"
    body, _ = fetch(base + "/api/v1/anime/releases/9137")
    release = json.loads(body)
    if release["id"] != 9137:
        raise ValueError("unexpected release")
    if release.get("is_blocked_by_geo") or release.get("is_blocked_by_copyrights"):
        raise PermissionError("release restricted by source")
    urls = [row["hls_1080"] for row in release["episodes"] if row.get("hls_1080")]
    if not urls:
        raise Missing("no 1080p variant in verified release")
    headers = {"Referer": base + "/"}
    url = urls[0]
    for _ in range(3):
        body, final_url = fetch(url, headers=headers)
        lines = body.decode().strip().splitlines()
        if not lines or lines[0] != "#EXTM3U":
            raise ValueError("invalid HLS playlist")
        child = next(line for line in lines if line.strip() and not line.startswith("#"))
        url = urljoin(final_url, child)
        if any(line.startswith("#EXTINF:") for line in lines):
            segment, _ = fetch(url, 32, {**headers, "Range": "bytes=0-31"})
            if not (segment.startswith((b"G", b"ID3")) or segment[4:8] in (b"ftyp", b"styp", b"moof")):
                raise ValueError("media sample not recognized")
            return
    raise ValueError("unexpected HLS nesting")


def check(name, probe):
    try:
        probe()
        status, detail = "ok", ""
    except HTTPError as error:
        # A CDN 404 may mean an expired URL. It does not prove deletion.
        status, detail = "http_error", str(error.code)
    except (URLError, TimeoutError):
        status, detail = "network_error", ""
    except PermissionError:
        status, detail = "restricted", ""
    except Missing as error:
        status, detail = "missing_from_catalog", str(error)
    except (KeyError, ValueError, TypeError, StopIteration):
        status, detail = "unexpected_response", ""
    return {"check": name, "status": status, "detail": detail}


if __name__ == "__main__":
    results = [check("Zona episode", zona), check("AnimeVost card", animevost),
               check("AnimeVost API catalog and card", animevost_api), check("AniLibria 1080p sample", libria)]
    print(json.dumps(results, indent=2))
    raise SystemExit(any(row["status"] != "ok" for row in results))
