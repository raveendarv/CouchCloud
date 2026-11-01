#!/usr/bin/env python3
"""Your recommendation profile for Free My TV, built on this computer from your TMDB account and sent to the TV.

    python3 tools/recs/recs.py auth              # once: approve this tool in the browser (TMDB v4 user auth)
    python3 tools/recs/recs.py pull              # your ratings, favorites and watchlist → data/library.json
    python3 tools/recs/recs.py token [IP]        # your TMDB token to the TV: what you do there goes to your account
    python3 tools/recs/recs.py status [IP]       # the TV's side: token, verdicts waiting for TMDB, last pull of the account
    python3 tools/recs/recs.py build             # library + a corpus of popular titles → data/profile.json
    python3 tools/recs/recs.py eval              # hold out your newest likes: hit-rate@10 and NDCG@10 vs popularity
    python3 tools/recs/recs.py push [IP]         # data/profile.json → the TV (the home screen re-ranks within 15 s)
    python3 tools/recs/recs.py all [IP]          # pull, build, push
    python3 tools/recs/recs.py import letterboxd ratings.csv   # extra ratings from a Letterboxd (or IMDb) export

Your TMDB account is the record: Watched on the TV is a rating (4 to 10), 👎 a rating of 0.5 (TMDB's lowest; model.py
reads it as a dislike without a watch), 👍 the watchlist. The TV sends them itself with the token and pulls the account
back every 6 hours, so `pull` here sees them too. The TMDB key is read from config.properties (tmdb_key); the account
approval lands in tools/recs/.auth.json. Both stay on this computer (gitignored). Options: --region IN,
--providers "netflix,prime video,jiohotstar,…", --pages 5.
"""
import argparse
import csv
import json
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import model  # noqa: E402
import tmdb  # noqa: E402

DATA = os.path.join(tmdb.HERE, 'data')
LIBRARY = os.path.join(DATA, 'library.json')
CORPUS = os.path.join(DATA, 'corpus.json')
PROFILE = os.path.join(DATA, 'profile.json')
IMPORTED = os.path.join(DATA, 'imported.json')
PROVIDERS = 'netflix,amazon prime video,jiohotstar,zee5,sony liv,sun nxt,aha,manoramamax'
LANGUAGES = ('ta', 'ml', 'te', 'hi', 'kn', 'en')


