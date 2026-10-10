"""只验收已安装的示例 release；保留应用数据，强停两次，写入约 3 MiB 测试日志。"""
import argparse
import gzip
import hashlib
import json
import locale
import re
import subprocess
import time
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", required=True)
parser.add_argument("--output", required=True, type=Path, help="新的结果目录，不能已存在")
parser.add_argument("--android-cli", default="android")
parser.add_argument("--root-emulator", action="store_true", help="仅对可 root 的测试模拟器启用 root ADB，以读取隔离的外部日志")
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=False)
PACKAGE = "com.ttxz.base"

def run(command):
    result = subprocess.run(command, capture_output=True, timeout=60)
    def decode(value):
        try:
            return value.decode("utf-8")
        except UnicodeDecodeError:
            # Windows 上 android CLI 可能按系统代码页输出中文；不能用 replace 损坏控件标签。
            return value.decode(locale.getencoding())
    result.stdout = decode(result.stdout)
    result.stderr = decode(result.stderr)
    if result.returncode:
        raise RuntimeError(f"Command failed: {command!r}\n{result.stdout}\n{result.stderr}")
    return result.stdout

def adb(*command):
    return run(["adb", "-s", args.serial, *command])

def layout():
    return json.loads(run([args.android_cli, "layout", "--device=" + args.serial]))

def scroll(nodes):
    container = next(n for n in nodes if "scrollable" in n.get("interactions", []))
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", container["bounds"]))
    adb("shell", "input", "swipe", str((x1+x2)//2), str(y1+(y2-y1)*3//4),
        str((x1+x2)//2), str(y1+(y2-y1)//4), "500")

def tap(label):
    for _ in range(12):
        nodes = layout()
        matches = [n for n in nodes if n.get("text") == label and not n.get("off-screen", False)]
        if matches:
            node = matches[-1]
            x, y = re.findall(r"\d+", node["center"])
            containers = [n for n in nodes if "scrollable" in n.get("interactions", [])]
            if containers:
                bounds = list(map(int, re.findall(r"\d+", containers[0]["bounds"])))
                if not bounds[1]+24 <= int(y) <= bounds[3]-24:
                    scroll(nodes)
                    continue
            adb("shell", "input", "tap", x, y)
            return
        scroll(nodes)
    raise RuntimeError("Control not reachable: " + label)

def wait_text(fragment):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        texts = [n.get("text", "") for n in layout()]
        for text in texts:
            if fragment in text:
                return text
        if any("检查未通过" in text for text in texts):
            raise RuntimeError("Check failed: " + str(texts))
    raise RuntimeError("Result not observed: " + fragment)

def open_logs():
    adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")
    wait_text("日志测试")
    tap("日志测试")

def require(condition, message):
    if not condition:
        raise RuntimeError(message)

if args.root_emulator:
    require(args.serial.startswith("emulator-") and adb("shell", "getprop", "ro.kernel.qemu").strip() == "1",
            "Root option is restricted to test emulators")
    root_output = adb("root")
    require("cannot" not in root_output.lower(), root_output)
    adb("wait-for-device")
api = adb("shell", "getprop", "ro.build.version.sdk").strip()
flags = adb("shell", "dumpsys", "package", PACKAGE)
require("DEBUGGABLE" not in flags, "Install the optimized non-debuggable release APK first")
adb("shell", "am", "force-stop", PACKAGE)
open_logs()
tap("运行接入检查")
integration = wait_text("本次文件核对通过")
require("6/6" in integration and "已启用等级全部接受：true" in integration, integration)
tap("分片与清理检查（写入约 3 MiB）")
lifecycle = wait_text("分片与清理检查通过")
match = re.search(r"标识：([0-9a-f-]{36})", lifecycle)
require(match is not None, "Missing lifecycle run id")
run_id = match.group(1)
# 从实际初始化反馈中取得目录，不假定写入路径。
adb("shell", "am", "force-stop", PACKAGE)
open_logs()
directory_text = wait_text("日志保存目录：")
directory = directory_text.split("日志保存目录：", 1)[1].strip()
require(directory.startswith("/storage/") and "/" + PACKAGE + "/files/" in directory,
        "Unexpected sample log directory: " + directory)
# 首次停止后保存历史文件；后续再次新写入前比较这些字节仍完整保留。
adb("pull", directory, str(args.output / "before"))
tap("运行接入检查")
restarted = wait_text("本次文件核对通过")
require("6/6" in restarted, restarted)
adb("pull", directory, str(args.output / "after"))
checks = []
def log_files(directory):
    return sorted(file for file in directory.iterdir() if file.is_file() and
                  (file.name.endswith(".log") or file.name.endswith(".log.gz")))

def open_log(file, **options):
    return gzip.open(file, **options) if file.name.endswith(".gz") else file.open(**options)

for old in log_files(args.output / "before"):
    new = args.output / "after" / old.name
    if not new.exists() and not old.name.endswith(".gz"):
        new = args.output / "after" / (old.name + ".gz")
    digest = hashlib.sha256()
    with open_log(old, mode="rb") as left, open_log(new, mode="rb") as right:
        while block := left.read(8192):
            require(block == right.read(len(block)), "History changed: " + old.name)
            digest.update(block)
    checks.append({"name": old.name, "beforeBytes": old.stat().st_size,
                   "afterBytes": new.stat().st_size, "sha256Prefix": digest.hexdigest()})
require(bool(checks), "No historical files found")
records = []
pattern = re.compile(re.escape("[LogLifecycle] " + run_id + " record=") + r"(\d+) (x+)\n$")
for file in log_files(args.output / "after"):
    with open_log(file, mode="rt", encoding="utf-8") as stream:
        for line in stream:
            matched = pattern.search(line)
            if matched:
                require(len(matched[2]) == 4096, "Payload was damaged")
                records.append(int(matched[1]))
require(len(records) == 768 and set(records) == set(range(768)), "Missing or duplicate records")
result = {"api": api, "serial": args.serial, "integration": integration,
          "lifecycle": lifecycle, "restart": restarted, "history": checks,
          "verifiedRecords": len(records), "releaseDebuggable": False}
(args.output / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(result, ensure_ascii=True))
