#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
#
# 生成 AgentLiteIOS.xcodeproj/project.pbxproj。
#
# 为什么用脚本生成而不是手写 .pbxproj：
#   pbxproj 是 Xcode 的私有格式，数千行、全靠 24 位十六进制 UUID 互相引用。
#   手写极易出现「某个 UUID 少一位」这类无法肉眼发现、Xcode 打开才报错的缺陷。
#   用脚本生成 = 引用关系由代码保证自洽，而且改一个设置不用在千行文件里搜。
#
# 用法：
#   python3 ios/App/generate-project.py          # 生成/覆盖 project.pbxproj 与 scheme
#   python3 ios/App/generate-project.py --check  # 只校验现有工程与脚本一致（给门禁用）
#
# 依赖：仅 Python 3 标准库（不引入 xcodegen/tuist —— 本项目的磁盘工具链一贯零第三方）。

import hashlib
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT_DIR = os.path.join(HERE, "AgentLiteIOS.xcodeproj")

APP_NAME = "AgentLiteIOS"
TEST_NAME = "AgentLiteIOSTests"
BUNDLE_ID = "com.dsh.agentlite.ios"
TEST_BUNDLE_ID = "com.dsh.agentlite.ios.tests"
DEPLOYMENT_TARGET = "16.0"
SWIFT_VERSION = "5.9"

# 本地 Swift Package 的相对路径（相对工程文件所在目录）
LOCAL_PACKAGE_PATH = ".."
LOCAL_PACKAGE_NAME = "AgentLite"

APP_SOURCES = [
    "AgentLiteIOSApp.swift",
    "RootView.swift",
    "EmptyStateView.swift",
    "AccountView.swift",
    "DevicesView.swift",
    "RemoteView.swift",
    "AppModel.swift",
    "LocalActions.swift",
    "BackgroundRefresh.swift",
]
TEST_SOURCES = ["SimulatorTests.swift"]


def uid(*parts):
    """稳定 UUID：同样输入永远得到同样的 24 位十六进制。
    稳定性很重要 —— 否则每次重新生成工程都会产生全量 diff，无法审查。"""
    h = hashlib.sha256("|".join(parts).encode()).hexdigest().upper()
    return h[:24]


# 随包分发的资源。**必须有证书**：证书 pin 的前提是"服务端证书在包里"，
# 拿不到证书时 App 会明确拒绝连接，而不是退回不校验（见 ServerConfigProvider）。
COPY_RESOURCES = ["server.crt"]


