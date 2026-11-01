"""A title's features, the same the TV computes (Recs.features in Java): genres, original language, decade, top 5 cast,
director(s) or creator(s), keywords, movie or series. Keep the two in step."""

LANGUAGES = {'ta': 'Tamil', 'ml': 'Malayalam', 'te': 'Telugu', 'hi': 'Hindi', 'kn': 'Kannada', 'en': 'English',
             'ko': 'Korean', 'ja': 'Japanese', 'es': 'Spanish', 'fr': 'French', 'bn': 'Bengali', 'mr': 'Marathi'}


def features(details, kind):
    """[feature ids] for a /3/{kind}/{id}?append_to_response=credits,keywords answer."""
    f = []
    for g in details.get('genres') or []:
        f.append(f'g:{g["id"]}')
    lang = details.get('original_language') or ''
    if lang:
        f.append(f'l:{lang}')
    date = details.get('release_date' if kind == 'movie' else 'first_air_date') or ''
    if len(date) >= 4 and date[:4].isdigit():
        f.append(f'd:{int(date[:4]) // 10 * 10}')
    credits = details.get('credits') or {}
    for c in (credits.get('cast') or [])[:5]:
        f.append(f'c:{c["id"]}')
    if kind == 'movie':
        for c in credits.get('crew') or []:
            if c.get('job') == 'Director':
                f.append(f'dir:{c["id"]}')
    else:
        for c in details.get('created_by') or []:
            f.append(f'dir:{c["id"]}')
    kw = details.get('keywords') or {}
    for k in kw.get('keywords' if kind == 'movie' else 'results') or []:
        f.append(f'k:{k["id"]}')
    f.append(f'kind:{kind}')
    return f


def names(details, kind):
    """feature id → what to call it in a reason on the TV."""
    n = {}
    for g in details.get('genres') or []:
        n[f'g:{g["id"]}'] = g['name']
    lang = details.get('original_language') or ''
    if lang:
        n[f'l:{lang}'] = LANGUAGES.get(lang, lang)
    date = details.get('release_date' if kind == 'movie' else 'first_air_date') or ''
    if len(date) >= 4 and date[:4].isdigit():
        n[f'd:{int(date[:4]) // 10 * 10}'] = f'the {int(date[:4]) // 10 * 10}s'
    credits = details.get('credits') or {}
    for c in (credits.get('cast') or [])[:5]:
        n[f'c:{c["id"]}'] = c.get('name', '')
    if kind == 'movie':
        for c in credits.get('crew') or []:
            if c.get('job') == 'Director':
                n[f'dir:{c["id"]}'] = c.get('name', '')
    else:
        for c in details.get('created_by') or []:
            n[f'dir:{c["id"]}'] = c.get('name', '')
    kw = details.get('keywords') or {}
    for k in kw.get('keywords' if kind == 'movie' else 'results') or []:
        n[f'k:{k["id"]}'] = k['name']
    n[f'kind:{kind}'] = 'movies' if kind == 'movie' else 'series'
    return n


def release_ms(details, kind):
    import calendar
    import datetime
    date = details.get('release_date' if kind == 'movie' else 'first_air_date') or ''
    try:
        d = datetime.date.fromisoformat(date)
        return calendar.timegm(d.timetuple()) * 1000
    except ValueError:
        return 0
