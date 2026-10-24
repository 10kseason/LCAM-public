"""Reject device identity collection and identifying literals before public source export.

Run from any directory: python tools/check_public_privacy.py [repository-root]
This checks source files, not Git history, released binaries, or photo metadata.
"""

from pathlib import Path
import re
import sys


def violations(path: Path, text: str) -> list[tuple[int, str]]:
    checks = [
        (r"\bSM-[A-Z][0-9]{3}[A-Z0-9]*\b", "hardware model code"),
        (r"\b(?:pa[0-9]q[a-z]*|csc_pref_\w+|ro\.build\.fingerprint)\b", "device or firmware identifier"),
    ]
    if path.suffix in {".kt", ".java"}:
        checks.append((r"\bBuild\s*\.\s*(?:MODEL|DEVICE|PRODUCT|FINGERPRINT|DISPLAY|SERIAL|MANUFACTURER|HARDWARE|BOARD|ID)\b", "device identity read"))
    if path.suffix == ".md":
        checks.extend([
            (r"\b(?:cacheKeyHash|buildFingerprintHash)\s+[0-9a-f]{16,64}\b", "derived firmware identifier"),
            (r"\|\s*펌웨어 지문 해시\s*\|\s*`[0-9a-f]+`", "firmware hash literal"),
            (r"\bSDK\s+[0-9]+\s+기기|\|\s*Android SDK\s*\|\s*`[0-9]+`", "recorded test-device SDK"),
        ])
    return [(number, label) for number, line in enumerate(text.splitlines(), 1)
            for pattern, label in checks if re.search(pattern, line)]


def main() -> int:
    root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[1]
    excluded = {".git", ".gradle", ".kotlin", "build", ".cxx", "output", "tmp", "__pycache__"}
    count = 0
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root)
        if set(relative.parts) & excluded or path.suffix not in {".kt", ".java", ".md"}:
            continue
        for line, label in violations(path, path.read_text(encoding="utf-8")):
            # Do not echo identifying values into CI logs.
            print(f"{relative.as_posix()}:{line}: {label}")
            count += 1
    print(f"Public privacy check: {count} violation(s)")
    return 1 if count else 0


if __name__ == "__main__":
    raise SystemExit(main())
