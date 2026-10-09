#!/usr/bin/env python3
"""Resolve uv's exported environment markers for Linux x86_64 / CPython 3.10."""
import argparse
from pathlib import Path

from packaging.markers import default_environment
from packaging.requirements import Requirement

parser = argparse.ArgumentParser()
parser.add_argument("source", type=Path)
parser.add_argument("destination", type=Path)
args = parser.parse_args()
environment = default_environment()
environment.update(sys_platform="linux", platform_system="Linux", platform_machine="x86_64",
                   os_name="posix", python_version="3.10", python_full_version="3.10.21",
                   implementation_name="cpython", platform_python_implementation="CPython")
blocks = []
for line in args.source.read_text().splitlines():
    if line and not line.startswith((" ", "#")):
        blocks.append([line])
    elif blocks:
        blocks[-1].append(line)
output = ["# Exported from uv.lock without development dependencies.",
          "# Environment markers resolved for Linux x86_64 / CPython 3.10."]
for block in blocks:
    first = block[0].rstrip(" \\")
    requirement = Requirement(first)
    if requirement.marker and not requirement.marker.evaluate(environment):
        continue
    if requirement.url or len(requirement.specifier) != 1:
        raise SystemExit("Only exactly pinned registry packages may be exported")
    specifier = next(iter(requirement.specifier))
    if specifier.operator != "==" or not any("--hash=sha256:" in line for line in block):
        raise SystemExit("Missing version pin or SHA-256")
    block[0] = requirement.name + str(requirement.specifier) + " \\"
    output.extend(block)
args.destination.write_text("\n".join(output) + "\n")
