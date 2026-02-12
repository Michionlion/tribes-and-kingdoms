#!/usr/bin/env python3
"""Analyze kingdom GeoJSON debug export and render a standalone SVG dashboard.

Usage:
  scripts/analyze_kingdom_geojson.py <input.geojson> [--out-dir DIR]
      [--expect-center RX RZ --expect-radius R]
      [--config PATH_TO_KINGDOM_TOML]

Outputs:
  - analysis-summary.json
  - kingdom-geojson-visual-review.svg
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import html
import json
import math
import struct
import tomllib
import zlib
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

PALETTE = {
    "WOOD": "#B8742A",
    "STONE": "#7D838C",
    "IRON": "#C9D6E3",
    "DIAMOND": "#00AEEF",
    "NETHERITE": "#A11D33",
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
    "OCEANIC": "#0057D9",
    "COASTAL": "#2B9CFF",
    "TEMPERATE": "#4CAF50",
    "ARID": "#D4A24C",
    "COLD": "#9BD9FF",
    "HIGHLAND": "#7A6F64",
    "OTHER": "#9C7BD9",
    "UNKNOWN": "#B0B0B0",
}

BIOME_CLASS_OVERLAY_OPACITY = {
    "OCEANIC": 0.70,
    "COASTAL": 0.48,
    "TEMPERATE": 0.18,
    "ARID": 0.20,
    "COLD": 0.24,
    "HIGHLAND": 0.20,
    "OTHER": 0.20,
    "UNKNOWN": 0.14,
}

BIOME_INTERPOLATION_MAX_POINTS = 6
TERRAIN_INTERPOLATION_MAX_POINTS = 8
CHUNK_BLOCK_SPAN = 16
TERRAIN_QUANTIZATION_BINS = 12
HILLSHADE_QUANTIZATION_BINS = 12
HILLSHADE_LIGHT_AZIMUTH_DEGREES = 315.0
HILLSHADE_LIGHT_ALTITUDE_DEGREES = 48.0
SCALE_BAR_TARGET_PX = 160.0
SCALE_BAR_CHOICES_BLOCKS = [64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384]
TERRAIN_SAMPLE_KIND = "terrain_sample"
CAPITAL_ICON_DIAMETER_CHUNKS = 6.0
SETTLEMENT_ICON_DIAMETER_CHUNKS = 3.0


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
        help="Optional kingdom.toml to apply spacing checks from cluster settings",
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


def to_optional_float(value: Any) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def clamp01(value: float) -> float:
    return max(0.0, min(1.0, value))


def quantize01(value: float, bins: int = TERRAIN_QUANTIZATION_BINS) -> int:
    if bins <= 1:
        return 0
    return int(round(clamp01(value) * (bins - 1)))


def dequantize01(value: int, bins: int = TERRAIN_QUANTIZATION_BINS) -> float:
    if bins <= 1:
        return 0.0
    return clamp01(value / (bins - 1))


def hex_to_rgb(color: str) -> tuple[int, int, int]:
    text = color.lstrip("#")
    if len(text) != 6:
        return 0, 0, 0
    return int(text[0:2], 16), int(text[2:4], 16), int(text[4:6], 16)


def rgb_to_hex(r: int, g: int, b: int) -> str:
    return f"#{max(0, min(255, r)):02x}{max(0, min(255, g)):02x}{max(0, min(255, b)):02x}"


def shade_hex(color: str, factor: float) -> str:
    r, g, b = hex_to_rgb(color)
    if factor >= 1.0:
        t = min(1.0, factor - 1.0)
        r = int(round(r + ((255 - r) * t)))
        g = int(round(g + ((255 - g) * t)))
        b = int(round(b + ((255 - b) * t)))
    else:
        r = int(round(r * factor))
        g = int(round(g * factor))
        b = int(round(b * factor))
    return rgb_to_hex(r, g, b)


def blend_hex(left: str, right: str, t: float) -> str:
    t = clamp01(t)
    lr, lg, lb = hex_to_rgb(left)
    rr, rg, rb = hex_to_rgb(right)
    r = int(round(lr + ((rr - lr) * t)))
    g = int(round(lg + ((rg - lg) * t)))
    b = int(round(lb + ((rb - lb) * t)))
    return rgb_to_hex(r, g, b)


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


def biome_overlay_opacity(biome_class: str) -> float:
    return clamp01(BIOME_CLASS_OVERLAY_OPACITY.get(biome_class, BIOME_CLASS_OVERLAY_OPACITY["UNKNOWN"]))


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


def nearest_weighted_scalar(
    x: float,
    z: float,
    points: list[tuple[float, float, float]],
    max_points: int = TERRAIN_INTERPOLATION_MAX_POINTS,
    default: float = 0.5,
) -> float:
    if not points:
        return default

    distances: list[tuple[float, float]] = []
    for px, pz, value in points:
        d = math.hypot(x - px, z - pz)
        if d <= 1e-9:
            return clamp01(value)
        distances.append((d, value))

    distances.sort(key=lambda item: item[0])
    top = distances[:max(1, max_points)]

    weighted = 0.0
    weight_total = 0.0
    for d, value in top:
        w = 1.0 / (d * d)
        weighted += value * w
        weight_total += w

    if weight_total <= 0.0:
        return default
    return clamp01(weighted / weight_total)


def hillshade_from_neighbors(
    east_height: float,
    west_height: float,
    south_height: float,
    north_height: float,
    cell_size_blocks: float = CHUNK_BLOCK_SPAN,
) -> float:
    safe_cell_size = max(1e-6, float(cell_size_blocks))
    dzdx = (east_height - west_height) / (2.0 * safe_cell_size)
    dzdz = (south_height - north_height) / (2.0 * safe_cell_size)

    nx, ny, nz = -dzdx, -dzdz, 1.0
    nlen = math.sqrt((nx * nx) + (ny * ny) + (nz * nz))
    if nlen <= 1e-9:
        return 0.5
    nx /= nlen
    ny /= nlen
    nz /= nlen

    azimuth = math.radians(HILLSHADE_LIGHT_AZIMUTH_DEGREES)
    altitude = math.radians(HILLSHADE_LIGHT_ALTITUDE_DEGREES)
    lx = math.cos(altitude) * math.cos(azimuth)
    ly = math.cos(altitude) * math.sin(azimuth)
    lz = math.sin(altitude)
    dot = (nx * lx) + (ny * ly) + (nz * lz)

    # Include a small ambient term so valleys/hills remain readable.
    return clamp01((dot * 0.85) + 0.15)


def terrain_fill_color(height_score: float, slope_score: float, hillshade_score: float) -> str:
    # Elevation drives the grayscale ramp; slope and hillshade amplify relief contrast.
    elevation = pow(clamp01(height_score), 0.72)
    steepness = 1.0 - clamp01(slope_score)
    hillshade_centered = (clamp01(hillshade_score) - 0.5) * 2.0

    base = 22.0 + (elevation * 210.0)
    relief_boost = (elevation - 0.5) * (16.0 + (34.0 * steepness))
    directional_boost = hillshade_centered * (20.0 + (52.0 * steepness))
    valley_occlusion = steepness * (1.0 - elevation) * 30.0

    brightness = base + relief_boost + directional_boost - valley_occlusion
    gray = max(0, min(255, int(round(brightness))))
    return rgb_to_hex(gray, gray, gray)


def build_chunk_biome_runs(
    min_x: float,
    max_x: float,
    min_z: float,
    max_z: float,
    biome_points: list[tuple[float, float, str]],
) -> list[tuple[float, float, float, float, str]]:
    min_chunk_x = math.floor(min_x / CHUNK_BLOCK_SPAN)
    max_chunk_x = math.floor(max_x / CHUNK_BLOCK_SPAN)
    min_chunk_z = math.floor(min_z / CHUNK_BLOCK_SPAN)
    max_chunk_z = math.floor(max_z / CHUNK_BLOCK_SPAN)

    runs: list[tuple[float, float, float, float, str]] = []

    for chunk_z in range(min_chunk_z, max_chunk_z + 1):
        row_z0 = chunk_z * CHUNK_BLOCK_SPAN
        row_z1 = row_z0 + CHUNK_BLOCK_SPAN

        run_start_chunk_x = min_chunk_x
        current_class: str | None = None

        for chunk_x in range(min_chunk_x, max_chunk_x + 1):
            center_x = (chunk_x * CHUNK_BLOCK_SPAN) + (CHUNK_BLOCK_SPAN * 0.5)
            center_z = (chunk_z * CHUNK_BLOCK_SPAN) + (CHUNK_BLOCK_SPAN * 0.5)
            biome_class = nearest_weighted_biome_class(center_x, center_z, biome_points)

            if current_class is None:
                current_class = biome_class
                run_start_chunk_x = chunk_x
                continue

            if biome_class != current_class:
                runs.append(
                    (
                        run_start_chunk_x * CHUNK_BLOCK_SPAN,
                        chunk_x * CHUNK_BLOCK_SPAN,
                        row_z0,
                        row_z1,
                        current_class,
                    )
                )
                run_start_chunk_x = chunk_x
                current_class = biome_class

        if current_class is not None:
            runs.append(
                (
                    run_start_chunk_x * CHUNK_BLOCK_SPAN,
                    (max_chunk_x + 1) * CHUNK_BLOCK_SPAN,
                    row_z0,
                    row_z1,
                    current_class,
                )
            )

    return runs


def build_chunk_terrain_runs_interpolated(
    min_x: float,
    max_x: float,
    min_z: float,
    max_z: float,
    biome_points: list[tuple[float, float, str]],
    height_points: list[tuple[float, float, float]],
    slope_points: list[tuple[float, float, float]],
) -> tuple[list[tuple[float, float, float, float, int, int, int]], int, tuple[float, float], tuple[float, float], tuple[float, float]]:
    min_chunk_x = math.floor(min_x / CHUNK_BLOCK_SPAN)
    max_chunk_x = math.floor(max_x / CHUNK_BLOCK_SPAN)
    min_chunk_z = math.floor(min_z / CHUNK_BLOCK_SPAN)
    max_chunk_z = math.floor(max_z / CHUNK_BLOCK_SPAN)

    runs: list[tuple[float, float, float, float, int, int, int]] = []
    chunk_count = 0

    for chunk_z in range(min_chunk_z, max_chunk_z + 1):
        row_z0 = chunk_z * CHUNK_BLOCK_SPAN
        row_z1 = row_z0 + CHUNK_BLOCK_SPAN

        run_start_chunk_x = min_chunk_x
        current_key: tuple[int, int, int] | None = None

        for chunk_x in range(min_chunk_x, max_chunk_x + 1):
            center_x = (chunk_x * CHUNK_BLOCK_SPAN) + (CHUNK_BLOCK_SPAN * 0.5)
            center_z = (chunk_z * CHUNK_BLOCK_SPAN) + (CHUNK_BLOCK_SPAN * 0.5)
            height_score = nearest_weighted_scalar(center_x, center_z, height_points)
            slope_score = nearest_weighted_scalar(center_x, center_z, slope_points)
            # Interpolated terrain lacks raw neighboring heights, so approximate a local gradient
            # using nearby interpolated samples and treat it as normalized elevation.
            east_height = nearest_weighted_scalar(center_x + CHUNK_BLOCK_SPAN, center_z, height_points)
            west_height = nearest_weighted_scalar(center_x - CHUNK_BLOCK_SPAN, center_z, height_points)
            south_height = nearest_weighted_scalar(center_x, center_z + CHUNK_BLOCK_SPAN, height_points)
            north_height = nearest_weighted_scalar(center_x, center_z - CHUNK_BLOCK_SPAN, height_points)
            hillshade = hillshade_from_neighbors(
                east_height=east_height * 255.0,
                west_height=west_height * 255.0,
                south_height=south_height * 255.0,
                north_height=north_height * 255.0,
                cell_size_blocks=CHUNK_BLOCK_SPAN,
            )
            key = (
                quantize01(height_score),
                quantize01(slope_score),
                quantize01(hillshade, HILLSHADE_QUANTIZATION_BINS),
            )
            chunk_count += 1

            if current_key is None:
                current_key = key
                run_start_chunk_x = chunk_x
                continue

            if key != current_key:
                height_bin_prev, slope_bin_prev, hillshade_bin_prev = current_key
                runs.append(
                    (
                        run_start_chunk_x * CHUNK_BLOCK_SPAN,
                        chunk_x * CHUNK_BLOCK_SPAN,
                        row_z0,
                        row_z1,
                        height_bin_prev,
                        slope_bin_prev,
                        hillshade_bin_prev,
                    )
                )
                run_start_chunk_x = chunk_x
                current_key = key

        if current_key is not None:
            height_bin_prev, slope_bin_prev, hillshade_bin_prev = current_key
            runs.append(
                (
                    run_start_chunk_x * CHUNK_BLOCK_SPAN,
                    (max_chunk_x + 1) * CHUNK_BLOCK_SPAN,
                    row_z0,
                    row_z1,
                    height_bin_prev,
                    slope_bin_prev,
                    hillshade_bin_prev,
                )
            )

    return runs, chunk_count, (0.0, 1.0), (0.0, 1.0), (0.0, 1.0)


def build_chunk_terrain_runs_from_samples(
    terrain_samples: list[tuple[int, int, str, float, float]],
) -> tuple[list[tuple[float, float, float, float, int, int, int]], int, tuple[float, float], tuple[float, float], tuple[float, float]]:
    if not terrain_samples:
        return [], 0, (0.0, 0.0), (0.0, 0.0), (0.0, 0.0)

    chunk_rows: dict[tuple[int, int], tuple[float, float]] = {}
    surface_values: list[float] = []
    slope_values: list[float] = []
    hillshade_values: list[float] = []
    min_chunk_x = math.inf
    max_chunk_x = -math.inf
    min_chunk_z = math.inf
    max_chunk_z = -math.inf

    for chunk_x, chunk_z, biome_class, surface_y, slope_delta in terrain_samples:
        chunk_rows[(chunk_x, chunk_z)] = (surface_y, max(0.0, slope_delta))
        surface_values.append(surface_y)
        slope_values.append(max(0.0, slope_delta))
        min_chunk_x = min(min_chunk_x, chunk_x)
        max_chunk_x = max(max_chunk_x, chunk_x)
        min_chunk_z = min(min_chunk_z, chunk_z)
        max_chunk_z = max(max_chunk_z, chunk_z)

    min_surface = min(surface_values)
    max_surface = max(surface_values)
    surface_span = max(1.0, max_surface - min_surface)
    min_slope = min(slope_values)
    max_slope = max(slope_values)
    slope_span = max(1.0, max_slope - min_slope)

    runs: list[tuple[float, float, float, float, int, int, int]] = []
    chunk_count = 0
    min_chunk_x_i = int(min_chunk_x)
    max_chunk_x_i = int(max_chunk_x)
    min_chunk_z_i = int(min_chunk_z)
    max_chunk_z_i = int(max_chunk_z)

    for chunk_z in range(min_chunk_z_i, max_chunk_z_i + 1):
        row_z0 = chunk_z * CHUNK_BLOCK_SPAN
        row_z1 = row_z0 + CHUNK_BLOCK_SPAN
        run_start_chunk_x = min_chunk_x_i
        current_key: tuple[int, int, int] | None = None

        for chunk_x in range(min_chunk_x_i, max_chunk_x_i + 1):
            surface_y, slope_delta = chunk_rows.get(
                (chunk_x, chunk_z),
                (min_surface, min_slope),
            )
            east_surface, _ = chunk_rows.get((chunk_x + 1, chunk_z), (surface_y, slope_delta))
            west_surface, _ = chunk_rows.get((chunk_x - 1, chunk_z), (surface_y, slope_delta))
            south_surface, _ = chunk_rows.get((chunk_x, chunk_z + 1), (surface_y, slope_delta))
            north_surface, _ = chunk_rows.get((chunk_x, chunk_z - 1), (surface_y, slope_delta))

            height_score = clamp01((surface_y - min_surface) / surface_span)
            steepness = clamp01((slope_delta - min_slope) / slope_span)
            slope_score = 1.0 - steepness
            hillshade = hillshade_from_neighbors(
                east_height=east_surface,
                west_height=west_surface,
                south_height=south_surface,
                north_height=north_surface,
                cell_size_blocks=CHUNK_BLOCK_SPAN,
            )
            hillshade_values.append(hillshade)
            key = (
                quantize01(height_score),
                quantize01(slope_score),
                quantize01(hillshade, HILLSHADE_QUANTIZATION_BINS),
            )
            chunk_count += 1

            if current_key is None:
                current_key = key
                run_start_chunk_x = chunk_x
                continue

            if key != current_key:
                height_bin_prev, slope_bin_prev, hillshade_bin_prev = current_key
                runs.append(
                    (
                        run_start_chunk_x * CHUNK_BLOCK_SPAN,
                        chunk_x * CHUNK_BLOCK_SPAN,
                        row_z0,
                        row_z1,
                        height_bin_prev,
                        slope_bin_prev,
                        hillshade_bin_prev,
                    )
                )
                run_start_chunk_x = chunk_x
                current_key = key

        if current_key is not None:
            height_bin_prev, slope_bin_prev, hillshade_bin_prev = current_key
            runs.append(
                (
                    run_start_chunk_x * CHUNK_BLOCK_SPAN,
                    (max_chunk_x_i + 1) * CHUNK_BLOCK_SPAN,
                    row_z0,
                    row_z1,
                    height_bin_prev,
                    slope_bin_prev,
                    hillshade_bin_prev,
                )
            )

    min_hillshade = min(hillshade_values) if hillshade_values else 0.0
    max_hillshade = max(hillshade_values) if hillshade_values else 0.0
    return runs, chunk_count, (min_surface, max_surface), (min_slope, max_slope), (min_hillshade, max_hillshade)


def build_chunk_biome_runs_from_samples(
    terrain_samples: list[tuple[int, int, str, float, float]],
) -> list[tuple[float, float, float, float, str]]:
    if not terrain_samples:
        return []

    chunk_rows: dict[tuple[int, int], str] = {}
    min_chunk_x = math.inf
    max_chunk_x = -math.inf
    min_chunk_z = math.inf
    max_chunk_z = -math.inf

    for chunk_x, chunk_z, biome_class, _surface_y, _slope_delta in terrain_samples:
        chunk_rows[(chunk_x, chunk_z)] = biome_class
        min_chunk_x = min(min_chunk_x, chunk_x)
        max_chunk_x = max(max_chunk_x, chunk_x)
        min_chunk_z = min(min_chunk_z, chunk_z)
        max_chunk_z = max(max_chunk_z, chunk_z)

    runs: list[tuple[float, float, float, float, str]] = []
    min_chunk_x_i = int(min_chunk_x)
    max_chunk_x_i = int(max_chunk_x)
    min_chunk_z_i = int(min_chunk_z)
    max_chunk_z_i = int(max_chunk_z)

    for chunk_z in range(min_chunk_z_i, max_chunk_z_i + 1):
        row_z0 = chunk_z * CHUNK_BLOCK_SPAN
        row_z1 = row_z0 + CHUNK_BLOCK_SPAN
        run_start_chunk_x = min_chunk_x_i
        current_class: str | None = None

        for chunk_x in range(min_chunk_x_i, max_chunk_x_i + 1):
            biome_class = chunk_rows.get((chunk_x, chunk_z), "UNKNOWN")
            if current_class is None:
                current_class = biome_class
                run_start_chunk_x = chunk_x
                continue

            if biome_class != current_class:
                runs.append(
                    (
                        run_start_chunk_x * CHUNK_BLOCK_SPAN,
                        chunk_x * CHUNK_BLOCK_SPAN,
                        row_z0,
                        row_z1,
                        current_class,
                    )
                )
                run_start_chunk_x = chunk_x
                current_class = biome_class

        if current_class is not None:
            runs.append(
                (
                    run_start_chunk_x * CHUNK_BLOCK_SPAN,
                    (max_chunk_x_i + 1) * CHUNK_BLOCK_SPAN,
                    row_z0,
                    row_z1,
                    current_class,
                )
            )

    return runs


def _chunk_bounds_from_runs(runs: list[tuple[Any, ...]]) -> tuple[int, int, int, int] | None:
    if not runs:
        return None

    min_chunk_x = math.inf
    max_chunk_x = -math.inf
    min_chunk_z = math.inf
    max_chunk_z = -math.inf
    for run in runs:
        x0, x1, z0, z1 = run[0], run[1], run[2], run[3]
        start_chunk_x = int(round(float(x0) / CHUNK_BLOCK_SPAN))
        end_chunk_x = int(round(float(x1) / CHUNK_BLOCK_SPAN))
        start_chunk_z = int(round(float(z0) / CHUNK_BLOCK_SPAN))
        end_chunk_z = int(round(float(z1) / CHUNK_BLOCK_SPAN))
        if end_chunk_x <= start_chunk_x or end_chunk_z <= start_chunk_z:
            continue
        min_chunk_x = min(min_chunk_x, start_chunk_x)
        max_chunk_x = max(max_chunk_x, end_chunk_x - 1)
        min_chunk_z = min(min_chunk_z, start_chunk_z)
        max_chunk_z = max(max_chunk_z, end_chunk_z - 1)

    if math.isinf(min_chunk_x):
        return None
    return int(min_chunk_x), int(max_chunk_x), int(min_chunk_z), int(max_chunk_z)


def _png_chunk(tag: bytes, payload: bytes) -> bytes:
    return (
        struct.pack(">I", len(payload))
        + tag
        + payload
        + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF)
    )


def _encode_png(width: int, height: int, pixels: bytes, channels: int) -> bytes:
    if width <= 0 or height <= 0:
        raise ValueError("PNG dimensions must be positive")
    expected_len = width * height * channels
    if len(pixels) != expected_len:
        raise ValueError(f"PNG pixel buffer size mismatch: expected {expected_len}, got {len(pixels)}")
    if channels not in (3, 4):
        raise ValueError(f"Unsupported channel count {channels}, expected 3 or 4")

    color_type = 2 if channels == 3 else 6
    row_stride = width * channels
    scanlines = bytearray()
    for y in range(height):
        offset = y * row_stride
        scanlines.append(0)  # filter type 0 (None)
        scanlines.extend(pixels[offset: offset + row_stride])

    return b"".join(
        [
            b"\x89PNG\r\n\x1a\n",
            _png_chunk(
                b"IHDR",
                struct.pack(">IIBBBBB", width, height, 8, color_type, 0, 0, 0),
            ),
            _png_chunk(b"IDAT", zlib.compress(bytes(scanlines), level=6)),
            _png_chunk(b"IEND", b""),
        ]
    )


def _png_data_uri(width: int, height: int, pixels: bytes, channels: int) -> str:
    encoded = base64.b64encode(_encode_png(width, height, pixels, channels)).decode("ascii")
    return f"data:image/png;base64,{encoded}"


def rasterize_chunk_layers(
    terrain_chunk_runs: list[tuple[float, float, float, float, int, int, int]],
    biome_overlay_runs: list[tuple[float, float, float, float, str]],
) -> dict[str, Any] | None:
    terrain_bounds = _chunk_bounds_from_runs(terrain_chunk_runs)
    biome_bounds = _chunk_bounds_from_runs(biome_overlay_runs)
    if terrain_bounds is None and biome_bounds is None:
        return None

    bounds_candidates = [b for b in (terrain_bounds, biome_bounds) if b is not None]
    assert bounds_candidates
    min_chunk_x = min(item[0] for item in bounds_candidates)
    max_chunk_x = max(item[1] for item in bounds_candidates)
    min_chunk_z = min(item[2] for item in bounds_candidates)
    max_chunk_z = max(item[3] for item in bounds_candidates)

    chunk_width = (max_chunk_x - min_chunk_x) + 1
    chunk_height = (max_chunk_z - min_chunk_z) + 1
    if chunk_width <= 0 or chunk_height <= 0:
        return None

    terrain_pixels = bytearray([140, 140, 140] * (chunk_width * chunk_height))
    terrain_color_cache: dict[tuple[int, int, int], tuple[int, int, int]] = {}

    for x0, x1, z0, z1, height_bin, slope_bin, hillshade_bin in terrain_chunk_runs:
        start_chunk_x = int(round(x0 / CHUNK_BLOCK_SPAN))
        end_chunk_x = int(round(x1 / CHUNK_BLOCK_SPAN))
        start_chunk_z = int(round(z0 / CHUNK_BLOCK_SPAN))
        end_chunk_z = int(round(z1 / CHUNK_BLOCK_SPAN))
        if end_chunk_x <= start_chunk_x or end_chunk_z <= start_chunk_z:
            continue

        color_key = (height_bin, slope_bin, hillshade_bin)
        color_rgb = terrain_color_cache.get(color_key)
        if color_rgb is None:
            color_hex = terrain_fill_color(
                dequantize01(height_bin),
                dequantize01(slope_bin),
                dequantize01(hillshade_bin, HILLSHADE_QUANTIZATION_BINS),
            )
            color_rgb = hex_to_rgb(color_hex)
            terrain_color_cache[color_key] = color_rgb

        r, g, b = color_rgb
        segment = bytes((r, g, b)) * max(1, end_chunk_x - start_chunk_x)
        for chunk_z in range(start_chunk_z, end_chunk_z):
            # SVG map space places larger Z toward the top, so raster rows must be flipped
            # to keep chunk pixels aligned with settlement points.
            row_index = max_chunk_z - chunk_z
            if row_index < 0 or row_index >= chunk_height:
                continue
            col_start = start_chunk_x - min_chunk_x
            col_end = end_chunk_x - min_chunk_x
            col_start = max(0, col_start)
            col_end = min(chunk_width, col_end)
            if col_end <= col_start:
                continue
            row_offset = ((row_index * chunk_width) + col_start) * 3
            terrain_pixels[row_offset: row_offset + ((col_end - col_start) * 3)] = segment[: (col_end - col_start) * 3]

    biome_pixels = bytearray([0, 0, 0, 0] * (chunk_width * chunk_height))
    biome_color_cache: dict[str, bytes] = {}
    for x0, x1, z0, z1, biome_class in biome_overlay_runs:
        start_chunk_x = int(round(x0 / CHUNK_BLOCK_SPAN))
        end_chunk_x = int(round(x1 / CHUNK_BLOCK_SPAN))
        start_chunk_z = int(round(z0 / CHUNK_BLOCK_SPAN))
        end_chunk_z = int(round(z1 / CHUNK_BLOCK_SPAN))
        if end_chunk_x <= start_chunk_x or end_chunk_z <= start_chunk_z:
            continue

        rgba_bytes = biome_color_cache.get(biome_class)
        if rgba_bytes is None:
            br, bg, bb = hex_to_rgb(BIOME_CLASS_PALETTE.get(biome_class, BIOME_CLASS_PALETTE["UNKNOWN"]))
            biome_alpha = int(round(biome_overlay_opacity(biome_class) * 255))
            rgba_bytes = bytes((br, bg, bb, biome_alpha))
            biome_color_cache[biome_class] = rgba_bytes

        segment = rgba_bytes * max(1, end_chunk_x - start_chunk_x)
        for chunk_z in range(start_chunk_z, end_chunk_z):
            # Match the terrain layer's Z orientation (larger Z at top of map).
            row_index = max_chunk_z - chunk_z
            if row_index < 0 or row_index >= chunk_height:
                continue
            col_start = start_chunk_x - min_chunk_x
            col_end = end_chunk_x - min_chunk_x
            col_start = max(0, col_start)
            col_end = min(chunk_width, col_end)
            if col_end <= col_start:
                continue
            row_offset = ((row_index * chunk_width) + col_start) * 4
            biome_pixels[row_offset: row_offset + ((col_end - col_start) * 4)] = segment[: (col_end - col_start) * 4]

    min_block_x = min_chunk_x * CHUNK_BLOCK_SPAN
    max_block_x = (max_chunk_x + 1) * CHUNK_BLOCK_SPAN
    min_block_z = min_chunk_z * CHUNK_BLOCK_SPAN
    max_block_z = (max_chunk_z + 1) * CHUNK_BLOCK_SPAN

    return {
        "chunk_width": chunk_width,
        "chunk_height": chunk_height,
        "min_block_x": float(min_block_x),
        "max_block_x": float(max_block_x),
        "min_block_z": float(min_block_z),
        "max_block_z": float(max_block_z),
        "terrain_png_data_uri": _png_data_uri(chunk_width, chunk_height, bytes(terrain_pixels), 3),
        "biome_png_data_uri": _png_data_uri(chunk_width, chunk_height, bytes(biome_pixels), 4),
    }


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


def block_diameter_to_screen(diameter_blocks: float, blocks_per_px_x: float, blocks_per_px_z: float) -> tuple[float, float]:
    safe_x = max(1e-9, blocks_per_px_x)
    safe_z = max(1e-9, blocks_per_px_z)
    return diameter_blocks / safe_x, diameter_blocks / safe_z


def regular_polygon_points(cx: float, cy: float, rx: float, ry: float, sides: int, rotation_radians: float = 0.0) -> str:
    safe_sides = max(3, int(sides))
    points: list[str] = []
    for i in range(safe_sides):
        angle = rotation_radians + ((2.0 * math.pi * i) / safe_sides)
        points.append(f"{cx + rx * math.cos(angle):.1f},{cy + ry * math.sin(angle):.1f}")
    return " ".join(points)


def star_points(
    cx: float,
    cy: float,
    outer_rx: float,
    outer_ry: float,
    tips: int = 5,
    inner_ratio: float = 0.45,
    rotation_radians: float = -math.pi / 2.0,
) -> str:
    safe_tips = max(3, int(tips))
    safe_inner = max(0.1, min(0.95, float(inner_ratio)))
    points: list[str] = []
    for i in range(safe_tips * 2):
        angle = rotation_radians + ((math.pi * i) / safe_tips)
        rr_x = outer_rx if i % 2 == 0 else outer_rx * safe_inner
        rr_y = outer_ry if i % 2 == 0 else outer_ry * safe_inner
        points.append(f"{cx + rr_x * math.cos(angle):.1f},{cy + rr_y * math.sin(angle):.1f}")
    return " ".join(points)


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
    tiers: list[str | None] = []
    types: list[str | None] = []
    regions: list[tuple[int | None, int | None]] = []
    all_keys: set[str] = set()
    score_height_count = 0
    score_slope_count = 0

    settlement_feature_count = 0
    settlement_features: list[dict[str, Any]] = []
    terrain_samples: list[dict[str, Any]] = []

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

        props = feature.get("properties", {})
        if not isinstance(props, dict):
            raise ValueError(f"Feature #{idx} properties must be object")

        all_keys.update(props.keys())
        feature_kind = str(props.get("feature_kind", "settlement")).strip().lower()
        if feature_kind == TERRAIN_SAMPLE_KIND:
            coords.append((x, z))
            surface_y = to_optional_float(props.get("surface_y"))
            slope_delta = to_optional_float(props.get("slope_delta_blocks"))
            chunk_x_raw = props.get("chunk_x")
            chunk_z_raw = props.get("chunk_z")
            chunk_x = int(chunk_x_raw) if isinstance(chunk_x_raw, int) else int(math.floor(x / CHUNK_BLOCK_SPAN))
            chunk_z = int(chunk_z_raw) if isinstance(chunk_z_raw, int) else int(math.floor(z / CHUNK_BLOCK_SPAN))
            if surface_y is not None and slope_delta is not None:
                terrain_samples.append(
                    {
                        "x": x,
                        "z": z,
                        "chunk_x": chunk_x,
                        "chunk_z": chunk_z,
                        "surface_y": surface_y,
                        "slope_delta": max(0.0, slope_delta),
                        "biome_class": classify_biome_class(props.get("biome")),
                    }
                )
            continue

        accepted = bool(props.get("accepted", False))
        if not accepted:
            # Ignore rejected candidates entirely to keep analysis focused on live anchors.
            continue
        coords.append((x, z))
        settlement_feature_count += 1

        if to_optional_float(props.get("score_height")) is not None:
            score_height_count += 1
        if to_optional_float(props.get("score_slope")) is not None:
            score_slope_count += 1

        ids.append(props.get("id"))
        civ_ids.append(props.get("civ_id"))
        tiers.append(props.get("tier"))
        types.append(props.get("type"))
        regions.append((props.get("region_x"), props.get("region_z")))

        item = {
            "x": x,
            "z": z,
            "properties": props,
        }
        settlement_features.append(item)

    raw_feature_count = len(features)
    settlement_count = len(settlement_features)
    count = settlement_count

    # Uniqueness checks
    non_null_ids = [x for x in ids if x is not None]
    id_counter = Counter(non_null_ids)
    duplicate_non_null_ids = {str(k): v for k, v in id_counter.items() if v > 1}

    # Regions / bounds
    if not coords:
        raise ValueError("No accepted settlement features or terrain_sample features available for analysis.")
    xs = [p[0] for p in coords]
    zs = [p[1] for p in coords]
    min_x, max_x = min(xs), max(xs)
    min_z, max_z = min(zs), max(zs)

    tier_counter = Counter(tiers)
    type_counter = Counter(types)
    biome_counter = Counter()
    biome_class_counter = Counter()
    accepted_by_tier = Counter(item["properties"].get("tier") for item in settlement_features)
    accepted_by_biome_class = Counter()
    region_counter = Counter(regions)

    for item in settlement_features:
        biome = item["properties"].get("biome")
        biome_counter[biome] += 1
        biome_class = classify_biome_class(biome)
        biome_class_counter[biome_class] += 1
        accepted_by_biome_class[biome_class] += 1

    # Key coverage checks
    missing_accept_keys = sorted(
        key for key in EXPECTED_ACCEPTED_KEYS if any(key not in item["properties"] for item in settlement_features)
    )

    # Capital invariants (accepted only)
    accepted_by_civ: dict[Any, list[dict[str, Any]]] = defaultdict(list)
    for item in settlement_features:
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
    if missing_expected_regions:
        warnings.append(f"Missing expected regions: {missing_expected_regions}")
    if unexpected_regions:
        warnings.append(f"Unexpected regions in export: {unexpected_regions}")
    if not terrain_samples:
        if score_height_count == 0 or score_slope_count == 0:
            warnings.append("No terrain_sample features found; fallback terrain interpolation may be low fidelity.")
    if settlement_feature_count == 0:
        warnings.append("No settlement features found in export.")
    if too_close_pairs:
        # keep warning concise in top-line summary
        warnings.append(
            f"Found {len(too_close_pairs)} accepted non-capital pairs below configured satellite min distance "
            f"({configured_satellite_min})."
        )

    terrain_surface_values = [sample["surface_y"] for sample in terrain_samples]
    terrain_slope_values = [sample["slope_delta"] for sample in terrain_samples]

    analysis = {
        "source": str(path),
        "sha256": hashlib.sha256(raw.encode("utf-8")).hexdigest(),
        "feature_count": count,
        "raw_feature_count": raw_feature_count,
        "settlement_feature_count": settlement_feature_count,
        "accepted_count": settlement_count,
        "terrain_sample_count": len(terrain_samples),
        "terrain_surface_y_min": min(terrain_surface_values) if terrain_surface_values else None,
        "terrain_surface_y_max": max(terrain_surface_values) if terrain_surface_values else None,
        "terrain_slope_delta_min": min(terrain_slope_values) if terrain_slope_values else None,
        "terrain_slope_delta_max": max(terrain_slope_values) if terrain_slope_values else None,
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
        "region_counter": {f"{rx},{rz}": n for (rx, rz), n in region_counter.items()},
        "capitals_per_civ": capitals_per_civ,
        "capital_invariant_violations": capital_invariant_violations,
        "avg_nearest_within_civ": avg_nearest,
        "min_nearest_within_civ": min_nearest,
        "all_property_keys": sorted(all_keys),
        "score_height_count": score_height_count,
        "score_slope_count": score_slope_count,
        "missing_expected_accept_keys": missing_accept_keys,
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
    height_points: list[tuple[float, float, float]] = []
    slope_points: list[tuple[float, float, float]] = []
    terrain_samples: list[tuple[int, int, str, float, float]] = []
    terrain_biome_class_counter: Counter[str] = Counter()
    points_by_civ: dict[str, list[tuple[float, float]]] = defaultdict(list)
    for feature in features:
        props = feature["properties"]
        x, z = feature["geometry"]["coordinates"]
        feature_kind = str(props.get("feature_kind", "settlement")).strip().lower()
        if feature_kind == TERRAIN_SAMPLE_KIND:
            surface_y = to_optional_float(props.get("surface_y"))
            slope_delta = to_optional_float(props.get("slope_delta_blocks"))
            if surface_y is not None and slope_delta is not None:
                chunk_x_raw = props.get("chunk_x")
                chunk_z_raw = props.get("chunk_z")
                chunk_x = int(chunk_x_raw) if isinstance(chunk_x_raw, int) else int(math.floor(float(x) / CHUNK_BLOCK_SPAN))
                chunk_z = int(chunk_z_raw) if isinstance(chunk_z_raw, int) else int(math.floor(float(z) / CHUNK_BLOCK_SPAN))
                terrain_samples.append(
                    (
                        chunk_x,
                        chunk_z,
                        classify_biome_class(props.get("biome")),
                        float(surface_y),
                        max(0.0, float(slope_delta)),
                    )
                )
                terrain_biome_class_counter[classify_biome_class(props.get("biome"))] += 1
            continue

        accepted = bool(props.get("accepted", False))
        if not accepted:
            continue
        tier = props.get("tier")
        is_capital = bool(props.get("is_capital", False))
        settlement_type = str(props.get("type", "") or "").strip().upper()
        civ_id = str(props.get("civ_id"))
        biome = props.get("biome")
        biome_class = classify_biome_class(biome)
        score_height = to_optional_float(props.get("score_height"))
        score_slope = to_optional_float(props.get("score_slope"))
        points.append((x, z, tier, is_capital, settlement_type, civ_id, biome, biome_class, score_height, score_slope))
        if biome_class != "UNKNOWN":
            biome_points.append((float(x), float(z), biome_class))
        if score_height is not None:
            height_points.append((float(x), float(z), clamp01(score_height)))
        if score_slope is not None:
            slope_points.append((float(x), float(z), clamp01(score_slope)))
        if props.get("civ_id") is not None:
            points_by_civ[civ_id].append((x, z))

    coord_min_x = float(analysis["coord_bounds"]["min_x"])
    coord_max_x = float(analysis["coord_bounds"]["max_x"])
    coord_min_z = float(analysis["coord_bounds"]["min_z"])
    coord_max_z = float(analysis["coord_bounds"]["max_z"])
    displayed_biome_counter = terrain_biome_class_counter if terrain_biome_class_counter else Counter(biome_class_counter)

    W, H = 1500, 980
    MAP_X, MAP_Y, MAP_W, MAP_H = 40, 70, 900, 860
    PANEL_X, PANEL_W = 980, 480

    def esc(value: Any) -> str:
        return html.escape(str(value))

    terrain_source = "raw_chunk_samples" if terrain_samples else "interpolated_candidate_scores"
    if terrain_samples:
        (
            terrain_chunk_runs,
            terrain_chunk_count,
            terrain_surface_range,
            terrain_slope_range,
            terrain_hillshade_range,
        ) = build_chunk_terrain_runs_from_samples(terrain_samples)
        biome_overlay_runs = build_chunk_biome_runs_from_samples(terrain_samples)
    else:
        (
            terrain_chunk_runs,
            terrain_chunk_count,
            terrain_surface_range,
            terrain_slope_range,
            terrain_hillshade_range,
        ) = build_chunk_terrain_runs_interpolated(
            coord_min_x,
            coord_max_x,
            coord_min_z,
            coord_max_z,
            biome_points,
            height_points,
            slope_points,
        )
        biome_overlay_runs = build_chunk_biome_runs(coord_min_x, coord_max_x, coord_min_z, coord_max_z, biome_points)

    raster_layers = rasterize_chunk_layers(terrain_chunk_runs, biome_overlay_runs)

    # If raw chunk samples are present, anchor the plot bounds to the sampled chunk edges so
    # the raster fully fills the map panel and does not appear inset relative to the grid.
    plot_min_x = coord_min_x
    plot_max_x = coord_max_x
    plot_min_z = coord_min_z
    plot_max_z = coord_max_z
    if terrain_samples and raster_layers is not None:
        plot_min_x = float(raster_layers["min_block_x"])
        plot_max_x = float(raster_layers["max_block_x"])
        plot_min_z = float(raster_layers["min_block_z"])
        plot_max_z = float(raster_layers["max_block_z"])

    def sx(x: float) -> float:
        if plot_max_x == plot_min_x:
            return MAP_X + MAP_W / 2.0
        return MAP_X + ((x - plot_min_x) / (plot_max_x - plot_min_x)) * MAP_W

    def sy(z: float) -> float:
        if plot_max_z == plot_min_z:
            return MAP_Y + MAP_H / 2.0
        return MAP_Y + (1.0 - ((z - plot_min_z) / (plot_max_z - plot_min_z))) * MAP_H

    span_x = max(1.0, plot_max_x - plot_min_x)
    span_z = max(1.0, plot_max_z - plot_min_z)
    blocks_per_px_x = span_x / MAP_W
    blocks_per_px_z = span_z / MAP_H
    scale_blocks = pick_scale_bar_blocks(blocks_per_px_x)
    scale_px = scale_blocks / blocks_per_px_x
    scale_chunks = scale_blocks / 16.0

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
    append(
        f'<text x="{MAP_X + 12}" y="{MAP_Y + 24}" class="label">'
        "Spatial Layout + Chunk Topography/Biome Overlay (X/Z)</text>"
    )
    append(
        "<defs>"
        f'<clipPath id="map-plot-clip"><rect x="{MAP_X}" y="{MAP_Y}" width="{MAP_W}" height="{MAP_H}" rx="10"/></clipPath>'
        "</defs>"
    )

    for i in range(6):
        gx = MAP_X + i * MAP_W / 5.0
        gy = MAP_Y + i * MAP_H / 5.0
        append(f'<line x1="{gx:.1f}" y1="{MAP_Y}" x2="{gx:.1f}" y2="{MAP_Y + MAP_H}" stroke="#e6ecf5" stroke-width="1"/>')
        append(f'<line x1="{MAP_X}" y1="{gy:.1f}" x2="{MAP_X + MAP_W}" y2="{gy:.1f}" stroke="#e6ecf5" stroke-width="1"/>')
        tick_x = plot_min_x + (i / 5.0) * (plot_max_x - plot_min_x)
        tick_z = plot_max_z - (i / 5.0) * (plot_max_z - plot_min_z)
        append(f'<text x="{gx:.1f}" y="{MAP_Y + MAP_H + 16:.1f}" class="tiny" text-anchor="middle">{int(round(tick_x))}b</text>')
        append(
            f'<text x="{gx:.1f}" y="{MAP_Y + MAP_H + 30:.1f}" class="tiny" text-anchor="middle">'
            f'{int(round(tick_x / 16.0))}c</text>'
        )
        append(f'<text x="{MAP_X - 8:.1f}" y="{gy + 4:.1f}" class="tiny" text-anchor="end">{int(round(tick_z))}b</text>')

    if raster_layers is not None:
        raster_left = sx(raster_layers["min_block_x"])
        raster_right = sx(raster_layers["max_block_x"])
        raster_top = sy(raster_layers["max_block_z"])
        raster_bottom = sy(raster_layers["min_block_z"])
        raster_width = abs(raster_right - raster_left)
        raster_height = abs(raster_bottom - raster_top)
        if raster_width > 0.0 and raster_height > 0.0:
            append('<g clip-path="url(#map-plot-clip)">')
            append(
                f'<image x="{min(raster_left, raster_right):.2f}" y="{min(raster_top, raster_bottom):.2f}" '
                f'width="{raster_width:.2f}" height="{raster_height:.2f}" preserveAspectRatio="none" '
                'image-rendering="pixelated" '
                f'href="{raster_layers["terrain_png_data_uri"]}"/>'
            )
            append(
                f'<image x="{min(raster_left, raster_right):.2f}" y="{min(raster_top, raster_bottom):.2f}" '
                f'width="{raster_width:.2f}" height="{raster_height:.2f}" preserveAspectRatio="none" '
                'image-rendering="pixelated" '
                f'href="{raster_layers["biome_png_data_uri"]}"/>'
            )
            append("</g>")
    else:
        for x0, x1, z0, z1, height_bin, slope_bin, hillshade_bin in terrain_chunk_runs:
            left = sx(x0)
            right = sx(x1)
            top = sy(z1)
            bottom = sy(z0)
            width = abs(right - left)
            height = abs(bottom - top)
            if width < 0.5 or height < 0.5:
                continue
            color = terrain_fill_color(
                dequantize01(height_bin),
                dequantize01(slope_bin),
                dequantize01(hillshade_bin, HILLSHADE_QUANTIZATION_BINS),
            )
            append(
                f'<rect x="{min(left, right):.2f}" y="{min(top, bottom):.2f}" '
                f'width="{width:.2f}" height="{height:.2f}" fill="{color}" opacity="1.0"/>'
            )

        for x0, x1, z0, z1, biome_class in biome_overlay_runs:
            left = sx(x0)
            right = sx(x1)
            top = sy(z1)
            bottom = sy(z0)
            width = abs(right - left)
            height = abs(bottom - top)
            if width < 0.5 or height < 0.5:
                continue
            biome_color = BIOME_CLASS_PALETTE.get(biome_class, BIOME_CLASS_PALETTE["UNKNOWN"])
            append(
                f'<rect x="{min(left, right):.2f}" y="{min(top, bottom):.2f}" '
                f'width="{width:.2f}" height="{height:.2f}" fill="{biome_color}" '
                f'opacity="{biome_overlay_opacity(biome_class):.2f}"/>'
            )

    capital_diameter_blocks = CAPITAL_ICON_DIAMETER_CHUNKS * CHUNK_BLOCK_SPAN
    settlement_diameter_blocks = SETTLEMENT_ICON_DIAMETER_CHUNKS * CHUNK_BLOCK_SPAN

    capital_diameter_px_x, capital_diameter_px_z = block_diameter_to_screen(capital_diameter_blocks, blocks_per_px_x, blocks_per_px_z)
    settlement_diameter_px_x, settlement_diameter_px_z = block_diameter_to_screen(
        settlement_diameter_blocks, blocks_per_px_x, blocks_per_px_z
    )

    capital_rx = capital_diameter_px_x * 0.5
    capital_ry = capital_diameter_px_z * 0.5
    settlement_rx = settlement_diameter_px_x * 0.5
    settlement_ry = settlement_diameter_px_z * 0.5

    def draw_settlement_icon(
        center_x: float,
        center_y: float,
        type_key: str,
        fill_color: str,
        stroke_color: str,
        rx: float,
        ry: float,
        stroke_width: float,
    ) -> None:
        if type_key == "KINGDOM_CAPITAL":
            append(
                f'<polygon points="{star_points(center_x, center_y, rx, ry)}" '
                f'fill="{fill_color}" stroke="{stroke_color}" stroke-width="{stroke_width:.1f}"/>'
            )
            return
        if type_key == "KINGDOM_TOWN":
            append(
                f'<ellipse cx="{center_x:.1f}" cy="{center_y:.1f}" rx="{rx:.1f}" ry="{ry:.1f}" '
                f'fill="{fill_color}" stroke="{stroke_color}" stroke-width="{stroke_width:.1f}"/>'
            )
            return
        if type_key == "OUTPOST":
            append(
                f'<rect x="{center_x - rx:.1f}" y="{center_y - ry:.1f}" '
                f'width="{rx * 2.0:.1f}" height="{ry * 2.0:.1f}" '
                f'fill="{fill_color}" stroke="{stroke_color}" stroke-width="{stroke_width:.1f}"/>'
            )
            # Crosshair helps distinguish OUTPOST from other filled symbols at large scales.
            append(
                f'<line x1="{center_x - (rx * 0.55):.1f}" y1="{center_y:.1f}" '
                f'x2="{center_x + (rx * 0.55):.1f}" y2="{center_y:.1f}" '
                f'stroke="{stroke_color}" stroke-width="{max(1.0, stroke_width * 0.75):.1f}"/>'
            )
            append(
                f'<line x1="{center_x:.1f}" y1="{center_y - (ry * 0.55):.1f}" '
                f'x2="{center_x:.1f}" y2="{center_y + (ry * 0.55):.1f}" '
                f'stroke="{stroke_color}" stroke-width="{max(1.0, stroke_width * 0.75):.1f}"/>'
            )
            return
        if type_key == "TRIBE":
            append(
                f'<polygon points="{regular_polygon_points(center_x, center_y, rx, ry, 3, -math.pi / 2.0)}" '
                f'fill="{fill_color}" stroke="{stroke_color}" stroke-width="{stroke_width:.1f}"/>'
            )
            return

        append(
            f'<rect x="{center_x - rx:.1f}" y="{center_y - ry:.1f}" '
            f'width="{rx * 2.0:.1f}" height="{ry * 2.0:.1f}" '
            f'fill="{fill_color}" stroke="{stroke_color}" stroke-width="{stroke_width:.1f}"/>'
        )

    off_map_settlement_count = 0
    append('<g clip-path="url(#map-plot-clip)">')
    for x, z, tier, is_capital, settlement_type, civ_id, biome, biome_class, _score_height, _score_slope in points:
        px, py = sx(float(x)), sy(float(z))
        if px < MAP_X or px > (MAP_X + MAP_W) or py < MAP_Y or py > (MAP_Y + MAP_H):
            off_map_settlement_count += 1
            continue
        color = PALETTE.get(tier, "#BBBBBB")
        stroke_color = shade_hex(color, 0.6)
        type_key = settlement_type or ("KINGDOM_CAPITAL" if is_capital else "")
        icon_rx, icon_ry = (capital_rx, capital_ry) if type_key == "KINGDOM_CAPITAL" else (settlement_rx, settlement_ry)
        draw_settlement_icon(px, py, type_key, color, stroke_color, icon_rx, icon_ry, 2.0)

    for civ_id, civ_points in points_by_civ.items():
        cx = sum(x for x, _ in civ_points) / len(civ_points)
        cz = sum(z for _, z in civ_points) / len(civ_points)
        cpx = sx(cx)
        cpy = sy(cz)
        if cpx < MAP_X or cpx > (MAP_X + MAP_W) or cpy < MAP_Y or cpy > (MAP_Y + MAP_H):
            continue
        append(f'<text x="{cpx:.1f}" y="{cpy - 8:.1f}" class="tiny" text-anchor="middle">{esc(civ_id)}</text>')
    append("</g>")

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

    icon_legend_x = MAP_X + MAP_W - 210
    icon_legend_y = MAP_Y + MAP_H - 206
    append(f'<rect x="{icon_legend_x}" y="{icon_legend_y}" width="190" height="176" rx="8" fill="#ffffff" stroke="#d4dcea"/>')
    append(f'<text x="{icon_legend_x + 10}" y="{icon_legend_y + 18}" class="small">Settlement Icons</text>')
    icon_rows = [
        ("KINGDOM_CAPITAL", "Capital", int(type_counter.get("KINGDOM_CAPITAL", 0)), PALETTE["STONE"]),
        ("KINGDOM_TOWN", "Town", int(type_counter.get("KINGDOM_TOWN", 0)), PALETTE["WOOD"]),
        ("OUTPOST", "Outpost", int(type_counter.get("OUTPOST", 0)), PALETTE["IRON"]),
        ("TRIBE", "Tribe", int(type_counter.get("TRIBE", 0)), PALETTE["DIAMOND"]),
    ]
    for idx, (type_key, label, count, fill_color) in enumerate(icon_rows):
        row_y = icon_legend_y + 38 + (idx * 30)
        icon_cx = icon_legend_x + 18
        icon_cy = row_y - 4
        rx = 7.0 if type_key == "KINGDOM_CAPITAL" else 6.0
        ry = 7.0 if type_key == "KINGDOM_CAPITAL" else 6.0
        draw_settlement_icon(icon_cx, icon_cy, type_key, fill_color, "#334155", rx, ry, 1.3)
        append(f'<text x="{icon_legend_x + 34}" y="{row_y}" class="tiny">{esc(label)} ({count})</text>')

    legend_x = MAP_X + MAP_W - 210
    legend_y = MAP_Y + 44
    append(f'<rect x="{legend_x}" y="{legend_y}" width="190" height="220" rx="8" fill="#ffffff" stroke="#d4dcea"/>')
    append(f'<text x="{legend_x + 10}" y="{legend_y + 18}" class="small">Biome Classes</text>')
    for i, biome_class in enumerate(BIOME_CLASS_ORDER):
        y = legend_y + 38 + (i * 22)
        fill = BIOME_CLASS_PALETTE[biome_class]
        count = int(displayed_biome_counter.get(biome_class, 0))
        append(f'<rect x="{legend_x + 10}" y="{y - 10}" width="12" height="12" rx="2" fill="{fill}"/>')
        append(f'<text x="{legend_x + 28}" y="{y}" class="tiny">{esc(biome_class)} ({count})</text>')

    terrain_legend_y = legend_y + 226
    append(f'<rect x="{legend_x}" y="{terrain_legend_y}" width="190" height="132" rx="8" fill="#ffffff" stroke="#d4dcea"/>')
    append(f'<text x="{legend_x + 10}" y="{terrain_legend_y + 18}" class="small">Terrain Shading (Grayscale)</text>')
    append(f'<text x="{legend_x + 10}" y="{terrain_legend_y + 34}" class="tiny">Elevation (low -> high)</text>')
    terrain_bar_width = 160.0
    elevation_swatch_w = terrain_bar_width / float(TERRAIN_QUANTIZATION_BINS)
    for i in range(TERRAIN_QUANTIZATION_BINS):
        h = dequantize01(i)
        swatch = terrain_fill_color(h, 0.85, 0.55)
        append(
            f'<rect x="{legend_x + 10 + (i * elevation_swatch_w):.2f}" y="{terrain_legend_y + 40}" '
            f'width="{elevation_swatch_w + 0.35:.2f}" height="12" fill="{swatch}"/>'
        )
    append(f'<text x="{legend_x + 10}" y="{terrain_legend_y + 64}" class="tiny">Slope light/shadow (hill -> valley)</text>')
    hillshade_swatch_w = terrain_bar_width / float(HILLSHADE_QUANTIZATION_BINS)
    for i in range(HILLSHADE_QUANTIZATION_BINS):
        hillshade = dequantize01(i, HILLSHADE_QUANTIZATION_BINS)
        swatch = terrain_fill_color(0.65, 0.45, hillshade)
        append(
            f'<rect x="{legend_x + 10 + (i * hillshade_swatch_w):.2f}" y="{terrain_legend_y + 70}" '
            f'width="{hillshade_swatch_w + 0.35:.2f}" height="12" fill="{swatch}"/>'
        )
    append(
        f'<text x="{legend_x + 10}" y="{terrain_legend_y + 96}" class="tiny">'
        f"Cells: {terrain_chunk_count} | source: {terrain_source}</text>"
    )
    append(
        f'<text x="{legend_x + 10}" y="{terrain_legend_y + 112}" class="tiny">'
        f"Y range: {terrain_surface_range[0]:.1f}..{terrain_surface_range[1]:.1f} | "
        f"Slope delta: {terrain_slope_range[0]:.1f}..{terrain_slope_range[1]:.1f}</text>"
    )

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
    biome_labels = [label for label in BIOME_CLASS_ORDER if int(displayed_biome_counter.get(label, 0)) > 0]
    biome_values = [int(displayed_biome_counter.get(label, 0)) for label in biome_labels]
    biome_colors = [BIOME_CLASS_PALETTE[label] for label in biome_labels]
    draw_bars(PANEL_X, 430, PANEL_W, 170, biome_labels, biome_values, biome_colors)

    panel(PANEL_X, 620, PANEL_W, 130, "Anchors")
    draw_bars(
        PANEL_X,
        620,
        PANEL_W,
        130,
        ["Placed"],
        [int(analysis["accepted_count"])],
        ["#2E8B57"],
    )

    panel(PANEL_X, 770, PANEL_W, 190, "Audit Notes")
    warnings = analysis.get("warnings", [])
    warning_line = warnings[0] if warnings else "No structural warnings."
    raster_chunk_width = int(raster_layers["chunk_width"]) if raster_layers is not None else 0
    raster_chunk_height = int(raster_layers["chunk_height"]) if raster_layers is not None else 0
    oceanic = int(displayed_biome_counter.get("OCEANIC", 0))
    coastal = int(displayed_biome_counter.get("COASTAL", 0))
    total = sum(int(v) for v in displayed_biome_counter.values()) or 1
    sea_ratio = ((oceanic + coastal) / total) * 100.0
    notes = [
        (
            f"Settlement features: {analysis['feature_count']} | Terrain samples: {analysis.get('terrain_sample_count', 0)}"
        ),
        f"Unique civ_id: {analysis['unique_civ_count']} | Non-null id unique: {analysis['unique_non_null_ids']}",
        f"Regions: {', '.join(sorted(analysis['region_counter'].keys())[:6])}"
        + (" ..." if len(analysis["region_counter"]) > 6 else ""),
        (
            (
                "Topographic overlay is rasterized (embedded PNG) at 1 chunk/pixel "
                f"({raster_chunk_width}x{raster_chunk_height}) with grayscale hillshade + biome tint."
                if raster_layers is not None
                else "Topographic overlay uses vector chunk cells with grayscale hillshade + biome tint."
            )
            if analysis.get("terrain_sample_count", 0) > 0
            else (
                "Topographic fallback: grayscale hillshade + biome tint from interpolated candidate fields "
                f"(height/slope samples: {analysis.get('score_height_count', 0)}/{analysis.get('score_slope_count', 0)})."
            )
        ),
        (
            "Biome class stats source: chunk terrain samples"
            if analysis.get("terrain_sample_count", 0) > 0
            else "Biome class stats source: settlement points"
        ),
        (
            f"Terrain Y range: {analysis.get('terrain_surface_y_min'):.1f}..{analysis.get('terrain_surface_y_max'):.1f} | "
            f"Slope delta range: {analysis.get('terrain_slope_delta_min'):.1f}..{analysis.get('terrain_slope_delta_max'):.1f} | "
            f"Hillshade: {terrain_hillshade_range[0]:.2f}..{terrain_hillshade_range[1]:.2f}"
            if analysis.get("terrain_surface_y_min") is not None and analysis.get("terrain_slope_delta_min") is not None
            else "Terrain ranges: n/a"
        ),
        f"Plot bounds X[{plot_min_x:.1f},{plot_max_x:.1f}] Z[{plot_min_z:.1f},{plot_max_z:.1f}]",
        f"Settlement bounds X[{coord_min_x:.1f},{coord_max_x:.1f}] Z[{coord_min_z:.1f},{coord_max_z:.1f}]",
        (
            f"Settlements outside plotted terrain bounds (clipped): {off_map_settlement_count}"
            if off_map_settlement_count > 0
            else "Settlements outside plotted terrain bounds (clipped): 0"
        ),
        f"Scale: ~{blocks_per_px_x:.2f} blocks/px X, ~{blocks_per_px_z:.2f} blocks/px Z",
        (
            f"Icon diameters: capital {CAPITAL_ICON_DIAMETER_CHUNKS:.0f}c/{int(capital_diameter_blocks)}b, "
            f"settlement {SETTLEMENT_ICON_DIAMETER_CHUNKS:.0f}c/{int(settlement_diameter_blocks)}b"
        ),
        "Type icons: KINGDOM_CAPITAL=star, KINGDOM_TOWN=circle, OUTPOST=square+crosshair, TRIBE=triangle",
        "Icon color is tier-based (not biome-based).",
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
        append(f'<text x="{PANEL_X + 14}" y="{794 + i * 14}" class="tiny">{esc(line)}</text>')

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
