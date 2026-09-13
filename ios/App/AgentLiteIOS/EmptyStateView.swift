// SPDX-License-Identifier: Apache-2.0
//
// iOS 16 兼容的空状态视图。
//
// 为什么要自己写：`ContentUnavailableView` 是 **iOS 17+** 的 API，
// 而本 App 的部署目标是 **iOS 16**（覆盖面比"少写 30 行代码"重要得多）。
// 用系统 API 就得把最低版本抬到 17，会直接砍掉一批设备。
//
// 这个替身只覆盖本项目实际用到的形态：图标 + 标题 + 说明 + 可选操作。

import SwiftUI

struct EmptyStateView<Actions: View>: View {
    let title: String
    let systemImage: String
    let description: String
    @ViewBuilder var actions: () -> Actions

    init(
        _ title: String,
        systemImage: String,
        description: String,
        @ViewBuilder actions: @escaping () -> Actions = { EmptyView() }
    ) {
        self.title = title
        self.systemImage = systemImage
        self.description = description
        self.actions = actions
    }

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 44))
                .foregroundStyle(.secondary)
            Text(title)
                .font(.headline)
            Text(description)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            actions()
        }
        .frame(maxWidth: .infinity)
        .padding(24)
    }
}
