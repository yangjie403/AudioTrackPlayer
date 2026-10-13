# Compose 播放进度与主题重组问题复盘

本文记录音频编辑页播放预览时，固定文字和操作按钮随播放进度频繁重组的原因、验证过程与修复方法。对应代码主要位于 `app/src/main/java/com/bigjelly/temporun/AudioEditorActivity.kt`；播放进度回调位于 `app/src/main/java/com/bigjelly/temporun/player/AdvancedAudioPlayer.kt`。

## 1. 现象与观察

播放预览时，播放器大约每 100 ms 报告一次进度。Android Studio Layout Inspector 的重组计数显示：波形和时间显示随进度更新，这符合预期；但“在当前进度分割”“删除选中分段”等按钮及其固定文案 `Text` 也持续出现实际重组。

本次观察中，计数的第一列是当前组件实际重组次数，第二列是子组件重组次数，第三列是跳过重组次数。固定字符串不变，并不自动意味着所在组合范围一定可以被跳过；但这些按钮在播放期间并不需要每次都更新，因此值得检查上层的失效传播。

做了两个对照实验：

1. 保持原布局，仅去掉自定义 `colorScheme`，频繁重组消失。
2. 保留自定义配色，但将各个 Compose State 的读取移入 `MaterialTheme` 的内容 lambda，固定 `Text` 的重组基本被跳过；波形仍按播放进度正常更新。

这两个实验指向同一条原因链：高频状态让主题调用所在范围重组，而主题每次接收到一个新创建的 `ColorScheme` 实例。

## 2. 播放进度如何传播到 Compose

`AdvancedAudioPlayer` 用 `PROGRESS_INTERVAL_MS = 100L` 节流进度回调。`AudioEditorActivity` 的 `onProgressListener` 调用 `handleWaveformProgress()`，播放有效时给 `playbackPositionState.longValue` 赋当前毫秒数。

原先的 `setContent` 大致是：

```kotlin
setContent {
    val playbackPositionMs = playbackPositionState.longValue
    // 其他 State 也在此读取

    MaterialTheme(colorScheme = editorColors()) {
        AudioEditorScreen(
            playbackPositionMs = playbackPositionMs,
            // ...
        )
    }
}
```

Compose 会在**读取 State 的组合范围**记录依赖。这里进度状态在主题调用之前、`setContent` 的外层内容中读取。播放进度变化使这个范围重新执行，连带重新执行 `editorColors()` 和 `MaterialTheme(...)` 调用。`AudioEditorScreen` 的参数 `playbackPositionMs` 也变化，因此屏幕本身需要更新。

屏幕内部还有 `LaunchedEffect(playbackPositionMs, durationMs)`，它把播放器进度同步到 `playHeadFraction`；波形、时间和部分按钮启用状态依赖这个状态。高频进度推动相关 UI 更新是正常的，问题在于更新还扩散到了无关的固定内容。

## 3. 为什么相同配色仍会使内容失效

项目原来的 `editorColors()` 每调用一次就执行一次 `darkColorScheme(...)`：

```kotlin
private fun editorColors() = darkColorScheme(
    primary = Color(0xFF8BD7FF),
    onPrimary = Color(0xFF003548),
    // ...
)
```

即使每个颜色参数都一样，`darkColorScheme()` 仍返回**新的 `ColorScheme` 对象**。Material3 的 `ColorScheme` 是普通类，没有按所有颜色字段实现结构相等；其 `@Immutable` 标注描述稳定性约定，不会把不同实例变成相等对象。

Material3 的 `MaterialTheme` 通过 `LocalColorScheme` 把配色提供给子树，该 Local 使用 `staticCompositionLocalOf`。提供的值从旧对象变为新对象时，框架会让该 Local 的内容范围失效，而不只是改变某个按钮表面的颜色。Material3 按钮还会通过主题获得默认颜色，按钮里的 `Text` 也会从按钮提供的局部环境取得默认文字颜色和样式。

所以完整链路是：

```text
播放器进度变化（约 100 ms 一次）
  -> 外层读取 playbackPositionState 的组合范围失效
  -> 再次调用 editorColors()，生成新 ColorScheme
  -> MaterialTheme 提供的 LocalColorScheme 值改变
  -> 主题内容范围发生额外失效
  -> 按钮和固定 Text 可能发生原本不需要的重组
```

这解释了为什么单独优化按钮的 `onClick` 无法解决问题：`rememberUpdatedState` 可以让点击回调使用最新播放头，同时保持传给按钮的回调稳定，但不能阻止更上游的主题内容失效。`derivedStateOf` 只能在派生结果不变时抑制对应状态的通知，也不能抵消主题对象被替换带来的影响。

