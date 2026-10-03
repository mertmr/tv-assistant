#!/usr/bin/env python3
"""Dedicated Android TV sandbox. Never selects a physical device or uses a TV account."""
import argparse
import os
from pathlib import Path
import socket
import subprocess
import time

ROOT = Path(__file__).resolve().parent
SDK = Path(os.environ.get('ANDROID_SDK_ROOT', os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))))
AVD_NAME = 'TVAssistant_API34'
AVD_HOME = ROOT / 'build' / 'avd'
IMAGE = 'system-images;android-34;android-tv;arm64-v8a'
PORT = 5560
SERIAL = f'emulator-{PORT}'
ADB = [str(SDK / 'platform-tools' / 'adb'), '-P', '5037', '-s', SERIAL]
ENV = dict(os.environ, ANDROID_AVD_HOME=str(AVD_HOME))


def run(command, **kwargs):
    return subprocess.run(command, check=True, text=True, **kwargs)


def adb(*args, check=True):
    return subprocess.run(ADB + list(args), check=check, text=True, capture_output=True, timeout=20)


def verify():
    if adb('shell', 'getprop', 'ro.kernel.qemu').stdout.strip() != '1':
        raise SystemExit('Refusing: target is not an emulator.')
    names = adb('emu', 'avd', 'name').stdout.splitlines()
    if not names or names[0] != AVD_NAME:
        raise SystemExit('Refusing: target is not this project’s TV emulator.')


def setup():
    image = SDK / 'system-images' / 'android-34' / 'android-tv' / 'arm64-v8a' / 'package.xml'
    if not image.exists():
        run([str(SDK / 'cmdline-tools/latest/bin/sdkmanager'), IMAGE])
    AVD_HOME.mkdir(parents=True, exist_ok=True)
    if not (AVD_HOME / f'{AVD_NAME}.ini').exists():
        run([str(SDK / 'cmdline-tools/latest/bin/avdmanager'), 'create', 'avd', '-n', AVD_NAME,
             '-k', IMAGE, '-d', 'tv_1080p', '-p', str(AVD_HOME / f'{AVD_NAME}.avd')], env=ENV, input='no\n')


def start(headless):
    setup()
    if adb('get-state', check=False).returncode == 0:
        verify()
        print(f'{AVD_NAME} already running ({SERIAL}).')
        return
    # Detect conflicts before launching; never stop another emulator or application.
    for port in [PORT, PORT + 1]:
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1', port)) == 0:
                raise SystemExit(f'Port {port} is occupied; no existing process was stopped.')
    command = [str(SDK / 'emulator/emulator'), '-avd', AVD_NAME, '-port', str(PORT),
               '-no-audio', '-no-boot-anim', '-no-snapshot-load', '-no-snapshot-save',
               '-memory', '2048', '-gpu', 'auto']
    if headless:
        command += ['-no-window']
    with (ROOT / 'build/emulator.log').open('a') as log:
        process = subprocess.Popen(command, env=ENV, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    until = time.monotonic() + 180
    while time.monotonic() < until:
        if process.poll() is not None:
            raise SystemExit('Emulator exited. Inspect build/emulator.log.')
        result = adb('shell', 'getprop', 'sys.boot_completed', check=False)
        if result.returncode == 0 and result.stdout.strip() == '1':
            verify()
            print(f'Booted {AVD_NAME} ({SERIAL}), Android API 34. Host audio disabled.')
            return
        time.sleep(2)
    raise SystemExit('Emulator boot timed out. Inspect build/emulator.log.')


def install():
    verify()
    run(['python3', str(ROOT / 'install.py'), '--device', SERIAL, '--port', '5037',
         '--enable-navigation', '--enable-playback', '--no-launch'], cwd=ROOT)


def open_app():
    verify()
    navigation = 'dev.mert.tvassistant/dev.mert.tvassistant.NavigationService'
    existing = adb('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services').stdout.strip()
    others = [service for service in existing.split(':') if service and service not in ['null', navigation]]
    adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ':'.join(others) or 'null')
    adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', ':'.join(others + [navigation]))
    adb('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1')
    adb('shell', 'am', 'start', '-n', 'dev.mert.tvassistant/.MainActivity')


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('action', choices=['setup', 'start', 'install', 'open', 'test', 'stop'])
parser.add_argument('--headless', action='store_true', help='Run without a desktop window')
args = parser.parse_args()
if args.action == 'setup':
    setup()
elif args.action == 'start':
    start(args.headless)
elif args.action == 'stop':
    verify()
    adb('emu', 'kill')
elif args.action == 'open':
    open_app()
elif args.action == 'install':
    install()
else:
    start(args.headless)
    run(['bash', str(ROOT / 'build.sh')], cwd=ROOT)
    install()
    try:
        for group, report in [('emulator', 'emulator-api34-results.txt'),
                              ('emulator-native', 'emulator-native-results.txt')]:
            test_env = dict(ENV, TV_DEVICE=SERIAL, TV_ADB_PORT='5037', TV_TEST_GROUP=group,
                            TV_TEST_REPORT=str(ROOT / 'reports' / report))
            run(['bash', str(ROOT / 'test.sh')], env=test_env, cwd=ROOT)
    finally:
        # Instrumentation ends the app process and can leave navigation marked crashed.
        adb('uninstall', 'dev.mert.tvassistant.tests', check=False)
        open_app()
