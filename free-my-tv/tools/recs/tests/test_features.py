import unittest

from features import features, names, release_ms

MOVIE = {
    'id': 1, 'original_language': 'ml', 'release_date': '2017-05-11', 'popularity': 12.5,
    'genres': [{'id': 18, 'name': 'Drama'}, {'id': 53, 'name': 'Thriller'}],
    'credits': {'cast': [{'id': 100 + i, 'name': f'Actor {i}'} for i in range(7)],
                'crew': [{'id': 77, 'job': 'Director', 'name': 'Dileesh Pothan'}, {'id': 78, 'job': 'Writer', 'name': 'W'}]},
    'keywords': {'keywords': [{'id': 9715, 'name': 'superhero'}]},
}
SERIES = {
    'id': 2, 'original_language': 'ta', 'first_air_date': '2023-01-01',
    'genres': [{'id': 80, 'name': 'Crime'}], 'created_by': [{'id': 5, 'name': 'Creator'}],
    'credits': {'cast': [{'id': 1, 'name': 'A'}]}, 'keywords': {'results': [{'id': 7, 'name': 'heist'}]},
}


class FeaturesTest(unittest.TestCase):
    def test_movie_features_match_the_java_side(self):
        f = features(MOVIE, 'movie')
        self.assertEqual(f[:2], ['g:18', 'g:53'])
        self.assertIn('l:ml', f)
        self.assertIn('d:2010', f)
        self.assertEqual([x for x in f if x.startswith('c:')], ['c:100', 'c:101', 'c:102', 'c:103', 'c:104'])  # top 5 only
        self.assertIn('dir:77', f)
        self.assertNotIn('dir:78', f)   # writers aren't directors
        self.assertIn('k:9715', f)
        self.assertEqual(f[-1], 'kind:movie')

    def test_series_uses_created_by_and_results(self):
        f = features(SERIES, 'tv')
        self.assertIn('dir:5', f)
        self.assertIn('k:7', f)
        self.assertIn('d:2020', f)
        self.assertEqual(f[-1], 'kind:tv')

    def test_names(self):
        n = names(MOVIE, 'movie')
        self.assertEqual(n['g:18'], 'Drama')
        self.assertEqual(n['dir:77'], 'Dileesh Pothan')
        self.assertEqual(n['l:ml'], 'Malayalam')
        self.assertEqual(n['d:2010'], 'the 2010s')
        self.assertEqual(n['k:9715'], 'superhero')

    def test_release_ms(self):
        self.assertEqual(release_ms(MOVIE, 'movie'), 1494460800000)
        self.assertEqual(release_ms({'release_date': ''}, 'movie'), 0)


if __name__ == '__main__':
    unittest.main()
