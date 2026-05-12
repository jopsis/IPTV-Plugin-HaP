import builtins
import gc
import os
import sys
import threading
import time
import traceback
import types
from datetime import datetime
from functools import wraps
from io import BytesIO, StringIO

ACE_ROOT = os.environ.get("ACE_ROOT", os.getcwd())
CACHE_DIR = os.environ.get("ACE_CACHE_DIR", os.path.join(ACE_ROOT, "cache"))

def root_path(*parts):
    return os.path.join(ACE_ROOT, *parts)

sys.path.insert(0, root_path("modules.zip"))
sys.path.insert(0, root_path("python", "lib", "stdlib"))
sys.path.insert(0, root_path("python", "lib", "modules"))
sys.path.insert(0, root_path("data"))
sys.path.insert(0, root_path("eggs-unpacked"))
sys.path.insert(0, root_path("lib"))

import json

def load_android_info():
    info_path = os.environ.get("ACE_ANDROID_INFO")
    if not info_path:
        return {}
    try:
        with open(info_path, "r") as handle:
            return json.load(handle)
    except Exception:
        return {}

ANDROID_INFO = load_android_info()
ANDROID_PATHS = ANDROID_INFO.get("paths", {})
ANDROID_MEMORY = ANDROID_INFO.get("memory", {})
ANDROID_STORAGE = ANDROID_INFO.get("storage", {})
ANDROID_DEVICE = ANDROID_INFO.get("device", {})

CACHE_DIR = os.environ.get("ACE_CACHE_DIR", ANDROID_PATHS.get("cacheDir", os.path.join(ACE_ROOT, "cache")))
ANDROID_DATA_DIR = os.environ.get("ANDROID_DATA", ANDROID_PATHS.get("androidDataDir", root_path("android-data")))
TEMP_DIR = os.environ.get("TEMP", ANDROID_PATHS.get("tempDir", root_path("tmp")))

os.environ.setdefault("ACESTREAM_HOME", ACE_ROOT)
os.environ.setdefault("ANDROID_ROOT", "/system")
os.environ.setdefault("ANDROID_DATA", ANDROID_DATA_DIR)
os.environ.setdefault("PYTHONHOME", root_path("python"))
os.environ.setdefault("TEMP", TEMP_DIR)

os.makedirs(CACHE_DIR, exist_ok=True)
os.makedirs(os.environ["ANDROID_DATA"], exist_ok=True)
os.makedirs(os.environ["TEMP"], exist_ok=True)

real_open = builtins.open

def int_value(source, key, fallback):
    try:
        value = int(source.get(key, fallback))
    except Exception:
        return fallback
    return value if value > 0 else fallback

