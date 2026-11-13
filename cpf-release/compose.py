#!/usr/bin/env python3
"""用已验收原件组合统一版本的隔离 Maven 仓库，不编译或上传任何制品。"""

import argparse
import copy
import datetime
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import sys
import xml.etree.ElementTree as ET
import zipfile


GROUP = "com.lihb.forks.worker"
GROUP_PATH = GROUP.replace(".", "/")
ALGORITHMS = ("md5", "sha1", "sha256", "sha512")
KPSM = "META-INF/kotlin-project-structure-metadata.json"
POM_NS = "http://maven.apache.org/POM/4.0.0"
ET.register_namespace("", POM_NS)
ET.register_namespace("xsi", "http://www.w3.org/2001/XMLSchema-instance")
MODULES = {
    "worker-kmp": ("ios", "jar"),
    "worker-kmp-jvm": ("ios", "jar"),
    "worker-kmp-iosarm64": ("ios", "klib"),
    "worker-kmp-iossimulatorarm64": ("ios", "klib"),
    "worker-kmp-ohosx64": ("ohos", "klib"),
    "worker-kmp-ohosarm64": ("ohos", "klib"),
    "worker-android": ("android", "jar"),
    "worker-android-android": ("android", "aar"),
    "worker-ios": ("ios", "jar"),
    "worker-ios-iosarm64": ("ios", "klib"),
    "worker-ios-iossimulatorarm64": ("ios", "klib"),
}
INPUT_MODULES = {
    "android": ("worker-kmp", "worker-kmp-jvm", "worker-android", "worker-android-android"),
    "ios": tuple(module for module, (platform, _) in MODULES.items() if platform == "ios"),
    "ohos": ("worker-kmp", "worker-kmp-ohosx64", "worker-kmp-ohosarm64"),
}
OHOS_ONLY = {"androidx.sqlite:sqlite-bundled", "com.squareup.okio:okio"}
RECEIPT = "cpf-release-receipt.json"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def digest(data, algorithm="sha256"):
    return hashlib.new(algorithm, data).hexdigest()


def default_properties():
    path = Path(__file__).resolve().parents[1] / "cpf-build/gradle.properties"
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.lstrip().startswith(("#", "!")) and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    require(values.get("cpf.group") == GROUP, f"Unexpected cpf.group in {path}")
    require(values.get("cpf.version"), f"Missing cpf.version in {path}")
    return path, values


def validate_version(value):
    require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]*", value), f"Unsafe version: {value}")
    return value


def absolute(path):
    # Maven 的 Native 制品路径可超过 MAX_PATH，Windows 文件读写统一使用长路径前缀。
    value = os.path.abspath(path)
    if os.name == "nt" and not value.startswith("\\\\?\\"):
        value = "\\\\?\\UNC\\" + value[2:] if value.startswith("\\\\") else "\\\\?\\" + value
    return Path(value).resolve()


def resolve_url(directory, url, root):
    # GMM 的 name 是逻辑名称，只有 url 是仓库内实际路径。
    require(":" not in url and "\\" not in url and not url.startswith("/"), f"Invalid repository URL: {url}")
    # 先归一化 ..，兼容 Windows 受限宿主的路径解析。
    path = absolute(directory / url)
    require(path.is_relative_to(root) and path.is_file(), f"Missing or escaped repository URL: {url}")
    return path


def read_zip(data):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate ZIP members")
        require(not any(PurePosixPath(name).is_absolute() or ".." in PurePosixPath(name).parts for name in names), "Unsafe ZIP member")
        require(archive.testzip() is None, "Invalid ZIP CRC")
        return {item.filename: archive.read(item) for item in archive.infolist() if not item.is_dir()}


def write_zip(members):
    # 固定顺序、时间与权限，使相同输入得到相同的组合归档字节。
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(members.items()):
            item = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            item.compress_type = zipfile.ZIP_DEFLATED
            item.external_attr = 0o100644 << 16
            archive.writestr(item, data)
    return buffer.getvalue()


def coordinate_dependencies(metadata):
    for variant in metadata["variants"]:
        for dependency in variant.get("dependencies", ()):
            yield variant, dependency


def dep_key(dependency):
    return dependency["group"] + ":" + dependency["module"]


def parse_pom(data):
    return ET.fromstring(data, parser=ET.XMLParser(target=ET.TreeBuilder(insert_comments=True)))


