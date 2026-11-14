from __future__ import annotations

import argparse
import hashlib
import os
import re
import subprocess
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BUILD_TOOLS = Path(os.environ["ANDROID_HOME"]) / "build-tools" / "36.0.0"
EXE = ".exe" if os.name == "nt" else ""
BAT = ".bat" if os.name == "nt" else ""
APKSIGNER = BUILD_TOOLS / ("apksigner" + BAT)
AAPT2 = BUILD_TOOLS / ("aapt2" + EXE)
APKANALYZER = Path(os.environ["ANDROID_HOME"]) / "cmdline-tools/latest/bin" / ("apkanalyzer" + BAT)
ENTRY = "io.github.saycm.huya.skipad.HuyaCompatEntry"
TARGET_CLASS = "com.huya.kiwi.hyext.impl.modules.internal.HyExtAd"
AMS_HELPER = "com.duowan.kiwi.ad.helper.AmsAdHelper"
AMS_LISTENER = "com.huya.adbusiness.ams.IAmsAdRewardADListener"
REWARD_RESULT = "com.qq.e.tg.rewardAD.RewardResult"
AD_SPLASH_FRAGMENT = "com.duowan.kiwi.adsplash.view.AdSplashFragment"


def run(*args: str) -> str:
    result = subprocess.run(args, check=True, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return result.stdout


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--module", type=Path, required=True)
    parser.add_argument(
        "--target",
        type=Path,
        required=True,
    )
    args = parser.parse_args()

    assert args.module.is_file(), f"module not found: {args.module}"
    with zipfile.ZipFile(args.module) as apk:
        names = set(apk.namelist())
        assert "classes.dex" in names
        assert apk.read("assets/xposed_init").decode("ascii").splitlines() == [ENTRY]

    manifest = run(str(AAPT2), "dump", "xmltree", "--file", "AndroidManifest.xml", str(args.module))
    assert 'package="io.github.saycm.huya.skipad"' in manifest
    assert '"xposedmodule"' in manifest
    assert 'true' in manifest

    resources = run(str(AAPT2), "dump", "resources", str(args.module))
    assert '"com.duowan.kiwi"' in resources

    defined = run(str(APKANALYZER), "dex", "packages", "--defined-only", str(args.module))
    assert ENTRY.replace(".", "/") in defined or "io.github.saycm.huya.skipad" in defined
    assert not re.search(r"^C\s+d\s+.*\tde\.robv\.android\.xposed", defined, re.MULTILINE), \
        "module bundles Xposed API classes"

    entry_code = run(str(APKANALYZER), "dex", "code", "--class", ENTRY, str(args.module))
    for expected in (
        '"com.duowan.kiwi"',
        '"com.huya.kiwi.hyext.impl.modules.internal.HyExtAd"',
        '"showRewardedVideoAd"',
        '"rewardedVideoAdId"',
        '"rewardedVideoAdClose"',
        '"isEnded"',
        '"dispatchEvent"',
        '"onSuccess"',
        f'"{AMS_HELPER}"',
        '"onCallAmsRewardAd"',
        f'"{AMS_LISTENER}"',
        f'"{REWARD_RESULT}"',
        f'"{AD_SPLASH_FRAGMENT}"',
        '"onVisibleToUser"',
        '"onADShow"',
        '"onADExpose"',
        '"onADComplete"',
        '"onReward"',
        '"onADClose"',
        '"com.duowan.kiwi.adsplash.controller.AdRouter"',
        '"isNeedShowAdSplash"',
        '"com.duowan.kiwi.ad.impl.HyAdModule"',
        '"isNeedShieldAdOutLivingRoom"',
        '"com.duowan.kiwi.listframe.adapter.ListLineRecyclerViewAdapter"',
        '"com.duowan.kiwi.listline.ListLineAdapter"',
        '"com.duowan.kiwi.list.patface.chain.PatFaceInterceptorChain"',
    ):
        assert expected in entry_code, f"compat DEX is missing {expected}"

    splash_factory = re.search(
        r"\.method private static hookSplash\b.*?\.end method", entry_code, re.DOTALL
    )
    assert splash_factory, "splash hook registration not found"
    splash_class = re.search(r"new-instance \w+, L([^;]+\$\d+);", splash_factory.group())
    assert splash_class, "splash callback class not found"
    splash_hook_code = run(
        str(APKANALYZER), "dex", "code", "--class",
        splash_class.group(1).replace("/", "."), str(args.module)
    )
    for expected in ('"dismissWhenError"', '"module_skip_splash"'):
        assert expected in splash_hook_code, f"splash hook DEX is missing {expected}"

    target_code = run(str(APKANALYZER), "dex", "code", "--class", TARGET_CLASS, str(args.target))
    assert (
        ".method public final showRewardedVideoAd(Ljava/lang/Object;"
        "Lcom/huya/hybrid/webview/jssdk/JsCallback;)V"
    ) in target_code
    assert '"rewardedVideoAdClose"' in target_code
    assert '"isEnded"' in target_code

    ams_helper_code = run(str(APKANALYZER), "dex", "code", "--class", AMS_HELPER, str(args.target))
    assert (
        ".method public onCallAmsRewardAd(Ljava/util/Map;Lcom/duowan/HUYA/AdInfo;"
        "Landroid/app/Activity;Lkotlin/jvm/functions/Function1;"
        "Lcom/huya/adbusiness/ams/IAmsAdRewardADListener;Ljava/util/Map;)V"
    ) in ams_helper_code

    reward_result_code = run(
        str(APKANALYZER), "dex", "code", "--class", REWARD_RESULT, str(args.target)
    )
    assert ".method public constructor <init>()V" in reward_result_code
    assert ".method public setPassthrough(Ljava/lang/String;)V" in reward_result_code

    splash_code = run(
        str(APKANALYZER), "dex", "code", "--class", AD_SPLASH_FRAGMENT, str(args.target)
    )
    assert (
        ".method public onCreateView(Landroid/view/LayoutInflater;"
        "Landroid/view/ViewGroup;Landroid/os/Bundle;)Landroid/view/View;"
    ) in splash_code
    assert ".method public onVisibleToUser()V" in splash_code
    assert ".method public dismissWhenError(Ljava/lang/String;)V" in splash_code

    display_interfaces = {
        "com.duowan.kiwi.adsplash.controller.AdRouter": (
            ".method public final isNeedShowAdSplash(Landroid/content/Context;ZZZ)I",
        ),
        "com.duowan.kiwi.ad.impl.HyAdModule": (
            ".method public isNeedShieldAdOutLivingRoom()Z",
        ),
        "com.duowan.kiwi.list.patface.chain.PatFaceInterceptorChain": (
            ".method public final proceed(I)Z",
        ),
        "com.duowan.kiwi.listframe.component.LineItem": (
            ".method public getLineItem()Landroid/os/Parcelable;",
        ),
        "com.duowan.kiwi.listframe.adapter.ListLineRecyclerViewAdapter": (
            ".method public replaceAndNotify(Ljava/util/List;)V",
            ".method public insertAndNotify(Lcom/duowan/kiwi/listframe/component/LineItem;I)V",
        ),
        "com.duowan.kiwi.listline.ListLineAdapter": (
            ".method public replaceAndNotify(Ljava/util/List;)V",
            ".method public insertItemAndNotify(Lcom/duowan/kiwi/listframe/component/LineItem;I)V",
        ),
    }
    for class_name, methods in display_interfaces.items():
        code = run(str(APKANALYZER), "dex", "code", "--class", class_name, str(args.target))
        for method in methods:
            assert method in code, f"target interface is missing {class_name}: {method}"

    signature = run(str(APKSIGNER), "verify", "--verbose", str(args.module))
    assert "Verifies" in signature
    assert "Verified using v2 scheme (APK Signature Scheme v2): true" in signature or \
        "Verified using v3 scheme (APK Signature Scheme v3): true" in signature

    digest = hashlib.sha256(args.module.read_bytes()).hexdigest().upper()
    print(f"Verified module: {args.module}")
    print(f"SHA-256: {digest}")


if __name__ == "__main__":
    main()