def build():
    objs = {}          # uuid -> 文本块
    file_refs = {}     # 路径 -> uuid

    def add(u, text):
        objs[u] = text

    # ---- 文件引用 ----
    for name in APP_SOURCES:
        u = uid("file", "app", name)
        file_refs[name] = u
        add(u, f'{u} /* {name} */ = {{isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = {name}; sourceTree = "<group>"; }};')
    for name in TEST_SOURCES:
        u = uid("file", "test", name)
        file_refs[name] = u
        add(u, f'{u} /* {name} */ = {{isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = {name}; sourceTree = "<group>"; }};')

    for name in COPY_RESOURCES:
        u = uid("file", "res", name)
        file_refs[name] = u
        add(u, f'{u} /* {name} */ = {{isa = PBXFileReference; lastKnownFileType = text; path = {name}; sourceTree = "<group>"; }};')

    app_product = uid("product", "app")
    test_product = uid("product", "test")
    add(app_product, f'{app_product} /* {APP_NAME}.app */ = {{isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = {APP_NAME}.app; sourceTree = BUILT_PRODUCTS_DIR; }};')
    add(test_product, f'{test_product} /* {TEST_NAME}.xctest */ = {{isa = PBXFileReference; explicitFileType = wrapper.cfbundle; includeInIndex = 0; path = {TEST_NAME}.xctest; sourceTree = BUILT_PRODUCTS_DIR; }};')

    # ---- 分组 ----
    app_group = uid("group", "app")
    test_group = uid("group", "test")
    products_group = uid("group", "products")
    main_group = uid("group", "main")

    res_group = uid("group", "res")
    res_children = "\n".join(f'\t\t\t\t{file_refs[n]} /* {n} */,' for n in COPY_RESOURCES)
    add(res_group, f'''{res_group} /* Resources */ = {{
			isa = PBXGroup;
			children = (
{res_children}
			);
			path = Resources;
			sourceTree = "<group>";
		}};''')

    app_children = "\n".join(f'\t\t\t\t{file_refs[n]} /* {n} */,' for n in APP_SOURCES)
    test_children = "\n".join(f'\t\t\t\t{file_refs[n]} /* {n} */,' for n in TEST_SOURCES)

    add(app_group, f'''{app_group} /* {APP_NAME} */ = {{
			isa = PBXGroup;
			children = (
{app_children}
			);
			path = {APP_NAME};
			sourceTree = "<group>";
		}};''')
    add(test_group, f'''{test_group} /* {TEST_NAME} */ = {{
			isa = PBXGroup;
			children = (
{test_children}
			);
			path = {TEST_NAME};
			sourceTree = "<group>";
		}};''')
    add(products_group, f'''{products_group} /* Products */ = {{
			isa = PBXGroup;
			children = (
				{app_product} /* {APP_NAME}.app */,
				{test_product} /* {TEST_NAME}.xctest */,
			);
			name = Products;
			sourceTree = "<group>";
		}};''')
    add(main_group, f'''{main_group} = {{
			isa = PBXGroup;
			children = (
				{app_group} /* {APP_NAME} */,
				{res_group} /* Resources */,
				{test_group} /* {TEST_NAME} */,
				{products_group} /* Products */,
			);
			sourceTree = "<group>";
		}};''')

    # ---- 构建文件（源文件 -> target） ----
    app_build_files, test_build_files = [], []
    for name in APP_SOURCES:
        u = uid("buildfile", "app", name)
        app_build_files.append(u)
        add(u, f'{u} /* {name} in Sources */ = {{isa = PBXBuildFile; fileRef = {file_refs[name]} /* {name} */; }};')
    for name in TEST_SOURCES:
        u = uid("buildfile", "test", name)
        test_build_files.append(u)
        add(u, f'{u} /* {name} in Sources */ = {{isa = PBXBuildFile; fileRef = {file_refs[name]} /* {name} */; }};')

    # ---- 本地 Swift Package 引用 ----
    pkg_ref = uid("pkgref")
    pkg_product_app = uid("pkgproduct", "app")
    pkg_product_test = uid("pkgproduct", "test")
    # 注意：每个产品依赖对象都必须带 package 指针，且 UUID 不能重复——
    # 两个 target 各要一份**独立**的依赖记录（共享一个 UUID 会让 pbxproj 解析失败）。
    add(pkg_ref, f'{pkg_ref} /* XCLocalSwiftPackageReference "{LOCAL_PACKAGE_PATH}" */ = {{isa = XCLocalSwiftPackageReference; relativePath = "{LOCAL_PACKAGE_PATH}"; }};')

    # ---- Frameworks 阶段：把包产物链进 target ----
    app_fw_files = uid("fwfile", "app")
    test_fw_files = uid("fwfile", "test")
    add(app_fw_files, f'{app_fw_files} /* AgentLiteCore in Frameworks */ = {{isa = PBXBuildFile; productRef = {pkg_product_app} /* AgentLiteCore */; }};')
    add(test_fw_files, f'{test_fw_files} /* AgentLiteCore in Frameworks */ = {{isa = PBXBuildFile; productRef = {pkg_product_test} /* AgentLiteCore */; }};')
    add(pkg_product_app, f'{pkg_product_app} /* AgentLiteCore */ = {{isa = XCSwiftPackageProductDependency; package = {pkg_ref}; productName = AgentLiteCore; }};')
    add(pkg_product_test, f'{pkg_product_test} /* AgentLiteCore */ = {{isa = XCSwiftPackageProductDependency; package = {pkg_ref}; productName = AgentLiteCore; }};')

    app_fw_phase = uid("phase", "app", "fw")
    app_src_phase = uid("phase", "app", "src")
    app_res_phase = uid("phase", "app", "res")
    test_fw_phase = uid("phase", "test", "fw")
    test_src_phase = uid("phase", "test", "src")
    test_res_phase = uid("phase", "test", "res")

    add(app_fw_phase, f'''{app_fw_phase} /* Frameworks */ = {{
			isa = PBXFrameworksBuildPhase;
			buildActionMask = 2147483647;
			files = (
				{app_fw_files} /* AgentLiteCore in Frameworks */,
			);
			runOnlyForDeploymentPostprocessing = 0;
		}};''')
    add(test_fw_phase, f'''{test_fw_phase} /* Frameworks */ = {{
			isa = PBXFrameworksBuildPhase;
			buildActionMask = 2147483647;
			files = (
				{test_fw_files} /* AgentLiteCore in Frameworks */,
			);
			runOnlyForDeploymentPostprocessing = 0;
		}};''')

    app_src_list = "\n".join(f'\t\t\t\t{u} /* {n} in Sources */,' for u, n in zip(app_build_files, APP_SOURCES))
    add(app_src_phase, f'''{app_src_phase} /* Sources */ = {{
			isa = PBXSourcesBuildPhase;
			buildActionMask = 2147483647;
			files = (
{app_src_list}
			);
			runOnlyForDeploymentPostprocessing = 0;
		}};''')
    test_src_list = "\n".join(f'\t\t\t\t{u} /* {n} in Sources */,' for u, n in zip(test_build_files, TEST_SOURCES))
    add(test_src_phase, f'''{test_src_phase} /* Sources */ = {{
			isa = PBXSourcesBuildPhase;
			buildActionMask = 2147483647;
			files = (
{test_src_list}
			);
			runOnlyForDeploymentPostprocessing = 0;
		}};''')
    res_build_files = []
    for name in COPY_RESOURCES:
        u = uid("buildfile", "res", name)
        res_build_files.append(u)
        add(u, f'{u} /* {name} in Resources */ = {{isa = PBXBuildFile; fileRef = {file_refs[name]} /* {name} */; }};')
    res_list = "\n".join(f'\t\t\t\t{u} /* {n} in Resources */,' for u, n in zip(res_build_files, COPY_RESOURCES))
    add(app_res_phase, f'''{app_res_phase} /* Resources */ = {{
			isa = PBXResourcesBuildPhase;
			buildActionMask = 2147483647;
			files = (
{res_list}
			);
			runOnlyForDeploymentPostprocessing = 0;
		}};''')
    add(test_res_phase, f'{test_res_phase} /* Resources */ = {{isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0; }};')

    # ---- Target 依赖：测试依赖 App（作为 test host） ----
    dep = uid("targetdep")
    proxy = uid("containerproxy")
    add(proxy, f'{proxy} /* PBXContainerItemProxy */ = {{isa = PBXContainerItemProxy; containerPortal = {uid("project")} /* Project object */; proxyType = 1; remoteGlobalIDString = {uid("target", "app")}; remoteInfo = {APP_NAME}; }};')
    add(dep, f'{dep} /* PBXTargetDependency */ = {{isa = PBXTargetDependency; target = {uid("target", "app")} /* {APP_NAME} */; targetProxy = {proxy} /* PBXContainerItemProxy */; }};')

    app_configs = []
    test_configs = []
    for cfg in ("Debug", "Release"):
        ac = uid("buildconfig", "app", cfg)
        tc = uid("buildconfig", "test", cfg)
        app_configs.append(ac)
        test_configs.append(tc)
        add(ac, f'''{ac} /* {cfg} */ = {{
			isa = XCBuildConfiguration;
			buildSettings = {{
				ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME = "";
				CODE_SIGNING_ALLOWED = NO;
				CODE_SIGNING_REQUIRED = NO;
				CODE_SIGN_IDENTITY = "";
				CURRENT_PROJECT_VERSION = 1;
				ENABLE_PREVIEWS = YES;
				GENERATE_INFOPLIST_FILE = YES;
				INFOPLIST_KEY_CFBundleDisplayName = "AgentLite";
				INFOPLIST_KEY_NSLocalNetworkUsageDescription = "用于在同账号设备之间直接同步数据，数据不经过服务器。";
				INFOPLIST_KEY_UILaunchScreen_Generation = YES;
				INFOPLIST_KEY_UISupportedInterfaceOrientations = "UIInterfaceOrientationPortrait UIInterfaceOrientationLandscapeLeft UIInterfaceOrientationLandscapeRight";
				IPHONEOS_DEPLOYMENT_TARGET = {DEPLOYMENT_TARGET};
				MARKETING_VERSION = 0.1.0;
				PRODUCT_BUNDLE_IDENTIFIER = {BUNDLE_ID};
				PRODUCT_NAME = "$(TARGET_NAME)";
				SWIFT_EMIT_LOC_STRINGS = YES;
				SWIFT_VERSION = {SWIFT_VERSION};
				TARGETED_DEVICE_FAMILY = "1,2";
			}};
			name = {cfg};
		}};''')
        add(tc, f'''{tc} /* {cfg} */ = {{
			isa = XCBuildConfiguration;
			buildSettings = {{
				BUNDLE_LOADER = "$(TEST_HOST)";
				CODE_SIGNING_ALLOWED = NO;
				CODE_SIGNING_REQUIRED = NO;
				CODE_SIGN_IDENTITY = "";
				CURRENT_PROJECT_VERSION = 1;
				GENERATE_INFOPLIST_FILE = YES;
				IPHONEOS_DEPLOYMENT_TARGET = {DEPLOYMENT_TARGET};
				MARKETING_VERSION = 0.1.0;
				PRODUCT_BUNDLE_IDENTIFIER = {TEST_BUNDLE_ID};
				PRODUCT_NAME = "$(TARGET_NAME)";
				SWIFT_VERSION = {SWIFT_VERSION};
				TARGETED_DEVICE_FAMILY = "1,2";
				TEST_HOST = "$(BUILT_PRODUCTS_DIR)/{APP_NAME}.app/{APP_NAME}";
			}};
			name = {cfg};
		}};''')

    proj_cfg_list = uid("cfgs", "project")
    app_cfg_list = uid("cfgs", "app")
    test_cfg_list = uid("cfgs", "test")
    proj_debug = uid("buildconfig", "project", "Debug")
    proj_release = uid("buildconfig", "project", "Release")

    add(proj_cfg_list, f'{proj_cfg_list} /* Build configuration list for PBXProject */ = {{isa = XCConfigurationList; buildConfigurations = ({proj_debug} /* Debug */, {proj_release} /* Release */, ); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release; }};')
    add(app_cfg_list, f'{app_cfg_list} /* Build configuration list for PBXNativeTarget "{APP_NAME}" */ = {{isa = XCConfigurationList; buildConfigurations = ({app_configs[0]} /* Debug */, {app_configs[1]} /* Release */, ); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release; }};')
    add(test_cfg_list, f'{test_cfg_list} /* Build configuration list for PBXNativeTarget "{TEST_NAME}" */ = {{isa = XCConfigurationList; buildConfigurations = ({test_configs[0]} /* Debug */, {test_configs[1]} /* Release */, ); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release; }};')

    proj_settings = '''				ALWAYS_SEARCH_USER_PATHS = NO;
				CLANG_ENABLE_MODULES = YES;
				CLANG_ENABLE_OBJC_ARC = YES;
				COPY_PHASE_STRIP = NO;
				DEBUG_INFORMATION_FORMAT = dwarf;
				ENABLE_STRICT_OBJC_MSGSEND = YES;
				ENABLE_TESTABILITY = YES;
				GCC_C_LANGUAGE_STANDARD = gnu17;
				GCC_OPTIMIZATION_LEVEL = 0;
				GCC_PREPROCESSOR_DEFINITIONS = ("DEBUG=1", "$(inherited)");
				IPHONEOS_DEPLOYMENT_TARGET = ''' + DEPLOYMENT_TARGET + ''';
				MTL_ENABLE_DEBUG_INFO = INCLUDE_SOURCE;
				ONLY_ACTIVE_ARCH = YES;
				SDKROOT = iphoneos;
				SWIFT_ACTIVE_COMPILATION_CONDITIONS = DEBUG;
				SWIFT_OPTIMIZATION_LEVEL = "-Onone";'''

    proj_settings_rel = '''				ALWAYS_SEARCH_USER_PATHS = NO;
				CLANG_ENABLE_MODULES = YES;
				CLANG_ENABLE_OBJC_ARC = YES;
				COPY_PHASE_STRIP = NO;
				DEBUG_INFORMATION_FORMAT = "dwarf-with-dsym";
				ENABLE_NS_ASSERTIONS = NO;
				ENABLE_STRICT_OBJC_MSGSEND = YES;
				GCC_C_LANGUAGE_STANDARD = gnu17;
				IPHONEOS_DEPLOYMENT_TARGET = ''' + DEPLOYMENT_TARGET + ''';
				MTL_ENABLE_DEBUG_INFO = NO;
				SDKROOT = iphoneos;
				SWIFT_COMPILATION_MODE = wholemodule;
				SWIFT_OPTIMIZATION_LEVEL = "-O";
				VALIDATE_PRODUCT = YES;'''

    add(proj_debug, f'{proj_debug} /* Debug */ = {{isa = XCBuildConfiguration; buildSettings = {{\n{proj_settings}\n			}}; name = Debug; }};')
    add(proj_release, f'{proj_release} /* Release */ = {{isa = XCBuildConfiguration; buildSettings = {{\n{proj_settings_rel}\n			}}; name = Release; }};')

    # ---- 两个 target ----
    add(uid("target", "app"), f'''{uid("target", "app")} /* {APP_NAME} */ = {{
			isa = PBXNativeTarget;
			buildConfigurationList = {app_cfg_list} /* Build configuration list for PBXNativeTarget "{APP_NAME}" */;
			buildPhases = (
				{app_src_phase} /* Sources */,
				{app_fw_phase} /* Frameworks */,
				{app_res_phase} /* Resources */,
			);
			buildRules = ();
			dependencies = ();
			name = {APP_NAME};
			packageProductDependencies = (
				{pkg_product_app} /* AgentLiteCore */,
			);
			productName = {APP_NAME};
			productReference = {app_product} /* {APP_NAME}.app */;
			productType = "com.apple.product-type.application";
		}};''')
    add(uid("target", "test"), f'''{uid("target", "test")} /* {TEST_NAME} */ = {{
			isa = PBXNativeTarget;
			buildConfigurationList = {test_cfg_list} /* Build configuration list for PBXNativeTarget "{TEST_NAME}" */;
			buildPhases = (
				{test_src_phase} /* Sources */,
				{test_fw_phase} /* Frameworks */,
				{test_res_phase} /* Resources */,
			);
			buildRules = ();
			dependencies = (
				{dep} /* PBXTargetDependency */,
			);
			name = {TEST_NAME};
			packageProductDependencies = (
				{pkg_product_test} /* AgentLiteCore */,
			);
			productName = {TEST_NAME};
			productReference = {test_product} /* {TEST_NAME}.xctest */;
			productType = "com.apple.product-type.bundle.unit-test";
		}};''')

    add(uid("project"), f'''{uid("project")} /* Project object */ = {{
			isa = PBXProject;
			attributes = {{
				BuildIndependentTargetsInParallel = 1;
				LastSwiftUpdateCheck = 2660;
				LastUpgradeCheck = 2660;
				TargetAttributes = {{
					{uid("target", "app")} = {{CreatedOnToolsVersion = 26.6; }};
					{uid("target", "test")} = {{CreatedOnToolsVersion = 26.6; TestTargetID = {uid("target", "app")}; }};
				}};
			}};
			buildConfigurationList = {proj_cfg_list} /* Build configuration list for PBXProject */;
			compatibilityVersion = "Xcode 14.0";
			developmentRegion = en;
			hasScannedForEncodings = 0;
			knownRegions = (en, Base, zh-Hans, );
			mainGroup = {main_group};
			packageReferences = (
				{pkg_ref} /* XCLocalSwiftPackageReference "{LOCAL_PACKAGE_PATH}" */,
			);
			productRefGroup = {products_group} /* Products */;
			projectDirPath = "";
			projectRoot = "";
			targets = (
				{uid("target", "app")} /* {APP_NAME} */,
				{uid("target", "test")} /* {TEST_NAME} */,
			);
		}};''')

    sections = [
        ("PBXBuildFile", [k for k, v in objs.items() if "PBXBuildFile" in v]),
        ("PBXContainerItemProxy", [k for k, v in objs.items() if "PBXContainerItemProxy" in v]),
        ("PBXFileReference", [k for k, v in objs.items() if "PBXFileReference" in v]),
        ("PBXFrameworksBuildPhase", [k for k, v in objs.items() if "PBXFrameworksBuildPhase" in v]),
        ("PBXGroup", [k for k, v in objs.items() if "isa = PBXGroup" in v]),
        ("PBXNativeTarget", [k for k, v in objs.items() if "PBXNativeTarget" in v]),
        ("PBXProject", [k for k, v in objs.items() if "isa = PBXProject" in v]),
        ("PBXResourcesBuildPhase", [k for k, v in objs.items() if "PBXResourcesBuildPhase" in v]),
        ("PBXSourcesBuildPhase", [k for k, v in objs.items() if "PBXSourcesBuildPhase" in v]),
        ("PBXTargetDependency", [k for k, v in objs.items() if "PBXTargetDependency" in v]),
        ("XCBuildConfiguration", [k for k, v in objs.items() if "XCBuildConfiguration" in v]),
        ("XCConfigurationList", [k for k, v in objs.items() if "XCConfigurationList" in v]),
        ("XCLocalSwiftPackageReference", [k for k, v in objs.items() if "XCLocalSwiftPackageReference" in v]),
        ("XCSwiftPackageProductDependency", [k for k, v in objs.items() if "XCSwiftPackageProductDependency" in v]),
    ]

    body = []
    for name, keys in sections:
        if not keys:
            continue
        body.append(f"/* Begin {name} section */")
        for k in sorted(keys):
            body.append(objs[k])
        body.append(f"/* End {name} section */")
        body.append("")

    return "// !$*UTF8*$!\n{\n\tarchiveVersion = 1;\n\tclasses = {\n\t};\n\tobjectVersion = 56;\n\tobjects = {\n\n" \
        + "\n".join(body) \
        + f"\n\t}};\n\trootObject = {uid('project')} /* Project object */;\n}}\n"


