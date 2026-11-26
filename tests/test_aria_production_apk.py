import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from zipfile import ZipFile
from xml.etree import ElementTree as ET

spec = importlib.util.spec_from_file_location(
    "aria_apks", Path(__file__).resolve().parents[1] / "scripts/verify-aria-production-apks.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


# Build a synthetic compiled-manifest-shaped fixture independently of the verifier's inventory.
def manifest_fixture():
    names = ("Default", "GlassLight", "GlassDark", "GlassClear", "GlassClearDark",
             "SamsungGlass", "SamsungGlassDark", "SamsungGlassClear", "SamsungGlassClearDark",
             "PixelGlass", "PixelGlassDark", "PixelGlassClear", "PixelGlassClearDark")
    root = ET.Element("manifest", {"package": "com.music.aria"})
    a = "{http://schemas.android.com/apk/res/android}"
    app = ET.SubElement(root, "application", {a + "name": ".AriaApplication"})
    ET.SubElement(app, "activity", {a + "name": ".MainActivity"})
    for name in names:
        alias = ET.SubElement(app, "activity-alias", {
            a + "name": ".Launcher" + name,
            a + "targetActivity": "com.music.aria.MainActivity",
            a + "enabled": "true" if name == "Default" else "false",
            a + "exported": "true", a + "icon": "@0x7f010001"})
        intent = ET.SubElement(alias, "intent-filter")
        ET.SubElement(intent, "action", {a + "name": "android.intent.action.MAIN"})
        ET.SubElement(intent, "category", {a + "name": "android.intent.category.LAUNCHER"})
    return ET.tostring(root, encoding="unicode")


class ProductionApkContracts(unittest.TestCase):
    def test_manifest_requires_all_aliases_and_only_default_enabled(self):
        valid = manifest_fixture()
        self.assertEqual(len(module.verify_manifest(valid)), 13)
        for change in (
            valid.replace('.LauncherGlassDark"', '.Unexpected"', 1),
            valid.replace('enabled="true"', 'enabled="false"', 1),
            valid.replace('enabled="false"', 'enabled="true"', 1),
            valid.replace('targetActivity="com.music.aria.MainActivity"', 'targetActivity=".Other"', 1),
            valid.replace('exported="true"', 'exported="false"', 1),
            valid.replace('android.intent.category.LAUNCHER', 'android.intent.category.DEFAULT', 1),
        ):
            self.assertNotEqual(change, valid)
            with self.assertRaises(ValueError):
                module.verify_manifest(change)

    def test_signer_output_formats_and_fail_closed(self):
        digest = "aa" * 32
        numbered = "Signer #1 certificate SHA-256 digest: " + digest + "\n"
        ranged = "Signer (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: " + digest + "\n"
        scheme = "V3.0 Signer: certificate SHA-256 digest: " + digest + "\n"
        for rows in (numbered, ranged, scheme, ranged + scheme):
            module.verify_signers("Number of signers: 1\n" + rows, digest, "fixture.apk")
        for invalid in (
            "Number of signers: 1\n",
            "Number of signers: 2\n" + numbered,
            numbered,
            "Number of signers: 1\n" + numbered + ranged.replace(digest, "bb" * 32),
            "Number of signers: 1\n" + numbered + "Signer unexpected certificate SHA-256 digest: " + digest,
        ):
            with self.assertRaises(ValueError):
                module.verify_signers(invalid, digest, "fixture.apk")

    def test_fingerprint_requires_sha256(self):
        self.assertEqual(module.fingerprint("AA:" * 31 + "AA"), "aa" * 32)
        for value in ("", "garbage", "aa" * 20):
            with self.assertRaises(ValueError):
                module.fingerprint(value)

    def test_inspection_rejects_wrong_package_certificate_and_native_inventory(self):
        badging = ("package: name='com.music.aria' versionCode='28' versionName='1.8.1'\n"
                   "sdkVersion:'26'\ntargetSdkVersion:'36'\napplication-label:'Aria'\n"
                   "launchable-activity: name='com.music.aria.MainActivity'\n")
        cert = "Number of signers: 1\nSigner #1 certificate SHA-256 digest: " + "aa" * 32 + "\n"
        with tempfile.TemporaryDirectory() as temp:
            apk = Path(temp) / "test.apk"
            with ZipFile(apk, "w") as archive:
                for name in module.LIBS:
                    archive.writestr("lib/arm64-v8a/" + name, b"fixture")
            with patch.object(module, "run", side_effect=[badging, manifest_fixture(), cert]):
                result = module.inspect(apk, "aa" * 32, "aapt", "apksigner", "apkanalyzer")
                self.assertEqual(result["name"], "Aria-v1.8.1_arm64-v8a.apk")
            for invalid in (badging.replace("com.music.aria'", "com.other.app'", 1),
                            badging + "application-debuggable\n"):
                with patch.object(module, "run", side_effect=[invalid, manifest_fixture(), cert]):
                    with self.assertRaises(ValueError):
                        module.inspect(apk, "aa" * 32, "aapt", "apksigner", "apkanalyzer")
            with patch.object(module, "run", side_effect=[badging, manifest_fixture(), cert]):
                with self.assertRaises(ValueError):
                    module.inspect(apk, "bb" * 32, "aapt", "apksigner", "apkanalyzer")
            with ZipFile(apk, "a") as archive:
                archive.writestr("lib/arm64-v8a/libbitchord_dsd.so", b"fixture")
            with patch.object(module, "run", side_effect=[badging, manifest_fixture(), cert]):
                with self.assertRaises(ValueError):
                    module.inspect(apk, "aa" * 32, "aapt", "apksigner", "apkanalyzer")


if __name__ == "__main__":
    unittest.main()
