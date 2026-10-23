"""Independent generated-noise experiments, never real photos or camera access."""
import math
import random
import statistics
import unittest

from ser_policy import can_submit_auxiliary, shadow_plan


def covariance(variance, correlation=0.0):
    return [[variance if i == j else variance * correlation for j in range(3)] for i in range(3)]


def plan(accepted=(True, True, True), ids=("reference", "a", "b"), correlation=0.0, **overrides):
    args = dict(covariance_upper=covariance(0.0001, correlation), model_error_mse_bound=0.0,
                signal_lower_bound=0.08)
    args.update(overrides)
    return shadow_plan([0.0001] * 3, accepted, ids, **args)


class ShadowPolicyExperiment(unittest.TestCase):
    def test_generated_independent_noise_stays_within_baseline_after_extra_gain(self):
        decision = plan()
        rng = random.Random(19421)
        errors = []
        gain = 2 ** decision.extra_ev
        for _ in range(30000):
            observations = [0.1 + rng.gauss(0, 0.01) for _ in range(3)]
            errors.append((decision.fuse(observations) - 0.1) * gain)
        self.assertLessEqual(statistics.variance(errors), 0.0001 * 1.04)
        self.assertLess(abs(statistics.mean(errors)), 0.0002)
        self.assertGreater(decision.extra_ev, 0.6)
        self.assertLess(decision.extra_ev, math.log2(math.sqrt(3)))

    def test_shared_pattern_noise_does_not_receive_independence_gain(self):
        decision = plan(correlation=0.8)
        rng = random.Random(101)
        errors = []
        for _ in range(30000):
            shared = rng.gauss(0, math.sqrt(0.00008))
            observations = [0.1 + shared + rng.gauss(0, math.sqrt(0.00002)) for _ in range(3)]
            errors.append((decision.fuse(observations) - 0.1) * 2 ** decision.extra_ev)
        self.assertLessEqual(statistics.variance(errors), 0.0001 * 1.04)
        self.assertLess(decision.extra_ev, 0.11)
        self.assertEqual(0.0, plan(correlation=1.0).extra_ev)

    def test_motion_rejection_excludes_bright_incorrect_auxiliary(self):
        decision = plan(accepted=(True, False, True))
        self.assertEqual(0.0, decision.coefficients[1])
        self.assertAlmostEqual(0.1, decision.fuse((0.1, 0.9, 0.1)))

    def test_duplicate_frame_cannot_create_independent_evidence(self):
        decision = plan(ids=("reference", "reference", "reference"))
        self.assertEqual((1.0, 0.0, 0.0), decision.coefficients)
        self.assertEqual(0.0, decision.extra_ev)

    def test_unknown_model_black_and_error_floor_preserve_reference(self):
        cases = [plan(covariance_upper=None), plan(model_error_mse_bound=None),
                 plan(signal_lower_bound=0.0001), plan(model_error_mse_bound=0.0001)]
        for decision in cases:
            self.assertEqual((1.0, 0.0, 0.0), decision.coefficients)
            self.assertEqual(0.0, decision.extra_ev)

    def test_convex_fusion_does_not_invent_local_values_or_reduce_reference_below_half(self):
        rng = random.Random(128)
        for _ in range(1000):
            variances = [rng.uniform(0.00001, 0.0002) for _ in range(3)]
            bounds = [[variances[i] if i == j else 0.0 for j in range(3)] for i in range(3)]
            decision = shadow_plan(variances, (True, True, True), ("r", "a", "b"),
                covariance_upper=bounds, model_error_mse_bound=0, signal_lower_bound=0.2)
            values = [rng.uniform(0.01, 0.2) for _ in range(3)]
            self.assertGreaterEqual(decision.coefficients[0], 0.5)
            self.assertAlmostEqual(1.0, sum(decision.coefficients))
            self.assertLessEqual(min(values), decision.fuse(values))
            self.assertGreaterEqual(max(values), decision.fuse(values))
        # Deterministic faint line survives the linear reducer when independently reproduced.
        decision = plan()
        self.assertAlmostEqual(0.002, decision.fuse((0.102,)*3) - decision.fuse((0.1,)*3))

    def test_invalid_calibration_does_not_silently_claim_recovery(self):
        with self.assertRaises(ValueError):
            plan(covariance_upper=covariance(float("nan")))
        with self.assertRaises(ValueError):
            plan(extra_ev_limit=3)
        with self.assertRaises(ValueError):
            plan().fuse((0.1, float("nan"), 0.1))

    def test_unmeasured_or_over_budget_tail_is_never_admitted(self):
        self.assertFalse(can_submit_auxiliary(500, None, 50))
        self.assertFalse(can_submit_auxiliary(954, 100, 80))
        self.assertFalse(can_submit_auxiliary(50, float("nan"), 50))
        self.assertTrue(can_submit_auxiliary(200, 700, 100))

    def test_extreme_numeric_scales_stay_finite_without_faking_a_noise_floor(self):
        for variance in (5e-324, 1e-300, 1e300):
            decision = shadow_plan([variance] * 3, (True, True, True), ("r", "a", "b"),
                covariance_upper=covariance(variance), model_error_mse_bound=0,
                signal_lower_bound=0.1)
            self.assertTrue(math.isfinite(decision.mse_bound))
            self.assertTrue(math.isfinite(decision.extra_ev))
            self.assertAlmostEqual(1.0, sum(decision.coefficients))
        self.assertEqual(0.0, plan(model_error_mse_bound=1e308).extra_ev)

    def test_model_error_correlated_with_reference_does_not_spend_false_reserve(self):
        # A mere sum of variances would grant +EV despite half-reference correlated error.
        rejected = plan(model_error_mse_bound=0.0001 * 0.5 ** 2)
        self.assertEqual(0.0, rejected.extra_ev)
        self.assertEqual((1.0, 0.0, 0.0), rejected.coefficients)
        decision = plan(model_error_mse_bound=0.0001 * 0.05 ** 2)
        rng = random.Random(243)
        errors = []
        for _ in range(30000):
            noise = [rng.gauss(0, 0.01) for _ in range(3)]
            observations = [0.1 + n for n in noise]
            errors.append((decision.fuse(observations) - 0.1 + 0.05 * noise[0])
                          * 2 ** decision.extra_ev)
        self.assertLessEqual(statistics.mean(e * e for e in errors), 0.0001 * 1.04)


if __name__ == "__main__":
    unittest.main()
