"""TMDB for the profile builder: the v3 API with your read token (from config.properties), the v4 account API once you
have approved the tool in the browser (.auth.json next to this file), and a disk cache of title details (cache/).
Standard library only, like tools/photos.py."""
import concurrent.futures
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
API3 = 'https://api.themoviedb.org/3'
API4 = 'https://api.themoviedb.org/4'
AUTH_FILE = os.path.join(HERE, '.auth.json')
CACHE = os.path.join(HERE, 'cache')


def read_key(config=os.path.join(ROOT, 'config.properties')):
    """tmdb_key= from config.properties (the v4 read access token, or a v3 API key)."""
    try:
        with open(config, encoding='utf-8') as f:
            for line in f:
                m = re.match(r'\s*tmdb_key\s*=\s*(.*?)\s*$', line)
                if m and m.group(1):
                    return m.group(1).strip('"\\')
    except OSError:
        pass
    sys.exit(f'No tmdb_key in {config}: add your TMDB read access token there (never in the chat or the repo).')


class Tmdb:
    def __init__(self, key, auth=None, verbose=False):
        self.key = key
        self.bearer = key.startswith('eyJ')
        self.auth = auth or {}
        self.verbose = verbose
        os.makedirs(os.path.join(CACHE, 'details'), exist_ok=True)

    # --- HTTP

    def _request(self, url, token=None, data=None, method=None, tries=8):
        # 8 tries: some evenings TMDB resets about half the connections from a home line, and a build makes hundreds
        # of requests; the wait between tries grows to 6 s at most.
        headers = {'Accept': 'application/json'}
        if data is not None:
            data = json.dumps(data).encode()
            headers['Content-Type'] = 'application/json'
        token = token or (self.key if self.bearer else None)
        if token:
            headers['Authorization'] = 'Bearer ' + token
        elif 'api_key=' not in url:
            url += ('&' if '?' in url else '?') + 'api_key=' + urllib.parse.quote(self.key)
        for attempt in range(tries):
            try:
                req = urllib.request.Request(url, data=data, headers=headers, method=method)
                with urllib.request.urlopen(req, timeout=20) as r:
                    return json.loads(r.read().decode())
            except urllib.error.HTTPError as e:
                if e.code == 429 or e.code >= 500:
                    time.sleep(min(6, 1.5 * (attempt + 1)))
                    continue
                body = e.read().decode(errors='replace')[:200]
                raise RuntimeError(f'TMDB {e.code} for {url.split("?")[0]}: {body}') from None
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                time.sleep(min(6, 1.5 * (attempt + 1)))
        raise RuntimeError(f'TMDB unreachable: {url.split("?")[0]}')

    def get3(self, path, **params):
        q = urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
        return self._request(API3 + path + ('?' + q if q else ''))

    def get4(self, path, **params):
        q = urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
        return self._request(API4 + path + ('?' + q if q else ''), token=self.auth.get('access_token'))

    # --- v4 user auth (approved once in the browser)

    def request_token(self):
        return self._request(API4 + '/auth/request_token', data={}, method='POST')['request_token']

    def access_token(self, request_token):
        r = self._request(API4 + '/auth/access_token', data={'request_token': request_token}, method='POST')
        self.auth = {'access_token': r['access_token'], 'account_id': r['account_id']}
        with open(AUTH_FILE, 'w', encoding='utf-8') as f:
            json.dump(self.auth, f)
        os.chmod(AUTH_FILE, 0o600)
        return self.auth

    @staticmethod
    def load_auth():
        try:
            with open(AUTH_FILE, encoding='utf-8') as f:
                return json.load(f)
        except (OSError, ValueError):
            return {}

    def account_list(self, kind, what):
        """All pages of /4/account/{id}/{movie|tv}/{rated|favorites|watchlist}."""
        out, page = [], 1
        while True:
            r = self.get4(f'/account/{self.auth["account_id"]}/{kind}/{what}', page=page, sort_by='created_at.desc')
            out += r.get('results', [])
            if page >= r.get('total_pages', 1):
                return out
            page += 1

    # --- Details, cached on disk

    def details(self, kind, tmdb_id):
        path = os.path.join(CACHE, 'details', f'{kind}_{tmdb_id}.json')
        try:
            with open(path, encoding='utf-8') as f:
                return json.load(f)
        except (OSError, ValueError):
            pass
        d = self.get3(f'/{kind}/{tmdb_id}', language='en-US', append_to_response='credits,keywords')
        with open(path, 'w', encoding='utf-8') as f:
            json.dump(d, f)
        return d

    def details_many(self, pairs, workers=6):
        """[(kind, id)] → {key: details}, fetched in parallel (TMDB allows ~50 requests a second)."""
        out = {}
        with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
            futures = {pool.submit(self.details, k, i): (k, i) for k, i in pairs}
            done = 0
            for fut in concurrent.futures.as_completed(futures):
                k, i = futures[fut]
                try:
                    out[f'{k}:{i}'] = fut.result()
                except Exception as e:  # one title gone from TMDB doesn't stop the build
                    if self.verbose:
                        print(f'  skip {k}:{i}: {e}', file=sys.stderr)
                done += 1
                if self.verbose and done % 50 == 0:
                    print(f'  {done}/{len(pairs)} details', file=sys.stderr)
        return out

    def providers(self, region, names):
        """Provider ids in `region` whose name contains one of `names` (lowercase), 'amazon channel' variants left out."""
        ids = set()
        for kind in ('movie', 'tv'):
            for p in self.get3(f'/watch/providers/{kind}', watch_region=region).get('results', []):
                n = p['provider_name'].lower()
                if ' channel' in n:
                    continue
                if any(w in n for w in names):
                    ids.add(p['provider_id'])
        return sorted(ids)

    def discover(self, kind, language, region, provider_ids, pages=5, **extra):
        out = []
        for page in range(1, pages + 1):
            r = self.get3(f'/discover/{kind}', with_original_language=language, watch_region=region,
                          with_watch_providers='|'.join(map(str, provider_ids)), with_watch_monetization_types='flatrate',
                          include_adult='false', sort_by='popularity.desc', page=page, **extra)
            out += r.get('results', [])
            if page >= r.get('total_pages', 1):
                break
        return out
