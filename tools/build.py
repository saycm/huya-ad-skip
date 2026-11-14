from __future__ import annotations

import os
import shutil
import subprocess
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID_HOME = Path(os.environ["ANDROID_HOME"])
JAVA_HOME = Path(os.environ["JAVA_HOME"])
ANDROID_JAR = ANDROID_HOME / "platforms" / "android-36" / "android.jar"
BUILD_TOOLS = ANDROID_HOME / "build-tools" / "36.0.0"
EXE = ".exe" if os.name == "nt" else ""
BAT = ".bat" if os.name == "nt" else ""
ENTRY = "io.github.saycm.huya.skipad.HuyaCompatEntry"


def run(*args: str) -> None:
    subprocess.run(args, check=True, cwd=ROOT)


def main() -> None:
    build = ROOT / "build" / "huya"
    if build.exists():
        shutil.rmtree(build)
    for folder in ("classes", "stubs", "dex", "res"):
        (build / folder).mkdir(parents=True, exist_ok=True)

    javac = str(JAVA_HOME / "bin" / ("javac" + EXE))
    stub_sources = [str(path) for path in (ROOT / "tools/xposed-stubs").rglob("*.java")]
    run(javac, "-encoding", "UTF-8", "-source", "8", "-target", "8", "-Xlint:-options",
        "-d", str(build / "stubs"), *stub_sources)
    sources = [str(path) for path in (ROOT / "tools/huya-compat-src").rglob("*.java")]
    classpath = os.pathsep.join((str(ANDROID_JAR), str(build / "stubs")))
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
        apk.writestr("assets/xposed_init", ENTRY + "\n")

    output = ROOT / "build/HuyaMiniappAD_13.5.44_v1.9-unsigned.apk"
    run(str(BUILD_TOOLS / ("zipalign" + EXE)), "-f", "-p", "4",
        str(linked), str(output))
    print(output)


if __name__ == "__main__":
    main()
