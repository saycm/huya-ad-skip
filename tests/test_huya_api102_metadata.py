"""Verify the module package uses the libxposed API 102 descriptor."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[1]
MODULE_DIR = ROOT / "tools/huya-module-src"
META_DIR = MODULE_DIR / "resources/META-INF/xposed"
ENTRY = "io.github.saycm.huya.skipad.HuyaCompatEntry"
API_VERSION = "102.0.0"
API_AAR = ROOT / "build/deps" / f"api-{API_VERSION}.aar"
JDK = Path(os.environ["JAVA_HOME"])
SDK = Path(os.environ["ANDROID_HOME"])
EXE = ".exe" if os.name == "nt" else ""


class HuyaApi102MetadataTest(unittest.TestCase):
    def test_module_prop_targets_api_102(self):
        props = {}
        for line in (META_DIR / "module.prop").read_text(encoding="utf-8").splitlines():
            if line and not line.startswith("#"):
                key, value = line.split("=", 1)
                props[key] = value

        self.assertEqual("102", props["minApiVersion"])
        self.assertEqual("102", props["targetApiVersion"])
        self.assertEqual("true", props["staticScope"])
        self.assertEqual("false", props["autoHotReload"])

    def test_modern_entry_and_scope_lists_are_packaged(self):
        self.assertEqual(
            [ENTRY],
            (META_DIR / "java_init.list").read_text(encoding="utf-8").splitlines(),
        )
        self.assertEqual(
            ["com.duowan.kiwi"],
            (META_DIR / "scope.list").read_text(encoding="utf-8").splitlines(),
        )

    def test_manifest_does_not_use_legacy_xposed_metadata(self):
        manifest = (MODULE_DIR / "AndroidManifest.xml").read_text(encoding="utf-8")
        self.assertNotIn('"xposedmodule"', manifest)
        self.assertNotIn('"xposedminversion"', manifest)
        self.assertNotIn('"xposedscope"', manifest)
        self.assertIn('android:description="@string/app_description"', manifest)
        self.assertIn('android:versionCode="12"', manifest)
        self.assertIn('android:versionName="1.11"', manifest)

    def test_entry_compiles_against_api_102_only(self):
        self.assertTrue(API_AAR.is_file(), f"missing API fixture: {API_AAR}")
        with tempfile.TemporaryDirectory(prefix="huya-api102-") as folder:
            root = Path(folder)
            api_jar = root / "api-102.0.0.jar"
            with zipfile.ZipFile(API_AAR) as aar:
                api_jar.write_bytes(aar.read("classes.jar"))

            android = SDK / "platforms/android-36/android.jar"
            classes = root / "classes"
            classes.mkdir()
            source = (
                ROOT
                / "tools/huya-compat-src/io/github/saycm/huya/skipad/HuyaCompatEntry.java"
            )
            result = subprocess.run(
                [
                    str(JDK / "bin" / ("javac" + EXE)),
                    "-encoding",
                    "UTF-8",
                    "-cp",
                    os.pathsep.join((str(android), str(api_jar))),
                    "-d",
                    str(classes),
                    str(source),
                ],
                capture_output=True,
                text=True,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

            signature = subprocess.run(
                [
                    str(JDK / "bin" / ("javap" + EXE)),
                    "-classpath",
                    os.pathsep.join((str(classes), str(api_jar), str(android))),
                    ENTRY,
                ],
                check=True,
                capture_output=True,
                text=True,
            ).stdout
            self.assertIn("extends io.github.libxposed.api.XposedModule", signature)
            self.assertIn("void onPackageReady(", signature)


if __name__ == "__main__":
    unittest.main()
