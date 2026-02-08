"""Search orchestration: smart, full, and quick grid search modes.

Smart search:
  Phase 1: Baseline test
  Phase 2: Individual parameter sweep (each dimension alone)
  Phase 3: Combine winners (top N from each dimension crossed)
  Phase 4: Fine-tune (narrow ranges around best combo)

Full search: Exhaustive cartesian product of all selected params.
Quick search: Only fragment + fingerprint + alpn.
"""

import asyncio
from typing import Callable

from config_generator import (
    PARAM_GROUPS,
    build_xray_json,
    generate_combination_grid,
    generate_full_grid,
    generate_quick_grid,
    generate_smart_grid,
)
from tester import TestResult, run_batch, test_base_config


async def smart_search(
    config: dict,
    param_groups: list[str],
    xray_bin: str,
    concurrency: int = 10,
    timeout: int = 15,
    measure_speed: bool = False,
    on_phase: Callable[[str, int, int], None] | None = None,
    on_result: Callable[[TestResult, int, int], None] | None = None,
) -> list[TestResult]:
    """Three-phase smart search.

    Args:
        config: Parsed URI config dict
        param_groups: List of param group names to test
        xray_bin: Path to xray binary
        concurrency: Max parallel tests
        on_phase: Callback(phase_name, phase_num, total_phases) for UI
        on_result: Callback(result, completed, total) for live updates
    """
    all_results: list[TestResult] = []
    total_phases = 3

    # ── Phase 1: Baseline ──────────────────────────────────────────────
    if on_phase:
        on_phase("Baseline Test", 1, total_phases)

    base_json = build_xray_json(config, {}, socks_port=10808)
    base_json["_overrides"] = {}
    base_json["_description"] = "baseline (no changes)"
    base_result = await test_base_config(base_json, xray_bin, timeout)

    if on_result:
        on_result(base_result, 1, 1)
    all_results.append(base_result)

    # ── Phase 2: Individual Parameter Sweep ────────────────────────────
    if on_phase:
        on_phase("Individual Parameter Sweep", 2, total_phases)

    sweep_cases = generate_smart_grid(config, param_groups)
    # Skip baseline since we already tested it
    sweep_cases = [(o, d) for o, d in sweep_cases if o]

    sweep_jsons = []
    for overrides, desc in sweep_cases:
        xj = build_xray_json(config, overrides, socks_port=10808)
        xj["_overrides"] = overrides
        xj["_description"] = desc
        sweep_jsons.append(xj)

    sweep_results = await run_batch(
        sweep_jsons, xray_bin, concurrency, timeout, measure_speed, on_result,
    )
    all_results.extend(sweep_results)

    # ── Identify winners per dimension ─────────────────────────────────
    winners: dict[str, list[tuple[dict, float]]] = {}
    for group_name in param_groups:
        keys = set(PARAM_GROUPS.get(group_name, [group_name]))
        group_results = []
        for r in sweep_results:
            if r.success and any(k in r.params for k in keys):
                group_results.append((r.params, r.latency_ms))
        group_results.sort(key=lambda x: x[1])
        if group_results:
            winners[group_name] = group_results

    # ── Phase 3: Combine Winners ───────────────────────────────────────
    if on_phase:
        on_phase("Combining Winners", 3, total_phases)

    if len(winners) > 1:
        combo_cases = generate_combination_grid(winners, top_n=3)
        combo_jsons = []
        for overrides, desc in combo_cases:
            xj = build_xray_json(config, overrides, socks_port=10808)
            xj["_overrides"] = overrides
            xj["_description"] = desc
            combo_jsons.append(xj)

        combo_results = await run_batch(
            combo_jsons, xray_bin, concurrency, timeout, measure_speed, on_result,
        )
        all_results.extend(combo_results)

    return all_results


async def full_search(
    config: dict,
    param_groups: list[str],
    xray_bin: str,
    concurrency: int = 10,
    timeout: int = 15,
    measure_speed: bool = False,
    on_phase: Callable[[str, int, int], None] | None = None,
    on_result: Callable[[TestResult, int, int], None] | None = None,
) -> list[TestResult]:
    """Exhaustive grid search — ALL parameter combinations."""
    if on_phase:
        on_phase("Full Grid Search", 1, 1)

    cases = generate_full_grid(config, param_groups)
    jsons = []
    for overrides, desc in cases:
        xj = build_xray_json(config, overrides, socks_port=10808)
        xj["_overrides"] = overrides
        xj["_description"] = desc
        jsons.append(xj)

    return await run_batch(
        jsons, xray_bin, concurrency, timeout, measure_speed, on_result,
    )


async def quick_search(
    config: dict,
    xray_bin: str,
    concurrency: int = 10,
    timeout: int = 15,
    measure_speed: bool = False,
    on_phase: Callable[[str, int, int], None] | None = None,
    on_result: Callable[[TestResult, int, int], None] | None = None,
) -> list[TestResult]:
    """Quick search — only fragment + fingerprint + alpn."""
    return await smart_search(
        config=config,
        param_groups=["fragment", "fingerprint", "alpn"],
        xray_bin=xray_bin,
        concurrency=concurrency,
        timeout=timeout,
        measure_speed=measure_speed,
        on_phase=on_phase,
        on_result=on_result,
    )
