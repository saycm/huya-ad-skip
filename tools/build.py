from __future__ import annotations

import os
import shutil
import subprocess
import urllib.request
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID_HOME = Path(os.environ["ANDROID_HOME"])
JAVA_HOME = Path(os.environ["JAVA_HOME"])
ANDROID_JAR = ANDROID_HOME / "platforms" / "android-36" / "android.jar"
BUILD_TOOLS = ANDROID_HOME / "build-tools" / "36.0.0"
EXE = ".exe" if os.name == "nt" else ""
BAT = ".bat" if os.name == "nt" else ""
LIBXPOSED_VERSION = "102.0.0"
LIBXPOSED_AAR = ROOT / "build/deps" / f"api-{LIBXPOSED_VERSION}.aar"
LIBXPOSED_URL = (
    "https://repo1.maven.org/maven2/io/github/libxposed/api/"
    f"{LIBXPOSED_VERSION}/api-{LIBXPOSED_VERSION}.aar"
)


def run(*args: str) -> None:
    subprocess.run(args, check=True, cwd=ROOT)


def libxposed_api_jar() -> Path:
    LIBXPOSED_AAR.parent.mkdir(parents=True, exist_ok=True)
    if not LIBXPOSED_AAR.is_file():
        urllib.request.urlretrieve(LIBXPOSED_URL, LIBXPOSED_AAR)
    api_jar = LIBXPOSED_AAR.with_suffix(".jar")
    if not api_jar.is_file() or api_jar.stat().st_mtime < LIBXPOSED_AAR.stat().st_mtime:
        with zipfile.ZipFile(LIBXPOSED_AAR) as aar:
            api_jar.write_bytes(aar.read("classes.jar"))
    return api_jar


def main() -> None:
    build = ROOT / "build" / "huya"
    if build.exists():
        shutil.rmtree(build)
    for folder in ("classes", "dex", "res"):
        (build / folder).mkdir(parents=True, exist_ok=True)

    javac = str(JAVA_HOME / "bin" / ("javac" + EXE))
    sources = [str(path) for path in (ROOT / "tools/huya-compat-src").rglob("*.java")]
    classpath = os.pathsep.join((str(ANDROID_JAR), str(libxposed_api_jar())))
    run(javac, "-encoding", "UTF-8", "-source", "8", "-target", "8", "-Xlint:-options",
        "-cp", classpath, "-d", str(build / "classes"), *sources)

    runtime_jar = build / "huya-runtime.jar"
    run(str(JAVA_HOME / "bin" / ("jar" + EXE)), "cf", str(runtime_jar),
        "-C", str(build / "classes"), ".")
    run(str(ANDROID_HOME / "cmdline-tools/latest/bin" / ("d8" + BAT)),
        "--release", "--min-api", "29", "--lib", str(ANDROID_JAR),
        "--output", str(build / "dex"), str(runtime_jar))

    module_res = ROOT / "tools/huya-module-src"
    aapt2 = str(BUILD_TOOLS / ("aapt2" + EXE))
    run(aapt2, "compile", "--dir", str(module_res / "res"),
        "-o", str(build / "res/compiled.zip"))
    linked = build / "huya-linked.apk"
    run(aapt2, "link", "-o", str(linked), "-I", str(ANDROID_JAR),
        "--manifest", str(module_res / "AndroidManifest.xml"),
        str(build / "res/compiled.zip"))
    with zipfile.ZipFile(linked, "a", compression=zipfile.ZIP_DEFLATED) as apk:
        apk.write(build / "dex/classes.dex", "classes.dex")
        metadata = module_res / "resources/META-INF/xposed"
        for name in ("java_init.list", "module.prop", "scope.list"):
            apk.write(metadata / name, "META-INF/xposed/" + name)

    output = ROOT / "build/HuyaMiniappAD_13.5.44_v1.11-unsigned.apk"
    run(str(BUILD_TOOLS / ("zipalign" + EXE)), "-f", "-p", "4",
        str(linked), str(output))
    print(output)


if __name__ == "__main__":
    main()
