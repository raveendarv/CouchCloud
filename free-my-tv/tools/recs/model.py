"""The taste profile: a linear model over sparse features with inverse-document-frequency scaling, built from your TMDB
ratings, favorites and watchlist (the TV's verdicts are in there too: 👎 is a rating of DOWN_RATING, 👍 the watchlist).
Transparent on purpose: every weight is a feature
you can name, and the TV's Scorer (Scorer.java) is the same formula, so `score()` here is its twin for evaluation.
Standard library only."""
import math
import time
from collections import Counter, defaultdict

from features import features, release_ms

NEUTRAL_RATING = 6.0        # ratings above like, below dislike: (r - 6) / 4 → -1..1
FAVORITE_WEIGHT = 1.0
FEEDBACK_WEIGHT = 0.5       # a 👍 (watchlist) or 👎 (a rating of DOWN_RATING, not watched) counts half a rating
DOWN_RATING = 0.5           # the TV's 👎 on TMDB: its lowest rating, never one the Watched pills (4 to 10) give
MAX_FEATURES = 2000
TASTE_SCALE = 3.0           # Scorer.TASTE_SCALE
FRESH_BONUS, FRESH_FULL_DAYS, FRESH_END_DAYS = 0.15, 30, 365
WATCHLIST_BONUS = 1.0
CONFIDENCE_MIN, CONFIDENCE_MAX, CONFIDENCE_FULL_AT = 0.3, 0.8, 20   # 0 rated in a language → 0.3; 20 → 0.8
GENERAL = ('g:', 'l:', 'd:', 'kind:')   # features that generalize from one title; the rest need min_support


def title_weight(entry):
    """How much a library entry says: rating → (r-6)/4 (DOWN_RATING, a 👎: −0.5), favorite without a rating → +1,
    watchlist without either → +0.5, else 0."""
    r = entry.get('rating')
    if r:
        if float(r) <= DOWN_RATING:
            return -FEEDBACK_WEIGHT
        w = max(-1.0, min(1.0, (float(r) - NEUTRAL_RATING) / 4.0))
        if entry.get('favorite'):
            w = max(w, FAVORITE_WEIGHT * 0.75)
        return w
    if entry.get('favorite'):
        return FAVORITE_WEIGHT
    if entry.get('watchlist'):
        return FEEDBACK_WEIGHT
    return 0.0


def idf_table(feature_lists):
    """feature → log((N+1)/(df+1)) + 1 over a corpus of feature lists."""
    df = Counter()
    for fs in feature_lists:
        df.update(set(fs))
    n = len(feature_lists)
    return {f: math.log((n + 1) / (d + 1)) + 1 for f, d in df.items()}


def build_profile(library, details, corpus_details, now_ms=None):
    """
    library: [{key, kind, id, rating, favorite, watchlist, at}]; details/corpus_details: key → TMDB details.
    Returns the profile.json dict: weights (already × idf, scaled so the strong likes are about 1, top MAX_FEATURES),
    names, seen, watchlist, confidence per language, built.
    """
    from features import names as feature_names
    now_ms = now_ms or int(time.time() * 1000)
    all_lists = [features(d, k.split(':')[0]) for k, d in corpus_details.items()] + \
                [features(details[e['key']], e['kind']) for e in library if e['key'] in details]
    idf = idf_table(all_lists)
    sums, support, names = defaultdict(float), Counter(), {}
    rated = [e for e in library if e['key'] in details and title_weight(e) != 0]
    min_support = 1 if len(rated) < 30 else 2
    lang_count = Counter()
    for e in rated:
        d = details[e['key']]
        fs = features(d, e['kind'])
        w = title_weight(e)
        for f in set(fs):
            sums[f] += w
            support[f] += 1
        names.update(feature_names(d, e['kind']))
        lang = d.get('original_language') or ''
        if lang and w > 0:
            lang_count[lang] += 1
    scale = 1.0 / math.sqrt(max(1, len(rated)))   # many ratings don't make every weight huge
    weights = {}
    for f, s in sums.items():
        if support[f] < min_support and not f.startswith(GENERAL):
            continue
        weights[f] = s * scale * idf.get(f, 1.0)
    if weights:   # the strong likes land near 1 (95th percentile of |w|), so the TV's tanh(sum / 1.5) has range
        mags = sorted(abs(w) for w in weights.values())
        p95 = mags[min(len(mags) - 1, int(len(mags) * 0.95))] or 1.0
        weights = {f: round(w / p95, 4) for f, w in weights.items()}
    top = sorted(weights.items(), key=lambda kv: -abs(kv[1]))[:MAX_FEATURES]
    weights = dict(top)
    names = {f: names[f] for f in weights if f in names}
    seen = sorted({e['key'] for e in library if e.get('rating') or e.get('favorite')})
    watchlist = sorted({e['key'] for e in library if e.get('watchlist')} - set(seen))
    confidence = {lang: round(CONFIDENCE_MIN + (CONFIDENCE_MAX - CONFIDENCE_MIN) * min(1.0, n / CONFIDENCE_FULL_AT), 3)
                  for lang, n in lang_count.items()}
    confidence['*'] = CONFIDENCE_MIN
    return {'version': 1, 'built': now_ms, 'rated': len(rated), 'weights': weights, 'names': names,
            'seen': seen, 'watchlist': watchlist, 'confidence': confidence}