def pom_value(element, key):
    return element.findtext("{" + POM_NS + "}" + key)


def check_pom(data, module, version):
    root = parse_pom(data)
    require((pom_value(root, "groupId"), pom_value(root, "artifactId"), pom_value(root, "version")) == (GROUP, module, version), f"Wrong POM coordinate: {module}")
    for dependency in root.findall(".//{" + POM_NS + "}dependency"):
        if pom_value(dependency, "groupId") == GROUP:
            require(pom_value(dependency, "artifactId") in MODULES and pom_value(dependency, "version") == version, f"Mixed internal POM dependency: {module}")


class Publication:
    def __init__(self, root, platform, module, version):
        self.root, self.platform, self.module, self.version = root, platform, module, version
        self.directory = root / GROUP_PATH / module / version
        require(self.directory.is_dir() and not self.directory.is_symlink(), f"Missing input publication: {self.directory}")
        self.files = {}
        for path in sorted(self.directory.iterdir()):
            require(path.is_file() and not path.is_symlink(), f"Unexpected input entry: {path}")
            self.files[path.name] = path.read_bytes()
        stem = f"{module}-{version}"
        extension = MODULES[module][1]
        for suffix in ("." + extension, ".pom", ".module", "-sources.jar"):
            name = stem + suffix
            require(self.files.get(name), f"Missing input file: {self.directory / name}")
            require(name + ".sha256" in self.files, f"Missing input SHA-256: {name}")
        for name, data in self.files.items():
            suffix = name.rsplit(".", 1)[-1]
            require(suffix != "asc", f"Signed inputs must be re-signed for a new version: {name}")
            if suffix in ALGORITHMS:
                original = name[: -(len(suffix) + 1)]
                require(original in self.files, f"Orphan input checksum: {name}")
                require(data.decode("ascii").strip().lower() == digest(self.files[original], suffix), f"Input checksum differs: {self.directory / name}")
        self.metadata = json.loads(self.files[stem + ".module"])
        check_pom(self.files[stem + ".pom"], module, version)
        validate_metadata(root, self.directory, module, version, self.metadata)
        self.archive = read_zip(self.files[stem + "." + extension])
        self.sources = read_zip(self.files[stem + "-sources.jar"])
        if extension == "klib":
            target = {"iosarm64": "ios_arm64", "iossimulatorarm64": "ios_simulator_arm64", "ohosx64": "ohos_x64", "ohosarm64": "ohos_arm64"}[module.rsplit("-", 1)[-1]]
            manifest = self.archive.get("default/manifest", b"").decode("utf-8")
            require(f"native_targets={target}" in manifest.splitlines(), f"Wrong KLIB target: {module}")

    def payload(self, suffix):
        return self.files[f"{self.module}-{self.version}{suffix}"]

    def source_id(self, name):
        return f"{self.platform}/{GROUP_PATH}/{self.module}/{self.version}/{name}"


def validate_metadata(root, directory, module, version, metadata):
    component = metadata["component"]
    require(component["group"] == GROUP and component["version"] == version and component["module"] in MODULES, f"Wrong GMM component: {module}")
    if "url" in component:
        target = resolve_url(directory, component["url"], root)
        require(target == root / GROUP_PATH / component["module"] / version / f"{component['module']}-{version}.module", f"Wrong GMM owner: {module}")
    else:
        require(component["module"] == module, f"Wrong GMM root: {module}")
    names = [variant["name"] for variant in metadata["variants"]]
    require(names and len(names) == len(set(names)), f"Duplicate or empty GMM variants: {module}")
    for variant in metadata["variants"]:
        redirect = variant.get("available-at")
        if redirect:
            require(redirect["group"] == GROUP and redirect["version"] == version and redirect["module"] in MODULES, f"Wrong GMM redirect: {module}")
            target = resolve_url(directory, redirect["url"], root)
            require(target == root / GROUP_PATH / redirect["module"] / version / f"{redirect['module']}-{version}.module", f"Wrong GMM redirect URL: {module}")
        for item in variant.get("files", ()):
            path = resolve_url(directory, item["url"], root)
            data = path.read_bytes()
            require(len(data) == item["size"] and item.get("sha256") == digest(data), f"GMM file differs: {path}")
            for algorithm in ALGORITHMS:
                if algorithm in item:
                    require(item[algorithm] == digest(data, algorithm), f"GMM {algorithm} differs: {path}")
    for _, dependency in coordinate_dependencies(metadata):
        if dependency["group"] == GROUP:
            require(dependency["module"] in MODULES and dependency["version"].get("requires") == version, f"Mixed internal GMM dependency: {module}")


