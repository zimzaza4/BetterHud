# 裁剪区域与 GPU 侧插值（`feat/improve`）

给 HUD 元素加两类能力，且**可以组合**：

1. **自定义裁剪区域** —— 不看整个贴图，只看它的一个矩形（可以圆角、羽化、按值推进、对称展开、扇形扫过）；
2. **GPU 侧插值** —— 元素每 tick 才更新一次数据，但边界/旋转/滚动在**每一帧**由客户端补出来，所以看起来是连续的。

两者都**纯可选**：元素不写这些键时，生成的 shader case body 与旧版本**逐字节相同** ✓（有单元测试钉住）。

---

## 1. 裁剪

```yaml
images:
  1:
    name: my_bar
    clip-rect: [x, y, width, height]   # 元素像素，左上角为原点
    clip-radius: 4                     # 可选：圆角半径
    clip-soft: 1                       # 可选：边界羽化宽度（像素）
    clip-follow: base                  # base（默认，固定窗口）/ content，见下
    clip-fill:                         # 可选：让边界随一个数值移动
      value: health_percentage
      direction: right                 # right/left/up/down/center-x/center-y/radial/pie
      invert: false
```

| 键 | 作用 |
|---|---|
| `clip-rect` | 可见矩形。数组或 `"x y w h"` 字符串都行。 |
| `clip-radius` | 圆角半径（SDF 实现 ✓ 描边不会变形）。 |
| `clip-soft` | 边界羽化。**不写 = 硬边（discard）**；写了 = 按 alpha 淡出。 |
| `clip-fill.value` | 一个数值占位符（与 `position` 同一种写法，可带 `@(...)` 方程）。 |
| `clip-fill.direction` | 边界怎么动：`right/left/up/down` 单边平移；`center-x/center-y` 从中间对称展开；`radial` 改半径（圆盘/圆环变大）；`pie` 像钟表指针扫过（冷却/装填环）。 |
| `clip-fill.invert` | 反向（从另一侧开始 / 扇形逆时针）。 |
| `clip-follow` | `base`（默认）：矩形钉在**元素自己的方框**里，从元素左上角量起，元素内容滚动/旋转时它**不动**，内容从它后面滑过去 ✓（罗盘/小地图窗口就是这种）。`content`：把矩形当"贴在图片上的模板"，跟着内容一起走（滚动+旋转都跟）。 |

## 2. 滚动（内容平移）

```yaml
    scroll:
      value: "player_yaw@(t * 2)"   # 像素偏移（可带方程 @(...)）
      axis: x                       # x / y
      scale: 360                    # 数值 ±scale 对应 payload 的整个量程
    smooth:
      duration: 3                   # 补帧时长（tick）
      easing: linear                # smoothstep（默认）/ linear / ease-out
```

要点：**滚动值必须走 `scroll`，不能用推进（advance）移动元素** —— 否则客户端分不出"基准"和"内容"，固定窗口（`clip-follow: base`）就没意义了 ✗。

**滚轮（tape / roller）罗盘的做法**（这套已经在测试 HUD 里跑通）：

- 长图按 **360° 一个周期** 画好：图上每 90° 一个字母（`N/E/S/W`），一个周期宽 = 720px；
- `scroll.scale` 取**半个周期**（本例 360），于是 payload 的 11 位量程正好覆盖 ±360px；
- 位移写成 `((720 - yaw*2) % 720 - 360)`：先按"面朝北时 N 落在窗口中心"标定，再折进量程。
  折回点相差整周期 ⇒ 画面完全相同 ⇒ **看不到接缝** ✓；
- 占位符**回卷**（yaw 跨过 ±180）时值会瞬间跳 720：`LinearRate` 把"超过半个量程"的跳变读成回卷而不是运动，
  那一次更新不带速率 ⇒ 客户端只画新值、不会把 720px 的跳变当成速度外推出去（这正是"突然抽一下"的来源 ✓）；
- 窗口宽 = 180° 视野（本例 360px），元素宽 1530px ⇒ 两边各留 540px 余量，任何朝向下画面都够用。

## 3. 插值（`smooth`）

```yaml
    smooth:
      duration: 8          # 补帧时长（tick）。数字写法 `smooth: 8` 等价
      easing: smoothstep   # 或 linear / ease-out
      rate: true           # 旋转类：同时发送角速度，客户端在两次更新之间继续转
      omega: 30            # 速率字段对应的最大角速度（度/tick，默认 30）
```

## 4. 一图一字形只有 24 位 —— 组合规则