要区分三件事：函数被重组、布局重新测量、实际重新绘制不是同一回事。固定 `Text` 重组并不代表每次都重新排版或重绘；但它仍说明本可跳过的组合工作被执行了。

## 4. 当前采用的修复

把频繁变化的 State 读取移到主题的内容 lambda 内，让主题调用本身不依赖播放进度：

```kotlin
setContent {
    MaterialTheme(colorScheme = editorColors()) {
        val firstTrack by firstTrackState
        val secondTrack by secondTrackState
        val status by statusState
        val busy by busyState
        val playbackPositionMs = playbackPositionState.longValue
        val waveformPlaying by waveformPlayingState

        AudioEditorScreen(
            firstTrack = firstTrack,
            secondTrack = secondTrack,
            status = status,
            busy = busy,
            playbackPositionMs = playbackPositionMs,
            waveformPlaying = waveformPlaying,
            // 其余参数不变
        )
    }
}
```

播放器进度现在由主题**内部**的组合内容读取。进度变化仍会让真正依赖它的内容重组，但不会仅因为播放进度变化就重新执行外层的 `editorColors()`。自定义配色依然保留，固定文字和不变的按钮状态可以正常被跳过。`MaterialTheme` 位于代码上方并不是魔法，真正起作用的是 **State 读取所在的组合范围被缩小了**。

项目当前的 `editorColors()` 不依赖 Compose State，因此无需声明为 `@Composable`。当前解法也不需要为它额外创建 `mutableStateOf`；没有需要写入的新配色状态时，这层可写状态只会增加概念负担。

## 5. 其他方案及边界

如果外层确实还需要读取其他频繁变化的 State，不能保证主题调用所在范围保持稳定，可以在主题外层复用同一个配色实例：

```kotlin
setContent {
    val colorScheme = remember { editorColors() }
    MaterialTheme(colorScheme = colorScheme) {
        // 内容
    }
}
```

这个方案解决的是“即使外层重新执行，也不向主题传入新实例”的问题；与当前的“避免进度触发外层重新执行”是两种不同的隔离手段。这里的 `editorColors()` 必须是普通函数，不能在 `remember` 的计算块中调用标记为 `@Composable` 的函数。

如果主题将来要根据深色模式、用户偏好或动态种子色变化，需要把真实的主题输入放在合适的位置，并在它们改变时**有意**更新配色，例如 `remember(isDark, seedColor) { ... }`。不能为了降低计数把动态主题永久固定成第一次创建的值。

当前修复并不保证整个 `AudioEditorScreen` 都不再重组：`playbackPositionMs` 仍是屏幕参数，`playHeadFraction` 仍用于波形和时间。它解决的是由“高频状态读取位置 + 每次新建主题对象”导致的无关子树重组。如果以后需要进一步降低屏幕级别的组合成本，应再把实时进度的读取与展示收拢到更小的可组合范围，按实际性能数据决定是否值得拆分。

## 6. 验证与性能判断

在 Layout Inspector 中先重置计数，再在同一音轨的同一分段内播放一段时间，对比：

1. 波形与播放时间仍随进度更新，证明必要的更新没有丢失。
2. 固定按钮文案 `Text` 的第三列“跳过”持续增长，而第一列实际重组不再随每个进度回调增长。
3. “在当前进度分割”的 `enabled` 在播放头进入、离开可分割区间时仍正确变化；分割、删除分段后按钮状态仍正确。
4. 切换音轨、暂停和恢复播放后，配色与 UI 状态保持正确。

播放过程中大量无关重组会增加主线程的组合计算与对象分配，复杂页面或低端设备上可能提高掉帧和耗电风险。但“重组次数高”本身不能直接证明已经掉帧：是否影响用户体验，应结合帧时间、卡顿记录和实际设备测试判断。本文解决的是明确可避免的失效传播，不要求把所有正常的播放进度重组归零。

## 7. 本次排查的经验

- 先分清 Layout Inspector 的实际重组、子组件重组与跳过次数；固定文字应主要被跳过，而不是要求播放期间整页完全不重组。
- `rememberUpdatedState`、`derivedStateOf`、稳定 `onClick` 各自只解决局部依赖，不能修复上层主题对象反复替换造成的子树失效。
- 分析 Compose 性能时，同时检查 **State 在哪里读取** 和 **向 CompositionLocal 提供的对象是否在每次组合时变成新实例**。
- 对本例而言，移动 State 读取位置比为了静态配色额外引入可写 State 更直接。
