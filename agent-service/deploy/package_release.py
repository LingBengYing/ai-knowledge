#!/usr/bin/env python3
"""Create a source-only Agent release archive without local environments or data."""
import argparse
import gzip
import hashlib
import io
from pathlib import Path
import tarfile

parser = argparse.ArgumentParser()
parser.add_argument("output", type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
files = [root / name for name in ("pyproject.toml", "uv.lock", "README.md", "UPSTREAM.md", "run.sh")]
files.extend(root.glob("knowledge_agent/*.py"))
files.extend(path for path in (root / "deploy").iterdir() if path.is_file())
files = sorted(files)
manifest = []
for path in files:
    if path.is_symlink():
        raise SystemExit("symlink not allowed in release")
    manifest.append(hashlib.sha256(path.read_bytes()).hexdigest() + "  " + str(path.relative_to(root)))
args.output.parent.mkdir(parents=True, exist_ok=True)
with args.output.open("xb") as raw:
    with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode="w") as archive:
            for path in files:
                content = path.read_bytes()
                info = tarfile.TarInfo(str(path.relative_to(root)))
                info.size = len(content)
                info.mode = 0o755 if path.suffix == ".sh" else 0o644
                archive.addfile(info, io.BytesIO(content))
            content = ("\n".join(manifest) + "\n").encode()
            info = tarfile.TarInfo("SHA256SUMS")
            info.size = len(content)
            info.mode = 0o644
            archive.addfile(info, io.BytesIO(content))
print(hashlib.sha256(args.output.read_bytes()).hexdigest(), args.output)
