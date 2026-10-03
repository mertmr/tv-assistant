#!/usr/bin/env python3
"""Install the APK and optionally enable explicitly requested TV permissions."""
import argparse
import pathlib
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--device", required=True, help="ADB serial, e.g. 192.168.1.104:5555")
parser.add_argument("--port", default="5038", help="ADB server port")
parser.add_argument("--enable-navigation", action="store_true")
parser.add_argument("--enable-playback", action="store_true")
parser.add_argument("--disable-navigation", action="store_true")
parser.add_argument("--disable-playback", action="store_true")
parser.add_argument("--no-launch", action="store_true", help="Update without interrupting the foreground app")
args = parser.parse_args()
adb = ["adb", "-P", args.port, "-s", args.device]

def run(*values):
    return subprocess.run(adb + list(values), check=True, text=True, capture_output=True).stdout.strip()

apk = pathlib.Path(__file__).resolve().parent / "dist" / "tv-assistant.apk"
print(run("install", "-r", str(apk)))
navigation = "dev.mert.tvassistant/dev.mert.tvassistant.NavigationService"
playback = "dev.mert.tvassistant/dev.mert.tvassistant.PlaybackService"
if args.enable_navigation or args.disable_navigation:
    old = run("shell", "settings", "get", "secure", "enabled_accessibility_services")
    services = [s for s in old.split(":") if s and s != "null" and s != navigation]
    # Rebind our service after an upgrade without disabling other accessibility services.
    run("shell", "settings", "put", "secure", "enabled_accessibility_services", ":".join(services) or "null")
    if args.enable_navigation:
        services.append(navigation)
    run("shell", "settings", "put", "secure", "enabled_accessibility_services", ":".join(services) or "null")
    if services:
        run("shell", "settings", "put", "secure", "accessibility_enabled", "1")
    print("Navigation enabled." if args.enable_navigation else "Navigation disabled.")
if args.enable_playback or args.disable_playback:
    run("shell", "cmd", "notification", "allow_listener" if args.enable_playback else "disallow_listener", playback, "0")
    print("Playback access enabled." if args.enable_playback else "Playback access disabled.")
if not args.no_launch:
    print(run("shell", "am", "start", "-W", "-n", "dev.mert.tvassistant/.MainActivity"))
