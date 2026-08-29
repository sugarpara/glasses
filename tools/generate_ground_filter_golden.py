#!/usr/bin/env python3
"""Generate Android-only ground-filter parity fixtures from the Python reference."""

from __future__ import annotations

import argparse
import importlib
import struct
import sys
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np


MAGIC = b"GFF1"
VERSION = 1
GRID_SIZE = 64
HEADER = struct.Struct("<4siii4fiiiii")


@dataclass(frozen=True)
class FixtureSpec:
    name: str
    depth: np.ndarray
    sample_step: int


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--reference-dir",
        type=Path,
        required=True,
        help="Directory containing mle_ground_filter.py",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=(
            Path(__file__).resolve().parents[1]
            / "app"
            / "src"
            / "androidTest"
            / "assets"
            / "ground_filter_golden"
        ),
    )
    return parser.parse_args()


def make_ground_depth(height: int, width: int) -> np.ndarray:
    y, x = np.mgrid[0:height, 0:width]
    x_normalized = 2.0 * x / max(width - 1, 1) - 1.0
    y_normalized = y / max(height - 1, 1)
    inverse_depth = 0.035 * x_normalized + 0.72 * y_normalized + 0.24
    return (1.0 / inverse_depth).astype(np.float32)


def bottom_connected_without_fallback(mask: np.ndarray, seed_fraction: float) -> np.ndarray:
    if not mask.any():
        return np.zeros(mask.shape, dtype=bool)
    count, labels = cv2.connectedComponents(mask.astype(np.uint8), connectivity=8)
    if count <= 1:
        return np.zeros(mask.shape, dtype=bool)
    seed_height = max(1, round(mask.shape[0] * seed_fraction))
    seed_labels = np.unique(labels[-seed_height:][mask[-seed_height:]])
    seed_labels = seed_labels[seed_labels > 0]
    if seed_labels.size == 0:
        return np.zeros(mask.shape, dtype=bool)
    return np.isin(labels, seed_labels)


def classify_full_frame(
    reference,
    depth: np.ndarray,
    config,
    classification_roi_top: float,
) -> tuple[np.ndarray, object | None]:
    model = reference.fit_ground_plane(depth, config)
    height, width = depth.shape
    valid_positive = np.isfinite(depth) & (depth > 0.0)
    classes = np.zeros(depth.shape, dtype=np.uint8)
    classes[valid_positive] = 3
    if model is None:
        return classes, None

    y, x = np.mgrid[0:height, 0:width]
    x_normalized = 2.0 * x.astype(np.float64) / max(width - 1, 1) - 1.0
    y_normalized = y.astype(np.float64) / max(height - 1, 1)
    classification_start = min(
        height - 1, max(0, round(height * classification_roi_top))
    )
    fit_start = min(height - 1, max(0, round(height * config.roi_top)))
    analysis_roi = y >= classification_start
    plane_valid = (
        np.isfinite(depth)
        & (depth >= config.min_depth)
        & (depth <= config.fit_max_depth)
        & analysis_roi
    )
    predicted = (
        model.x_coefficient * x_normalized
        + model.y_coefficient * y_normalized
        + model.intercept
    )
    residual = np.zeros(depth.shape, dtype=np.float64)
    residual[plane_valid] = 1.0 / depth[plane_valid].astype(np.float64) - predicted[
        plane_valid
    ]
    ground_density = reference._normal_density(residual, model.sigma)
    numerator = model.ground_prior * ground_density
    denominator = numerator + (1.0 - model.ground_prior) * model.outlier_density
    posterior = numerator / np.maximum(denominator, 1e-12)
    ground_candidate = (
        plane_valid
        & (posterior >= config.posterior_threshold)
        & (np.abs(residual) <= config.sigma_multiplier * model.sigma)
    )
    if config.morphology_kernel > 1:
        kernel = cv2.getStructuringElement(
            cv2.MORPH_ELLIPSE,
            (config.morphology_kernel, config.morphology_kernel),
        )
        ground_candidate = cv2.morphologyEx(
            ground_candidate.astype(np.uint8), cv2.MORPH_OPEN, kernel
        ).astype(bool)
    ground = bottom_connected_without_fallback(
        ground_candidate & plane_valid, config.bottom_seed_fraction
    )

    valid_near = valid_positive & (depth <= config.obstacle_max_depth)
    upper_non_ground = y < fit_start
    closer_lower = (depth < config.min_depth) | (
        plane_valid & (residual > config.sigma_multiplier * model.sigma)
    )
    obstacle = (
        valid_near
        & analysis_roi
        & ~ground
        & (upper_non_ground | closer_lower)
    )
    classes[ground] = 1
    classes[obstacle] = 2
    return classes, model


