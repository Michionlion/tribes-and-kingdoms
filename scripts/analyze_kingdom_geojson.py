#!/usr/bin/env python3
"""Analyze kingdom GeoJSON debug export and render a standalone SVG dashboard.

Usage:
  scripts/analyze_kingdom_geojson.py <input.geojson> [--out-dir DIR]
      [--expect-center RX RZ --expect-radius R]
      [--config PATH_TO_KINGDOM_PLACEMENT_TOML]

Outputs:
  - analysis-summary.json
  - kingdom-geojson-visual-review.svg
"""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import math
import tomllib
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any


EXPECTED_ACCEPTED_KEYS = {
    "accepted",
    "id",
    "civ_id",
    "tier",
    "type",
    "biome",
    "region_x",
    "region_z",
    "is_capital",
}

EXPECTED_REJECTED_KEYS = {
    "accepted",
    "region_x",
    "region_z",
    "tier",
    "biome",
    "rejection_reason",
    "score_total",
    "score_biome",
    "score_height",
    "score_slope",
    "score_water",
}

PALETTE = {
    "WOOD": "#8C6A43",
    "STONE": "#6E6E6E",
    "IRON": "#8A9BA8",
    "DIAMOND": "#1CA6A6",
    "NETHERITE": "#3E3A42",
    None: "#BBBBBB",
}

BIOME_CLASS_ORDER = [
    "OCEANIC",
    "COASTAL",
    "TEMPERATE",
    "ARID",
    "COLD",
    "HIGHLAND",
    "OTHER",
    "UNKNOWN",
]

BIOME_CLASS_PALETTE = {
    "OCEANIC": "#2B6CB0",
    "COASTAL": "#3FA7D6",
    "TEMPERATE": "#4CAF50",
    "ARID": "#D4A24C",
    "COLD": "#9FD4F5",
    "HIGHLAND": "#7A6F64",
    "OTHER": "#9C7BD9",
    "UNKNOWN": "#B0B0B0",
}

