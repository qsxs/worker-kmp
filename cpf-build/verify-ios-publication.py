#!/usr/bin/env python3
"""核对 iOS Maven 发布文件并记录原始字节摘要。"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile

GROUP = "com.lihb.forks.worker"
VERSION = "4.0.8-cpf.3-ios.1"
MODULES = {
    "worker-kmp": "jar",
    "worker-kmp-jvm": "jar",
    "worker-kmp-iosarm64": "klib",
    "worker-kmp-iossimulatorarm64": "klib",
    "worker-ios": "jar",
    "worker-ios-iosarm64": "klib",
    "worker-ios-iossimulatorarm64": "klib",
}
ALGORITHMS = {"md5": "md5", "sha1": "sha1", "sha256": "sha256", "sha512": "sha512"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path, algorithm="sha256"):
    return hashlib.new(algorithm, path.read_bytes()).hexdigest()


def json_file(path):
    return json.loads(path.read_text(encoding="utf-8"))


def check_sources(archive, source_root, source_sets, excluded=()):
    with zipfile.ZipFile(archive) as package:
        names = package.namelist()
        count = 0
        for source_set in source_sets:
            for path in sorted((source_root / source_set / "kotlin").rglob("*.kt")):
                if path.name in excluded:
                    continue
                relative = path.relative_to(source_root / source_set / "kotlin").as_posix()
                candidates = [name for name in names if name == relative or name.endswith("/" + relative)]
                require(len(candidates) == 1, f"Missing or duplicated source member: {relative}")
                require(package.read(candidates[0]) == path.read_bytes(), f"Source bytes differ: {relative}")
                count += 1
        for name in names:
            require(not any(name.endswith(item) for item in excluded), f"Excluded launcher in sources: {name}")
        require("LICENSE" in names, f"Missing license in {archive.name}")
        return count


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("repository", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = args.repository.resolve()
    fork = Path(__file__).resolve().parents[1]
    files = sorted(path for path in root.rglob("*") if path.is_file())
    require(files, "Empty Maven repository")
    for path in files:
        require(not path.is_symlink(), f"Publication cannot contain a symbolic link: {path}")
        if path.suffix[1:] in ALGORITHMS:
            algorithm = ALGORITHMS[path.suffix[1:]]
            original = Path(str(path)[: -len(path.suffix)])
            require(original.is_file(), f"Missing checksum payload: {path}")
            require(path.read_text(encoding="ascii").strip().lower() == sha(original, algorithm), f"Checksum differs: {path}")

    base = root / GROUP.replace(".", "/")
    require({path.name for path in base.iterdir() if path.is_dir()} == set(MODULES), "Unexpected Maven modules")
    artifacts = []
    for module, extension in MODULES.items():
        directory = base / module / VERSION
        stem = f"{module}-{VERSION}"
        for suffix in (f".{extension}", ".pom", ".module", "-sources.jar"):
            path = directory / (stem + suffix)
            require(path.is_file() and path.stat().st_size > 0, f"Missing publication file: {path}")
        namespace = {"p": "http://maven.apache.org/POM/4.0.0"}
        pom = ET.parse(directory / (stem + ".pom")).getroot()
        require(pom.findtext("p:groupId", namespaces=namespace) == GROUP, f"Wrong POM group: {module}")
        require(pom.findtext("p:artifactId", namespaces=namespace) == module, f"Wrong POM module: {module}")
        require(pom.findtext("p:version", namespaces=namespace) == VERSION, f"Wrong POM version: {module}")

        metadata = json_file(directory / (stem + ".module"))
        for variant in metadata["variants"]:
            redirect = variant.get("available-at")
            if redirect:
                require(redirect["group"] == GROUP and redirect["version"] == VERSION, "Unexpected variant coordinate")
                require(redirect["module"] in MODULES, "Unknown target module")
                target = (directory / redirect["url"]).resolve()
                require(target.is_relative_to(root) and target.is_file(), f"Missing target metadata: {target}")
            for item in variant.get("files", ()):
                target = (directory / item["url"]).resolve()
                require(target.is_relative_to(root) and target.is_file(), "Publication file is missing or outside the repository")
                require(target.stat().st_size == item["size"], f"Wrong artifact size: {target}")
                require(sha(target) == item["sha256"], f"Wrong artifact checksum: {target}")
        if module in ("worker-kmp", "worker-ios"):
            leaves = {v["available-at"]["module"] for v in metadata["variants"] if "available-at" in v}
            expected = {module + "-iosarm64", module + "-iossimulatorarm64"}
            if module == "worker-kmp":
                expected.add("worker-kmp-jvm")
            require(leaves == expected, f"Wrong target graph: {module}: {leaves}")
        if extension == "klib":
            with zipfile.ZipFile(directory / (stem + ".klib")) as klib:
                manifest = klib.read("default/manifest").decode("utf-8")
                target = "ios_simulator_arm64" if "iossimulatorarm64" in module else "ios_arm64"
                require(f"native_targets={target}" in manifest, f"Wrong KLIB target: {module}")
        if module.startswith("worker-ios"):
            for variant in metadata["variants"]:
                if "sources" in variant["name"].lower():
                    continue
                deps = variant.get("dependencies", ())
                require(any(d["group"] == GROUP and d["version"].get("requires") == VERSION for d in deps), "Backend lacks same-version core dependency")
                require(not any(d["group"].startswith(("org.jetbrains.compose", "org.jetbrains.androidx", "io.insert-koin")) for d in deps), "Unexpected launcher dependency")
        artifacts.append(module)

    source_counts = {
        "worker-kmp": check_sources(
            base / "worker-kmp" / VERSION / f"worker-kmp-{VERSION}-sources.jar",
            fork / "cmp-worker-kmp/src",
            ("commonMain", "jvmMain", "iosMain"),
        ),
        "worker-ios": check_sources(
            base / "worker-ios" / VERSION / f"worker-ios-{VERSION}-sources.jar",
            fork / "cmp-worker-ios/src",
            ("commonMain", "iosMain"),
            ("WorkerKmpMainViewController.kt",),
        ),
    }
    for module in ("worker-kmp", "worker-ios"):
        license_path = base / module / VERSION / "LICENSE"
        license_path.write_bytes((fork / "LICENSE").read_bytes())
        for algorithm in ("sha1", "sha256"):
            Path(str(license_path) + "." + algorithm).write_text(sha(license_path, algorithm) + "\n", encoding="ascii")

    inventory = [
        {"path": path.relative_to(root).as_posix(), "bytes": path.stat().st_size, "sha256": sha(path)}
        for path in sorted(root.rglob("*")) if path.is_file()
    ]
    record = {
        "status": "VERIFIED",
        "sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=fork, text=True).strip(),
        "githubRunId": os.environ.get("GITHUB_RUN_ID"),
        "githubRunAttempt": os.environ.get("GITHUB_RUN_ATTEMPT"),
        "group": GROUP,
        "version": VERSION,
        "modules": artifacts,
        "sourceFiles": source_counts,
        "files": inventory,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in record.items() if key != "files"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
