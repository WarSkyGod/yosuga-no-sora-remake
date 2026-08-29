#!/usr/bin/env python3
"""Complete the OpenHarmony SDK device definitions for HarmonyOS device types.

The OpenHarmony SDK bundled with the HarmonyOS command line tools only
defines open-source device types (default/tablet/car/...), while the module
declares phone/tablet/2in1 like any HarmonyOS NEXT app. Without a
definition hvigor's SysCapTransform computes an empty capability-set
intersection across the declared devices and the build fails.

This script derives phone.json and 2in1.json from default.json (the
standard-system capability set) in both the ets and js API trees. Every
capability this app requires is a generic one present on phones, tablets,
and 2-in-1 PCs alike, so the intersection stays non-empty and the built HAP
installs on all three device forms.
"""

import json
import os
import sys


def main() -> int:
    sdk = os.environ.get("OHOS_SDK_ROOT", "")
    api = os.environ.get("OHOS_SDK_API", "")
    if not sdk or not api:
        print("error: OHOS_SDK_ROOT / OHOS_SDK_API are not set", file=sys.stderr)
        return 1
    for base in ("ets", "js"):
        dd = os.path.join(sdk, api, base, "api", "device-define")
        src = os.path.join(dd, "default.json")
        if not os.path.exists(src):
            print("skip (no default.json):", dd)
            continue
        with open(src, encoding="utf-8") as fh:
            caps = json.load(fh)
        for dev in ("phone", "2in1"):
            path = os.path.join(dd, dev + ".json")
            if os.path.exists(path):
                print("already present:", path)
                continue
            with open(path, "w", encoding="utf-8") as fh:
                json.dump(caps, fh)
            print("generated:", path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