客户端每个字形只有 **Color 的 RGB 三个字节**可写（alpha 由文本管线决定，插件写不进去）⇒ **一个字形只能带一份运行期数据**。系统按下面的优先级自动挑一种：

| 优先级 | 布局 | 装什么 | 什么时候用 |
|---|---|---|---|
| 1 | **payload 6** | 10 位 from + 10 位 to + 4 位时间戳 | **`scroll`（内容平移）**：两个绝对端点 ⇒ 不会过冲；客户端按 `bhSmoothDur` 走一段 tween，`from` 每次更新都重定位到"当前曲线上那一点"（reel 式，见 §8.3） |
| 2 | **payload 5** | 11 位值 + 5 位时间戳 + 8 位速率 | `rate: true` 的旋转（角速度外推） |
| 3 | **payload 4** | from + to + 时间戳 | `clip-fill` 的边界值（一次 tween），也用于缓存的填充值 |
| 4 | payload 3 | 8 位角度 + dx/dy 各 8 位（±128） | 位置与旋转**同时动态**（都没有速率外推） |
| 5 | payload 1/2 | 16 位角度 / 12+12 位偏移 | 旧行为（无插值） |

**payload 5 的单位契约（踩过坑，别改错）**：11 位字段装 `bhNorm = value / valueRange`，8 位字段装
`bhRate = rate / rateRange`，而 shader 侧乘的是 **`bhOmegaScale`（弧度/tick）** 或 **`2 * bhValueScale`（像素/tick）**。
所以两端必须同单位：

| 用途 | value / valueRange | rate / rateRange | shader 里乘 |
|---|---|---|---|
| 旋转 | 角度° / 360 | **弧度**/tick / `toRadians(omega)` | `bhOmegaScale` |
| 滚动 | 像素 / `2*scale`（中心 0） | 像素/tick / `2*scale` | `bhValueScale * 2` |

把"度/tick"塞进按弧度标定的字段 ⇒ 字节直接饱和 ⇒ 客户端每帧都过冲整个量程 ⇒ 看起来是剧烈抖动而不是补帧 ✗
（`SmoothTest.testRatePayloadRoundTripsThroughTheShadersArithmetic` 钉住这条）。速率**不做低通滤波**：客户端只用它外推**一次更新的时长**，滞后估计会让它每次都走不到位置 ⇒ 那是抖动，不是降噪。滚动用 `LinearRate`（直接差值，不回卷），旋转用 `AngleRate`（360° 最短弧）。

**时间戳的两个范围别搞混（踩过大坑）**：

| 布局 | 时间戳字段 | shader 读法 | 服务端必须写 |
|---|---|---|---|
| payload 4（tween 值） | 蓝 = 8 位计数器 | `bhAge(tick, 240)` | `worldTick mod 240`（`SmoothValue.tickOf`） |
| payload 5（值+速率） | 红绿的低 5 位 | `bhAge(bits & 31, 32)` | **`worldTick mod 32`（`RatePayload.tickOf`）** |
| payload 6（from/to tween） | 蓝的低 4 位 | `bhAge(bits & 15, 16)` | **`worldTick mod 16`（`RatePayload.rampTickOf`）** |

**4 位时间戳 ⇒ 更新间隔必须 < 16 tick**（`tick: 1~8` 安全）：间隔更大时客户端的 `age` 会回绕 ⇒ 补间相位错乱 ✗。

**32 不能整除 240**（240 = 7×32 + 16）⇒ 用 `tickOf` 的 240 结果塞进 5 位字段，每隔 240 tick 就会有 16 tick 偏差；
客户端算出的 age 落在 ±16 上（> 8）⇒ **直接丢弃速率** ⇒ 元素退化成每 tick 一步 ✗。
现象很有欺骗性：**一半时间正常、一半时间是 20Hz 抽帧**，站着不动看不见（速率为 0），一转身/走动就抽 ✓
（`SmoothTest.testAFreshRatePayloadNeverLooksSixteenTicksOld` 按 `bhAge` 的算术逐 tick 钉住）。
同一个 `tick` 值仍用于**测量更新间隔**（`RateTracker` 用 240 的回卷），两者用途不同、不要合并。

**因此**：

- 填充条（`clip-fill`）+ 旋转平滑 ⇒ **冲突** ✗（拆成两个元素 ✓）；
- 位置 + 旋转同时动 ⇒ 只能 payload 3 ⇒ 都没有补帧（**这是"移动一卡一卡"的原因** ✓）；
- 想让 **2D 位置**也连续 ⇒ 需要新布局 `dx(8) + rateX(4) + dy(8) + rateY(4)`（刚好 24 位 ✓，**待实现**）。

## 5. 版本兼容

