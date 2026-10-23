"""PHOTON Shadow Evidence Reserve: experimental, sensor-linear policy reference.

Not connected to the APK. Inputs are externally validated alignment/texture decisions and
calibrated noise bounds, not JPEG-derived sensor noise guesses. No image loading or rendering.
"""
from dataclasses import dataclass
import math
from typing import Sequence


@dataclass(frozen=True)
class ShadowPlan:
    coefficients: tuple[float, float, float]
    mse_bound: float
    extra_ev: float
    reason: str

    def fuse(self, observations: Sequence[float]) -> float:
        if len(observations) != 3 or not all(math.isfinite(x) for x in observations):
            raise ValueError("three finite, already aligned linear observations required")
        return sum(a * x for a, x in zip(self.coefficients, observations))


def shadow_plan(
    variances: Sequence[float],
    accepted: Sequence[bool],
    frame_ids: Sequence[str],
    *,
    covariance_upper: Sequence[Sequence[float]] | None,
    model_error_mse_bound: float | None,
    signal_lower_bound: float,
    shadow_weight: float = 1.0,
    extra_ev_limit: float = 0.75,
) -> ShadowPlan:
    """Reserve extra display gain only from a validated conservative noise reduction.

    The fixed 0.75 anchor blend leaves at least half of the reference's weight. Weights
    must come from independent calibration/held-out evidence, not a noisy pixel's residual.
    Variance prediction covers this unclipped linear model only, not adaptive clamps/warps.
    """
    if len(variances) != 3 or len(accepted) != 3 or len(frame_ids) != 3:
        raise ValueError("reference plus exactly two auxiliary slots required")
    if not all(math.isfinite(v) and v > 0 for v in variances):
        raise ValueError("positive finite calibrated variances required")
    if not all(isinstance(x, bool) for x in accepted) or not accepted[0]:
        raise ValueError("validated reference required")
    if not math.isfinite(shadow_weight) or not 0 <= shadow_weight <= 1:
        raise ValueError("shadow weight outside [0,1]")
    if not math.isfinite(extra_ev_limit) or not 0 <= extra_ev_limit <= 0.75:
        raise ValueError("prototype EV limit outside [0,.75]")
    if not math.isfinite(signal_lower_bound) or signal_lower_bound < 0:
        raise ValueError("nonnegative calibrated signal bound required")
    reference_variance = variances[0]

    def reference(reason: str) -> ShadowPlan:
        return ShadowPlan((1.0, 0.0, 0.0), reference_variance, 0.0, reason)

    if covariance_upper is None or model_error_mse_bound is None:
        return reference("unverified_noise_bounds")
    if not math.isfinite(model_error_mse_bound) or model_error_mse_bound < 0:
        raise ValueError("invalid model error bound")
    if len(covariance_upper) != 3 or any(len(row) != 3 for row in covariance_upper):
        raise ValueError("3x3 covariance upper bounds required")
    for i in range(3):
        for j in range(3):
            bound = covariance_upper[i][j]
            if not math.isfinite(bound) or bound < 0:
                raise ValueError("nonnegative finite covariance bounds required")
            if not math.isclose(bound, covariance_upper[j][i], rel_tol=1e-9, abs_tol=1e-15):
                raise ValueError("symmetric covariance bounds required")
            if i == j and not math.isclose(bound, variances[i], rel_tol=1e-9):
                raise ValueError("diagonal must equal calibrated variance")
    seen = {frame_ids[0]}
    # Relative weights avoid reciprocal overflow without inventing a noise floor.
    weights = [1.0]
    for i in (1, 2):
        # Repeated buffers are not independent evidence, even if both pass image gates.
        valid = accepted[i] and bool(frame_ids[i]) and frame_ids[i] not in seen
        seen.add(frame_ids[i])
        weights.append(min(1.0, reference_variance / variances[i]) if valid else 0.0)
    if not any(weights[1:]) or shadow_weight == 0:
        return reference("no_shadow_evidence")
    total = sum(weights)
    coefficients = [0.75 * w / total for w in weights]
    coefficients[0] += 0.25
    predicted = sum(coefficients[i] ** 2 * variances[i] for i in range(3))
    predicted += 2 * sum(
        coefficients[i] * coefficients[j] * covariance_upper[i][j]
        for i in range(3) for j in range(i + 1, 3)
    )
    if not math.isfinite(predicted) or predicted <= 0:
        return reference("unstable_noise_bound")
    # Model error may be biased and correlated with the fusion noise. Adding only its
    # variance would omit the cross term. The RMS triangle bound includes unknown correlation.
    rms_bound = math.sqrt(predicted) + math.sqrt(model_error_mse_bound)
    if not math.isfinite(rms_bound) or rms_bound >= math.sqrt(reference_variance) * (1.0 - 1e-12):
        return reference("no_noise_reserve")
    predicted = rms_bound * rms_bound
    # A positive mean is not recoverable shadow signal. This lower bound needs calibration.
    if signal_lower_bound < 4.0 * math.sqrt(predicted):
        return reference("insufficient_signal")
    reserve_ev = 0.5 * math.log2(reference_variance / predicted)
    extra_ev = min(extra_ev_limit, reserve_ev) * shadow_weight
    return ShadowPlan(tuple(coefficients), predicted, extra_ev, "verified_linear_reserve")


def can_submit_auxiliary(
    elapsed_ms: float, sensor_tail_bound_ms: float | None, restore_bound_ms: float | None,
    margin_ms: float = 100.0,
) -> bool:
    """Admission policy only; camera completion still must be measured before unlocking.

    Bounds must include outstanding requests/callbacks and session restoration. Unknown
    timing never admits another request; background GPU work does not shorten sensor time.
    """
    values = (elapsed_ms, sensor_tail_bound_ms, restore_bound_ms, margin_ms)
    if any(x is None or not math.isfinite(x) or x < 0 for x in values):
        return False
    return sum(values) <= 1200.0
