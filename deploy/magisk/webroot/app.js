(function () {
  "use strict";

  var MODULE_DIR = "/data/adb/modules/global-agent-deployment-helper";
  var STATE_DIR = "/data/misc/global_agent";
  var callbackSequence = 0;
  var toastTimer = 0;
  var displayWidth = 0;
  var displayHeight = 0;
  var captureObjectUrl = "";

  var elements = {
    connectionBadge: document.getElementById("connectionBadge"),
    connectionText: document.getElementById("connectionText"),
    statusBand: document.getElementById("statusBand"),
    statusTitle: document.getElementById("statusTitle"),
    statusDetail: document.getElementById("statusDetail"),
    moduleVersion: document.getElementById("moduleVersion"),
    deviceModel: document.getElementById("deviceModel"),
    androidVersion: document.getElementById("androidVersion"),
    deviceArch: document.getElementById("deviceArch"),
    refreshButton: document.getElementById("refreshButton"),
    runButton: document.getElementById("runButton"),
    clearButton: document.getElementById("clearButton"),
    copyButton: document.getElementById("copyButton"),
    terminalOutput: document.getElementById("terminalOutput"),
    deviceToolStatus: document.getElementById("deviceToolStatus"),
    deviceToolTitle: document.getElementById("deviceToolTitle"),
    deviceToolDetail: document.getElementById("deviceToolDetail"),
    displaySize: document.getElementById("displaySize"),
    captureStage: document.getElementById("captureStage"),
    captureEmpty: document.getElementById("captureEmpty"),
    captureImage: document.getElementById("captureImage"),
    captureButton: document.getElementById("captureButton"),
    discardCaptureButton: document.getElementById("discardCaptureButton"),
    tapX: document.getElementById("tapX"),
    tapY: document.getElementById("tapY"),
    tapButton: document.getElementById("tapButton"),
    toast: document.getElementById("toast")
  };

  function hasBridge() {
    return typeof window.ksu === "object" &&
        window.ksu !== null && typeof window.ksu.exec === "function";
  }

  function setConnection(state, label) {
    elements.connectionBadge.dataset.state = state;
    elements.connectionText.textContent = label;
  }

  function setStatus(tone, title, detail) {
    elements.statusBand.dataset.tone = tone;
    elements.statusTitle.textContent = title;
    elements.statusDetail.textContent = detail;
  }

  function setDeviceToolStatus(tone, title, detail) {
    elements.deviceToolStatus.dataset.tone = tone;
    elements.deviceToolTitle.textContent = title;
    elements.deviceToolDetail.textContent = detail;
  }

  function setBusy(button, busy) {
    button.disabled = busy || !hasBridge();
    var icon = button.querySelector("img");
    if (icon) {
      icon.classList.toggle("is-spinning", busy);
    }
  }

  function showToast(message) {
    window.clearTimeout(toastTimer);
    elements.toast.textContent = message;
    elements.toast.classList.add("is-visible");
    toastTimer = window.setTimeout(function () {
      elements.toast.classList.remove("is-visible");
    }, 2400);
  }

  function writeLog(title, result) {
    var timestamp = new Date().toLocaleTimeString();
    var lines = ["[" + timestamp + "] " + title];
    if (result.stdout) {
      lines.push(result.stdout.trim());
    }
    if (result.stderr) {
      lines.push("stderr: " + result.stderr.trim());
    }
    lines.push("exit=" + result.errno);
    elements.terminalOutput.textContent = lines.join("\n");
  }

  function exec(command) {
    return new Promise(function (resolve, reject) {
      if (!hasBridge()) {
        reject(new Error("KernelSU Bridge 不可用"));
        return;
      }

      callbackSequence += 1;
      var callbackName = "__globalAgentCallback" + callbackSequence;
      var settled = false;
      var timer = window.setTimeout(function () {
        if (!settled) {
          settled = true;
          delete window[callbackName];
          reject(new Error("命令执行超时"));
        }
      }, 15000);

      window[callbackName] = function (errno, stdout, stderr) {
        if (settled) {
          return;
        }
        settled = true;
        window.clearTimeout(timer);
        delete window[callbackName];
        resolve({
          errno: Number(errno),
          stdout: String(stdout || ""),
          stderr: String(stderr || "")
        });
      };

      try {
        window.ksu.exec(command, "{}", "window." + callbackName);
      } catch (error) {
        settled = true;
        window.clearTimeout(timer);
        delete window[callbackName];
        reject(error);
      }
    });
  }

  function readModuleInfo() {
    if (!hasBridge() || typeof window.ksu.moduleInfo !== "function") {
      return;
    }
    try {
      var info = JSON.parse(window.ksu.moduleInfo());
      if (typeof info.moduleDir === "string" &&
          /^\/data\/adb\/modules\/[A-Za-z0-9._-]+$/.test(info.moduleDir)) {
        MODULE_DIR = info.moduleDir;
      }
      if (typeof info.version === "string" && info.version) {
        elements.moduleVersion.textContent = info.version;
      }
    } catch (error) {
      showToast("模块信息读取失败");
    }
  }

  function parseKeyValues(output) {
    var values = {};
    output.split("\n").forEach(function (line) {
      var separator = line.indexOf("=");
      if (separator > 0) {
        values[line.slice(0, separator)] = line.slice(separator + 1);
      }
    });
    return values;
  }

  function refreshStatus() {
    if (!hasBridge()) {
      setConnection("offline", "预览模式");
      setStatus("warning", "KernelSU Bridge 不可用", "页面已安全降级，设备操作已禁用");
      elements.runButton.disabled = true;
      elements.clearButton.disabled = true;
      elements.refreshButton.disabled = true;
      elements.captureButton.disabled = true;
      elements.discardCaptureButton.disabled = true;
      elements.tapButton.disabled = true;
      setDeviceToolStatus("warning", "KernelSU Bridge 不可用",
          "截屏和输入操作已禁用");
      elements.terminalOutput.textContent = "WebUI 已加载。\n未检测到 KernelSU JavaScript Bridge。";
      return Promise.resolve();
    }

    setBusy(elements.refreshButton, true);
    setConnection("online", "已连接");
    readModuleInfo();

    var command =
        "printf 'model=%s\\n' \"$(getprop ro.product.model)\"; " +
        "printf 'android=%s\\n' \"$(getprop ro.build.version.release)\"; " +
        "printf 'sdk=%s\\n' \"$(getprop ro.build.version.sdk)\"; " +
        "printf 'arch=%s\\n' \"$(getprop ro.product.cpu.abi)\"; " +
        "printf 'display=%s\\n' \"$(wm size | tail -n 1 | sed 's/.*: //')\"; " +
        "if [ -x '" + MODULE_DIR + "/bin/global-agentd' ]; then " +
        "printf 'binary=ready\\n'; else printf 'binary=missing\\n'; fi";

    return exec(command).then(function (result) {
      var values = parseKeyValues(result.stdout);
      elements.deviceModel.textContent = values.model || "未知";
      elements.androidVersion.textContent = values.android ?
          values.android + " / API " + (values.sdk || "?") : "未知";
      elements.deviceArch.textContent = values.arch || "未知";
      var displayMatch = /^(\d+)x(\d+)$/.exec(values.display || "");
      if (displayMatch) {
        displayWidth = Number(displayMatch[1]);
        displayHeight = Number(displayMatch[2]);
        elements.displaySize.textContent = displayWidth + " x " + displayHeight;
        elements.tapX.max = String(displayWidth - 1);
        elements.tapY.max = String(displayHeight - 1);
        elements.tapX.value = String(Math.floor(displayWidth / 2));
        elements.tapY.value = String(Math.floor(displayHeight / 2));
        setDeviceToolStatus("success", "设备工具已就绪",
            "单点输入限制在当前显示边界内");
      } else {
        displayWidth = 0;
        displayHeight = 0;
        elements.displaySize.textContent = "尺寸未知";
        setDeviceToolStatus("warning", "显示尺寸读取失败",
            "截屏可用，输入坐标将使用安全硬上限");
      }

      if (result.errno === 0 && values.binary === "ready") {
        setStatus("success", "调试核心已就绪", "可运行受限的 synthetic smoke test");
      } else {
        setStatus("error", "模块文件不完整", "未找到可执行的 arm64 调试核心");
      }
      writeLog("刷新状态", result);
    }).catch(function (error) {
      setConnection("offline", "连接失败");
      setStatus("error", "状态读取失败", error.message || String(error));
      elements.terminalOutput.textContent = String(error.stack || error);
    }).finally(function () {
      setBusy(elements.refreshButton, false);
    });
  }

  function cleanupCaptureFile() {
    if (!hasBridge()) {
      return Promise.resolve();
    }
    return exec("rm -f '" + MODULE_DIR +
        "/webroot/runtime/capture.png'").catch(function () {});
  }

  function discardCapture() {
    if (captureObjectUrl) {
      URL.revokeObjectURL(captureObjectUrl);
      captureObjectUrl = "";
    }
    elements.captureImage.removeAttribute("src");
    elements.captureImage.hidden = true;
    elements.captureEmpty.hidden = false;
    elements.captureStage.dataset.state = "empty";
    cleanupCaptureFile();
  }

  function captureScreen() {
    setBusy(elements.captureButton, true);
    setDeviceToolStatus("neutral", "正在截取屏幕", "等待 SurfaceFlinger 输出");
    var runtimeDir = MODULE_DIR + "/webroot/runtime";
    var capturePath = runtimeDir + "/capture.png";
    var command =
        "mkdir -p '" + runtimeDir + "' && chmod 0700 '" + runtimeDir + "' && " +
        "/system/bin/screencap -p '" + capturePath + "' && " +
        "chmod 0600 '" + capturePath + "'";

    exec(command).then(function (result) {
      if (result.errno !== 0) {
        throw new Error(result.stderr.trim() || "screencap 返回非零状态");
      }
      return fetch("runtime/capture.png?t=" + Date.now(), { cache: "no-store" });
    }).then(function (response) {
      if (!response.ok) {
        throw new Error("截图文件读取失败: HTTP " + response.status);
      }
      return response.blob();
    }).then(function (blob) {
      if (captureObjectUrl) {
        URL.revokeObjectURL(captureObjectUrl);
      }
      captureObjectUrl = URL.createObjectURL(blob);
      elements.captureImage.onload = function () {
        elements.captureImage.onload = null;
        cleanupCaptureFile();
      };
      elements.captureImage.src = captureObjectUrl;
      elements.captureImage.hidden = false;
      elements.captureEmpty.hidden = true;
      elements.captureStage.dataset.state = "ready";
      setDeviceToolStatus("success", "截屏完成",
          "临时文件将在预览载入后删除");
      writeLog("截取屏幕", { errno: 0, stdout: "capture loaded", stderr: "" });
      showToast("屏幕截图已更新");
    }).catch(function (error) {
      setDeviceToolStatus("error", "截屏失败", error.message || String(error));
      elements.terminalOutput.textContent = String(error.stack || error);
      cleanupCaptureFile();
      showToast("截屏失败");
    }).finally(function () {
      setBusy(elements.captureButton, false);
    });
  }

  function injectTap() {
    var x = Number(elements.tapX.value);
    var y = Number(elements.tapY.value);
    var maxX = displayWidth > 0 ? displayWidth - 1 : 100000;
    var maxY = displayHeight > 0 ? displayHeight - 1 : 100000;
    if (!Number.isInteger(x) || !Number.isInteger(y) ||
        x < 0 || y < 0 || x > maxX || y > maxY) {
      setDeviceToolStatus("error", "坐标无效",
          "请输入当前显示范围内的整数坐标");
      showToast("坐标超出显示范围");
      return;
    }

    setBusy(elements.tapButton, true);
    setDeviceToolStatus("neutral", "正在发送点击",
        "坐标 " + x + ", " + y);
    var command = "/system/bin/input touchscreen tap " + x + " " + y;
    exec(command).then(function (result) {
      writeLog("单点注入 " + x + "," + y, result);
      if (result.errno === 0) {
        setDeviceToolStatus("success", "点击已发送",
            "坐标 " + x + ", " + y);
        showToast("单点输入已发送");
      } else {
        setDeviceToolStatus("error", "输入注入失败",
            result.stderr.trim() || "input 返回非零状态");
        showToast("输入注入失败");
      }
    }).catch(function (error) {
      setDeviceToolStatus("error", "输入注入失败",
          error.message || String(error));
      elements.terminalOutput.textContent = String(error.stack || error);
      showToast("输入注入失败");
    }).finally(function () {
      setBusy(elements.tapButton, false);
    });
  }

  function runSmokeTest() {
    setBusy(elements.runButton, true);
    setStatus("neutral", "测试运行中", "正在执行四步 synthetic 验证");
    var command =
        "mkdir -p '" + STATE_DIR + "' && " +
        "chmod 0700 '" + STATE_DIR + "' && " +
        "'" + MODULE_DIR + "/bin/global-agentd' " +
        "--state '" + STATE_DIR + "/debug-state.bin' " +
        "--iterations 4 --interval-ms 5 --demo-action";

    exec(command).then(function (result) {
      writeLog("运行测试", result);
      if (result.errno === 0) {
        setStatus("success", "测试通过", result.stdout.trim() || "命令执行成功");
        showToast("Smoke test 已通过");
      } else {
        setStatus("error", "测试失败", result.stderr.trim() || "返回非零状态");
        showToast("Smoke test 失败");
      }
      selectTab("logs");
    }).catch(function (error) {
      setStatus("error", "测试失败", error.message || String(error));
      elements.terminalOutput.textContent = String(error.stack || error);
      selectTab("logs");
    }).finally(function () {
      setBusy(elements.runButton, false);
    });
  }

  function clearState() {
    if (!window.confirm("清除调试状态文件？")) {
      return;
    }
    setBusy(elements.clearButton, true);
    exec("rm -f '" + STATE_DIR + "/debug-state.bin'").then(function (result) {
      writeLog("清除状态", result);
      if (result.errno === 0) {
        showToast("调试状态已清除");
      } else {
        showToast("状态清除失败");
      }
    }).catch(function (error) {
      elements.terminalOutput.textContent = String(error.stack || error);
      showToast("状态清除失败");
    }).finally(function () {
      setBusy(elements.clearButton, false);
    });
  }

  function selectTab(name) {
    document.querySelectorAll(".tab").forEach(function (button) {
      var selected = button.dataset.tab === name;
      button.classList.toggle("is-active", selected);
      button.setAttribute("aria-selected", selected ? "true" : "false");
    });
    document.getElementById("overviewPanel").hidden = name !== "overview";
    document.getElementById("logsPanel").hidden = name !== "logs";
    document.getElementById("devicePanel").hidden = name !== "device";
  }

  function copyLogs() {
    var text = elements.terminalOutput.textContent;
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(function () {
        showToast("日志已复制");
      }).catch(function () {
        showToast("无法访问剪贴板");
      });
    } else {
      showToast("当前 WebView 不支持复制");
    }
  }

  document.querySelectorAll(".tab").forEach(function (button) {
    button.addEventListener("click", function () {
      selectTab(button.dataset.tab);
    });
  });
  elements.refreshButton.addEventListener("click", refreshStatus);
  elements.runButton.addEventListener("click", runSmokeTest);
  elements.clearButton.addEventListener("click", clearState);
  elements.copyButton.addEventListener("click", copyLogs);
  elements.captureButton.addEventListener("click", captureScreen);
  elements.discardCaptureButton.addEventListener("click", discardCapture);
  elements.tapButton.addEventListener("click", injectTap);

  window.addEventListener("error", function (event) {
    setStatus("error", "页面运行异常", event.message || "未知错误");
    elements.terminalOutput.textContent = String(event.error || event.message);
  });
  window.addEventListener("unhandledrejection", function (event) {
    var reason = event.reason || "未知错误";
    setStatus("error", "操作执行异常", reason.message || String(reason));
    elements.terminalOutput.textContent = String(reason.stack || reason);
  });
  window.addEventListener("pagehide", function () {
    discardCapture();
  });

  refreshStatus();
})();