- 一份 shader 源码（`common-resources/text.vsh|fsh`），overlay 只差 include、`#version` 与 `SHADER_VERSION`；
- 不使用 `gl_VertexID`（26.2 的 GUI 合批会带 baseVertex 偏移 ✗）、不使用 `gl_FragCoord`（Vulkan 后端 Y 轴可能相反 ✗）、不新增 uniform；
- 时间统一用 `GameTime * 24000`（当天刻），所有取模都整除 24000；
- 有单元测试钉住上述三条（`ShaderCompatTest`）。

## 6. 已知限制

- ~~剪裁的 `clip-rect` 数值与 shader 里 `bhLocalV` 的坐标单位是否严格 1:1 仍在验证中~~ ⇒ **已定**：`bhLocalV = (顶点 - 元素中心) + 元素半宽` = 顶点相对元素左上角，单位就是元素像素（和 `clip-rect` 同一把尺）。图块化时每个 case 的 `bhRotAnchor` 把"元素中心"统一到整图中心、`bhElemHalf` 换成整图半宽，所以矩形跨图块连续 ✓（`TileClipTest` 钉住）。实测生成的包：长图裁剪 `bhElemHalf = (715.5, 7.5)`、条子 `(60.0, 3.0)`；
- 图像被切块（`ImageTiles`）时，图块会 `copy` 一份 shader 并只改 pivot —— 约定"区域/滚动/插值必须跟着 copy 走"，有测试钉住（`TileClipTest`）；
- 循环滚动的图片需要"恰好一个周期宽"，并用 `@(... % 周期)` 回卷（周期回卷后相位相同 ⇒ 看不出接缝 ✓）。

## 7. 开发流程约定（重要）

| 改了什么 | 生效方式 |
|---|---|
| `dist/`、`api/` 的 **Kotlin/Java 代码** | `CI=true ./gradlew :bootstrap:bukkit:shadowJar` → **部署 jar** → **stop + start 重启**（reload 不够 ✗） |
| `common-resources/*.vsh|fsh`（shader 源） | 复制到 `plugins/BetterHud/shaders/` → `betterhud reload` |
| 布局/图片/素材（`layouts/` `images/` `assets/`） | `betterhud reload` |

> 服务器控制台发命令时**前置换行**（否则首字符会被吃掉 ⇒ 命令不执行 ✗）；**不要**在服务器控制台按 Ctrl-C（会直接停服 ✗）。
>
> 本机测试服（`di-ce-test`）用的是 **EmberCore 合并资源包** ⇒ 改完 `plugins/BetterHud/` 下的东西还要 `ecore reload pack` 才会重新合并下发；只改布局/图片时 `betterhud reload` + `ecore reload pack` 两者都要。

## 8. 让插值不卡 —— 已验证的规则

三条**互相独立**的原因，按出现频率排序。任何一条没满足画面就会"抽"，而且**抽的形态能反推是哪一条**。

### 8.1 补间能不能跑完，取决于输入给不给它时间（最常见）

**先看一件事：这个输入在两次变化之间，有没有留够一个斜坡的时间？**

| 输入 | 例子 | 会发生什么 | 该怎么配 |
|---|---|---|---|
| **事件驱动**（变了才变） | 血条、进度、弹药 | 上一次的斜坡**早就跑完了** ⇒ 重定位落在**已经跑完的端点**上 ⇒ 与离散链完全等价 ✓ | **`duration` 随便给** ✓（`clip-fill` 只有 reel 一种实现 ✓，事件驱动下它不会抽 ✓）|
| **每 N tick 采样**（有节奏地变） | 仪表、翻页、yaw/pitch 条 | 斜坡与采样**谁长谁短**决定观感 ✓ | **`duration ≤ tick`** ⇒ 走完再换目标（端点精确 ⇒ 不会跳 ✓）；但**周期要够长**（≥10 tick ✓），否则 1~2 tick 的包到达延迟会占掉一大块时间 ⇒ 变成**定格动画** ✗ |
| **每 tick 都在变** | 罗盘、转动指针 | 等斜坡走完就等于把连续输入**量化成台阶** ✗ | **`duration > tick`** ⇒ 每 tick 从"当前曲线上的点"继续 ✓；代价是暴露 1~3 tick 时钟偏差（速度相关的小抖 ✗）|

**血条是第一种** ✓：它走的是**连续分支**（`duration: 6 > tick: 1` ✓），但事件驱动下每次重定位都落在**已经跑完的端点**上 ⇒ **行为与离散链一致** ✓ ⇒ 这才是它"怎么都不抽"的真正原因 ✓（不是因为它"是离散形状" ✗）。

