#!/usr/bin/env python3
"""Populate test bucket directories from on_device_test and plans/samples.

The directory name IS the ground truth:
  confident_N  → must detect gesture with score N, uncertain=false
  uncertain_N  → may detect or filter, gesture score N
  rejected     → must NOT detect any gesture
  confident_rejected → must detect (real hand on rejected image)

Usage:
    cd <project-root>
    python3 ml/litert/scripts/test/copy_images.py
"""
import shutil
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parent.parent.parent.parent
ON_DEVICE = PROJECT_ROOT / "on_device_test" / "images"
PLANS = PROJECT_ROOT / "plans" / "samples"

# Mapping: bucket_dir_name -> list of (source_dir, filename)
# rejected images go to rejected/ only (no duplicates in score buckets).
BUCKETS = {
    "confident_5": [
        ("confident_kept", "26_PXL_20260705_093636185.MP.jpg"),
        ("confident_kept", "56_PXL_20260610_081311796.MP.jpg"),
        ("confident_kept", "200_PXL_20260318_210354731.jpg"),
        ("confident_kept", "228_PXL_20260303_214023670.jpg"),
        ("confident_kept", "3448_PXL_20260715_062107395.MP.jpg"),
        ("confident_kept", "3469_PXL_20260719_051625406.MP.jpg"),
        ("confident_kept", "3530_PXL_20260801_162513858.MP.jpg"),
        ("confident_kept", "3531_PXL_20260801_162522271.MP.jpg"),
        ("confident_kept", "3553_PXL_20260807_195842904.MP.jpg"),
        ("confident_kept", "3574_PXL_20260813_161951898.MP.jpg"),
        ("confident_kept", "3589_PXL_20260817_042827887.MP.jpg"),
        ("confident_kept", "3598_PXL_20260818_044053486.MP.jpg"),
        ("5", "5_alesto.jpg"),
        ("5", "5_also.jpg"),
        ("5", "5_buco.jpg"),
        ("5", "5_dalmayr.jpg"),
        ("5", "5_dishwasher.jpg"),
        ("5", "5_gold.jpg"),
        ("5", "5_horizontal.jpg"),
        ("5", "5_kenya.jpg"),
        ("5", "5_kimbo.jpg"),
        ("5", "5_oats.jpg"),
        ("5", "5_rock.jpg"),
        ("5", "5_rock_alternative.jpg"),
        ("5", "5_trope.jpg"),
        ("5", "5_vertical.jpg"),
    ],
    "confident_3": [
        ("confident_kept", "49_PXL_20260612_161135227.MP.jpg"),
        ("confident_kept", "3532_PXL_20260801_162530553.MP.jpg"),
    ],
    "confident_rejected": [
        ("uncertain_rejected", "131_PXL_20260422_063349065.MP.jpg"),
    ],
    "uncertain_5": [
        ("uncertain_kept", "130_PXL_20260422_063421556.MP.jpg"),
        ("uncertain_kept", "189_PXL_20260320_194703490.MP.jpg"),
        ("uncertain_kept", "190_PXL_20260320_194701793.MP.jpg"),
        ("uncertain_kept", "212_PXL_20260313_073332957.MACRO_FOCUS.MP.jpg"),
        ("uncertain_kept", "249_PXL_20260205_190114836.MP.jpg"),
        ("uncertain_kept", "3533_PXL_20260801_162552608.MP.jpg"),
        ("user_corrections", "42_PXL_20260621_161610440.MP.jpg"),
    ],
    "uncertain_4": [
        ("uncertain_kept", "193_PXL_20260320_194600715.MACRO_FOCUS.jpg"),
        ("uncertain_rejected", "22_PXL_20260708_091646664.MP.jpg"),
        ("uncertain_rejected", "33_Screenshot_20260629-165135.png"),
        ("uncertain_rejected", "46_PXL_20260616_083802194.MP.jpg"),
        ("uncertain_rejected", "83_PXL_20260519_064442494.MP.jpg"),
        ("uncertain_rejected", "118_PXL_20260427_133559312.MP.jpg"),
        ("uncertain_rejected", "119_PXL_20260427_133551024.MP.jpg"),
        ("uncertain_rejected", "174_PXL_20260403_172040241.MP.jpg"),
        ("uncertain_rejected", "175_PXL_20260403_172036906.MP.jpg"),
        ("uncertain_rejected", "267_PXL_20260129_201511819.jpg"),
        ("uncertain_rejected", "271_PXL_20260126_193942415.jpg"),
        ("uncertain_rejected", "278_PXL_20260125_104716253.jpg"),
        ("uncertain_rejected", "279_PXL_20260125_104707110.jpg"),
    ],
    "uncertain_3": [
        ("uncertain_kept", "140_PXL_20260416_093534863.MP.jpg"),
        ("uncertain_kept", "181_PXL_20260329_171427874.MP.jpg"),
        ("uncertain_kept", "248_PXL_20260205_190128836.jpg"),
        ("uncertain_rejected", "215_PXL_20260309_214133565.jpg"),
        ("user_corrections", "148_PXL_20260412_073429860.MP.jpg"),
    ],
    "uncertain_2": [
        ("uncertain_kept", "146_PXL_20260412_163943804.MP.jpg"),
        ("uncertain_rejected", "3346_PXL_20260711_095950542.MP.jpg"),
        ("user_corrections", "147_PXL_20260412_163936013.MP.jpg"),
    ],
    "uncertain_1": [
        ("uncertain_rejected", "61_PXL_20260607_194543390.MP.jpg"),
        ("uncertain_rejected", "198_PXL_20260318_210529942.MP.jpg"),
        ("uncertain_rejected", "34_Screenshot_20260629-165105.png"),
    ],
    "rejected": [
        ("uncertain_rejected", "22_PXL_20260708_091646664.MP.jpg"),
        ("uncertain_rejected", "33_Screenshot_20260629-165135.png"),
        ("uncertain_rejected", "34_Screenshot_20260629-165105.png"),
        ("uncertain_rejected", "46_PXL_20260616_083802194.MP.jpg"),
        ("uncertain_rejected", "61_PXL_20260607_194543390.MP.jpg"),
        ("uncertain_rejected", "83_PXL_20260519_064442494.MP.jpg"),
        ("uncertain_rejected", "91_PXL_20260515_065723802.MP.jpg"),
        ("uncertain_rejected", "118_PXL_20260427_133559312.MP.jpg"),
        ("uncertain_rejected", "119_PXL_20260427_133551024.MP.jpg"),
        ("uncertain_rejected", "174_PXL_20260403_172040241.MP.jpg"),
        ("uncertain_rejected", "175_PXL_20260403_172036906.MP.jpg"),
        ("uncertain_rejected", "198_PXL_20260318_210529942.MP.jpg"),
        ("uncertain_rejected", "215_PXL_20260309_214133565.jpg"),
        ("uncertain_rejected", "267_PXL_20260129_201511819.jpg"),
        ("uncertain_rejected", "271_PXL_20260126_193942415.jpg"),
        ("uncertain_rejected", "278_PXL_20260125_104716253.jpg"),
        ("uncertain_rejected", "279_PXL_20260125_104707110.jpg"),
        ("uncertain_rejected", "3346_PXL_20260711_095950542.MP.jpg"),
        ("uncertain_rejected", "3452_PXL_20260715_192001005.jpg"),
        ("uncertain_rejected", "61_PXL_20260607_194543390.MP.jpg"),
        ("uncertain_rejected", "91_PXL_20260515_065723802.MP.jpg"),
        ("uncertain_rejected", "131_PXL_20260422_063349065.MP.jpg"),
        ("no_score", "no_score_hands.jpg"),
        ("no_score", "no_score_holding_in_hand.jpg"),
        ("no_score", "no_score_holding_in_two_hands.jpg"),
        ("no_score", "no_score_holding_on_folded_hands.jpg"),
        ("no_score", "no_score_holding_paper.jpg"),
        ("no_score", "no_score_holding_spray_bottle.jpg"),
        ("no_score", "no_score_no_hands.jpg"),
    ],
}


def find_image(source_dir: str, filename: str) -> Path | None:
    """Find an image in on_device_test or plans/samples."""
    # Try on_device_test subdirectories
    for bucket in ["confident_kept", "uncertain_kept", "uncertain_rejected", "user_corrections"]:
        path = ON_DEVICE / bucket / filename
        if path.exists():
            return path
    # Try plans/samples score directories
    path = PLANS / source_dir / filename
    if path.exists():
        return path
    return None


def main():
    copied = 0
    missing = 0

    for bucket_name, entries in BUCKETS.items():
        dest_dir = SCRIPT_DIR / "images" / bucket_name
        dest_dir.mkdir(parents=True, exist_ok=True)

        seen = set()
        for source_dir, filename in entries:
            if filename in seen:
                continue
            seen.add(filename)

            src = find_image(source_dir, filename)
            if src is None:
                print(f"  MISSING: {filename} (bucket: {bucket_name})")
                missing += 1
                continue

            dest = dest_dir / filename
            if not dest.exists():
                shutil.copy2(src, dest)
                copied += 1

    print(f"\nDone: {copied} copied, {missing} missing")
    return 0 if missing == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