SCHEME = f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion = "2660" version = "1.7">
   <BuildAction parallelizeBuildables = "YES" buildImplicitDependencies = "YES">
      <BuildActionEntries>
         <BuildActionEntry buildForTesting = "YES" buildForRunning = "YES" buildForProfiling = "YES" buildForArchiving = "YES" buildForAnalyzing = "YES">
            <BuildableReference
               BuildableIdentifier = "primary"
               BlueprintIdentifier = "{{APP_TARGET_ID}}"
               BuildableName = "{APP_NAME}.app"
               BlueprintName = "{APP_NAME}"
               ReferencedContainer = "container:{APP_NAME}.xcodeproj">
            </BuildableReference>
         </BuildActionEntry>
      </BuildActionEntries>
   </BuildAction>
   <TestAction buildConfiguration = "Debug" selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv = "YES">
      <EnvironmentVariables>
         <!-- 端到端测试的目标服务端。默认指向宿主环回：模拟器与宿主共享网络栈，
              因此宿主的 127.0.0.1 在模拟器里同样是 127.0.0.1。
              证书路径用 $(PROJECT_DIR)/../ 相对定位，避免写死绝对路径导致换机失效。 -->
         <EnvironmentVariable key = "AGENTLITE_E2E_BASE" value = "https://127.0.0.1:18443" isEnabled = "YES"/>
         <EnvironmentVariable key = "AGENTLITE_E2E_CERT" value = "$(PROJECT_DIR)/Resources/server.crt" isEnabled = "YES"/>
      </EnvironmentVariables>
      <Testables>
         <TestableReference skipped = "NO">
            <BuildableReference
               BuildableIdentifier = "primary"
               BlueprintIdentifier = "{{TEST_TARGET_ID}}"
               BuildableName = "{TEST_NAME}.xctest"
               BlueprintName = "{TEST_NAME}"
               ReferencedContainer = "container:{APP_NAME}.xcodeproj">
            </BuildableReference>
         </TestableReference>
      </Testables>
   </TestAction>
   <LaunchAction buildConfiguration = "Debug" selectedDebuggerIdentifier = "Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier = "Xcode.DebuggerFoundation.Launcher.LLDB" launchStyle = "0" useCustomWorkingDirectory = "NO" ignoresPersistentStateOnLaunch = "NO" debugDocumentVersioning = "YES" debugServiceExtension = "internal" allowLocationSimulation = "YES">
      <BuildableProductRunnable runnableDebuggingMode = "0">
         <BuildableReference
            BuildableIdentifier = "primary"
            BlueprintIdentifier = "{{APP_TARGET_ID}}"
            BuildableName = "{APP_NAME}.app"
            BlueprintName = "{APP_NAME}"
            ReferencedContainer = "container:{APP_NAME}.xcodeproj">
         </BuildableReference>
      </BuildableProductRunnable>
   </LaunchAction>
   <ProfileAction buildConfiguration = "Release" shouldUseLaunchSchemeArgsEnv = "YES" savedToolIdentifier = "" useCustomWorkingDirectory = "NO" debugDocumentVersioning = "YES">
      <BuildableProductRunnable runnableDebuggingMode = "0">
         <BuildableReference
            BuildableIdentifier = "primary"
            BlueprintIdentifier = "{{APP_TARGET_ID}}"
            BuildableName = "{APP_NAME}.app"
            BlueprintName = "{APP_NAME}"
            ReferencedContainer = "container:{APP_NAME}.xcodeproj">
         </BuildableReference>
      </BuildableProductRunnable>
   </ProfileAction>
   <AnalyzeAction buildConfiguration = "Debug"/>
   <ArchiveAction buildConfiguration = "Release" revealArchiveInOrganizer = "YES"/>
</Scheme>
'''


def main():
    check = "--check" in sys.argv
    pbx = build()
    scheme = (SCHEME
              .replace("{APP_TARGET_ID}", uid("target", "app"))
              .replace("{TEST_TARGET_ID}", uid("target", "test")))

    targets = {
        os.path.join(PROJECT_DIR, "project.pbxproj"): pbx,
        os.path.join(PROJECT_DIR, "xcshareddata", "xcschemes", f"{APP_NAME}.xcscheme"): scheme,
    }

    if check:
        bad = []
        for path, want in targets.items():
            have = open(path, encoding="utf-8").read() if os.path.exists(path) else None
            if have != want:
                bad.append(path)
        if bad:
            print("✗ 工程文件与 generate-project.py 不一致：")
            for p in bad:
                print("   ", os.path.relpath(p, HERE))
            print("  修复：python3 ios/App/generate-project.py")
            return 1
        print("✓ Xcode 工程与生成脚本一致")
        return 0

    os.makedirs(os.path.join(PROJECT_DIR, "xcshareddata", "xcschemes"), exist_ok=True)
    for path, want in targets.items():
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(want)
        print("✓ 已生成", os.path.relpath(path, HERE))
    return 0


if __name__ == "__main__":
    sys.exit(main())
