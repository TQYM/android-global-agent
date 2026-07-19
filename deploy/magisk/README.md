# KernelSU/Magisk helper limitations

This directory follows the common KernelSU/Magisk module layout, but it is not
a production installation path. The packaged `bin/global-agentd` is the API 34
arm64 portable-core stub. The manager action runs a four-step synthetic smoke
test; it does not capture the screen or inject device input.

KernelSU Manager loads the offline dashboard from `webroot/index.html`. The UI
uses the injected `ksu` JavaScript bridge for status, smoke testing, and debug
state cleanup. In a normal browser it degrades to a read-only preview instead
of throwing or closing the page.

The device-tools tab exposes two explicit root-shell diagnostics: Android's
`screencap -p` for the current display and one bounded `input touchscreen tap`
at user-entered coordinates. Captures are loaded into an in-memory Blob and the
temporary file is deleted immediately. Android continues to redact secure/DRM
surfaces. These manual tools are not an automatic decision policy or a
replacement for the platform-signed production bridge.

`post-fs-data.sh` only creates the state directory and sets known file labels
and modes. `chcon` does not grant SELinux permissions, create an `agentd`
domain, platform-sign the bridge APK, or make init import a late-mounted rc
file. No boot-time daemon is started by this helper.

The helper attempts only the project-owned `global_agent_data_file` and
`agentd_exec` labels; on a stock policy those `chcon` calls are skipped. It
deliberately does not relabel `/dev/uinput`. Input is sent through
the platform-signed bridge and the framework's permission checks; changing a
device-node label would be an unsafe and incomplete attempt to bypass policy.

Use it for inspecting a rooted engineering device or staging files before they
are integrated into a matching AOSP build. No `sepolicy.rule` is shipped because
a generic runtime rule would either fail on OEM policy or grant an unsafe amount
of authority.

Build the installable ZIP with `tools/package-kernelsu.sh [output.zip]`. The
archive contains `module.prop`, `customize.sh`, `post-fs-data.sh`, `action.sh`,
this README, the offline `webroot/`, and the arm64 stub under `bin/`.
