"""Read-only source diagnostics. Does not prove Android playback or Russian ISP access."""
import argparse
import base64
import hashlib
import itertools
import json
import re
import time
from datetime import datetime, timezone
from urllib.parse import urljoin

import requests
from bs4 import BeautifulSoup

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

def session():
    s = requests.Session()
    s.headers.update({"User-Agent": UA})
    return s

def get(s, url, **kwargs):
    r = s.get(url, timeout=20, **kwargs)
    r.raise_for_status()
    return r

def sample_hls(s, url, referer):
    r = get(s, url, headers={"Referer": referer})
    if not r.text.startswith("#EXTM3U"):
        raise ValueError("Response is not an HLS playlist")
    paths = [line.strip() for line in r.text.splitlines() if line.strip() and not line.startswith('#')]
    if not paths:
        raise ValueError("Empty HLS playlist")
    media_url = urljoin(r.url, paths[0])
    if '.m3u8' in media_url:
        r = get(s, media_url, headers={"Referer": referer})
        paths = [line.strip() for line in r.text.splitlines() if line.strip() and not line.startswith('#')]
        media_url = urljoin(r.url, paths[0])
    with get(s, media_url, stream=True, headers={"Referer": referer, "Range": "bytes=0-4095"}) as part:
        chunk = next(part.iter_content(4096), b'')
        if not chunk:
            raise ValueError("Empty media segment")
        return {"playlist": True, "media_bytes_sampled": len(chunk), "media_http": part.status_code}

def libria():
    s = session()
    base = 'https://anilibria.top'
    catalog = get(s, base + '/api/v1/anime/catalog/releases?limit=24&page=1').json()
    second = get(s, base + '/api/v1/anime/catalog/releases?limit=24&page=2').json()
    search = get(s, base + '/api/v1/app/search/releases', params={"query": "Наруто"}).json()
    if not search:
        raise ValueError('Search has no results')
    # Include an older title to check that the adapter is not limited to the current year.
    candidate = next((x for x in search if x.get('year', 9999) < 2026 and not x.get('is_blocked_by_geo') and not x.get('is_blocked_by_copyrights')), search[0])
    release = get(s, base + '/api/v1/anime/releases/' + str(candidate['id'])).json()
    ep = next(x for x in release['episodes'] if x.get('hls_720'))
    return {"status": "ok", "catalog_total": catalog['meta']['pagination']['total'],
            "page_1": len(catalog['data']), "page_2": len(second['data']),
            "search_results": len(search), "sample_year": release['year'], "episodes": len(release['episodes']),
            "stream": sample_hls(s, ep['hls_720'], base + '/')}

def rezka_connection():
    s = session()
    base = 'https://hdrezka-home.tv'
    r = get(s, base + '/')
    soup = BeautifulSoup(r.text, 'html.parser')
    challenge_node = soup.select_one('#anubis_challenge')
    if challenge_node:
        data = json.loads(challenge_node.string)
        challenge = data['challenge']
        difficulty = data.get('rules', {}).get('difficulty', 4)
        if difficulty > 6:
            raise ValueError('Challenge difficulty exceeds budget')
        started = time.monotonic()
        nonce = 0
        while True:
            digest = hashlib.sha256((challenge['randomData'] + str(nonce)).encode()).hexdigest()
            if digest.startswith('0' * difficulty):
                break
            nonce += 1
            if nonce % 4096 == 0 and time.monotonic() - started > 8:
                raise TimeoutError('Browser challenge exceeded 8 seconds')
        prefix_node = soup.select_one('#anubis_base_prefix')
        prefix = json.loads(prefix_node.string) if prefix_node else ''
        r = get(s, base + prefix + '/.within.website/x/cmd/anubis/api/pass-challenge', params={
            'id': challenge['id'], 'response': digest, 'nonce': nonce, 'redir': base + '/',
            'elapsedTime': max(50, int((time.monotonic() - started) * 1000))})
        r = get(s, base + '/')
        soup = BeautifulSoup(r.text, 'html.parser')
    return s, base, soup

