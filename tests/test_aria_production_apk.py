import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from zipfile import ZipFile

spec = importlib.util.spec_from_file_location(
    "aria_apks", Path(__file__).resolve().parents[1] / "scripts/verify-aria-production-apks.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ProductionApkContracts(unittest.TestCase):
    def test_fingerprint_requires_sha256(self):
        self.assertEqual(module.fingerprint("AA:" * 31 + "AA"), "aa" * 32)
        for value in ("", "garbage", "aa" * 20):
            with self.assertRaises(ValueError):
                module.fingerprint(value)

    def test_inspection_rejects_wrong_package_certificate_and_native_inventory(self):
        badging = ("package: name='com.music.aria' versionCode='28' versionName='1.8.1'\n"
                   "sdkVersion:'26'\ntargetSdkVersion:'36'\napplication-label:'Aria'\n"
                   "launchable-activity: name='com.music.aria.LauncherDefault'\n")
        cert = "Signer #1 certificate SHA-256 digest: " + "aa" * 32 + "\n"
        with tempfile.TemporaryDirectory() as temp:
            apk = Path(temp) / "test.apk"
            with ZipFile(apk, "w") as archive:
                for name in module.LIBS:
                    archive.writestr("lib/arm64-v8a/" + name, b"fixture")
            with patch.object(module, "run", side_effect=[badging, cert]):
                result = module.inspect(apk, "aa" * 32, "aapt", "apksigner")
                self.assertEqual(result["name"], "Aria-v1.8.1_arm64-v8a.apk")
            for invalid in (badging.replace("com.music.aria'", "com.other.app'", 1),
                            badging + "application-debuggable\n"):
                with patch.object(module, "run", side_effect=[invalid, cert]):
                    with self.assertRaises(ValueError):
                        module.inspect(apk, "aa" * 32, "aapt", "apksigner")
            with patch.object(module, "run", side_effect=[badging, cert]):
                with self.assertRaises(ValueError):
                    module.inspect(apk, "bb" * 32, "aapt", "apksigner")
            with ZipFile(apk, "a") as archive:
                archive.writestr("lib/arm64-v8a/libbitchord_dsd.so", b"fixture")
            with patch.object(module, "run", side_effect=[badging, cert]):
                with self.assertRaises(ValueError):
                    module.inspect(apk, "aa" * 32, "aapt", "apksigner")


if __name__ == "__main__":
    unittest.main()
