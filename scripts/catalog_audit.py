"""Audit available metadata, without claiming a playback check for every title."""
import json
import re
import time
from datetime import datetime, timezone
from pathlib import Path
from live_smoke import session, get, rezka_connection, BeautifulSoup

result = {'checked_at_utc':datetime.now(timezone.utc).isoformat(),
          'scope':'metadata audit; not a title-by-title playback or licensing audit', 'sources':{}}

def audit_libria():
    s = session()
    rows = []
    page = 1
    expected = None
    while True:
        response = get(s, 'https://anilibria.top/api/v1/anime/catalog/releases', params={'limit':50, 'page':page})
        payload = response.json()
        pagination = payload['meta']['pagination']
        expected = pagination['total']
        rows.extend(payload['data'])
        print(f'AniLibria metadata page {page}/{pagination["total_pages"]}: {len(rows)}', flush=True)
        if page >= pagination['total_pages']:
            break
        page += 1
        time.sleep(0.25)
    ids = [x['id'] for x in rows]
    years = [x['year'] for x in rows if isinstance(x.get('year'), int)]
    return {'status':'ok', 'pages':page, 'reported_total':expected, 'rows':len(rows), 'unique_ids':len(set(ids)),
            'complete_at_time_of_read':len(ids)==expected and len(set(ids))==expected,
            'year_min':min(years), 'year_max':max(years),
            'year_2026':sum(y==2026 for y in years), 'before_2026':sum(y<2026 for y in years),
            'missing_name':sum(not x.get('name',{}).get('main') for x in rows),
            'blocked_by_geo':sum(bool(x.get('is_blocked_by_geo')) for x in rows),
            'blocked_by_copyright':sum(bool(x.get('is_blocked_by_copyrights')) for x in rows)}

def audit_rezka():
    s, base, _ = rezka_connection()
    sections = {}
    for path, label in [('films','films'),('series','series'),('cartoons','cartoons'),('animation','anime')]:
        response = get(s, f'{base}/{path}/?filter=last')
        response.encoding = 'utf-8'
        soup = BeautifulSoup(response.text, 'html.parser')
        cards = soup.select('.b-content__inline_items .b-content__inline_item')
        if not cards:
            raise ValueError(f'{label}: no catalogue cards')
        page_numbers = [int(x) for x in re.findall(r'/page/(\d+)/', str(soup.select_one('.b-navigation') or ''))]
        last_page = max(page_numbers, default=1)
        years = []
        for card in cards:
            node = card.select_one('.b-content__inline_item-link > div')
            m = re.search(r'\b(?:18|19|20)\d{2}\b', node.get_text() if node else '')
            if m: years.append(int(m[0]))
        last = get(s, f'{base}/{path}/page/{last_page}/?filter=last')
        last_cards = BeautifulSoup(last.text, 'html.parser').select('.b-content__inline_items .b-content__inline_item')
        sections[label] = {'first_page_cards':len(cards), 'last_page':last_page, 'last_page_cards':len(last_cards),
                           'first_page_years':sorted(set(years)),
                           'sample_titles':[x.select_one('.b-content__inline_item-link > a').get_text(strip=True) for x in cards[:3]]}
        print(f'HDRezka {label}: first={len(cards)}, last page={last_page}, last={len(last_cards)}', flush=True)
        time.sleep(0.25)
    return {'status':'category_pages_verified', 'sections':sections,
            'all_titles_enumerated':False, 'all_titles_played':False}

for name, check in [('AniLibria',audit_libria),('HDRezka',audit_rezka)]:
    try:
        result['sources'][name] = check()
    except Exception as error:
        result['sources'][name] = {'status':'incomplete', 'error':re.sub(r'https?://\S+', '[URL]',str(error))[:250]}
    print(name, json.dumps(result['sources'][name],ensure_ascii=True),flush=True)

Path('docs/catalog-audit.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