def rewrite_strings(value, old_versions, version):
    if isinstance(value, str):
        for old in old_versions:
            value = value.replace(old, version)
        return value
    if isinstance(value, list):
        return [rewrite_strings(item, old_versions, version) for item in value]
    if isinstance(value, dict):
        return {key: rewrite_strings(item, old_versions, version) for key, item in value.items()}
    return value


def rewrite_pom(data, old_versions, version):
    root = parse_pom(data)
    for element in root.iter():
        if element.text:
            element.text = rewrite_strings(element.text, old_versions, version)
    ET.indent(root, space="  ")
    return ET.tostring(root, encoding="utf-8", xml_declaration=True) + b"\n"


def structure(publication):
    require(KPSM in publication.archive, f"Missing KPSM in {publication.directory}")
    return json.loads(publication.archive[KPSM])["projectStructure"]


def validate_structure(project):
    require(project["formatVersion"] == "0.3.3" and str(project["isPublishedAsRoot"]).lower() == "true", "Unsupported KPSM format")
    source_sets = {item["name"]: item for item in project["sourceSets"]}
    require(len(source_sets) == len(project["sourceSets"]), "Duplicate KPSM source sets")
    for name, source_set in source_sets.items():
        dependencies = source_set["dependsOn"]
        require(name not in dependencies and set(dependencies) <= source_sets.keys(), f"Invalid dependsOn: {name}")
        def visit(current, seen):
            require(current not in seen, f"Cyclic dependsOn: {name}")
            for parent in source_sets[current]["dependsOn"]:
                visit(parent, seen | {current})
        visit(name, set())
    for variant in project["variants"]:
        require(len(variant["sourceSet"]) == len(set(variant["sourceSet"])) and set(variant["sourceSet"]) <= source_sets.keys(), f"Invalid KPSM variant: {variant['name']}")
    return source_sets


def merge_core(ios, ohos, jvm, android_jvm):
    # 唯一 commonMain 使用官方 2.4.20 的 iOS 原件，绝不换成 Android 的空根 JAR。
    project = copy.deepcopy(structure(ios))
    other = structure(ohos)
    validate_structure(project)
    validate_structure(other)
    require({item["name"] for item in project["sourceSets"]} == {"commonMain", "iosMain"}, "Unexpected iOS source sets")
    require({item["name"] for item in other["sourceSets"]} == {"commonMain", "ohosMain"}, "Unexpected OHOS source sets")
    require(any(name.startswith("commonMain/") for name in ios.archive), "iOS root has no commonMain binary")
    require(any(name.startswith("ohosMain/") for name in ohos.archive), "OHOS root has no ohosMain binary")
    project["sourceSets"].extend(copy.deepcopy(item) for item in other["sourceSets"] if item["name"] != "commonMain")
    project["variants"].extend(copy.deepcopy(other["variants"]))
    expected = {"jvmApiElements", "jvmRuntimeElements", "iosArm64ApiElements", "iosSimulatorArm64ApiElements", "ohosArm64ApiElements", "ohosX64ApiElements"}
    require(len(project["variants"]) == len(expected) and {item["name"] for item in project["variants"]} == expected, "Unexpected combined KPSM variants")
    source_sets = validate_structure(project)
    require(source_sets["iosMain"]["dependsOn"] == ["commonMain"] and source_sets["iosMain"].get("hostSpecific") == "true", "Invalid iOS fragment boundary")
    require(source_sets["ohosMain"]["dependsOn"] == ["commonMain"], "Invalid OHOS fragment boundary")
    require(str(source_sets["ohosMain"].get("hostSpecific", "false")).lower() == "false", "OHOS fragment must remain in the universal root")
    require(OHOS_ONLY <= set(source_sets["ohosMain"]["moduleDependency"]), "OHOS fragment lacks runtime dependencies")
    require(not OHOS_ONLY & set(source_sets["commonMain"]["moduleDependency"] + source_sets["iosMain"]["moduleDependency"]), "OHOS dependencies leaked into another fragment")

    archive = dict(ios.archive)
    archive.update({name: data for name, data in ohos.archive.items() if name.startswith(("ohosMain/", "ohosMain-cinterop/"))})
    archive[KPSM] = encoded({"projectStructure": project})
    sources = dict(ios.sources)
    sources.update({name: data for name, data in jvm.sources.items() if name.startswith("jvmMain/")})
    sources.update({name: data for name, data in ohos.sources.items() if name.startswith("ohosMain/")})
    require(all(any(name.startswith(source_set + "/") for name in sources) for source_set in ("commonMain", "jvmMain", "iosMain", "ohosMain")), "Incomplete combined root sources")

    comparisons = []
    common = {name: data.replace(b"\r\n", b"\n") for name, data in ios.sources.items() if name.startswith("commonMain/")}
    for label, source in (("ios-jvm", jvm.sources), ("ohos", ohos.sources), ("android-jvm-comparison-only", android_jvm.sources)):
        candidate = {name: data.replace(b"\r\n", b"\n") for name, data in source.items() if name.startswith("commonMain/")}
        missing = set(common) - candidate.keys()
        require(not candidate.keys() - common.keys(), f"Unexpected common sources: {label}")
        require(not missing or (label == "android-jvm-comparison-only" and all(name.endswith("/ProcessOneTimeWorkManager.kt") for name in missing)), f"Missing common sources: {label}: {missing}")
        require(all(common[name] == data for name, data in candidate.items()), f"Different common source bytes: {label}")
        comparisons.append({"input": label, "comparedFiles": len(candidate), "missing": sorted(missing), "normalization": "CRLF to LF for comparison only"})
    return write_zip(archive), write_zip(sources), project, comparisons