def occupancy_from_classes(classes: np.ndarray) -> np.ndarray:
    height, width = classes.shape
    occupancy = np.zeros((GRID_SIZE, GRID_SIZE), dtype=np.float32)
    for grid_row in range(GRID_SIZE):
        top = grid_row * height // GRID_SIZE
        bottom = (grid_row + 1) * height // GRID_SIZE
        for grid_column in range(GRID_SIZE):
            left = grid_column * width // GRID_SIZE
            right = (grid_column + 1) * width // GRID_SIZE
            cell = classes[top:bottom, left:right]
            occupancy[grid_row, grid_column] = (
                0.0 if cell.size == 0 else np.mean(cell == 2, dtype=np.float64)
            )
    return occupancy


def build_specs() -> list[FixtureSpec]:
    clean = make_ground_depth(120, 160)

    near_obstacle = clean.copy()
    near_obstacle[52:112, 62:98] = 0.85

    top_obstacle = clean.copy()
    top_obstacle[8:28, 55:105] = 0.8

    invalid_depth = clean.copy()
    invalid_depth[:24, :52] = np.nan
    invalid_depth[72:100, 20:48] = 0.0

    competing_planes = clean.copy()
    inverse_depth = 1.0 / competing_planes
    inverse_depth[:, 80:] += 0.04
    competing_planes = (1.0 / inverse_depth).astype(np.float32)

    fit_failure = np.full((80, 100), np.nan, dtype=np.float32)
    fit_failure[60:80, 35:65] = 2.0

    narrow_obstacle = make_ground_depth(640, 640)
    narrow_obstacle[270:274, 318:322] = 0.8

    return [
        FixtureSpec("clean_ground_120x160", clean, 2),
        FixtureSpec("near_obstacle_120x160", near_obstacle, 2),
        FixtureSpec("top_obstacle_120x160", top_obstacle, 2),
        FixtureSpec("invalid_depth_120x160", invalid_depth, 2),
        FixtureSpec("competing_planes_120x160", competing_planes, 2),
        FixtureSpec("fit_failure_80x100", fit_failure, 2),
        FixtureSpec("narrow_obstacle_640x640", narrow_obstacle, 4),
    ]


def write_fixture(path: Path, spec: FixtureSpec, reference) -> None:
    fit_roi_top = 0.45
    classification_roi_top = 0.0
    obstacle_max_depth = 5.0
    fit_max_depth = 30.0
    max_iterations = 20
    config = reference.MLEGroundConfig(
        roi_top=fit_roi_top,
        sample_step=spec.sample_step,
        max_iterations=max_iterations,
        obstacle_max_depth=obstacle_max_depth,
        fit_max_depth=fit_max_depth,
    )
    classes, model = classify_full_frame(
        reference, spec.depth, config, classification_roi_top
    )
    occupancy = occupancy_from_classes(classes)
    height, width = spec.depth.shape
    pixel_count = width * height
    header = HEADER.pack(
        MAGIC,
        VERSION,
        width,
        height,
        fit_roi_top,
        classification_roi_top,
        obstacle_max_depth,
        fit_max_depth,
        spec.sample_step,
        max_iterations,
        int(model is not None),
        pixel_count,
        GRID_SIZE * GRID_SIZE,
    )
    payload = b"".join(
        (
            header,
            np.asarray(spec.depth, dtype="<f4").tobytes(order="C"),
            np.asarray(classes, dtype=np.uint8).tobytes(order="C"),
            np.asarray(occupancy, dtype="<f4").tobytes(order="C"),
        )
    )
    path.write_bytes(payload)


def main() -> None:
    args = parse_args()
    sys.path.insert(0, str(args.reference_dir.resolve()))
    reference = importlib.import_module("mle_ground_filter")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for spec in build_specs():
        path = args.output_dir / f"{spec.name}.gff"
        write_fixture(path, spec, reference)
        print(path.name)


if __name__ == "__main__":
    main()