def rezka():
    s, base, soup = rezka_connection()
    cards = soup.select('.b-content__inline_item')
    if not cards:
        raise ValueError('Browser verification or login still blocks the catalogue')
    search = get(s, base + '/search/', params={'do':'search','subaction':'search','q':'Матрица'})
    found = BeautifulSoup(search.text, 'html.parser').select('.b-content__inline_item')
    if not found:
        raise ValueError('Movie search returned no cards')
    href = found[0].select_one('a[href]')['href']
    page = get(s, href)
    detail = BeautifulSoup(page.text, 'html.parser')
    translators = detail.select('#translators-list [data-translator_id]')
    if not translators:
        raise ValueError('Movie has no available voiceover nodes')
    translator = translators[0]
    favs = detail.select_one('#ctrl_favs')
    post_id = re.search(r'/(\d+)-', href)[1]
    response = s.post(base + '/ajax/get_cdn_series/', params={'t':int(time.time()*1000)}, timeout=20,
                      headers={'Referer':href}, data={
                          'id':post_id, 'translator_id':translator['data-translator_id'],
                          'favs':favs.get('value','') if favs else '', 'action':'get_movie',
                          'is_camrip':translator.get('data-camrip','0'), 'is_ads':translator.get('data-ads','0'),
                          'is_director':translator.get('data-director','0')})
    response.raise_for_status()
    payload = response.json()
    raw = payload.get('url', '').removeprefix('#h').replace('//_//','')
    if not raw.startswith('['):
        for size in (3, 2):
            for token in itertools.product('@#!^$', repeat=size):
                raw = raw.replace(base64.b64encode(''.join(token).encode()).decode(), '')
        raw = base64.b64decode(raw).decode()
    links = re.findall(r'\[([^]]+)](.*?)(?=,\s*\[|$)', raw)
    result = {"status":"links_only", "home_cards":len(cards), "search_cards":len(found),
              "voiceover_nodes":len(translators), "qualities":[label for label, _ in links], "video_verified":False}
    url = next(value.strip().split(' or ')[0] for _, value in links if '.m3u8' in value)
    try:
        result['stream'] = sample_hls(s, url, base + '/')
        result['video_verified'] = True
        result['status'] = 'ok'
    except Exception as error:
        result['stream_error'] = type(error).__name__
    return result

def decode_filmix(value):
    value = value.strip().strip('"').replace('\\/', '/')
    if value.startswith(('http://', 'https://', '[')):
        return value
    if value.startswith(('#0', '#h')):
        value = value[2:]
    for token in [':<:bzl3UHQwaWk0MkdXZVM3TDdB', ':<:SURhQnQwOEM5V2Y3bFlyMGVI', ':<:bE5qSTlWNVUxZ01uc3h0NFFy', ':<:Mm93S0RVb0d6c3VMTkV5aE54', ':<:MTluMWlLQnI4OXVic2tTNXpU']:
        value = value.replace(token, '')
    return base64.b64decode(value).decode()

def filmix():
    s = session()
    base = 'https://filmix.gg'
    home = get(s, base + '/')
    soup = BeautifulSoup(home.text, 'html.parser')
    cards = soup.select('article.shortstory')
    if not cards:
        raise ValueError('No catalogue cards')
    movie = next(x for x in cards if '/serial' not in (x.select_one('a.watch')['href'] if x.select_one('a.watch') else ''))
    post_id = movie['data-id']
    r = s.post(base + '/api/movies/player-data', params={'t':int(time.time()*1000)},
               data={'post_id':post_id,'showfull':'true'}, timeout=20,
               headers={'Referer':base+'/', 'Origin':base,'X-Requested-With':'XMLHttpRequest',
                        'x-a-key':s.cookies.get('x-a-key', 'sinatra')})
    r.raise_for_status()
    payload = r.json()
    videos = payload.get('message', {}).get('translations', {}).get('video', {})
    if not videos:
        raise ValueError('Player returned no video translations: ' + str(payload.get('type')))
    raw = decode_filmix(next(iter(videos.values())))
    links = re.findall(r'\[([^]]+)](https?://.*?)(?=,\[|$)', raw)
    if not links:
        raise ValueError('No decoded movie links')
    quality, url = links[0]
    url = url.split(' or ')[0]
    if '.m3u8' in url:
        stream = sample_hls(s, url, base + '/')
    else:
        with get(s, url, stream=True, headers={'Referer':base+'/', 'Range':'bytes=0-4095'}) as part:
            chunk = next(part.iter_content(4096), b'')
            if not chunk: raise ValueError('Empty video')
            stream = {'media_bytes_sampled':len(chunk), 'media_http':part.status_code}
    return {'status':'ok','home_cards':len(cards),'voiceovers':len(videos),'sample_quality':quality,'stream':stream}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', default='docs/source-check.json')
    args = parser.parse_args()
    result = {'checked_at_utc':datetime.now(timezone.utc).isoformat(),
              'scope':'HTTP diagnostics from the build host; no Android device or Russian ISP verification', 'sources':{}}
    for name, check in [('AniLibria', libria), ('Filmix', filmix), ('HDRezka', rezka)]:
        try:
            result['sources'][name] = check()
        except Exception as error:
            result['sources'][name] = {'status':'unverified', 'error':re.sub(r'https?://\S+', '[URL]', str(error))[:350]}
        print(name, json.dumps(result['sources'][name], ensure_ascii=True), flush=True)
    with open(args.output, 'w', encoding='utf-8') as handle:
        json.dump(result, handle, ensure_ascii=False, indent=2)

if __name__ == '__main__':
    main()