BIOME_INTERPOLATION_TARGET_CELLS = 3600
BIOME_INTERPOLATION_MAX_POINTS = 6
SCALE_BAR_TARGET_PX = 160.0
SCALE_BAR_CHOICES_BLOCKS = [64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Analyze kingdom GeoJSON export")
    parser.add_argument("input", type=Path, help="Path to exported .geojson")
    parser.add_argument("--out-dir", type=Path, default=Path("/tmp/kingdom-geojson-review"))
    parser.add_argument(
        "--expect-center",
        nargs=2,
        type=int,
        metavar=("RX", "RZ"),
        help="Expected center region coordinates for coverage checks",
    )
    parser.add_argument(
        "--expect-radius",
        type=int,
        metavar="R",
        help="Expected radius in regions around --expect-center (e.g. 1 = 3x3 window)",
    )
    parser.add_argument(
        "--config",
        type=Path,
        help="Optional kingdom-placement.toml to apply spacing checks from cluster settings",
    )
    return parser.parse_args()


def load_config(config_path: Path | None) -> dict[str, Any]:
    if config_path is None:
        return {}
    if not config_path.exists():
        raise FileNotFoundError(f"Config not found: {config_path}")
    return tomllib.loads(config_path.read_text())


def region_window(center: tuple[int, int], radius: int) -> set[tuple[int, int]]:
    cx, cz = center
    out: set[tuple[int, int]] = set()
    for rx in range(cx - radius, cx + radius + 1):
        for rz in range(cz - radius, cz + radius + 1):
            out.add((rx, rz))
    return out


def to_float(value: Any, default: float = 0.0) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def classify_biome_class(biome_id: Any) -> str:
    if not biome_id:
        return "UNKNOWN"

    biome_text = str(biome_id)
    path = biome_text.split(":", 1)[-1].lower()
    if any(token in path for token in ("ocean", "deep_ocean")):
        return "OCEANIC"
    if any(token in path for token in ("beach", "shore", "river", "swamp", "mangrove")):
        return "COASTAL"
    if any(token in path for token in ("mountain", "peak", "hills", "ridge", "cliff")):
        return "HIGHLAND"
    if any(token in path for token in ("desert", "badlands", "savanna", "eroded")):
        return "ARID"
    if any(token in path for token in ("snow", "frozen", "ice", "jagged", "grove")):
        return "COLD"
    if any(token in path for token in ("nether", "end", "void")):
        return "OTHER"
    return "TEMPERATE"


def nearest_weighted_biome_class(
    x: float,
    z: float,
    points: list[tuple[float, float, str]],
    max_points: int = BIOME_INTERPOLATION_MAX_POINTS,
) -> str:
    if not points:
        return "UNKNOWN"

    distances: list[tuple[float, str]] = []
    for px, pz, biome_class in points:
        d = math.hypot(x - px, z - pz)
        if d <= 1e-9:
            return biome_class
        distances.append((d, biome_class))

    distances.sort(key=lambda item: item[0])
    top = distances[:max(1, max_points)]

    weights: dict[str, float] = defaultdict(float)
    for d, biome_class in top:
        # Squared falloff keeps local classes dominant while still smooth across sparse areas.
        weights[biome_class] += 1.0 / (d * d)

    return max(weights.items(), key=lambda item: item[1])[0]


def build_biome_cells(
    min_x: float,
    max_x: float,
    min_z: float,
    max_z: float,
    biome_points: list[tuple[float, float, str]],
) -> list[tuple[float, float, float, float, str]]:
    extent_x = max(1.0, max_x - min_x)
    extent_z = max(1.0, max_z - min_z)
    aspect = extent_x / extent_z

    cols = max(8, int(math.sqrt(BIOME_INTERPOLATION_TARGET_CELLS * aspect)))
    rows = max(8, int(BIOME_INTERPOLATION_TARGET_CELLS / cols))
    cell_w = extent_x / cols
    cell_h = extent_z / rows

    cells: list[tuple[float, float, float, float, str]] = []
    for row in range(rows):
        z0 = min_z + row * cell_h
        z1 = z0 + cell_h
        cz = (z0 + z1) * 0.5
        for col in range(cols):
            x0 = min_x + col * cell_w
            x1 = x0 + cell_w
            cx = (x0 + x1) * 0.5
            biome_class = nearest_weighted_biome_class(cx, cz, biome_points)
            cells.append((x0, x1, z0, z1, biome_class))

    return cells


def pick_scale_bar_blocks(blocks_per_px: float, target_px: float = SCALE_BAR_TARGET_PX) -> int:
    if blocks_per_px <= 0:
        return 256
    target_blocks = blocks_per_px * target_px
    best = SCALE_BAR_CHOICES_BLOCKS[0]
    best_distance = abs(best - target_blocks)
    for choice in SCALE_BAR_CHOICES_BLOCKS[1:]:
        distance = abs(choice - target_blocks)
        if distance < best_distance:
            best = choice
            best_distance = distance
    return best


def analyze_geojson(
    path: Path,
    expect_center: tuple[int, int] | None,
    expect_radius: int | None,
    config: dict[str, Any],
) -> dict[str, Any]:
    raw = path.read_text()
    data = json.loads(raw)

    if data.get("type") != "FeatureCollection":
        raise ValueError(f"Expected FeatureCollection, got {data.get('type')!r}")

    features = data.get("features", [])
    if not isinstance(features, list):
        raise ValueError("features must be a list")

    ids: list[Any] = []
    civ_ids: list[Any] = []
    coords: list[tuple[float, float]] = []
    accepted_flags: list[bool] = []
    tiers: list[str | None] = []
    types: list[str | None] = []
    regions: list[tuple[int | None, int | None]] = []
    capitals: list[bool] = []
    all_keys: set[str] = set()

    accepted_features: list[dict[str, Any]] = []
    rejected_features: list[dict[str, Any]] = []

    for idx, feature in enumerate(features):
        if feature.get("type") != "Feature":
            raise ValueError(f"Feature #{idx} missing type=Feature")

        geometry = feature.get("geometry", {})
        if geometry.get("type") != "Point":
            raise ValueError(f"Feature #{idx} geometry must be Point")

        coordinates = geometry.get("coordinates", [])
        if not isinstance(coordinates, list) or len(coordinates) < 2:
            raise ValueError(f"Feature #{idx} point coordinates invalid")

        x = to_float(coordinates[0])
        z = to_float(coordinates[1])
        coords.append((x, z))

        props = feature.get("properties", {})
        if not isinstance(props, dict):
            raise ValueError(f"Feature #{idx} properties must be object")

        all_keys.update(props.keys())
        accepted = bool(props.get("accepted", False))
        accepted_flags.append(accepted)

        ids.append(props.get("id"))
        civ_ids.append(props.get("civ_id"))
        tiers.append(props.get("tier"))
        types.append(props.get("type"))
        regions.append((props.get("region_x"), props.get("region_z")))
        capitals.append(bool(props.get("is_capital", False)))

        item = {
            "x": x,
            "z": z,
            "properties": props,
        }
        if accepted:
            accepted_features.append(item)
        else:
            rejected_features.append(item)

    count = len(features)
    accepted_count = len(accepted_features)
    rejected_count = len(rejected_features)

    # Uniqueness checks
    non_null_ids = [x for x in ids if x is not None]
    id_counter = Counter(non_null_ids)
    duplicate_non_null_ids = {str(k): v for k, v in id_counter.items() if v > 1}

    # Regions / bounds
    xs = [p[0] for p in coords]
    zs = [p[1] for p in coords]
    min_x, max_x = min(xs), max(xs)
    min_z, max_z = min(zs), max(zs)

    tier_counter = Counter(tiers)
    type_counter = Counter(types)
    biome_counter = Counter()
    biome_class_counter = Counter()
    accepted_by_tier = Counter(item["properties"].get("tier") for item in accepted_features)
    rejected_by_tier = Counter(item["properties"].get("tier") for item in rejected_features)
    accepted_by_biome_class = Counter()
    region_counter = Counter(regions)

    for item in accepted_features:
        biome = item["properties"].get("biome")
        biome_counter[biome] += 1
        biome_class = classify_biome_class(biome)
        biome_class_counter[biome_class] += 1
        accepted_by_biome_class[biome_class] += 1
    for item in rejected_features:
        biome = item["properties"].get("biome")
        biome_counter[biome] += 1
        biome_class_counter[classify_biome_class(biome)] += 1

    # Key coverage checks
    missing_accept_keys = sorted(
        key for key in EXPECTED_ACCEPTED_KEYS if any(key not in item["properties"] for item in accepted_features)
    )
    missing_reject_keys = sorted(
        key for key in EXPECTED_REJECTED_KEYS if any(key not in item["properties"] for item in rejected_features)
    )

    # Capital invariants (accepted only)
    accepted_by_civ: dict[Any, list[dict[str, Any]]] = defaultdict(list)
    for item in accepted_features:
        accepted_by_civ[item["properties"].get("civ_id")].append(item)

    capitals_per_civ: dict[str, int] = {}
    capital_invariant_violations: dict[str, int] = {}
    for civ_id, items in accepted_by_civ.items():
        cap_count = sum(1 for item in items if bool(item["properties"].get("is_capital", False)))
        civ_key = str(civ_id)
        capitals_per_civ[civ_key] = cap_count
        if cap_count != 1:
            capital_invariant_violations[civ_key] = cap_count

    # Spacing diagnostics (accepted only)
    nearest_within_civ: list[float] = []
    too_close_pairs: list[dict[str, Any]] = []

    cluster_cfg = config.get("cluster", {}) if isinstance(config, dict) else {}
    configured_satellite_min = int(cluster_cfg.get("satelliteMinDistanceBlocks", 0) or 0)
    configured_satellite_max = int(cluster_cfg.get("satelliteMaxDistanceBlocks", 0) or 0)

    for civ_id, items in accepted_by_civ.items():
        points = [(item["x"], item["z"], item["properties"]) for item in items]
        if len(points) < 2:
            continue

        for i, (x1, z1, p1) in enumerate(points):
            nearest = None
            for j, (x2, z2, p2) in enumerate(points):
                if i == j:
                    continue
                distance = math.hypot(x1 - x2, z1 - z2)
                if nearest is None or distance < nearest:
                    nearest = distance

                if configured_satellite_min > 0:
                    is_sat1 = not bool(p1.get("is_capital", False))
                    is_sat2 = not bool(p2.get("is_capital", False))
                    # Report each pair once (i < j), but still compute nearest over all points.
                    if i < j and is_sat1 and is_sat2 and distance < configured_satellite_min:
                        too_close_pairs.append(
                            {
                                "civ_id": str(civ_id),
                                "distance": distance,
                                "a": {
                                    "x": x1,
                                    "z": z1,
                                    "type": p1.get("type"),
                                    "id": p1.get("id"),
                                },
                                "b": {
                                    "x": x2,
                                    "z": z2,
                                    "type": p2.get("type"),
                                    "id": p2.get("id"),
                                },
                            }
                        )

            if nearest is not None:
                nearest_within_civ.append(nearest)

    avg_nearest = (sum(nearest_within_civ) / len(nearest_within_civ)) if nearest_within_civ else None
    min_nearest = min(nearest_within_civ) if nearest_within_civ else None

    # Region window checks
    expected_regions: set[tuple[int, int]] | None = None
    regions_present = {(int(rx), int(rz)) for (rx, rz) in region_counter.keys() if rx is not None and rz is not None}
    missing_expected_regions: list[str] = []
    unexpected_regions: list[str] = []
    if expect_center is not None and expect_radius is not None:
        expected_regions = region_window(expect_center, expect_radius)
        missing_expected_regions = [f"{rx},{rz}" for (rx, rz) in sorted(expected_regions - regions_present)]
        unexpected_regions = [f"{rx},{rz}" for (rx, rz) in sorted(regions_present - expected_regions)]

    warnings: list[str] = []
    if duplicate_non_null_ids:
        warnings.append(f"Duplicate non-null anchor ids: {duplicate_non_null_ids}")
    if capital_invariant_violations:
        warnings.append(f"Accepted civs with capital count != 1: {capital_invariant_violations}")
    if missing_accept_keys:
        warnings.append(f"Accepted features missing expected keys: {missing_accept_keys}")
    if missing_reject_keys:
        warnings.append(f"Rejected features missing expected keys: {missing_reject_keys}")
    if missing_expected_regions:
        warnings.append(f"Missing expected regions: {missing_expected_regions}")
    if unexpected_regions:
        warnings.append(f"Unexpected regions in export: {unexpected_regions}")
    if too_close_pairs:
        # keep warning concise in top-line summary
        warnings.append(
            f"Found {len(too_close_pairs)} accepted non-capital pairs below configured satellite min distance "
            f"({configured_satellite_min})."
        )

    analysis = {
        "source": str(path),
        "sha256": hashlib.sha256(raw.encode("utf-8")).hexdigest(),
        "feature_count": count,
        "accepted_count": accepted_count,
        "rejected_count": rejected_count,
        "unique_non_null_ids": len(duplicate_non_null_ids) == 0,
        "duplicate_non_null_ids": duplicate_non_null_ids,
        "unique_civ_count": len({x for x in civ_ids if x is not None}),
        "coord_bounds": {
            "min_x": min_x,
            "max_x": max_x,
            "min_z": min_z,
            "max_z": max_z,
        },
        "tier_counter": {str(k): v for k, v in tier_counter.items()},
        "type_counter": {str(k): v for k, v in type_counter.items()},
        "biome_counter": {str(k): v for k, v in biome_counter.items()},
        "biome_class_counter": {str(k): v for k, v in biome_class_counter.items()},
        "accepted_by_biome_class": {str(k): v for k, v in accepted_by_biome_class.items()},
        "accepted_by_tier": {str(k): v for k, v in accepted_by_tier.items()},
        "rejected_by_tier": {str(k): v for k, v in rejected_by_tier.items()},
        "region_counter": {f"{rx},{rz}": n for (rx, rz), n in region_counter.items()},
        "capitals_per_civ": capitals_per_civ,
        "capital_invariant_violations": capital_invariant_violations,
        "avg_nearest_within_civ": avg_nearest,
        "min_nearest_within_civ": min_nearest,
        "all_property_keys": sorted(all_keys),
        "missing_expected_accept_keys": missing_accept_keys,
        "missing_expected_reject_keys": missing_reject_keys,
        "configured_satellite_min_distance": configured_satellite_min,
        "configured_satellite_max_distance": configured_satellite_max,
        "too_close_non_capital_pairs": too_close_pairs,
        "warnings": warnings,
    }

    if expected_regions is not None:
        analysis["expected_region_window"] = {
            "center": [expect_center[0], expect_center[1]],
            "radius": expect_radius,
            "expected_count": len(expected_regions),
            "present_count": len(regions_present),
            "missing_expected_regions": missing_expected_regions,
            "unexpected_regions": unexpected_regions,
        }

    return analysis


def render_svg(analysis: dict[str, Any], out_path: Path) -> None:
    tier_counter = analysis["tier_counter"]
    type_counter = analysis["type_counter"]
    biome_class_counter = analysis.get("biome_class_counter", {})

    source_path = Path(analysis["source"])
    data = json.loads(source_path.read_text())
    features = data["features"]

    points = []
    biome_points: list[tuple[float, float, str]] = []
    points_by_civ: dict[str, list[tuple[float, float]]] = defaultdict(list)
    for feature in features:
        props = feature["properties"]
        x, z = feature["geometry"]["coordinates"]
        accepted = bool(props.get("accepted", False))
        tier = props.get("tier")
        is_capital = bool(props.get("is_capital", False))
        civ_id = str(props.get("civ_id"))
        biome = props.get("biome")
        biome_class = classify_biome_class(biome)
        points.append((x, z, accepted, tier, is_capital, civ_id, biome, biome_class))
        if biome_class != "UNKNOWN":
            biome_points.append((float(x), float(z), biome_class))
        if accepted and props.get("civ_id") is not None:
            points_by_civ[civ_id].append((x, z))

    min_x = float(analysis["coord_bounds"]["min_x"])
    max_x = float(analysis["coord_bounds"]["max_x"])
    min_z = float(analysis["coord_bounds"]["min_z"])
    max_z = float(analysis["coord_bounds"]["max_z"])

    W, H = 1500, 980
    MAP_X, MAP_Y, MAP_W, MAP_H = 40, 70, 900, 860
    PANEL_X, PANEL_W = 980, 480

    def esc(value: Any) -> str:
        return html.escape(str(value))

    def sx(x: float) -> float:
        if max_x == min_x:
            return MAP_X + MAP_W / 2.0
        return MAP_X + ((x - min_x) / (max_x - min_x)) * MAP_W

    def sy(z: float) -> float:
        if max_z == min_z:
            return MAP_Y + MAP_H / 2.0
        return MAP_Y + (1.0 - ((z - min_z) / (max_z - min_z))) * MAP_H

    span_x = max(1.0, max_x - min_x)
    span_z = max(1.0, max_z - min_z)
    blocks_per_px_x = span_x / MAP_W
    blocks_per_px_z = span_z / MAP_H
    scale_blocks = pick_scale_bar_blocks(blocks_per_px_x)
    scale_px = scale_blocks / blocks_per_px_x
    scale_chunks = scale_blocks / 16.0

    biome_cells = build_biome_cells(min_x, max_x, min_z, max_z, biome_points)

    svg: list[str] = []
    append = svg.append

    append(f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">')
    append("<defs><style>")
    append(
        ".title{font:700 30px sans-serif;fill:#14213d}.subtitle{font:500 15px sans-serif;fill:#334155}"
        ".label{font:600 14px sans-serif;fill:#1f2937}.small{font:12px sans-serif;fill:#334155}"
        ".tiny{font:11px sans-serif;fill:#475569}"
    )
    append("</style></defs>")
    append('<rect x="0" y="0" width="100%" height="100%" fill="#f8fafc"/>')
    append('<rect x="15" y="15" width="1470" height="950" rx="14" fill="#ffffff" stroke="#dbe3ee"/>')
    append('<text x="40" y="45" class="title">Tribes and Kingdoms GeoJSON Review</text>')
    append('<text x="40" y="64" class="subtitle">Deterministic placement export analysis</text>')

    # map panel
    append(f'<rect x="{MAP_X}" y="{MAP_Y}" width="{MAP_W}" height="{MAP_H}" rx="10" fill="#f9fbff" stroke="#d4dcea"/>')
    append(f'<text x="{MAP_X + 12}" y="{MAP_Y + 24}" class="label">Spatial Layout + Biome Overlay (X/Z)</text>')

    for i in range(6):
        gx = MAP_X + i * MAP_W / 5.0
        gy = MAP_Y + i * MAP_H / 5.0
        append(f'<line x1="{gx:.1f}" y1="{MAP_Y}" x2="{gx:.1f}" y2="{MAP_Y + MAP_H}" stroke="#e6ecf5" stroke-width="1"/>')
        append(f'<line x1="{MAP_X}" y1="{gy:.1f}" x2="{MAP_X + MAP_W}" y2="{gy:.1f}" stroke="#e6ecf5" stroke-width="1"/>')
        tick_x = min_x + (i / 5.0) * (max_x - min_x)
        tick_z = max_z - (i / 5.0) * (max_z - min_z)
        append(f'<text x="{gx:.1f}" y="{MAP_Y + MAP_H + 16:.1f}" class="tiny" text-anchor="middle">{int(round(tick_x))}b</text>')
        append(
            f'<text x="{gx:.1f}" y="{MAP_Y + MAP_H + 30:.1f}" class="tiny" text-anchor="middle">'
            f'{int(round(tick_x / 16.0))}c</text>'
        )
        append(f'<text x="{MAP_X - 8:.1f}" y="{gy + 4:.1f}" class="tiny" text-anchor="end">{int(round(tick_z))}b</text>')

    for x0, x1, z0, z1, biome_class in biome_cells:
        left = sx(x0)
        right = sx(x1)
        top = sy(z1)
        bottom = sy(z0)
        width = abs(right - left)
        height = abs(bottom - top)
        if width < 0.5 or height < 0.5:
            continue
        color = BIOME_CLASS_PALETTE.get(biome_class, BIOME_CLASS_PALETTE["UNKNOWN"])
        append(
            f'<rect x="{min(left, right):.2f}" y="{min(top, bottom):.2f}" '
            f'width="{width:.2f}" height="{height:.2f}" fill="{color}" opacity="0.30"/>'
        )

    for x, z, accepted, tier, is_capital, civ_id, biome, biome_class in points:
        px, py = sx(float(x)), sy(float(z))
        biome_color = BIOME_CLASS_PALETTE.get(biome_class, BIOME_CLASS_PALETTE["UNKNOWN"])
        color = PALETTE.get(tier, "#BBBBBB")
        if is_capital:
            r = 10.0
            stars = []
            for k in range(10):
                angle = -math.pi / 2.0 + k * math.pi / 5.0
                rr = r if k % 2 == 0 else r * 0.45
                stars.append(f"{px + rr * math.cos(angle):.1f},{py + rr * math.sin(angle):.1f}")
            append(
                f'<polygon points="{" ".join(stars)}" fill="{color}" stroke="{biome_color}" stroke-width="2"/>'
            )
        elif accepted:
            append(
                f'<circle cx="{px:.1f}" cy="{py:.1f}" r="5.5" fill="{color}" stroke="{biome_color}" stroke-width="2"/>'
            )
        else:
            append(
                f'<line x1="{px - 4.5:.1f}" y1="{py - 4.5:.1f}" x2="{px + 4.5:.1f}" y2="{py + 4.5:.1f}" '
                f'stroke="{biome_color}" stroke-width="1.8"/>'
            )
            append(
                f'<line x1="{px - 4.5:.1f}" y1="{py + 4.5:.1f}" x2="{px + 4.5:.1f}" y2="{py - 4.5:.1f}" '
                f'stroke="{biome_color}" stroke-width="1.8"/>'
            )

    for civ_id, civ_points in points_by_civ.items():
        cx = sum(x for x, _ in civ_points) / len(civ_points)
        cz = sum(z for _, z in civ_points) / len(civ_points)
        append(f'<text x="{sx(cx):.1f}" y="{sy(cz) - 8:.1f}" class="tiny" text-anchor="middle">{esc(civ_id)}</text>')

    scale_x = MAP_X + 20
    scale_y = MAP_Y + MAP_H - 26
    append(f'<rect x="{scale_x - 10}" y="{scale_y - 30}" width="{scale_px + 24:.1f}" height="44" rx="6" fill="#ffffff" opacity="0.85" stroke="#cbd5e1"/>')
    append(f'<line x1="{scale_x:.1f}" y1="{scale_y:.1f}" x2="{scale_x + scale_px:.1f}" y2="{scale_y:.1f}" stroke="#0f172a" stroke-width="3"/>')
    append(f'<line x1="{scale_x:.1f}" y1="{scale_y - 6:.1f}" x2="{scale_x:.1f}" y2="{scale_y + 6:.1f}" stroke="#0f172a" stroke-width="2"/>')
    append(
        f'<line x1="{scale_x + scale_px:.1f}" y1="{scale_y - 6:.1f}" '
        f'x2="{scale_x + scale_px:.1f}" y2="{scale_y + 6:.1f}" stroke="#0f172a" stroke-width="2"/>'
    )
    append(
        f'<text x="{scale_x + scale_px / 2:.1f}" y="{scale_y - 10:.1f}" class="small" text-anchor="middle">'
        f'{int(scale_blocks)} blocks ({scale_chunks:.1f} chunks)</text>'
    )
    append(
        f'<text x="{scale_x + scale_px / 2:.1f}" y="{scale_y + 16:.1f}" class="tiny" text-anchor="middle">'
        "Map distance legend</text>"
    )

    legend_x = MAP_X + MAP_W - 210
    legend_y = MAP_Y + 44
    append(f'<rect x="{legend_x}" y="{legend_y}" width="190" height="220" rx="8" fill="#ffffff" stroke="#d4dcea"/>')
    append(f'<text x="{legend_x + 10}" y="{legend_y + 18}" class="small">Biome Classes</text>')
    for i, biome_class in enumerate(BIOME_CLASS_ORDER):
        y = legend_y + 38 + (i * 22)
        fill = BIOME_CLASS_PALETTE[biome_class]
        count = int(biome_class_counter.get(biome_class, 0))
        append(f'<rect x="{legend_x + 10}" y="{y - 10}" width="12" height="12" rx="2" fill="{fill}"/>')
        append(f'<text x="{legend_x + 28}" y="{y}" class="tiny">{esc(biome_class)} ({count})</text>')

    # right side panels
    def panel(x: int, y: int, w: int, h: int, title: str) -> None:
        append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="10" fill="#f9fbff" stroke="#d4dcea"/>')
        append(f'<text x="{x + 12}" y="{y + 24}" class="label">{esc(title)}</text>')

    def draw_bars(x: int, y: int, w: int, h: int, labels: list[str], values: list[int], colors: list[str]) -> None:
        if not labels:
            return
        top = y + 44
        left = x + 14
        width = w - 28
        height = h - 64
        count = len(labels)
        gap = 8.0
        bar_w = (width - gap * (count - 1)) / count if count > 0 else 0.0
        max_v = max(values) if values else 0

        for i, (label, value) in enumerate(zip(labels, values)):
            bar_h = 0.0 if max_v == 0 else (value / max_v) * height
            bx = left + i * (bar_w + gap)
            by = top + (height - bar_h)
            color = colors[i] if i < len(colors) else "#4C78A8"
            append(f'<rect x="{bx:.1f}" y="{by:.1f}" width="{bar_w:.1f}" height="{bar_h:.1f}" fill="{color}" rx="4"/>')
            append(f'<text x="{bx + bar_w / 2:.1f}" y="{by - 4:.1f}" class="tiny" text-anchor="middle">{value}</text>')
            append(f'<text x="{bx + bar_w / 2:.1f}" y="{top + height + 14:.1f}" class="tiny" text-anchor="middle">{esc(label)}</text>')

    panel(PANEL_X, 70, PANEL_W, 170, "Tier Distribution")
    ordered_tiers = ["WOOD", "STONE", "IRON", "DIAMOND", "NETHERITE"]
    tier_values = [int(tier_counter.get(tier, 0)) for tier in ordered_tiers]
    tier_colors = [PALETTE[tier] for tier in ordered_tiers]
    draw_bars(PANEL_X, 70, PANEL_W, 170, ordered_tiers, tier_values, tier_colors)

    panel(PANEL_X, 260, PANEL_W, 150, "Type Distribution")
    type_labels = list(type_counter.keys())
    type_values = [int(type_counter[label]) for label in type_labels]
    draw_bars(PANEL_X, 260, PANEL_W, 150, type_labels, type_values, ["#4C78A8"] * len(type_labels))

    panel(PANEL_X, 430, PANEL_W, 170, "Biome Classes")
    biome_labels = [label for label in BIOME_CLASS_ORDER if int(biome_class_counter.get(label, 0)) > 0]
    biome_values = [int(biome_class_counter.get(label, 0)) for label in biome_labels]
    biome_colors = [BIOME_CLASS_PALETTE[label] for label in biome_labels]
    draw_bars(PANEL_X, 430, PANEL_W, 170, biome_labels, biome_values, biome_colors)

    panel(PANEL_X, 620, PANEL_W, 130, "Acceptance")
    draw_bars(
        PANEL_X,
        620,
        PANEL_W,
        130,
        ["Accepted", "Rejected"],
        [int(analysis["accepted_count"]), int(analysis["rejected_count"])],
        ["#2E8B57", "#B23A48"],
    )

    panel(PANEL_X, 770, PANEL_W, 160, "Audit Notes")
    warnings = analysis.get("warnings", [])
    warning_line = warnings[0] if warnings else "No structural warnings."
    oceanic = int(biome_class_counter.get("OCEANIC", 0))
    coastal = int(biome_class_counter.get("COASTAL", 0))
    total = int(analysis["feature_count"]) if analysis["feature_count"] else 1
    sea_ratio = ((oceanic + coastal) / total) * 100.0
    notes = [
        f"Features: {analysis['feature_count']} | Accepted: {analysis['accepted_count']} | Rejected: {analysis['rejected_count']}",
        f"Unique civ_id: {analysis['unique_civ_count']} | Non-null id unique: {analysis['unique_non_null_ids']}",
        f"Regions: {', '.join(sorted(analysis['region_counter'].keys())[:6])}"
        + (" ..." if len(analysis["region_counter"]) > 6 else ""),
        "Biome polygons are interpolated from sampled point biomes (not full world scan).",
        f"Bounds X[{analysis['coord_bounds']['min_x']},{analysis['coord_bounds']['max_x']}] Z[{analysis['coord_bounds']['min_z']},{analysis['coord_bounds']['max_z']}]",
        f"Scale: ~{blocks_per_px_x:.2f} blocks/px X, ~{blocks_per_px_z:.2f} blocks/px Z",
        f"Land/sea split: {(100.0 - sea_ratio):.1f}% land-like vs {sea_ratio:.1f}% sea/coastal",
        f"Configured satellite min/max: {analysis['configured_satellite_min_distance']}/{analysis['configured_satellite_max_distance']}",
        (
            f"Nearest accepted same-civ spacing: min {analysis['min_nearest_within_civ']:.1f}, "
            f"avg {analysis['avg_nearest_within_civ']:.1f}"
            if analysis["min_nearest_within_civ"] is not None
            else "Nearest accepted same-civ spacing: n/a"
        ),
        f"Warning: {warning_line}",
    ]

    for i, line in enumerate(notes):
        append(f'<text x="{PANEL_X + 14}" y="{796 + i * 19}" class="small">{esc(line)}</text>')

    append(f'<text x="40" y="955" class="tiny">Source: {esc(analysis["source"])}</text>')
    append(f'<text x="40" y="972" class="tiny">SHA256: {esc(analysis["sha256"])}</text>')
    append("</svg>")

    out_path.write_text("\n".join(svg))


def main() -> None:
    args = parse_args()
    args.out_dir.mkdir(parents=True, exist_ok=True)

    if args.expect_center is None and args.expect_radius is not None:
        raise SystemExit("--expect-radius requires --expect-center")

    config = load_config(args.config)
    expect_center = tuple(args.expect_center) if args.expect_center is not None else None
    analysis = analyze_geojson(args.input, expect_center, args.expect_radius, config)

    summary_path = args.out_dir / "analysis-summary.json"
    svg_path = args.out_dir / "kingdom-geojson-visual-review.svg"
    summary_path.write_text(json.dumps(analysis, indent=2, sort_keys=True))
    render_svg(analysis, svg_path)

    print(f"Wrote summary: {summary_path}")
    print(f"Wrote svg: {svg_path}")
    if analysis["warnings"]:
        print("Warnings:")
        for warning in analysis["warnings"]:
            print(f"- {warning}")
    else:
        print("No warnings.")


if __name__ == "__main__":
    main()