def merge_root_metadata(ios, ohos):
    metadata = copy.deepcopy(ios.metadata)
    metadata["variants"].extend(copy.deepcopy(variant) for variant in ohos.metadata["variants"] if "available-at" in variant)
    common = next(variant for variant in metadata["variants"] if variant["name"] == "metadataApiElements")
    other = next(variant for variant in ohos.metadata["variants"] if variant["name"] == "metadataApiElements")
    existing = {dep_key(item): item for item in common["dependencies"]}
    preserved = []
    for dependency in other["dependencies"]:
        key = dep_key(dependency)
        if key in existing:
            preserved.append({"module": key, "common": existing[key]["version"], "ohosLeaf": dependency["version"]})
        else:
            require(key in OHOS_ONLY, f"Unreviewed OHOS metadata dependency: {key}")
            # 私有实现依赖只留在 KPSM 的 ohosMain 与 Native 叶，避免公共元数据强制解析 CPF 仓库。
    require({dep_key(item) for item in common["dependencies"]} == set(existing), "Common public metadata dependencies changed")
    require(not OHOS_ONLY & set(existing), "Private OHOS dependencies leaked into root metadata")
    return metadata, preserved


def merge_tooling(ios, ohos):
    left = json.loads(ios.payload("-kotlin-tooling-metadata.json"))
    right = json.loads(ohos.payload("-kotlin-tooling-metadata.json"))
    left["projectTargets"].extend(item for item in right["projectTargets"] if item.get("extras", {}).get("native", {}).get("konanTarget", "").startswith("ohos_"))
    return encoded(left)


def generate_checksums(path):
    data = path.read_bytes()
    for algorithm in ALGORITHMS:
        Path(str(path) + "." + algorithm).write_text(digest(data, algorithm) + "\n", encoding="ascii")


def write_index(output, module, version):
    root = ET.Element("metadata")
    ET.SubElement(root, "groupId").text = GROUP
    ET.SubElement(root, "artifactId").text = module
    versioning = ET.SubElement(root, "versioning")
    ET.SubElement(versioning, "latest").text = version
    ET.SubElement(versioning, "release").text = version
    ET.SubElement(ET.SubElement(versioning, "versions"), "version").text = version
    # 索引固定时间，实际组合时间单独存入 provenance。
    ET.SubElement(versioning, "lastUpdated").text = "19800101000000"
    ET.indent(root, space="  ")
    path = output / GROUP_PATH / module / "maven-metadata.xml"
    path.write_bytes(ET.tostring(root, encoding="utf-8", xml_declaration=True) + b"\n")
    generate_checksums(path)


