"""Fault-model tests for the hardware fixture's acceptance rules; no GPU is needed."""
import unittest

from flow_pingpong_metrics import BORDER, measure


class FlowPingPongMetricsTest(unittest.TestCase):
    width, height = 40, 24
    expected = (4, -2)

    def field(self, vector=None, cost=16):
        return [*(vector or self.expected), cost, 1] * (self.width * self.height)

    def interior(self):
        return [(y * self.width + x) * 4 for y in range(BORDER, self.height - BORDER)
                for x in range(BORDER, self.width - BORDER)]

    def check_field(self, pixels, stationary=False, difference=.5):
        return measure(pixels, self.width, self.height, (0, 0) if stationary else self.expected, stationary, difference)

    def test_accurate_field(self):
        r = self.check_field(self.field())
        self.assertTrue(r['passed'])
        self.assertEqual(r['population'], 512)
        self.assertEqual(r['costCoverage'], 1)

    def test_repeated_pattern_tail_fails_even_when_cost_can_remove_it(self):
        pixels = self.field()
        for i in self.interior()[:38]:  # 7.42%: similar to the reproduced diagonal-cell mismatch
            pixels[i:i + 4] = [38, -36, 192, 1]
        r = self.check_field(pixels)
        self.assertFalse(r['passed'])
        self.assertGreater(r['p95Epe'], 48)
        self.assertEqual(r['costSelectedP95Epe'], 0)
        self.assertEqual(r['outlierCount'], 38)
        self.assertEqual(r['costSelectedCount'], 474)

    def test_zero_reversed_and_stale_motion_are_rejected(self):
        for vector in [(0, 0), (-4, 2), (6, 1)]:
            with self.subTest(vector=vector):
                self.assertFalse(self.check_field(self.field(vector))['passed'])

    def test_too_many_bad_vectors_fail_even_if_they_are_expensive(self):
        pixels = self.field()
        for i in self.interior()[:60]:
            pixels[i:i + 4] = [38, -36, 192, 1]
        r = self.check_field(pixels)
        self.assertFalse(r['passed'])
        self.assertEqual(r['costSelectedP95Epe'], 0)

    def test_cost_is_not_a_guarantee_of_accuracy(self):
        pixels = self.field()
        for i in self.interior()[:31]:
            pixels[i:i + 2] = [38, -36]  # cheap wrong matches must still fail raw p95
        self.assertFalse(self.check_field(pixels)['passed'])

    def test_cost_does_not_change_the_raw_accuracy_verdict(self):
        pixels = self.field(cost=192)
        for i in self.interior()[:100]:
            pixels[i + 2] = 16
        self.assertTrue(self.check_field(pixels)['passed'])
        self.assertTrue(self.check_field(self.field(cost=192))['passed'])
        for i in self.interior()[100:]:
            pixels[i:i + 2] = [38, -36]
        self.assertFalse(self.check_field(pixels)['passed'])

    def test_identical_moving_inputs_are_rejected(self):
        self.assertFalse(self.check_field(self.field(), difference=0)['passed'])

    def test_paused_test_covers_border_and_ignores_cost_filter(self):
        pixels = self.field((0, 0), cost=192)
        self.assertTrue(self.check_field(pixels, stationary=True, difference=0)['passed'])
        pixels[0] = 2  # outside the moving population's border
        self.assertFalse(self.check_field(pixels, stationary=True, difference=0)['passed'])
        self.assertFalse(self.check_field(self.field((0, 0)), stationary=True)['passed'])

    def test_non_finite_and_invalid_channels_fail(self):
        for lane, value in [(0, float('nan')), (1, float('inf')), (2, float('nan')), (2, -1), (3, 0)]:
            pixels = self.field()
            pixels[lane] = value
            self.assertFalse(self.check_field(pixels)['passed'])


if __name__ == '__main__':
    unittest.main()