def load(path, default):
    try:
        with open(path, encoding='utf-8') as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def save(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(obj, f, ensure_ascii=False, separators=(',', ':'))


def client(args, need_auth=False):
    auth = tmdb.Tmdb.load_auth()
    if need_auth and not auth:
        sys.exit('Not approved yet: run  python3 tools/recs/recs.py auth')
    return tmdb.Tmdb(tmdb.read_key(args.config), auth, verbose=True)


def adb(ip, *cmd, **kw):
    serial = ['-s', ip if ':' in ip else ip + ':5555'] if ip else []
    return subprocess.run(['adb', *serial, *cmd], **kw)


# --- Commands

REQUEST_TOKEN = os.path.join(DATA, '.request_token')


def cmd_auth(args):
    """Two steps when not in a terminal: the first run prints the address to approve, the second run finishes."""
    t = client(args)
    pending = load(REQUEST_TOKEN, None)
    if pending:
        try:
            auth = t.access_token(pending)
        except RuntimeError as e:
            os.remove(REQUEST_TOKEN)
            sys.exit(f'Not approved (or expired): {e}\nRun  auth  again for a new address.')
        os.remove(REQUEST_TOKEN)
        print(f'Approved: account {auth["account_id"]} (saved to {tmdb.AUTH_FILE}).')
        return
    token = t.request_token()
    save(REQUEST_TOKEN, token)
    print('Open this address, sign in to TMDB and approve:')
    print(f'  https://www.themoviedb.org/auth/access?request_token={token}')
    if sys.stdin.isatty():
        print('Then press Enter here.')
        try:
            input()
        except EOFError:
            pass
        cmd_auth(args)
    else:
        print('Then run  python3 tools/recs/recs.py auth  again to finish.')


def cmd_pull(args):
    t = client(args, need_auth=True)
    entries = {}
    for kind in ('movie', 'tv'):
        for what, field in (('rated', 'rating'), ('favorites', 'favorite'), ('watchlist', 'watchlist')):
            items = t.account_list(kind, what)
            print(f'  {len(items):4d} {kind} {what}')
            for it in items:
                key = f'{kind}:{it["id"]}'
                e = entries.setdefault(key, {'key': key, 'kind': kind, 'id': it['id'],
                                             'title': it.get('title') or it.get('name') or '', 'at': 0})
                if field == 'rating':
                    ar = it.get('account_rating') or {}
                    e['rating'] = ar.get('value')
                    e['at'] = max(e['at'], _ms(ar.get('created_at')))
                else:
                    e[field] = True
    imported = load(IMPORTED, [])
    for e in imported:   # Letterboxd/IMDb ratings, unless TMDB has a rating for the same title
        if e['key'] not in entries or not entries[e['key']].get('rating'):
            entries.setdefault(e['key'], dict(e)).update({k: v for k, v in e.items() if k == 'rating' or k == 'at'})
    library = list(entries.values())
    save(LIBRARY, library)
    rated = sum(1 for e in library if e.get('rating'))
    print(f'{len(library)} titles in your library ({rated} rated) → {LIBRARY}')


def _ms(iso):
    if not iso:
        return 0
    try:
        return int(time.mktime(time.strptime(iso[:19], '%Y-%m-%d %H:%M:%S')) * 1000)
    except ValueError:
        try:
            return int(time.mktime(time.strptime(iso[:19], '%Y-%m-%dT%H:%M:%S')) * 1000)
        except ValueError:
            return 0


def cmd_status(args):
    """What the TV says (RecsInbox's status.json, read fresh from its files): the token, the outbox, the account's pull."""
    r = adb(args.ip, 'exec-out', 'content', 'read', '--uri', 'content://io.freemytv.recs/status.json', capture_output=True)
    out = r.stdout.decode(errors='replace').strip()
    if r.returncode != 0 or not out.startswith('{'):
        sys.exit(f'No answer from the TV: {(out + r.stderr.decode(errors="replace")).strip()}\nIs it connected (adb devices) and Free My TV installed?')
    s = json.loads(out)
    if not s.get('token'):
        print('No TMDB token on the TV: run  recs.py token <IP>  (what you rate there waits until then).')
    elif s.get('refused'):
        print('TMDB REFUSED the token on the TV (401/403): run  recs.py token <IP>  again (approve again with  auth  if that fails too).')
    else:
        print('TMDB token on the TV: OK.')
    n = s.get('pending', 0)
    print(f'{n} verdict(s) waiting for TMDB' + (f' (last error: {s["error"]})' if n and s.get('error') else '') + '.')
    at = s.get('pulledAt', 0)
    if at:
        print(f'Account pulled {time.strftime("%Y-%m-%d %H:%M", time.localtime(at / 1000))}: {s.get("rated", 0)} rated, '
              f'{s.get("watchlist", 0)} on the watchlist, {s.get("favorites", 0)} favorites.')
    else:
        print('The TV has not pulled the account yet (it does within a minute of the home screen showing, with a token).')


def cmd_build(args):
    t = client(args)
    library = load(LIBRARY, [])
    if not library:
        sys.exit('No library yet: run  pull  first.')
    corpus = load(CORPUS, {})
    if not corpus or args.refresh_corpus:
        corpus = fetch_corpus(t, args)
    print(f'{len(library)} library titles: details…')
    details = t.details_many([(e['kind'], e['id']) for e in library])
    print(f'{len(corpus)} corpus titles: details…')
    corpus_details = t.details_many([tuple(k.split(':')) for k in corpus])
    profile = model.build_profile(library, details, corpus_details)
    save(PROFILE, profile)
    size = os.path.getsize(PROFILE)
    print(f'Profile: {profile["rated"]} rated titles → {len(profile["weights"])} features, {len(profile["seen"])} seen, '
          f'{len(profile["watchlist"])} on the watchlist, {size // 1024} KB → {PROFILE}')
    top = sorted(profile['weights'].items(), key=lambda kv: -kv[1])[:12]
    print('Strongest likes: ' + ', '.join(f'{profile["names"].get(f, f)} ({w:+.2f})' for f, w in top))
    low = sorted(profile['weights'].items(), key=lambda kv: kv[1])[:6]
    print('Strongest dislikes: ' + ', '.join(f'{profile["names"].get(f, f)} ({w:+.2f})' for f, w in low))
    print('Confidence by language: ' + ', '.join(f'{k} {v}' for k, v in sorted(profile['confidence'].items())))


def fetch_corpus(t, args):
    """Popular titles on your services, per language and kind: the IDF background and the evaluation's distractors."""
    names = [n.strip().lower() for n in args.providers.split(',') if n.strip()]
    ids = t.providers(args.region, names)
    print(f'Providers in {args.region}: {ids}')
    corpus = {}
    for lang in args.languages.split(','):
        for kind in ('movie', 'tv'):
            for r in t.discover(kind, lang, args.region, ids, pages=args.pages):
                corpus[f'{kind}:{r["id"]}'] = {'title': r.get('title') or r.get('name'), 'lang': lang,
                                               'popularity': r.get('popularity', 0)}
    save(CORPUS, corpus)
    print(f'{len(corpus)} corpus titles → {CORPUS}')
    return corpus


def cmd_eval(args):
    t = client(args)
    library = load(LIBRARY, [])
    corpus = load(CORPUS, {}) or fetch_corpus(t, args)
    details = t.details_many([(e['kind'], e['id']) for e in library])
    corpus_details = t.details_many([tuple(k.split(':')) for k in corpus])
    r = model.evaluate(library, details, corpus_details, k=args.k)
    if 'error' in r:
        sys.exit(r['error'])
    print(f'Held out {r["tested"]} newest likes (trained on {r["train"]}), each among {r["distractors"]} popular titles of its language:')
    print(f'  hit-rate@{r["k"]}:  profile {r["hit_rate"]:.3f}   popularity only {r["baseline_hit_rate"]:.3f}')
    print(f'  NDCG@{r["k"]}:      profile {r["ndcg"]:.3f}   popularity only {r["baseline_ndcg"]:.3f}')


def cmd_push(args):
    if not os.path.exists(PROFILE):
        sys.exit('No profile yet: run  build  first.')
    size = os.path.getsize(PROFILE)
    with open(PROFILE, 'rb') as f:
        r = adb(args.ip, 'exec-in', 'content', 'write', '--uri', f'content://io.freemytv.recs/profile.json?size={size}',
                stdin=f, capture_output=True, text=True)
    if r.returncode != 0 or 'Exception' in (r.stdout + r.stderr):
        sys.exit(f'Push failed: {(r.stdout + r.stderr).strip()}\nIs the TV connected (adb devices) and Free My TV installed?')
    print(f'Pushed {size // 1024} KB to the TV; the home screen re-ranks its picks within 15 s.')


def cmd_token(args):
    """Your TMDB access token and account id to the TV's private files (content provider): what you do there reaches your account."""
    auth = tmdb.Tmdb.load_auth()
    if not auth or not auth.get('access_token'):
        sys.exit('Not approved yet: run  python3 tools/recs/recs.py auth')
    data = json.dumps({'access_token': auth['access_token'], 'account_id': auth.get('account_id', '')}).encode()
    r = adb(args.ip, 'exec-in', 'content', 'write', '--uri', f'content://io.freemytv.recs/tmdb-token?size={len(data)}',
            input=data, capture_output=True)
    out = (r.stdout + r.stderr).decode(errors='replace')
    if r.returncode != 0 or 'Exception' in out:
        sys.exit(f'Token push failed: {out.strip()}\nIs the TV connected (adb devices) and Free My TV installed?')
    print('TMDB token on the TV; what you rate or put on the watchlist there goes to your account.')


def cmd_all(args):
    cmd_pull(args)
    cmd_build(args)
    cmd_push(args)


def cmd_import(args):
    """Letterboxd (ratings.csv: Name, Year, Rating 0.5-5) or IMDb (Const, Your Rating 1-10, Title, Year) → TMDB ids."""
    t = client(args)
    imported = {e['key']: e for e in load(IMPORTED, [])}
    added = 0
    with open(args.file, encoding='utf-8-sig', newline='') as f:
        for row in csv.DictReader(f):
            if args.source == 'letterboxd':
                title, year, rating = row.get('Name'), row.get('Year'), row.get('Rating')
                if not rating:
                    continue
                rating = float(rating) * 2
                at = _ms((row.get('Date') or '') + ' 00:00:00')
                found = t.get3('/search/movie', query=title, year=year).get('results', [])
                kind = 'movie'
            else:
                title, year, rating = row.get('Title'), row.get('Year'), row.get('Your Rating')
                if not rating:
                    continue
                rating = float(rating)
                at = _ms((row.get('Date Rated') or '') + ' 00:00:00')
                imdb = row.get('Const')
                r = t.get3(f'/find/{imdb}', external_source='imdb_id') if imdb else {}
                kind = 'movie' if r.get('movie_results') else 'tv'
                found = r.get('movie_results') or r.get('tv_results') or []
            if not found:
                print(f'  not found: {title} ({year})')
                continue
            key = f'{kind}:{found[0]["id"]}'
            imported[key] = {'key': key, 'kind': kind, 'id': found[0]['id'], 'title': title, 'rating': rating, 'at': at}
            added += 1
    save(IMPORTED, list(imported.values()))
    print(f'{added} ratings imported → {IMPORTED} (they join the library on the next  pull).')


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('command', choices=['auth', 'pull', 'build', 'eval', 'push', 'all', 'import', 'token', 'status'])
    p.add_argument('rest', nargs='*', help='IP of the TV (push, token, status, all) or source and file (import)')
    p.add_argument('--config', default=os.path.join(tmdb.ROOT, 'config.properties'))
    p.add_argument('--region', default='IN')
    p.add_argument('--providers', default=PROVIDERS)
    p.add_argument('--languages', default=','.join(LANGUAGES))
    p.add_argument('--pages', type=int, default=5, help='discover pages per language and kind for the corpus')
    p.add_argument('--refresh-corpus', action='store_true')
    p.add_argument('-k', type=int, default=10)
    args = p.parse_args()
    args.ip = ''
    if args.command == 'import':
        if len(args.rest) != 2 or args.rest[0] not in ('letterboxd', 'imdb'):
            sys.exit('usage: recs.py import letterboxd|imdb <file.csv>')
        args.source, args.file = args.rest
    elif args.rest:
        args.ip = args.rest[0]
    globals()['cmd_' + args.command](args)


if __name__ == '__main__':
    main()