def validate_repository(output, version):
    base = output / GROUP_PATH
    require({path.name for path in base.iterdir()} == set(MODULES), "Wrong combined module set")
    counts = {"modules": 0, "filesByUrl": 0, "checksums": 0}
    for module, (_, extension) in MODULES.items():
        directory = base / module / version
        require({path.name for path in (base / module).iterdir() if path.is_dir()} == {version}, f"Mixed versions: {module}")
        stem = f"{module}-{version}"
        check_pom((directory / (stem + ".pom")).read_bytes(), module, version)
        metadata = json.loads((directory / (stem + ".module")).read_bytes())
        validate_metadata(output, directory, module, version, metadata)
        require((directory / (stem + "." + extension)).is_file() and (directory / (stem + "-sources.jar")).is_file(), f"Missing combined artifact: {module}")
        counts["filesByUrl"] += sum(len(variant.get("files", ())) for variant in metadata["variants"])
        counts["modules"] += 1
        index = ET.parse(base / module / "maven-metadata.xml").getroot()
        require(index.findtext("groupId") == GROUP and index.findtext("artifactId") == module and index.findtext("versioning/latest") == version and index.findtext("versioning/release") == version, f"Wrong Maven index: {module}")
        require([item.text for item in index.findall("versioning/versions/version")] == [version], f"Wrong index versions: {module}")
        if module != "worker-kmp" and not module.startswith("worker-kmp-ohos"):
            require(not any(dep_key(item) in OHOS_ONLY for _, item in coordinate_dependencies(metadata)), f"OHOS runtime dependency leaked: {module}")
        if module.startswith("worker-kmp-ohos"):
            for variant in metadata["variants"]:
                if variant.get("attributes", {}).get("org.gradle.category") == "library":
                    require(OHOS_ONLY <= {dep_key(item) for item in variant.get("dependencies", ())}, f"OHOS leaf runtime dependencies missing: {module}")
        for path in directory.iterdir():
            if path.suffix[1:] not in ALGORITHMS:
                require(all(Path(str(path) + "." + algorithm).is_file() for algorithm in ALGORITHMS), f"Missing output checksums: {path}")
    for path in output.rglob("*"):
        require(not path.is_symlink(), f"Output contains a symbolic link: {path}")
        if path.is_file() and path.suffix[1:] in ALGORITHMS:
            original = Path(str(path)[: -len(path.suffix)])
            require(original.is_file() and path.read_text(encoding="ascii").strip() == digest(original.read_bytes(), path.suffix[1:]), f"Wrong output checksum: {path}")
            counts["checksums"] += 1
    root = read_zip((base / "worker-kmp" / version / f"worker-kmp-{version}.jar").read_bytes())
    project = json.loads(root[KPSM])["projectStructure"]
    source_sets = validate_structure(project)
    require(set(source_sets) == {"commonMain", "iosMain", "ohosMain"}, "Wrong combined fragments")
    for name in ("commonMain", "iosMain"):
        require(not OHOS_ONLY & set(source_sets[name]["moduleDependency"]), f"OHOS dependency leaked into {name}")
    require(OHOS_ONLY <= set(source_sets["ohosMain"]["moduleDependency"]), "OHOS fragment dependencies missing")
    require(str(source_sets["ohosMain"].get("hostSpecific", "false")).lower() == "false", "OHOS fragment must not become host-specific")
    require(any(name.startswith("ohosMain/") for name in root), "OHOS fragment binary missing from root")
    expected_leaves = {module for module in MODULES if module.startswith("worker-kmp-")}
    metadata = json.loads((base / "worker-kmp" / version / f"worker-kmp-{version}.module").read_bytes())
    common = next(item for item in metadata["variants"] if item["name"] == "metadataApiElements")
    require(not OHOS_ONLY & {dep_key(item) for item in common.get("dependencies", ())}, "Private OHOS implementation dependency leaked into root metadata")
    require({item["available-at"]["module"] for item in metadata["variants"] if "available-at" in item} == expected_leaves, "Incomplete core target graph")
    return counts


def verify_receipt(output):
    receipt = json.loads((output / RECEIPT).read_bytes())
    require(receipt["status"] == "METADATA_VERIFIED", "Incomplete output repository")
    expected = {item["path"] for item in receipt["outputs"]}
    excluded = {RECEIPT} | {RECEIPT + "." + algorithm for algorithm in ALGORITHMS}
    actual = {path.relative_to(output).as_posix() for path in output.rglob("*") if path.is_file()} - excluded
    require(actual == expected, "Output inventory differs from receipt")
    for item in receipt["outputs"]:
        path = resolve_url(output, item["path"], output)
        data = path.read_bytes()
        require(len(data) == item["bytes"] and digest(data) == item["sha256"], f"Receipt output differs: {path}")
    return validate_repository(output, receipt["version"])


