# Magisk helper limitations

This directory is not a production installation path. `post-fs-data.sh` only
sets known file labels and modes. `chcon` does not grant SELinux permissions,
create an `agentd` domain, platform-sign the bridge APK, or make init import a
late-mounted rc file.

The helper attempts only the project-owned `global_agent_data_file` and
`agentd_exec` labels; on a stock policy those `chcon` calls are skipped. It
deliberately does not relabel `/dev/uinput`. Input is sent through
the platform-signed bridge and the framework's permission checks; changing a
device-node label would be an unsafe and incomplete attempt to bypass policy.

Use it for inspecting a rooted engineering device or staging files before they
are integrated into a matching AOSP build. No `sepolicy.rule` is shipped because
a generic runtime rule would either fail on OEM policy or grant an unsafe amount
of authority.