**⇒ 所以没有"能离散就离散"这条规则** ✗：形状是**输入节奏的结果**，不是可以先定的前提 ✓。把连续输入**降采样**成离散（例如罗盘 `tick: 2 / duration: 2`）确实能换掉抖动 ✓，但买来的是**滞后** ✗ —— 换不换取决于你更能接受哪一个 ✓。

**另外两条必须遵守的（都是踩出来的）：**

- **网格死区**：目标变化小于**一个端点网格步长**时不算变化，否则落在四舍五入边界上的目标会在相邻两格之间反复翻转 ⇒ 每 tick 重启斜坡 ⇒ 原地 ~0.7px 抖 ✗（长条 720px / 10 位）
- **走完必须塌缩**：斜坡跑完后 payload 里的 `from` 要塌缩成 `to` ✗ 否则 tick 字段（4 位）每 16 tick 回绕一次，客户端就把这段斜坡**重放一遍** ⇒ 抖动量正好等于输入**停止前**的速度 ✓
- **tick 字段是"重定位时刻"**，不是"当前时刻" ✗ 每 tick 换一个 tick 会让客户端**每次都从斜坡开头重走** ⇒ 等于没有插值

### 8.2 驱动必须是连续的（否则是在平滑一个不该平滑的跳变）

- **每 N tick 换一个随机目标** ⇒ 画面每 N tick 必然跳一次，**那是驱动的抖，不是补间的毛病**。
  用它判断补间好坏会一直误判 ✗（这一轮绕了好几圈就是这么来的）
- **锯齿**（到顶瞬间跳回）⇒ 补间会把这个不连续平滑成一次**横跨全幅的"兜圈"**，看起来像 bug ✓
- ⇒ 判断补间质量**必须**用连续驱动（正弦 / 匀速 / 真实状态）；随机只适合测"目标跳变后的跟随"

### 8.3 周期性内容：回卷要在正确的层做

- 值跨过整周期时（`player_yaw` 过 ±180 ⇒ `360 - yaw * 2` 恰好跳一个周期），**客户端**要"最短弧"
  （shader 里 `bhDelta > 0.5 → -= 1.0`），**服务端**的补间状态必须是**无界**的
- 旧实现把状态 clamp 在 0..1 ⇒ 回卷时被钉在 `1.0`，之后要横跨**半个周期**走回来
  ⇒ 在**特定朝向**出现一次"兜一圈" ✗
- 现在：`ScrollTween`（无界域）+ `align()`（把目标拉到与前一个端点相差半周期以内）+ 打包时才 `wrap01`
  ⇒ 两个端点一起回卷、差值不变，客户端的最短弧规则与之一致 ✓

### 8.4 结构上的坑

- **同一个窗口不要用两个元素拼** ✗：两个元素各有独立补间状态，ticker 相位可能差 1 tick，
  补间一长这点相位差就是**可见的位置差** ⇒ 叠影/闪。
  单张长图足够：窗口 240px 时，1431px 的长图已覆盖 yaw 全程 ±360px ✓
- **元素 `tick` 决定抽样率**：`tick: 1` 最新但最"硬"，`tick: 4~8` 通常更顺（补间余量更大）

### 8.5 服务端 20Hz 是硬上限

连续变化的值（罗盘 yaw）每 tick 只有一个采样点 ⇒ 补间再完美也只能在这 50ms 一个台阶之间插值
⇒ **服务端侧无解**。彻底方案 = **客户端侧数据源**：原版 locator bar / waypoint 的
`ClientboundTrackedWaypointPacket.addWaypointAzimuth(uuid, icon, 弧度)`（N/E/S/W 注册为方位角 waypoint），
客户端每帧用相机 yaw 与它做 `degreesDifference` ⇒ **0 延迟、0 插值、0 时钟偏移** ✓

### 8.6 选型

| 场景 | 用什么 | 为什么 |
|---|---|---|
| 血条 / 进度条（会停住） | `clip-fill`（payload 4） | 补间能走完，"走完就停"本来就是正常观感 ✓ |
| 任何**离散采样**的滚动（仪表、翻页、低速条） | `scroll` + **`duration ≤ tick`**（离散形状） | 同一套"走完再换目标"的机制 ⇒ 与 `clip-fill` 一样不会跳 ✓ |
| **连续**量（罗盘跟随 yaw） | 先**降采样**（如 `tick: 2 / duration: 2`）；要 0 延迟则用客户端侧数据源 | 服务端只有 20Hz，且与客户端有 1~3 tick 时钟偏差；locator/waypoint 的方位角由客户端每帧求值，两者都不存在 |
