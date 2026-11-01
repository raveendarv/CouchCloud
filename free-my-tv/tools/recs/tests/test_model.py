import unittest

import model

NOW = 1_800_000_000_000
DAY = 86_400_000


def title(key, lang, genres, cast, date='2015-01-01', pop=10.0, keywords=()):
    kind = key.split(':')[0]
    d = {'id': int(key.split(':')[1]), 'original_language': lang, 'popularity': pop,
         'genres': [{'id': g, 'name': f'Genre {g}'} for g in genres],
         'credits': {'cast': [{'id': c, 'name': f'Actor {c}'} for c in cast], 'crew': []},
         'keywords': {'keywords' if kind == 'movie' else 'results': [{'id': k, 'name': f'kw {k}'} for k in keywords]}}
    d['release_date' if kind == 'movie' else 'first_air_date'] = date
    return d


def corpus(n=60):
    """Many genres and actors; Drama (18) common, actor 500 rare (high idf)."""
    out = {}
    genres = [18, 35, 53, 28, 80, 10749, 9648]
    for i in range(n):
        out[f'movie:{1000 + i}'] = title(f'movie:{1000 + i}', 'ml', [genres[i % 7], genres[(i * 3) % 7]], [900 + (i % 40), 950 + (i % 25)], pop=float(i))
    out['movie:2000'] = title('movie:2000', 'ml', [18], [500])
    return out


class ModelTest(unittest.TestCase):
    def test_title_weight(self):
        self.assertEqual(model.title_weight({'rating': 10}), 1.0)
        self.assertEqual(model.title_weight({'rating': 6}), 0.0)
        self.assertEqual(model.title_weight({'rating': 2}), -1.0)
        self.assertEqual(model.title_weight({'favorite': True}), 1.0)
        self.assertEqual(model.title_weight({'rating': 0.5}), -0.5)          # the TV's 👎: a dislike without a watch
        self.assertEqual(model.title_weight({'watchlist': True}), 0.5)       # the TV's 👍
        self.assertEqual(model.title_weight({'rating': 8, 'watchlist': True}), 0.5)
        self.assertEqual(model.title_weight({}), 0.0)

    def test_profile_prefers_rare_liked_features_and_excludes_seen(self):
        details = {
            'movie:1': title('movie:1', 'ml', [18, 53], [500, 501]),      # loved: rare actor 500
            'movie:2': title('movie:2', 'ml', [18, 35], [900]),           # hated
            'movie:3': title('movie:3', 'ml', [18, 53], [500, 502]),      # loved again
            'tv:4': title('tv:4', 'ta', [80], [1]),                       # on the watchlist only
        }
        library = [{'key': 'movie:1', 'kind': 'movie', 'id': 1, 'rating': 9, 'at': 1},
                   {'key': 'movie:2', 'kind': 'movie', 'id': 2, 'rating': 2, 'at': 2},
                   {'key': 'movie:3', 'kind': 'movie', 'id': 3, 'favorite': True, 'at': 3},
                   {'key': 'tv:4', 'kind': 'tv', 'id': 4, 'watchlist': True, 'at': 4}]
        p = model.build_profile(library, details, corpus(), NOW)
        w = p['weights']
        self.assertGreater(w['c:500'], 0)
        self.assertGreater(w['c:500'], w['g:18'])          # the actor is rarer than Drama, and Drama was also hated once
        self.assertLess(w['g:35'], 0)                      # comedy came only with the hated title
        self.assertEqual(p['names']['c:500'], 'Actor 500')
        self.assertEqual(p['seen'], ['movie:1', 'movie:2', 'movie:3'])
        self.assertEqual(p['watchlist'], ['tv:4'])
        self.assertAlmostEqual(p['confidence']['ml'], 0.3 + 0.5 * 2 / 20, places=3)   # two liked Malayalam titles
        self.assertEqual(p['confidence']['*'], 0.3)
        self.assertLessEqual(max(abs(x) for x in w.values()), 1.0 + 1e-9)   # p95 scaling with few features → max is 1

    def test_tv_verdicts_from_the_account(self):
        details = {'movie:1': title('movie:1', 'ta', [28], [7]), 'movie:2': title('movie:2', 'ta', [28], [8])}
        library = [{'key': 'movie:1', 'kind': 'movie', 'id': 1, 'watchlist': True, 'at': 1},        # 👍 on the TV
                   {'key': 'movie:2', 'kind': 'movie', 'id': 2, 'rating': 0.5, 'at': 2}]            # 👎 on the TV
        p = model.build_profile(library, details, corpus(), NOW)
        self.assertGreater(p['weights']['c:7'], 0)
        self.assertLess(p['weights']['c:8'], 0)
        self.assertEqual(p['seen'], ['movie:2'])                 # the 👎 is out, the 👍 stays and comes first
        self.assertEqual(p['watchlist'], ['movie:1'])

    def test_score_matches_the_java_formula(self):
        p = {'weights': {'c:5': 1.5}, 'confidence': {'ta': 1.0, '*': 0.3}}
        s = model.score(p, ['c:5'], 'ta', 10, 10, 0, NOW)
        self.assertAlmostEqual(s, __import__('math').tanh(0.5), places=6)
        cold = model.score(p, ['c:5'], 'kn', 10, 10, NOW - 5 * DAY, NOW)   # confidence 0.3, fresh
        self.assertAlmostEqual(cold, 0.3 * __import__('math').tanh(0.5) + 0.7 * 1.0 + 0.15, places=6)
        self.assertEqual(model.freshness(NOW - 400 * DAY, NOW), 0.0)
        self.assertAlmostEqual(model.freshness(NOW - 197.5 * DAY, NOW), 0.075, places=6)

    def test_evaluate_beats_popularity_on_a_planted_taste(self):
        # 30 liked titles share rare actor 500; the corpus's popular titles don't.
        details, library = {}, []
        for i in range(30):
            key = f'movie:{i + 1}'
            details[key] = title(key, 'ml', [18, 53], [500, 600 + i], pop=30.0)
            library.append({'key': key, 'kind': 'movie', 'id': i + 1, 'rating': 9, 'at': i})
        corp = corpus(300)
        r = model.evaluate(library, details, corp, k=10, distractors=50, now_ms=NOW)
        self.assertNotIn('error', r)
        self.assertEqual(r['tested'], 6)
        self.assertGreater(r['hit_rate'], r['baseline_hit_rate'])
        self.assertGreaterEqual(r['hit_rate'], 0.5)


if __name__ == '__main__':
    unittest.main()