TOTAL_MEMORY = int_value(ANDROID_MEMORY, "totalBytes", 512 * 1024 * 1024)
AVAILABLE_MEMORY = int_value(ANDROID_MEMORY, "availableBytes", TOTAL_MEMORY // 2)
PAGE_SIZE = os.sysconf("SC_PAGE_SIZE") if "SC_PAGE_SIZE" in os.sysconf_names else 4096
if PAGE_SIZE <= 0:
    PAGE_SIZE = 4096
TOTAL_PAGES = max(1, TOTAL_MEMORY // PAGE_SIZE)
AVAILABLE_PAGES = max(1, AVAILABLE_MEMORY // PAGE_SIZE)
MEM_TOTAL_KB = max(1, TOTAL_MEMORY // 1024)
MEM_FREE_KB = max(1, AVAILABLE_MEMORY // 1024)
CACHED_KB = max(1, min(MEM_TOTAL_KB // 4, MEM_FREE_KB // 2))
BUFFERS_KB = max(1, min(MEM_TOTAL_KB // 32, MEM_FREE_KB // 8))
ARCH = ANDROID_DEVICE.get("arch", "arm64-v8a")
CPU_ARCH = "8" if ARCH == "arm64-v8a" else "7"

SYNTHETIC_PROC = {
    "/proc/cpuinfo": (
        "processor\t: 0\n"
        "BogoMIPS\t: 38.40\n"
        "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics\n"
        "CPU implementer\t: 0x41\n"
        "CPU architecture: {}\n".format(CPU_ARCH) +
        "CPU variant\t: 0x0\n"
        "CPU part\t: 0xd03\n"
        "CPU revision\t: 4\n"
        "\n"
    ),
    "/proc/loadavg": "0.00 0.00 0.00 1/128 1\n",
    "/proc/meminfo": (
        "MemTotal:     {:>10} kB\n"
        "MemFree:      {:>10} kB\n"
        "Buffers:      {:>10} kB\n"
        "Cached:       {:>10} kB\n"
        "SwapTotal:             0 kB\n"
        "SwapFree:              0 kB\n"
    ).format(MEM_TOTAL_KB, MEM_FREE_KB, BUFFERS_KB, CACHED_KB),
    "/proc/stat": (
        "cpu  100 0 100 100000 0 0 0 0 0 0\n"
        "cpu0 100 0 100 100000 0 0 0 0 0 0\n"
        "intr 0\n"
        "ctxt 0\n"
        "btime 1700000000\n"
        "processes 1\n"
        "procs_running 1\n"
        "procs_blocked 0\n"
        "softirq 0 0 0 0 0 0 0 0 0 0 0\n"
    ),
    "/proc/uptime": "100000.00 100000.00\n",
}

def patched_open(path, *args, **kwargs):
    normalized = os.fspath(path) if isinstance(path, (str, bytes, os.PathLike)) else path
    if isinstance(normalized, bytes):
        normalized = normalized.decode(errors="ignore")
    if isinstance(normalized, str) and normalized in SYNTHETIC_PROC:
        mode = args[0] if args else kwargs.get("mode", "r")
        data = SYNTHETIC_PROC[normalized]
        if "b" in mode:
            return BytesIO(data.encode())
        return StringIO(data)
    return real_open(path, *args, **kwargs)

builtins.open = patched_open

original_sysconf = os.sysconf

def patched_sysconf(name):
    try:
        if name in ["SC_PHYS_PAGES", 30]:
            return TOTAL_PAGES
        if name in ["SC_AVPHYS_PAGES", 40]:
            return AVAILABLE_PAGES
    except Exception:
        pass
    return original_sysconf(name)

os.sysconf = patched_sysconf

def log(message):
    line = "{}|{}|bootstrap|{}".format(
        datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        threading.current_thread().name,
        message,
    )
    print(line, flush=True)
    try:
        with real_open(os.path.join(CACHE_DIR, "acestream.log"), "a") as handle:
            handle.write(line + "\n")
    except Exception:
        pass

def cache_monitor():
    while True:
        try:
            total_size = 0
            for dirpath, _, filenames in os.walk(CACHE_DIR):
                for name in filenames:
                    total_size += os.path.getsize(os.path.join(dirpath, name))
            if total_size > 0:
                log("cache {:.2f} MB".format(total_size / (1024 * 1024)))
        except Exception:
            pass
        time.sleep(15)

def patch_player_config():
    patched_ids = set()
    while True:
        for obj in gc.get_objects():
            try:
                oid = id(obj)
                if oid in patched_ids:
                    continue
                cls_name = type(obj).__name__
                if cls_name == "CoreApp" and hasattr(obj, "set_playerconfig"):
                    log("CoreApp detected; applying cache settings")
                    obj.set_playerconfig("live_cache_type", "disk")
                    obj.set_playerconfig("live_cache_size", 1048570000)
                    obj.set_playerconfig("download_dir", CACHE_DIR)
                    original_set = obj.set_playerconfig

                    @wraps(original_set)
                    def wrapped_set(key, value, *args, **kwargs):
                        if key == "live_cache_type":
                            value = "disk"
                        if key == "download_dir":
                            value = CACHE_DIR
                        return original_set(key, value, *args, **kwargs)

                    obj.set_playerconfig = wrapped_set
                    try:
                        setter = getattr(obj, "set_epg_system_sources_enabled", None)
                        if setter:
                            setter(False)
                        obj.get_epg_system_sources_enabled = lambda *args, **kwargs: False
                        log("CoreApp system EPG disabled")
                    except Exception as exc:
                        log("failed to disable CoreApp system EPG: {}".format(exc))
                    patched_ids.add(oid)
            except Exception:
                pass
        time.sleep(0.5)

threading.Thread(target=cache_monitor, name="cache-monitor", daemon=True).start()
threading.Thread(target=patch_player_config, name="ace-patcher", daemon=True).start()

def core_params():
    params = list(sys.argv)
    conf_file = os.path.join(CACHE_DIR, "acestream.conf")
    parsed_params = []
    if os.path.isfile(conf_file):
        import argparse
        parser = argparse.ArgumentParser(prog="acestream", fromfile_prefix_chars="@")
        try:
            _, parsed_params = parser.parse_known_args(["@" + conf_file])
        except Exception as exc:
            log("failed to load conf file: {}".format(exc))
    if "--client-console" not in params:
        params.append("--client-console")
    if parsed_params:
        params.extend(parsed_params)
    return params

def start_core():
    from acestreamengine import Core
    params = core_params()
    log("starting acestreamengine.Core")
    Core.run(params)

def install_android_compat_module():
    module = types.ModuleType("app_bridge")

    class NativeAndroid(object):
        def __init__(self, addr=None):
            self.use_fake_host = False

        def _native_rpc(self, method, *args):
            if method in ["getAceStreamHome", "getLogsDir", "getCacheDir"]:
                return CACHE_DIR
            if method in ["getRAMSize", "getMaxMemory", "getTotalMemory", "getSystemMemory", "getMemorySize"]:
                return TOTAL_MEMORY
            if method == "getMemoryClass":
                return int_value(ANDROID_MEMORY, "memoryClassMb", 64)
            if method == "adjustCacheSettings":
                return json.dumps({
                    "live_cache_type": "disk",
                    "cache_dir": CACHE_DIR,
                    "live_cache_size": 1048570000,
                })
            if method == "getDeviceId":
                return ANDROID_DEVICE.get("deviceId", "android")
            if method == "getAppId":
                return ANDROID_DEVICE.get("appId", "android")
            if method == "getAppVersionCode":
                return ANDROID_INFO.get("app", {}).get("aceCompatVersionCode", "302131302")
            if method == "getArch":
                return ANDROID_DEVICE.get("arch", ARCH)
            if method == "getDeviceABI":
                return ANDROID_DEVICE.get("deviceAbi", ARCH)
            if method == "getDeviceManufacturer":
                return ANDROID_DEVICE.get("manufacturer", "Android")
            if method == "getDeviceModel":
                return ANDROID_DEVICE.get("model", "Android")
            if method == "getDeviceName":
                return ANDROID_DEVICE.get("deviceName", "Android")
            if method == "getDeviceProductName":
                return ANDROID_DEVICE.get("productName", "Android")
            if method == "getDisplayLanguage":
                return ANDROID_DEVICE.get("displayLanguage", "en")
            if method == "getLocale":
                return ANDROID_DEVICE.get("locale", "en-US")
            if method == "isAndroidTv":
                return bool(ANDROID_DEVICE.get("isAndroidTv", False))
            if method == "hasBrowser":
                return bool(ANDROID_DEVICE.get("hasBrowser", False))
            if method == "hasWebView":
                return bool(ANDROID_DEVICE.get("hasWebView", False))
            if method == "getAppInfo":
                return json.dumps({
                    "appId": ANDROID_DEVICE.get("appId", "android"),
                    "appVersionCode": ANDROID_INFO.get("app", {}).get("aceCompatVersionCode", "302131302"),
                    "deviceId": ANDROID_DEVICE.get("deviceId", "android"),
                    "arch": ANDROID_DEVICE.get("arch", ARCH),
                    "locale": ANDROID_DEVICE.get("locale", "en-US"),
                    "isAndroidTv": bool(ANDROID_DEVICE.get("isAndroidTv", False)),
                    "hasBrowser": bool(ANDROID_DEVICE.get("hasBrowser", False)),
                    "hasWebView": bool(ANDROID_DEVICE.get("hasWebView", False)),
                })
            if method.endswith("BlockSize"):
                return int_value(ANDROID_STORAGE, "cacheBlockSizeBytes", 4096)
            if method.endswith("AvailableBlocks"):
                return int_value(ANDROID_STORAGE, "cacheAvailableBlocks", 200000000)
            if method.endswith("BlockCount") or method.endswith("Blocks"):
                return int_value(ANDROID_STORAGE, "cacheBlockCount", 200000000)
            if method in ["makeToast", "onSettingsUpdated", "onEvent", "publishFileReceiverState", "onAuthUpdated"]:
                return None
            return None

        def __getattr__(self, name):
            def rpc_call(*args):
                return self._native_rpc(name, *args)
            return rpc_call

    module.Android = NativeAndroid
    sys.modules["app_bridge"] = module

def patch_typing_for_android():
    try:
        import typing
    except Exception as exc:
        log("failed to import typing for Android patch: {}".format(exc))
        return

    fallback = getattr(typing, "Any", object)
    patched = []

    def safe_getitem_factory(cls_name, original_getitem):
        @wraps(original_getitem)
        def safe_getitem(self, parameters):
            try:
                return original_getitem(self, parameters)
            except SystemError as exc:
                log("typing annotation fallback in {}: {}".format(cls_name, exc))
                return fallback

        safe_getitem._android_safe_typing = True
        return safe_getitem

    for cls_name in [
        "_SpecialForm",
        "_SpecialGenericAlias",
        "_GenericAlias",
        "_VariadicGenericAlias",
        "_CallableGenericAlias",
    ]:
        cls = getattr(typing, cls_name, None)
        if cls is None:
            continue
        original_getitem = getattr(cls, "__getitem__", None)
        if original_getitem is None or getattr(original_getitem, "_android_safe_typing", False):
            continue
        try:
            cls.__getitem__ = safe_getitem_factory(cls_name, original_getitem)
            patched.append(cls_name)
        except Exception as exc:
            log("failed to patch typing {}: {}".format(cls_name, exc))

    if patched:
        log("Android typing compatibility patched: {}".format(",".join(patched)))

def patch_sqlite_cachedb_close():
    try:
        from ACEStream.Core.CacheDB import SqliteCacheDBHandler
        handler_cls = getattr(SqliteCacheDBHandler, "BasicDBHandler", None)
        if handler_cls is None:
            return
        original_close = getattr(handler_cls, "close", None)
        if original_close is None or getattr(original_close, "_android_safe_close", False):
            return

        @wraps(original_close)
        def safe_close(self, *args, **kwargs):
            try:
                return original_close(self, *args, **kwargs)
            except ValueError as exc:
                if "list.remove" in str(exc):
                    log("SQLite cache DB close ignored stale registration")
                    return None
                raise

        safe_close._android_safe_close = True
        handler_cls.close = safe_close
        log("SQLite cache DB close patched")
    except Exception as exc:
        log("failed to patch SQLite cache DB close: {}".format(exc))

def disable_system_epg_tasks():
    try:
        from ACEStream.Core.Tasks import UpdateLocalSystemEpgTask, UpdateSystemEpgTask

        def skip_system_epg_task(self, *args, **kwargs):
            logger = getattr(self, "logger", None)
            if logger:
                logger.info("system EPG task disabled by Android bootstrap")
            return None

        UpdateLocalSystemEpgTask.run = skip_system_epg_task
        UpdateSystemEpgTask.run = skip_system_epg_task
        UpdateSystemEpgTask.do_update = skip_system_epg_task
        log("system EPG tasks disabled")
    except Exception as exc:
        log("failed to disable system EPG tasks: {}".format(exc))

try:
    install_android_compat_module()
    patch_typing_for_android()
    patch_sqlite_cachedb_close()
    disable_system_epg_tasks()
    log("AceStream home: {}".format(CACHE_DIR))
    if ANDROID_INFO:
        log("Android runtime info: abi={} model={} locale={}".format(
            ANDROID_DEVICE.get("arch", "unknown"),
            ANDROID_DEVICE.get("model", "unknown"),
            ANDROID_DEVICE.get("locale", "unknown"),
        ))

    try:
        import aceserve
        log("starting aceserve.main")
        os.makedirs(os.path.join(CACHE_DIR, ".ACEStream"), exist_ok=True)
        aceserve.main()
    except ImportError as exc:
        log("aceserve unavailable; falling back to Core: {}".format(exc))
        start_core()
except Exception as exc:
    log("startup failed: {}".format(exc))
    traceback.print_exc()
    raise