# --- The TV's formula, for evaluation (Scorer.java)

def freshness(released_ms, now_ms):
    if released_ms <= 0:
        return 0.0
    days = (now_ms - released_ms) / 86_400_000.0
    if days < 0:
        return 0.0
    if days <= FRESH_FULL_DAYS:
        return FRESH_BONUS
    if days >= FRESH_END_DAYS:
        return 0.0
    return FRESH_BONUS * (FRESH_END_DAYS - days) / (FRESH_END_DAYS - FRESH_FULL_DAYS)


def confidence_for(profile, lang):
    c = profile['confidence'].get(lang, profile['confidence'].get('*', 0))
    return max(0.0, min(1.0, c))


def score(profile, fs, lang, popularity, max_pop, released_ms, now_ms, personal=True):
    taste = math.tanh(sum(profile['weights'].get(f, 0.0) for f in fs) / TASTE_SCALE) if personal else 0.0
    pop = math.log1p(popularity) / math.log1p(max_pop) if popularity > 0 and max_pop > 0 else 0.0
    c = confidence_for(profile, lang) if personal else 0.0
    return c * taste + (1 - c) * pop + freshness(released_ms, now_ms)


def evaluate(library, details, corpus_details, k=10, distractors=200, now_ms=None, seed=7):
    """
    Time-ordered split: the older 80% of the liked titles (rating ≥ 7, or favorites) build a profile, each newer one is
    ranked among `distractors` corpus titles of its language it hasn't rated. hit@k and NDCG@k, against popularity only.
    """
    import random
    now_ms = now_ms or int(time.time() * 1000)
    liked = sorted([e for e in library if e['key'] in details and (e.get('favorite') or (e.get('rating') or 0) >= 7)],
                   key=lambda e: e.get('at') or 0)
    if len(liked) < 5:
        return {'error': f'only {len(liked)} liked titles: not enough to evaluate'}
    cut = max(1, int(len(liked) * 0.8))
    train_keys = {e['key'] for e in liked[:cut]}
    train = [e for e in library if e['key'] in train_keys or not (e.get('favorite') or (e.get('rating') or 0) >= 7)]
    profile = build_profile(train, details, corpus_details, now_ms)
    library_keys = {e['key'] for e in library}
    rnd = random.Random(seed)
    by_lang = defaultdict(list)
    for key, d in corpus_details.items():
        if key not in library_keys:
            by_lang[d.get('original_language') or ''].append(key)
    hits = ndcg = hits_pop = ndcg_pop = 0.0
    tested = 0
    for e in liked[cut:]:
        d = details[e['key']]
        lang = d.get('original_language') or ''
        pool_keys = by_lang.get(lang, [])
        if len(pool_keys) < 20:
            continue
        pool = rnd.sample(pool_keys, min(distractors, len(pool_keys)))
        rows = [(e['key'], d)] + [(kk, corpus_details[kk]) for kk in pool]
        max_pop = max(float(dd.get('popularity') or 0) for _, dd in rows) or 1.0
        for personal in (True, False):
            ranked = sorted(rows, key=lambda kd: -score(profile, features(kd[1], kd[0].split(':')[0]), kd[1].get('original_language') or '',
                                                        float(kd[1].get('popularity') or 0), max_pop,
                                                        release_ms(kd[1], kd[0].split(':')[0]), now_ms, personal))
            rank = [kk for kk, _ in ranked].index(e['key']) + 1
            hit = 1.0 if rank <= k else 0.0
            gain = 1.0 / math.log2(rank + 1) if rank <= k else 0.0
            if personal:
                hits += hit
                ndcg += gain
            else:
                hits_pop += hit
                ndcg_pop += gain
        tested += 1
    if tested == 0:
        return {'error': 'no held-out title had enough titles of its language in the corpus'}
    return {'tested': tested, 'train': cut, 'k': k, 'distractors': distractors,
            'hit_rate': round(hits / tested, 3), 'ndcg': round(ndcg / tested, 3),
            'baseline_hit_rate': round(hits_pop / tested, 3), 'baseline_ndcg': round(ndcg_pop / tested, 3)}