def compose(args):
    properties, defaults = default_properties()
    version = validate_version(args.version or defaults["cpf.version"])
    output = absolute(args.output)
    require(not output.exists(), f"Refusing to overwrite existing output: {output}")
    roots = {platform: absolute(getattr(args, platform + "_repository")) for platform in INPUT_MODULES}
    versions = {platform: validate_version(getattr(args, platform + "_version")) for platform in INPUT_MODULES}
    require(all(root.is_dir() and not output.is_relative_to(root) and not root.is_relative_to(output) for root in roots.values()), "Output must be isolated from input repositories")
    publications = {}
    for platform, modules in INPUT_MODULES.items():
        for module in modules:
            publications[platform, module] = Publication(roots[platform], platform, module, versions[platform])
    ios, ohos = publications["ios", "worker-kmp"], publications["ohos", "worker-kmp"]
    core_jar, core_sources, project, comparisons = merge_core(ios, ohos, publications["ios", "worker-kmp-jvm"], publications["android", "worker-kmp-jvm"])
    core_metadata, dependency_policy = merge_root_metadata(ios, ohos)
    tooling = merge_tooling(ios, ohos)
    old_versions = sorted(set(versions.values()), key=len, reverse=True)
    origins = {}
    input_records = []
    for publication in publications.values():
        for name, data in sorted(publication.files.items()):
            input_records.append({"id": publication.source_id(name), "platform": publication.platform, "path": str(publication.directory.relative_to(publication.root) / name).replace("\\", "/"), "bytes": len(data), "sha256": digest(data)})

    # 所有输入已完成预检；mkdir 独占一个全新仓库，失败现场保留且不可复用。
    output.mkdir(parents=True, exist_ok=False)
    for module, (platform, _) in MODULES.items():
        publication = publications[platform, module]
        directory = output / GROUP_PATH / module / version
        directory.mkdir(parents=True)
        for name, original in publication.files.items():
            if name.rsplit(".", 1)[-1] in ALGORITHMS or name == "publication.json":
                continue
            target_name = rewrite_strings(name, old_versions, version)
            target = directory / target_name
            data = original
            action = "byte-preserved"
            source_ids = [publication.source_id(name)]
            if name.endswith(".pom"):
                data, action = rewrite_pom(original, old_versions, version), "rewrite-internal-coordinates"
            elif name.endswith(".module"):
                value = core_metadata if module == "worker-kmp" else publication.metadata
                value = rewrite_strings(value, old_versions, version)
                # 文件写完后再按 url 更新摘要，name 允许与实际仓库文件名不同。
                data, action = encoded(value), "rewrite-gmm-and-file-hashes"
                if module == "worker-kmp":
                    source_ids.append(ohos.source_id(f"worker-kmp-{ohos.version}.module"))
            elif module == "worker-kmp" and name.endswith("-sources.jar"):
                data, action = core_sources, "merge-ios-common-ios-jvm-and-ohos-sources"
                source_ids.extend([publications["ios", "worker-kmp-jvm"].source_id(f"worker-kmp-jvm-{ios.version}-sources.jar"), ohos.source_id(f"worker-kmp-{ohos.version}-sources.jar")])
            elif module == "worker-kmp" and name == f"worker-kmp-{publication.version}.jar":
                data, action = core_jar, "merge-ios-common-ohos-fragment-and-kpsm"
                source_ids.append(ohos.source_id(f"worker-kmp-{ohos.version}.jar"))
            elif module == "worker-kmp" and name.endswith("-kotlin-tooling-metadata.json"):
                data, action = tooling, "merge-target-tooling-with-original-compiler-versions"
                source_ids.append(ohos.source_id(f"worker-kmp-{ohos.version}-kotlin-tooling-metadata.json"))
            target.write_bytes(data)
            origins[target.relative_to(output).as_posix()] = {"action": action, "inputs": source_ids}

    for module in MODULES:
        directory = output / GROUP_PATH / module / version
        path = directory / f"{module}-{version}.module"
        value = json.loads(path.read_bytes())
        for variant in value["variants"]:
            for item in variant.get("files", ()):
                payload = resolve_url(directory, item["url"], output).read_bytes()
                item.update({"size": len(payload), **{algorithm: digest(payload, algorithm) for algorithm in ALGORITHMS}})
        path.write_bytes(encoded(value))
        for path in sorted(directory.iterdir()):
            generate_checksums(path)
        write_index(output, module, version)

    verification = validate_repository(output, version)
    for item in input_records:
        path = roots[item["platform"]] / item["path"]
        require(path.read_bytes() == publications[item["id"].split("/", 1)[0], path.parent.parent.name].files[path.name], f"Input changed while composing: {path}")
    # 二进制叶和其他未变归档逐字节比对；组合根仅修改容器和 KPSM，片段字节保持。
    byte_preserved = 0
    for relative, origin in origins.items():
        if origin["action"] == "byte-preserved":
            original = next(item for item in input_records if item["id"] == origin["inputs"][0])
            require(digest((output / relative).read_bytes()) == original["sha256"], f"Copied payload changed: {relative}")
            byte_preserved += 1
    combined = read_zip(core_jar)
    require(all(combined[name] == data for name, data in ios.archive.items() if name != KPSM), "iOS common fragment changed")
    require(all(combined[name] == data for name, data in ohos.archive.items() if name.startswith(("ohosMain/", "ohosMain-cinterop/"))), "OHOS fragment changed")
    outputs = []
    for path in sorted(output.rglob("*")):
        if path.is_file():
            relative = path.relative_to(output).as_posix()
            data = path.read_bytes()
            origin = origins.get(relative, {"action": "generated-checksum" if path.suffix[1:] in ALGORITHMS else "generated-module-index", "inputs": []})
            outputs.append({"path": relative, "bytes": len(data), "sha256": digest(data), **origin})
    receipt = {
        "schemaVersion": 1,
        "status": "METADATA_VERIFIED",
        "createdAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "group": GROUP, "version": version, "modules": list(MODULES),
        "tool": {"path": "cpf-release/compose.py", "sha256": digest(Path(__file__).read_bytes())},
        "versionSource": {"path": "cpf-build/gradle.properties", "sha256": digest(properties.read_bytes()), "default": defaults["cpf.version"], "override": args.version},
        "inputs": input_records,
        "sourceVersions": versions,
        "sourceComparisons": comparisons,
        "dependencyPolicy": {"commonVersionsRetained": dependency_policy, "ohosFragmentAndNativeLeafOnly": sorted(OHOS_ONLY), "rootMetadataPrivateDependenciesOmitted": sorted(OHOS_ONLY), "platformRuntimeDependencies": "unchanged except same-version internal GAVs"},
        "kpsm": project,
        "verification": {**verification, "bytePreservedFiles": byte_preserved, "originalInputsUnchanged": True, "commonAndOhosFragmentMembersUnchanged": True, "consumerCompile": "NOT_RUN", "nativeLinkOrRuntime": "NOT_RUN"},
        "outputs": outputs,
        "notice": "Only isolated repository composition and standard-library metadata verification; no compilation, upload, or publication.",
    }
    (output / RECEIPT).write_bytes(encoded(receipt))
    generate_checksums(output / RECEIPT)
    verify_receipt(output)
    print(json.dumps({"status": receipt["status"], "version": version, "output": str(output), "receipt": str(output / RECEIPT), "modules": len(MODULES), "bytePreservedFiles": byte_preserved}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("version", help="读取唯一 cpf.version")
    command = commands.add_parser("compose", help="从三组原件创建全新隔离仓库")
    for platform in INPUT_MODULES:
        command.add_argument("--" + platform + "-repository", type=Path, required=True)
        command.add_argument("--" + platform + "-version", required=True)
    command.add_argument("--version", help="候选版本；默认读取 cpf-build/gradle.properties")
    command.add_argument("--output", type=Path, required=True)
    verify = commands.add_parser("verify", help="只读复核输出元数据、全部摘要和回执")
    verify.add_argument("repository", type=Path)
    args = parser.parse_args()
    if args.command == "version":
        print(default_properties()[1]["cpf.version"])
    elif args.command == "compose":
        compose(args)
    else:
        result = verify_receipt(absolute(args.repository))
        print(json.dumps({"status": "METADATA_VERIFIED", **result}))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, zipfile.BadZipFile, ET.ParseError) as failure:
        print(f"ERROR: {failure}", file=sys.stderr)
        sys.exit(1)
